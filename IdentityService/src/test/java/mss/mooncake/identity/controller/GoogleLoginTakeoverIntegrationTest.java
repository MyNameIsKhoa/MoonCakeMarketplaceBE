package mss.mooncake.identity.controller;

import mss.mooncake.identity.service.GoogleIdentityTokenService;
import mss.mooncake.identity.service.GoogleIdentityTokenService.GoogleIdentity;
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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA: kẻ gian đăng ký trước bằng email của nạn nhân (không xác minh được). Khi nạn nhân đăng nhập Google,
 * tài khoản được đánh dấu đã xác minh — mật khẩu kẻ gian đặt không được phép đăng nhập vào tài khoản đó nữa.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoogleLoginTakeoverIntegrationTest {

    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender mailSender;
    @MockitoBean GoogleIdentityTokenService googleIdentityTokenService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void squatterPasswordStopsWorkingAfterOwnerSignsInWithGoogle() throws Exception {
        String email = "takeover-" + java.util.UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Squatter\",\"email\":\"" + email
                                + "\",\"password\":\"attacker123\"}"))
                .andExpect(status().isCreated());

        when(googleIdentityTokenService.verify("google-id-token")).thenReturn(new GoogleIdentity(email, "Owner"));
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"google-id-token\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"attacker123\"}"))
                .andExpect(status().isUnauthorized());
    }
}
