package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
import mss.mooncake.catalogstore.dto.request.CreateVariantRequest;
import mss.mooncake.catalogstore.dto.request.UpdateVariantRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductVariantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductVariantController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("ProductVariantController MockMvc Tests")
class ProductVariantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private ProductVariantService variantService;

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

    private static VariantResponse variant() {
        return new VariantResponse(7L, 5L, "SKU-7", "Hộp 4 bánh", null, new BigDecimal("250000"), 20, 20, "DRAFT");
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("GET list public không cần JWT")
    void list_public() throws Exception {
        when(variantService.getVariants(eq(5L), isNull(), any()))
                .thenReturn(new PageResponse<>(List.of(variant()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/products/{productId}/variants", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("SKU-7"));
    }

    @Test
    @DisplayName("POST → 201 + Location lồng theo product")
    void create_201() throws Exception {
        when(variantService.createVariant(eq(5L), any(CreateVariantRequest.class), eq(USER_ID))).thenReturn(variant());

        mockMvc.perform(post("/api/products/{productId}/variants", 5L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateVariantRequest("SKU-7", "Hộp 4 bánh", null, new BigDecimal("250000"), 20))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/5/variants/7"));
    }

    @Test
    @DisplayName("POST giá âm / tồn kho âm → 400")
    void create_negative_400() throws Exception {
        mockMvc.perform(post("/api/products/{productId}/variants", 5L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateVariantRequest("SKU-7", "Hộp", null, new BigDecimal("-1"), -5))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST không có JWT → 401")
    void create_401() throws Exception {
        mockMvc.perform(post("/api/products/{productId}/variants", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateVariantRequest("SKU-7", "Hộp", null, BigDecimal.ONE, 1))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH SKU trùng → 409")
    void update_conflict_409() throws Exception {
        when(variantService.updateVariant(eq(5L), eq(7L), any(UpdateVariantRequest.class), eq(USER_ID)))
                .thenThrow(new ConflictException("SKU đã tồn tại"));

        mockMvc.perform(patch("/api/products/{productId}/variants/{variantId}", 5L, 7L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateVariantRequest("SKU-8", null, null, null, null, null))))
                .andExpect(status().isConflict());
    }
}
