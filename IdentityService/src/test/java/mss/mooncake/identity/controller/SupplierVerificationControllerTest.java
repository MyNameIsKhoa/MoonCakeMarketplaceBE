package mss.mooncake.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.enums.SupplierVerificationStatus;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.SupplierApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class SupplierVerificationControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean SupplierApplicationService supplierService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private UserPrincipal principal(String role) {
        return UserPrincipal.create(1L, 1L, "admin@example.com", List.of(role));
    }

    @Test
    void openApiNamesAdminGroupAndHidesInternalContracts() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = new ObjectMapper().readTree(json).path("paths");
        assertEquals("Admin - Supplier Verification", paths.path("/api/admin/suppliers")
                .path("get").path("tags").path(0).asText());
        assertFalse(paths.has("/api/address/{id}/summary"));
        assertFalse(paths.has("/api/supplier/{id}/summary"));
        assertFalse(paths.has("/api/user/{userId}/summary"));
    }

    @Test
    void adminCanListAndReviewUsingDocumentedContract() throws Exception {
        SupplierProfileResponse supplier = new SupplierProfileResponse(
                7L, 2L, "Supplier", "TAX-7", "PENDING", LocalDateTime.now(), LocalDateTime.now());
        when(supplierService.getSupplierProfilesByStatus(eq(SupplierVerificationStatus.PENDING)))
                .thenReturn(List.of(supplier));
        when(supplierService.reviewSupplier(7L, SupplierVerificationStatus.VERIFIED, 1L, "Approved"))
                .thenReturn(supplier);

        mockMvc.perform(get("/api/admin/suppliers?status=PENDING")
                        .with(user(principal("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].supplierId").value(7));

        mockMvc.perform(patch("/api/admin/suppliers/7/verification")
                        .with(user(principal("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"verificationStatus\":\"VERIFIED\",\"reason\":\"Approved\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotAccessSupplierQueue() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers").with(user(principal("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/suppliers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reviewRequiresDecisionAndReason() throws Exception {
        mockMvc.perform(patch("/api/admin/suppliers/7/verification")
                        .with(user(principal("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{\"verificationStatus\":\"VERIFIED\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
