package mss.mooncake.catalogstore.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class StockReservationSecurityTest {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void onlyCommerceCanAccessInventory() throws Exception {
        String url = "/internal/v1/inventory/reservations/missing";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("X-Service-Name", "rfq")
                .header("X-Service-Token", "test-rfq-catalog-token-32-bytes-minimum"))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("X-Service-Name", "commerce")
                .header("X-Service-Token", "test-commerce-catalog-token-32-bytes-minimum"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyCommerceCanCalculateBasketPromotions() throws Exception {
        String url = "/internal/v1/promotions/calculate";
        String basket = "{\"storeId\":1,\"lines\":[{\"variantId\":999999,\"quantity\":1}]}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(basket))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("X-Service-Name", "rfq")
                .header("X-Service-Token", "test-rfq-catalog-token-32-bytes-minimum")
                .contentType(MediaType.APPLICATION_JSON).content(basket))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("X-Service-Name", "commerce")
                .header("X-Service-Token", "test-commerce-catalog-token-32-bytes-minimum")
                .contentType(MediaType.APPLICATION_JSON).content(basket))
                .andExpect(status().isNotFound());
    }
}
