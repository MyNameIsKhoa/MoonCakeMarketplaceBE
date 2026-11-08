package mss.mooncake.identity.controller;

import java.util.List;
import mss.mooncake.identity.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Số điện thoại (đăng ký, hồ sơ, địa chỉ) chỉ gồm 8–30 ký tự số, khoảng trắng, + . - (giống form FE). */
@SpringBootTest
@ActiveProfiles("test")
class PhoneValidationIntegrationTest {

    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mailSender;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static UserPrincipal customer() {
        return UserPrincipal.create(424242L, 424242L, "phone-check@example.com", List.of("ROLE_CUSTOMER"));
    }

    @Test
    void registerRejectsMalformedPhone() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A\",\"email\":\"phone-reg@example.com\",\"password\":\"password123\","
                                + "\"phone\":\"call me maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.phone").exists());
    }

    @Test
    void profileUpdateRejectsMalformedPhone() throws Exception {
        mvc.perform(patch("/api/users/me").with(user(customer())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.phone").exists());
    }

    @Test
    void addressCreateAndUpdateRejectMalformedPhone() throws Exception {
        mvc.perform(post("/api/addresses").with(user(customer())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"A\",\"recipientPhone\":\"abc-xyz-123\",\"addressLine\":\"1 Lê Lợi\","
                                + "\"city\":\"Hà Nội\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.recipientPhone").exists());

        mvc.perform(patch("/api/addresses/1").with(user(customer())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientPhone\":\"<script>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.recipientPhone").exists());
    }
}
