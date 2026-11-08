package mss.mooncake.identity.controller;

import java.util.List;
import mss.mooncake.identity.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA: dữ liệu sai định dạng phải trả 400 với body ErrorResponse chuẩn và thông báo tiếng Việt,
 * không trả 500 kèm nội dung exception Java.
 */
@SpringBootTest
@ActiveProfiles("test")
class BadInputErrorHandlingIntegrationTest {

    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mailSender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static UserPrincipal admin() {
        return UserPrincipal.create(1L, 1L, "admin@example.com", List.of("ROLE_ADMIN"));
    }

    private static void expectCleanBadRequest(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.message", not(containsString("java."))))
                .andExpect(jsonPath("$.message", not(containsString("JSON parse error"))))
                .andExpect(jsonPath("$.message", not(containsString("Failed to convert"))));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        expectCleanBadRequest(mockMvc.perform(post("/api/auth/login")
                .contentType("application/json").content("{\"email\": \"a@b.com\", ")));
    }

    @Test
    void unknownEnumInQueryReturns400NamingTheParameter() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/suppliers").param("verificationStatus", "FOO")
                .with(user(admin())));
        expectCleanBadRequest(result);
        result.andExpect(jsonPath("$.message", containsString("verificationStatus")));
    }

    @Test
    void nonNumericPathVariableReturns400() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/suppliers/abc/verification-history").with(user(admin())));
        expectCleanBadRequest(result);
        result.andExpect(jsonPath("$.message", containsString("supplierId")));
    }

    @Test
    void unknownEnumInJsonBodyReturns400NamingTheField() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/suppliers/7/verification").with(user(admin()))
                .contentType("application/json").content("{\"decision\":\"FOO\",\"reason\":\"Thiếu giấy phép\"}"));
        expectCleanBadRequest(result);
        result.andExpect(jsonPath("$.message", containsString("decision")));
    }

    @Test
    void unknownSortPropertyReturns400() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/suppliers").param("sort", "foo").with(user(admin())));
        expectCleanBadRequest(result);
        result.andExpect(jsonPath("$.message", containsString("foo")));
    }
}
