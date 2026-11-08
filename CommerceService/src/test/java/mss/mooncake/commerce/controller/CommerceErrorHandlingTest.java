package mss.mooncake.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import feign.FeignException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mss.mooncake.commerce.exception.FinanceExceptionHandler;
import mss.mooncake.commerce.exception.GlobalExceptionHandler;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.CartApplicationService;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import mss.mooncake.commerce.service.RetailCheckoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** QA M3: downstream and request errors become clean 4xx/503 responses without internal details. */
class CommerceErrorHandlingTest {
    private static final String CATALOG = "http://catalog-store-service:8082";
    private static final String IDENTITY = "http://identity-service:8081";

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    private static FeignException downstream(String url, int status, String body) {
        var request = feign.Request.create(feign.Request.HttpMethod.POST, url, Map.of(), null,
                StandardCharsets.UTF_8, null);
        var response = feign.Response.builder().status(status).reason("downstream").request(request)
                .headers(Map.of()).body(body, StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("Client#call()", response);
    }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new FinanceExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private void signedInBuyer() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CommercePrincipal(101L, 10L, "buyer@example.test", authorities), null, authorities));
    }

    @Test
    void insufficientCatalogStockDuringCheckoutIsAConflictWithoutTheInternalUrl() throws Exception {
        var checkout = mock(RetailCheckoutService.class);
        when(checkout.place(anyLong(), anyLong(), anyLong(), anyString())).thenThrow(downstream(
                CATALOG + "/internal/v1/inventory/reservations", 409,
                "{\"status\":409,\"message\":\"Insufficient stock for variant 7\"}"));
        signedInBuyer();

        String body = mvc(new CheckoutController(checkout)).perform(post("/api/checkout")
                        .header("Idempotency-Key", "checkout-abc123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressId\":11}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("http", "internal", "8082", "variant 7");
    }

    @Test
    void downstreamFailuresAreMappedToStableCodes() {
        var handler = new GlobalExceptionHandler();
        record Case(String url, int status, String body, HttpStatus expected, String code) { }
        for (var c : List.of(
                new Case(IDENTITY + "/internal/v1/addresses/99/summary", 404, "{}", HttpStatus.NOT_FOUND,
                        "ADDRESS_NOT_FOUND"),
                new Case(CATALOG + "/internal/v1/variants/snapshots", 404, "{}", HttpStatus.NOT_FOUND,
                        "PRODUCT_NOT_FOUND"),
                new Case(CATALOG + "/internal/v1/stores/5/summary", 404, "{}", HttpStatus.NOT_FOUND,
                        "STORE_NOT_FOUND"),
                new Case(CATALOG + "/internal/v1/inventory/reservations", 409,
                        "{\"message\":\"Product or store is not active\"}", HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE"),
                new Case(CATALOG + "/internal/v1/inventory/reservations", 400, "{}", HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST"),
                new Case(CATALOG + "/internal/v1/promotions/calculate", 500, "{}", HttpStatus.SERVICE_UNAVAILABLE,
                        "DOWNSTREAM_UNAVAILABLE"),
                new Case(IDENTITY + "/internal/v1/users/1/summary", 401, "{}", HttpStatus.SERVICE_UNAVAILABLE,
                        "DOWNSTREAM_UNAVAILABLE"))) {
            var response = handler.handleDownstream(downstream(c.url(), c.status(), c.body()));
            assertThat(response.getStatusCode()).as(c.url()).isEqualTo(c.expected());
            assertThat(response.getBody().code()).as(c.url()).isEqualTo(c.code());
            assertThat(response.getBody().message()).as(c.url()).isNotBlank().doesNotContain("http", "internal");
        }
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() throws Exception {
        var controller = new FinanceController(mock(FinanceService.class), mock(FinanceActorResolver.class));
        mvc(controller).perform(post("/api/payments/5/initiate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void malformedJsonAndTypeMismatchAreBadRequests() throws Exception {
        signedInBuyer();
        mvc(new CartController(mock(CartApplicationService.class))).perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc(new ShipmentController(mock(FulfillmentApplicationService.class), mock(FinanceActorResolver.class)))
                .perform(get("/api/shipments/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternalDetails() throws Exception {
        var fulfillment = mock(FulfillmentApplicationService.class);
        when(fulfillment.getShipmentById(any(), any()))
                .thenThrow(new IllegalStateException("jdbc:sqlserver://db:1433;password=secret"));
        String body = mvc(new ShipmentController(fulfillment, mock(FinanceActorResolver.class)))
                .perform(get("/api/shipments/5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("jdbc", "secret");
    }
}
