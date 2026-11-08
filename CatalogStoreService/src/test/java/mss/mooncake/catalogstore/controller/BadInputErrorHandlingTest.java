package mss.mooncake.catalogstore.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductService;
import mss.mooncake.catalogstore.service.PromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * QA: dữ liệu sai định dạng (JSON hỏng, sai kiểu path/query, enum lạ, ngày sai định dạng) phải trả 400 với body
 * ErrorResponse chuẩn và message tiếng Việt, không trả 500 "Lỗi hệ thống: <exception Java>".
 */
@WebMvcTest({ProductController.class, PromotionController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("Xử lý dữ liệu đầu vào sai định dạng")
class BadInputErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private PromotionService promotionService;

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

    private static UserPrincipal supplier() {
        return UserPrincipal.create(10L, "supplier@test.com", List.of("ROLE_CUSTOMER", "ROLE_SUPPLIER"));
    }

    private static ResultActions expectCleanBadRequest(ResultActions result) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.message", not(containsString("java."))))
                .andExpect(jsonPath("$.message", not(containsString("JSON parse error"))))
                .andExpect(jsonPath("$.message", not(containsString("Failed to convert"))));
    }

    private ResultActions createPromotion(String body) throws Exception {
        return mockMvc.perform(post("/api/promotions").with(user(supplier()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("GET /api/products/abc → 400 nêu tên tham số")
    void nonNumericPathVariable() throws Exception {
        expectCleanBadRequest(mockMvc.perform(get("/api/products/abc")))
                .andExpect(jsonPath("$.message", containsString("productId")));
    }

    @Test
    @DisplayName("GET /api/products?categoryId=abc → 400 nêu tên tham số")
    void nonNumericQueryParameter() throws Exception {
        expectCleanBadRequest(mockMvc.perform(get("/api/products").param("categoryId", "abc")))
                .andExpect(jsonPath("$.message", containsString("categoryId")));
    }

    @Test
    @DisplayName("GET /api/products?page=abc → 400, lỗi field bằng tiếng Việt")
    void nonNumericPageParameter() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page", not(containsString("Failed to convert"))));
    }

    @Test
    @DisplayName("POST JSON hỏng → 400")
    void malformedJson() throws Exception {
        expectCleanBadRequest(createPromotion("{\"storeId\":1,\"name\":"));
    }

    @Test
    @DisplayName("ruleType không thuộc enum → 400 nêu tên trường")
    void unknownEnumInBody() throws Exception {
        expectCleanBadRequest(createPromotion("""
                {"storeId":1,"name":"Trung thu","startAt":"2026-09-01T00:00:00","endAt":"2026-09-30T00:00:00",
                 "rules":[{"ruleType":"FOO","discountAmount":20000}]}
                """)).andExpect(jsonPath("$.message", containsString("ruleType")));
    }

    @Test
    @DisplayName("startAt sai định dạng ngày → 400 nêu tên trường")
    void badDateFormat() throws Exception {
        expectCleanBadRequest(createPromotion("""
                {"storeId":1,"name":"Trung thu","startAt":"01/09/2026","endAt":"2026-09-30T00:00:00",
                 "rules":[{"ruleType":"FIXED_DISCOUNT","discountAmount":20000}]}
                """)).andExpect(jsonPath("$.message", containsString("startAt")));
    }
}
