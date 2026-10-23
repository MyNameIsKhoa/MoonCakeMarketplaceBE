package mss.mooncake.catalogstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreatePromotionRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.PromotionResponse;
import mss.mooncake.catalogstore.dto.response.PromotionRuleResponse;
import mss.mooncake.catalogstore.exception.GlobalExceptionHandler;
import mss.mooncake.catalogstore.security.JwtAuthenticationFilter;
import mss.mooncake.catalogstore.security.SecurityConfig;
import mss.mooncake.catalogstore.security.UserPrincipal;
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

@WebMvcTest(PromotionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("PromotionController MockMvc Tests")
class PromotionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PromotionService promotionService;

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

    private static PromotionResponse promotion() {
        return new PromotionResponse(30L, 1L, "Trung thu", LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 30, 0, 0), "DRAFT",
                List.of(new PromotionRuleResponse(1L, "FIXED_DISCOUNT", null, null, null, null,
                        new java.math.BigDecimal("20000"), null)));
    }

    private static final String CREATE_BODY = """
            {"storeId":1,"name":"Trung thu","startAt":"2026-09-01T00:00:00","endAt":"2026-09-30T00:00:00",
             "rules":[{"ruleType":"FIXED_DISCOUNT","discountAmount":20000}]}
            """;

    @Test
    @DisplayName("GET list public không cần JWT")
    void list_public() throws Exception {
        when(promotionService.getPromotions(isNull(), eq(false), isNull(), any()))
                .thenReturn(new PageResponse<>(List.of(promotion()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rules[0].ruleType").value("FIXED_DISCOUNT"));
    }

    @Test
    @DisplayName("GET mine=true không có JWT → 401")
    void list_mine_401() throws Exception {
        mockMvc.perform(get("/api/promotions").param("mine", "true"))
                .andExpect(status().isUnauthorized());
        verify(promotionService, never()).getPromotions(any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("POST → 201 + Location")
    void create_201() throws Exception {
        when(promotionService.createPromotion(any(CreatePromotionRequest.class), eq(USER_ID))).thenReturn(promotion());

        mockMvc.perform(post("/api/promotions").with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/promotions/30"));
    }

    @Test
    @DisplayName("POST thiếu rules → 400; không có JWT → 401")
    void create_invalid() throws Exception {
        mockMvc.perform(post("/api/promotions").with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":1,\"name\":\"KM\",\"startAt\":\"2026-09-01T00:00:00\",\"endAt\":\"2026-09-30T00:00:00\",\"rules\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/promotions").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT rules: phần tử vi phạm validation (percent > 100) → 400, không gọi service")
    void replaceRules_elementValidation_400() throws Exception {
        mockMvc.perform(put("/api/promotions/{id}/rules", 30L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"ruleType\":\"PERCENT_DISCOUNT\",\"discountPercent\":150}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rules[0].discountPercent")));
        verify(promotionService, never()).replaceRules(any(), anyList(), any());
    }

    @Test
    @DisplayName("PUT rules hợp lệ → 200")
    void replaceRules_200() throws Exception {
        when(promotionService.replaceRules(eq(30L), anyList(), eq(USER_ID))).thenReturn(promotion());

        mockMvc.perform(put("/api/promotions/{id}/rules", 30L).with(user(supplier()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"ruleType\":\"FIXED_DISCOUNT\",\"discountAmount\":20000}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotionId").value(30));
    }
}
