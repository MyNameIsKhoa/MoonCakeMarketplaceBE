package mss.mooncake.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import mss.mooncake.identity.dto.request.LoginRequest;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.request.UpdateProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;
import mss.mooncake.identity.repository.UserRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthAndUserControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired private UserRepository users;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    private static String jwtToken;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Order(1)
    void register_ValidRequest_Returns201Created() throws Exception {
        RegisterRequest request = new RegisterRequest("Le Van C", "levanc@example.com", "password123", "0912345678");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.customerId").isNumber())
                .andExpect(jsonPath("$.fullName").value("Le Van C"))
                .andExpect(jsonPath("$.email").value("levanc@example.com"))
                .andExpect(jsonPath("$.phone").value("0912345678"));
        var user = users.findByEmail("levanc@example.com").orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(user.isEmailVerified());
        user.setEmailVerified(true); // Existing profile tests start after verification has completed.
        users.save(user);
    }

    @Test
    @Order(2)
    void register_DuplicateEmail_Returns409Conflict() throws Exception {
        RegisterRequest request = new RegisterRequest("Le Van C Clone", "levanc@example.com", "anotherpass", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message", containsString("Email đã được đăng ký")));
    }

    @Test
    @Order(3)
    void register_InvalidPayload_Returns400BadRequest() throws Exception {
        RegisterRequest request = new RegisterRequest("", "invalid-email", "123", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.fullName").exists())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    @Order(4)
    void login_ValidCredentials_Returns200AndToken() throws Exception {
        LoginRequest request = new LoginRequest("levanc@example.com", "password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("levanc@example.com"))
                .andExpect(jsonPath("$.user.fullName").value("Le Van C"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> responseMap = objectMapper.readValue(responseBody, Map.class);
        jwtToken = (String) responseMap.get("accessToken");
    }

    @Test
    @Order(5)
    void login_InvalidPassword_Returns401Unauthorized() throws Exception {
        LoginRequest request = new LoginRequest("levanc@example.com", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @Order(6)
    void getProfile_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    void getProfile_WithValidToken_Returns200AndProfile() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("levanc@example.com"))
                .andExpect(jsonPath("$.fullName").value("Le Van C"))
                .andExpect(jsonPath("$.phone").value("0912345678"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @Order(8)
    void updateProfile_WithValidToken_Returns200AndUpdatedProfile() throws Exception {
        UpdateProfileRequest updateRequest = new UpdateProfileRequest("Le Van C Updated", "0999888777");

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Le Van C Updated"))
                .andExpect(jsonPath("$.phone").value("0999888777"));
    }
}
