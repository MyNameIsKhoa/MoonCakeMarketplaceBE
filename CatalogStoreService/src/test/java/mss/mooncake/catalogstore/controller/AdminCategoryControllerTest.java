package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCategoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AdminCategoryController — GET /api/admin/catalog/categories")
class AdminCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

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

    @Test
    @DisplayName("admin nhận mọi danh mục, gồm INACTIVE và parentCategoryId")
    void adminListsAllCategories() throws Exception {
        when(categoryService.getAllCategoriesForAdmin()).thenReturn(List.of(
                new CategoryResponse(1L, null, "Bánh nướng", "banh-nuong", "ACTIVE"),
                new CategoryResponse(2L, 1L, "Bánh nướng chay", "banh-nuong-chay", "INACTIVE")));

        mockMvc.perform(get("/api/admin/catalog/categories")
                        .with(user(UserPrincipal.create(1L, "admin@test.com", List.of("ROLE_ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("INACTIVE"))
                .andExpect(jsonPath("$[1].parentCategoryId").value(1));
    }

    @Test
    @DisplayName("không phải admin → 403, chưa đăng nhập → 401")
    void nonAdminRejected() throws Exception {
        mockMvc.perform(get("/api/admin/catalog/categories")
                        .with(user(UserPrincipal.create(2L, "s@test.com", List.of("ROLE_SUPPLIER")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/catalog/categories"))
                .andExpect(status().isUnauthorized());
        verify(categoryService, never()).getAllCategoriesForAdmin();
    }
}
