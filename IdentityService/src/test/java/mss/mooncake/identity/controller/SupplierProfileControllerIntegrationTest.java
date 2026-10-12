package mss.mooncake.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import mss.mooncake.identity.dto.request.LoginRequest;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupplierProfileControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    private static String tokenUser1;
    private static String tokenUser2;
    private static Long user1Id;
    private static Long supplier1Id;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Order(1)
    void setupUsers_RegisterAndLoginUser1AndUser2() throws Exception {
        // Register User 1
        RegisterRequest reg1 = new RegisterRequest("Supplier User 1", "supp1@example.com", "password123", "0901111222");
        MvcResult resReg1 = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg1)))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> mapReg1 = objectMapper.readValue(resReg1.getResponse().getContentAsString(), Map.class);
        user1Id = ((Number) mapReg1.get("userId")).longValue();

        // Login User 1
        MvcResult res1 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("supp1@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map1 = objectMapper.readValue(res1.getResponse().getContentAsString(), Map.class);
        tokenUser1 = (String) map1.get("accessToken");

        // Register User 2
        RegisterRequest reg2 = new RegisterRequest("Supplier User 2", "supp2@example.com", "password123", "0903333444");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg2)))
                .andExpect(status().isCreated());

        // Login User 2
        MvcResult res2 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("supp2@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map2 = objectMapper.readValue(res2.getResponse().getContentAsString(), Map.class);
        tokenUser2 = (String) map2.get("accessToken");
    }

    @Test
    @Order(2)
    void getSupplierProfile_BeforeRegistering_Returns404NotFound() throws Exception {
        mockMvc.perform(get("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @Order(3)
    void registerSupplier_User1_Returns201Created() throws Exception {
        SupplierProfileRequest req = new SupplierProfileRequest("Tiem Banh Nhu Lan", "0300123456");

        MvcResult result = mockMvc.perform(post("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierId").isNumber())
                .andExpect(jsonPath("$.companyName").value("Tiem Banh Nhu Lan"))
                .andExpect(jsonPath("$.taxCode").value("0300123456"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andReturn();

        Map<?, ?> resMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        supplier1Id = ((Number) resMap.get("supplierId")).longValue();
    }

    @Test
    @Order(4)
    void registerSupplier_User1AttemptsAgain_Returns409Conflict() throws Exception {
        SupplierProfileRequest req = new SupplierProfileRequest("Tiem Banh Nhu Lan 2", "0300999999");

        mockMvc.perform(post("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("already registered as a supplier")));
    }

    @Test
    @Order(5)
    void registerSupplier_User2AttemptsWithDuplicateTaxCode_Returns409Conflict() throws Exception {
        SupplierProfileRequest req = new SupplierProfileRequest("Cong Ty Khac", "0300123456");

        mockMvc.perform(post("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("Tax code is already registered")));
    }

    @Test
    @Order(6)
    void registerSupplier_User2WithNewTaxCode_Returns201Created() throws Exception {
        SupplierProfileRequest req = new SupplierProfileRequest("Dong Khanh Bakery", "0300888999");

        mockMvc.perform(post("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyName").value("Dong Khanh Bakery"))
                .andExpect(jsonPath("$.taxCode").value("0300888999"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"));
    }

    @Test
    @Order(7)
    void getSupplierProfile_User1_Returns200OK() throws Exception {
        mockMvc.perform(get("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(supplier1Id))
                .andExpect(jsonPath("$.companyName").value("Tiem Banh Nhu Lan"))
                .andExpect(jsonPath("$.taxCode").value("0300123456"));
    }

    @Test
    @Order(8)
    void updateSupplierProfile_User1_Returns200OK() throws Exception {
        UpdateSupplierProfileRequest updateReq = new UpdateSupplierProfileRequest("Tiem Banh Nhu Lan Sai Gon", null);

        mockMvc.perform(patch("/api/supplier-profile")
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(supplier1Id))
                .andExpect(jsonPath("$.companyName").value("Tiem Banh Nhu Lan Sai Gon"))
                .andExpect(jsonPath("$.taxCode").value("0300123456"));
    }

    @Test
    @Order(9)
    void getSupplierSummary_InternalEndpoint_Returns200OK() throws Exception {
        mockMvc.perform(get("/internal/v1/suppliers/" + supplier1Id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(supplier1Id))
                .andExpect(jsonPath("$.userId").value(user1Id))
                .andExpect(jsonPath("$.companyName").value("Tiem Banh Nhu Lan Sai Gon"))
                .andExpect(jsonPath("$.taxCode").value("0300123456"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"));
    }

    @Test
    @Order(10)
    void getSupplierSummaryByUserId_InternalEndpoint_Returns200OK() throws Exception {
        mockMvc.perform(get("/internal/v1/suppliers/by-user/" + user1Id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(supplier1Id))
                .andExpect(jsonPath("$.userId").value(user1Id))
                .andExpect(jsonPath("$.companyName").value("Tiem Banh Nhu Lan Sai Gon"));
    }
}
