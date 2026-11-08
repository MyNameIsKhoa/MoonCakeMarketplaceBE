package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateCategoryRequest;
import mss.mooncake.catalogstore.dto.request.UpdateCategoryRequest;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("CategoryController MockMvc Tests")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private CategoryService categoryService;

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

    private static UserPrincipal admin() {
        return UserPrincipal.create(1L, "admin@test.com", List.of("ROLE_CUSTOMER", "ROLE_ADMIN"));
    }

    private static UserPrincipal customer() {
        return UserPrincipal.create(2L, "buyer@test.com", List.of("ROLE_CUSTOMER"));
    }

    private static CategoryResponse chay() {
        return new CategoryResponse(3L, null, "Bánh chay", "chay", "ACTIVE");
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    // ── GET — public ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/categories trả 200 không cần JWT, mặc định page=0 size=20 sort name,id")
    void list_public() throws Exception {
        Pageable expected = org.springframework.data.domain.PageRequest.of(0, 20, Sort.by("name").and(Sort.by("id")));
        when(categoryService.getCategories(isNull(), eq(expected)))
                .thenReturn(new PageResponse<>(List.of(chay()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("chay"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/categories?size=101 trả 400")
    void list_sizeTooLarge() throws Exception {
        mockMvc.perform(get("/api/categories").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/categories?sort=password trả 400 (sort ngoài allowlist)")
    void list_sortNotAllowed() throws Exception {
        mockMvc.perform(get("/api/categories").param("sort", "password,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/categories/{id} trả 404 khi không có")
    void detail_notFound() throws Exception {
        when(categoryService.getCategory(99L)).thenThrow(new ResourceNotFoundException("Không tìm thấy"));

        mockMvc.perform(get("/api/categories/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/categories/{id} với JWT admin trả cả danh mục INACTIVE")
    void detail_adminSeesInactive() throws Exception {
        when(categoryService.getCategoryForAdmin(5L))
                .thenReturn(new CategoryResponse(5L, 3L, "Bánh chay cũ", "chay-cu", "INACTIVE"));

        mockMvc.perform(get("/api/categories/{id}", 5L).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        verify(categoryService, never()).getCategory(any());
    }

    @Test
    @DisplayName("GET /api/categories/{id} với JWT customer vẫn chỉ thấy ACTIVE")
    void detail_customerUsesPublicLookup() throws Exception {
        when(categoryService.getCategory(5L)).thenThrow(new ResourceNotFoundException("Không tìm thấy"));

        mockMvc.perform(get("/api/categories/{id}", 5L).with(user(customer())))
                .andExpect(status().isNotFound());
        verify(categoryService, never()).getCategoryForAdmin(any());
    }

    // ── POST / PATCH — admin ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST trả 201 + Location khi là admin")
    void create_admin_201() throws Exception {
        when(categoryService.createCategory(any(CreateCategoryRequest.class))).thenReturn(chay());

        mockMvc.perform(post("/api/categories").with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCategoryRequest("Bánh chay", "chay", null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/categories/3"))
                .andExpect(jsonPath("$.categoryId").value(3));
    }

    @Test
    @DisplayName("POST trả 401 khi không có JWT")
    void create_noJwt_401() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCategoryRequest("Bánh chay", "chay", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST trả 403 khi không phải admin")
    void create_customer_403() throws Exception {
        mockMvc.perform(post("/api/categories").with(user(customer()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCategoryRequest("Bánh chay", "chay", null))))
                .andExpect(status().isForbidden());
        verify(categoryService, never()).createCategory(any());
    }

    @Test
    @DisplayName("POST trả 400 khi slug sai định dạng")
    void create_invalidSlug_400() throws Exception {
        mockMvc.perform(post("/api/categories").with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCategoryRequest("Bánh chay", "Bánh Chay!", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH trả 200 khi là admin, 403 khi không phải")
    void update_adminOnly() throws Exception {
        when(categoryService.updateCategory(eq(3L), any(UpdateCategoryRequest.class))).thenReturn(chay());
        String body = json(new UpdateCategoryRequest("Bánh chay", null, null, null));

        mockMvc.perform(patch("/api/categories/{id}", 3L).with(user(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/categories/{id}", 3L).with(user(customer()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
