package mss.mooncake.commerce.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.math.BigDecimal;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CartControllerIntegrationTest {
    private static final String SECRET = "MoonCakeMarketplaceTestSecretKeyMustBeAtLeast256BitsLong!";
    @Autowired private WebApplicationContext context;
    @MockitoBean private CatalogServiceClient catalog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(catalog.getVariantSnapshots(any())).thenAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            return List.of(new VariantSnapshotResponse(request.variantIds().getFirst(), 1L, 1L, 1L,
                    "P", "Product", "Variant", "SKU", null, new BigDecimal("100"), 10,
                    "ACTIVE", "ACTIVE", "ACTIVE", 10));
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void requiresJwtAndKeepsCartItemsPrivate() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/pricing")).andExpect(status().isUnauthorized());
        String buyerA = token(3001L, 4001L);
        String buyerB = token(3002L, 4002L);
        String body = "{\"variantId\":901,\"quantity\":2}";
        String response = mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + buyerA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long itemId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("cartItemId").asLong();
        mvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + buyerA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(4));
        mvc.perform(get("/api/cart").header("Authorization", "Bearer " + buyerB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/cart/pricing").header("Authorization", "Bearer " + buyerB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(0));
        mvc.perform(patch("/api/cart/items/{id}", itemId)
                        .header("Authorization", "Bearer " + buyerB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    private String token(Long userId, Long customerId) {
        Date now = new Date();
        return Jwts.builder().setSubject("buyer" + userId + "@example.com")
                .setIssuer("mooncake-identity").setAudience("mooncake-api")
                .claim("userId", userId).claim("customerId", customerId)
                .claim("roles", List.of("ROLE_CUSTOMER"))
                .setIssuedAt(now).setExpiration(new Date(now.getTime() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
    }
}
