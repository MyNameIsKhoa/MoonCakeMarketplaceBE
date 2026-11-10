package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.math.BigDecimal;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateProductRequest;
import mss.mooncake.catalogstore.dto.request.UpdateProductRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.ProductResponse;
import mss.mooncake.catalogstore.dto.response.ProductSummaryResponse;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("ProductController MockMvc Tests")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ProductService productService;

    // Filter bean cần cho SecurityConfig
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
            ServletRequest req = inv.getArgument(0);
            ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long USER_ID = 10L;

    private static UserPrincipal supplier() {
        return UserPrincipal.create(USER_ID, "supplier@test.com", List.of("ROLE_CUSTOMER", "ROLE_SUPPLIER"));
    }

    private static ProductResponse product() {
        return new ProductResponse(5L, 1L, 3L, "PRD-ABCDEF12", "Bánh nướng", null, "DRAFT", null, null, List.of());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ── GET /api/products — CA06 ─────────────────────────────────────────────────

    @Test
    @DisplayName("search public trả 200 không cần JWT, truyền filter và sort mặc định createdAt desc")
    void search_public() throws Exception {
        var expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        when(productService.searchProducts(eq("nướng"), isNull(), eq(3L), eq(false), isNull(), eq(expected)))
                .thenReturn(new PageResponse<>(List.of(new ProductSummaryResponse(5L, 1L, 3L, "PRD-ABCDEF12",
                        "Bánh nướng", "ACTIVE", new BigDecimal("100000"), null, true)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/products").param("q", "nướng").param("categoryId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productCode").value("PRD-ABCDEF12"))
                .andExpect(jsonPath("$.items[0].minPrice").value(100000));
    }

    @Test
    @DisplayName("mine=true không có JWT → 401")
    void search_mineWithoutJwt_401() throws Exception {
        mockMvc.perform(get("/api/products").param("mine", "true"))
                .andExpect(status().isUnauthorized());
        verify(productService, never()).searchProducts(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("mine=true có JWT → truyền userId từ principal")
    void search_mine_usesPrincipal() throws Exception {
        when(productService.searchProducts(isNull(), isNull(), isNull(), eq(true), eq(USER_ID), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/products").param("mine", "true").with(user(supplier())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("sort ngoài allowlist → 400")
    void search_badSort_400() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "basePrice,asc"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/products/{id} — CA07 ────────────────────────────────────────────

    @Test
    @DisplayName("detail không có JWT truyền userId null; không tìm thấy → 404")
    void detail_notFound() throws Exception {
        when(productService.getProduct(5L, null)).thenThrow(new ResourceNotFoundException("Không tìm thấy"));

        mockMvc.perform(get("/api/products/{id}", 5L))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/products — CA08 ────────────────────────────────────────────────

    @Test
    @DisplayName("tạo product → 201 + Location")
    void create_201() throws Exception {
        when(productService.createProduct(any(CreateProductRequest.class), eq(USER_ID))).thenReturn(product());

        mockMvc.perform(post("/api/products").with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateProductRequest(1L, 3L, "Bánh nướng", null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/5"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("tạo product không có JWT → 401")
    void create_401() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateProductRequest(1L, 3L, "Bánh nướng", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("thiếu categoryId hoặc tên rỗng → 400")
    void create_invalid_400() throws Exception {
        mockMvc.perform(post("/api/products").with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateProductRequest(1L, null, "", null))))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /api/products/{id} — CA09 ──────────────────────────────────────────

    @Test
    @DisplayName("sửa product của supplier khác → 403")
    void update_403() throws Exception {
        when(productService.updateProduct(eq(5L), any(UpdateProductRequest.class), eq(USER_ID)))
                .thenThrow(new ForbiddenException("Bạn không có quyền thao tác trên store này."));

        mockMvc.perform(patch("/api/products/{id}", 5L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProductRequest(null, "Hack", null, null))))
                .andExpect(status().isForbidden());
    }
}
