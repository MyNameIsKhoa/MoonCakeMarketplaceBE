package mss.mooncake.identity.controller;

import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AuthEmailFlowIntegrationTest {
    @Autowired private WebApplicationContext context;
    @MockitoBean private JavaMailSender mailSender;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void verificationAndResetTokensAreSingleUse() throws Exception {
        String email = "email-flow-" + java.util.UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Email Flow\",\"email\":\"" + email
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        String oldLogin = "{\"email\":\"" + email + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(oldLogin))
                .andExpect(status().isForbidden());

        var messages = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messages.capture());
        String verificationToken = tokenFrom(messages.getValue(), "verify-email");
        String verifyBody = "{\"token\":\"" + verificationToken + "\"}";
        mvc.perform(post("/api/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(oldLogin))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/password-reset-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        verify(mailSender, times(2)).send(messages.capture());
        String resetToken = tokenFrom(messages.getValue(), "reset-password");
        String resetBody = "{\"token\":\"" + resetToken + "\",\"newPassword\":\"newPassword123\"}";
        mvc.perform(post("/api/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(oldLogin))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"newPassword123\"}"))
                .andExpect(status().isOk());
    }

    private String tokenFrom(SimpleMailMessage message, String page) {
        var matcher = Pattern.compile("/" + page + "\\?token=([A-Za-z0-9_-]+)")
                .matcher(message.getText());
        org.junit.jupiter.api.Assertions.assertTrue(matcher.find());
        return matcher.group(1);
    }
}
