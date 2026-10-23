package mss.mooncake.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import mss.mooncake.identity.dto.request.AddressRequest;
import mss.mooncake.identity.dto.request.LoginRequest;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.request.UpdateAddressRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    private static String tokenUserA;
    private static String tokenUserB;
    private static Long addressId1;
    private static Long addressId2;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Order(1)
    void setupUsers_RegisterAndLoginUserAAndUserB() throws Exception {
        // Register User A
        RegisterRequest regA = new RegisterRequest("User A", "usera_addr@example.com", "password123", "0901111111");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regA)))
                .andExpect(status().isCreated());

        // Login User A
        MvcResult resA = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("usera_addr@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> mapA = objectMapper.readValue(resA.getResponse().getContentAsString(), Map.class);
        tokenUserA = (String) mapA.get("accessToken");

        // Register User B
        RegisterRequest regB = new RegisterRequest("User B", "userb_addr@example.com", "password123", "0902222222");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regB)))
                .andExpect(status().isCreated());

        // Login User B
        MvcResult resB = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("userb_addr@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> mapB = objectMapper.readValue(resB.getResponse().getContentAsString(), Map.class);
        tokenUserB = (String) mapB.get("accessToken");
    }

    @Test
    @Order(2)
    void createAddress_UserACreatesFirstAddress_AutomaticallyDefault() throws Exception {
        AddressRequest req = new AddressRequest(
                "Nguoi Nhan 1", "0901111111", "123 Duong Le Loi", "Phuong Ben Nghe", "Quan 1", "TP Ho Chi Minh", false
        );

        MvcResult result = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressId").isNumber())
                .andExpect(jsonPath("$.recipientName").value("Nguoi Nhan 1"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn();

        Map<?, ?> resMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        addressId1 = ((Number) resMap.get("addressId")).longValue();
    }

    @Test
    @Order(3)
    void createAddress_UserACreatesSecondAddressWithDefault_ResetsFirstAddressDefault() throws Exception {
        AddressRequest req = new AddressRequest(
                "Nguoi Nhan 2", "0902222222", "456 Duong Nguyen Hue", "Phuong Ben Nghe", "Quan 1", "TP Ho Chi Minh", true
        );

        MvcResult result = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientName").value("Nguoi Nhan 2"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andReturn();

        Map<?, ?> resMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        addressId2 = ((Number) resMap.get("addressId")).longValue();

        // Check list for User A: address 2 is default, address 1 is not default
        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].addressId").value(addressId2))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].addressId").value(addressId1))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    @Order(4)
    void getAddresses_UserB_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(5)
    void updateAddress_UserBTriesToUpdateAddressOfUserA_Returns403Forbidden() throws Exception {
        UpdateAddressRequest hackReq = new UpdateAddressRequest("Hacker B", "0999999999", null, null, null, null, null);

        mockMvc.perform(patch("/api/addresses/" + addressId1)
                        .header("Authorization", "Bearer " + tokenUserB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hackReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message", containsString("do not have permission")));
    }

    @Test
    @Order(6)
    void deleteAddress_UserBTriesToDeleteAddressOfUserA_Returns403Forbidden() throws Exception {
        mockMvc.perform(delete("/api/addresses/" + addressId1)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message", containsString("do not have permission")));
    }

    @Test
    @Order(7)
    void updateAddress_UserAUpdatesOwnAddress_Returns200OK() throws Exception {
        UpdateAddressRequest updateReq = new UpdateAddressRequest(
                "Nguoi Nhan 1 Cap Nhat", "0909999999", "123 Le Loi Updated", null, null, null, null
        );

        mockMvc.perform(patch("/api/addresses/" + addressId1)
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("Nguoi Nhan 1 Cap Nhat"))
                .andExpect(jsonPath("$.recipientPhone").value("0909999999"))
                .andExpect(jsonPath("$.addressLine").value("123 Le Loi Updated"));
    }

    @Test
    @Order(8)
    void deleteAddress_UserADeletesAddress2_Returns204AndAddress1PromotedToDefault() throws Exception {
        mockMvc.perform(delete("/api/addresses/" + addressId2)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isNoContent());

        // Now verify address 1 is the only one remaining and is set to default
        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].addressId").value(addressId1))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    @Order(9)
    void getAddressSummary_InternalEndpoint_Returns200OK() throws Exception {
        mockMvc.perform(get("/api/address/" + addressId1 + "/summary")
                        .header("X-Service-Name", "commerce")
                        .header("X-Service-Token", "test-commerce-identity-token-32-bytes-minimum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(addressId1))
                .andExpect(jsonPath("$.recipientName").value("Nguoi Nhan 1 Cap Nhat"))
                .andExpect(jsonPath("$.addressLine").value("123 Le Loi Updated"))
                .andExpect(jsonPath("$.city").value("TP Ho Chi Minh"));
    }
}
