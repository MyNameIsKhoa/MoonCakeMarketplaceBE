package mss.mooncake.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.controller.FinanceController;
import mss.mooncake.commerce.exception.FinanceExceptionHandler;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.security.JwtAuthenticationFilter;
import mss.mooncake.commerce.security.JwtTokenProvider;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.service.impl.FinanceActorResolverImpl;
import mss.mooncake.commerce.service.FinanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinanceAuthenticationTest {
    private static final String SECRET = "FinanceRegressionSecretAtLeastThirtyTwoBytesLong";
    private final IdentityServiceClient identity = mock(IdentityServiceClient.class);
    private final FinanceActorResolver resolver = new FinanceActorResolverImpl(identity);

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    private UsernamePasswordAuthenticationToken authentication(Long customerId, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken(
                new CommercePrincipal(101L, customerId, "buyer@example.test", authorities), null, authorities);
    }

    @Test
    void realSignedJwtFilterPrincipalReachesFinanceControllerWithoutDetails() throws Exception {
        String token = Jwts.builder().setSubject("buyer@example.test")
                .setIssuer("mooncake-identity").setAudience("mooncake-api")
                .setExpiration(new Date(System.currentTimeMillis() + 60000))
                .claim("userId", 101L).claim("customerId", 10L).claim("roles", List.of("ROLE_CUSTOMER"))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var filter = new JwtAuthenticationFilter(new JwtTokenProvider(SECRET, "mooncake-identity", "mooncake-api"));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> { });
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isInstanceOf(CommercePrincipal.class);
        assertThat(auth.getDetails()).isNull();
        var finance = mock(FinanceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FinanceController(finance, resolver))
                .setControllerAdvice(new FinanceExceptionHandler()).build();
        mvc.perform(get("/api/payments/orders/1").principal(auth)).andExpect(status().isOk());
        verify(finance).summary(1, new FinanceService.Actor(101, 10L, null, false));
        verifyNoInteractions(identity);
    }

    @Test
    void supplierProfileIsResolvedUsingIdentityByUserContract() {
        when(identity.getSupplierSummaryByUser(101L)).thenReturn(
                new SupplierSummaryResponse(20L, 101L, "Supplier", null, "VERIFIED"));
        assertThat(resolver.resolve(authentication(10L, "ROLE_CUSTOMER", "ROLE_SUPPLIER")))
                .isEqualTo(new FinanceService.Actor(101, 10L, 20L, false));
    }

    @Test
    void disputeQueueRequiresAdminEvenWhenControllerIsCalledDirectly() {
        var repository = mock(mss.mooncake.commerce.repository.FinanceRepository.class);
        var finance = mock(FinanceService.class);
        var controller = new mss.mooncake.commerce.controller.AdminDisputeController(repository, finance, resolver);
        assertThatThrownBy(() -> controller.pending(0, 20, authentication(10L, "ROLE_CUSTOMER")))
                .hasMessage("FINANCE_ADMIN_REQUIRED");
        when(repository.pendingDisputeOrderIds(0, 21)).thenReturn(List.of());
        assertThat(controller.pending(0, 20, authentication(null, "ROLE_ADMIN")).content()).isEmpty();
        verifyNoInteractions(finance);
    }

    @Test
    void shipmentControllerPassesSupplierProfileIdInsteadOfUserId() {
        when(identity.getSupplierSummaryByUser(101L)).thenReturn(
                new SupplierSummaryResponse(20L, 101L, "Supplier", null, "VERIFIED"));
        var fulfillment = mock(mss.mooncake.commerce.service.FulfillmentApplicationService.class);
        var controller = new mss.mooncake.commerce.controller.ShipmentController(fulfillment, resolver);
        controller.markAsShipped(7L, authentication(10L, "ROLE_CUSTOMER", "ROLE_SUPPLIER"));
        verify(fulfillment).markAsShipped(7L, 20L);
        assertThatThrownBy(() -> controller.markAsShipped(7L, authentication(10L, "ROLE_CUSTOMER")))
                .isInstanceOf(mss.mooncake.commerce.exception.ForbiddenException.class);
    }

    @Test
    void anotherUsersSupplierProfileIsRejected() {
        when(identity.getSupplierSummaryByUser(101L)).thenReturn(
                new SupplierSummaryResponse(20L, 999L, "Supplier", null, "VERIFIED"));
        assertThatThrownBy(() -> resolver.resolve(authentication(null, "ROLE_SUPPLIER")))
                .hasMessage("SUPPLIER_CONTEXT_INVALID");
    }

    @Test
    void suspendedSupplierCannotUseAnOldSupplierJwt() {
        when(identity.getSupplierSummaryByUser(101L)).thenReturn(
                new SupplierSummaryResponse(20L, 101L, "Supplier", null, "SUSPENDED"));
        assertThatThrownBy(() -> resolver.resolve(authentication(10L, "ROLE_CUSTOMER", "ROLE_SUPPLIER")))
                .hasMessage("SUPPLIER_CONTEXT_INVALID");
    }

    @Test
    void legacyDetailsCannotSubstituteForAuthenticatedPrincipal() {
        var authentication = new UsernamePasswordAuthenticationToken("email", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        authentication.setDetails(10L);
        assertThatThrownBy(() -> resolver.resolve(authentication)).hasMessage("AUTHENTICATED_USER_ID_REQUIRED");
        assertThatThrownBy(() -> resolver.resolve(null)).hasMessage("AUTHENTICATED_USER_ID_REQUIRED");
        var unauthenticated = new UsernamePasswordAuthenticationToken(new CommercePrincipal(101L, 10L, "email"), null);
        assertThatThrownBy(() -> resolver.resolve(unauthenticated)).hasMessage("AUTHENTICATED_USER_ID_REQUIRED");
    }

    @Test
    void missingSupplierProfileNeverFallsBackToUserId() {
        when(identity.getSupplierSummaryByUser(101L)).thenThrow(mock(feign.FeignException.NotFound.class));
        assertThat(resolver.resolve(authentication(10L, "ROLE_CUSTOMER", "ROLE_SUPPLIER")))
                .isEqualTo(new FinanceService.Actor(101, 10L, null, false));
    }

    @Test
    void unavailableIdentityDoesNotGrantSupplierAccess() {
        when(identity.getSupplierSummaryByUser(101L)).thenThrow(mock(feign.FeignException.ServiceUnavailable.class));
        assertThatThrownBy(() -> resolver.resolve(authentication(null, "ROLE_SUPPLIER")))
                .hasMessage("IDENTITY_CONTEXT_UNAVAILABLE");
    }

    @Test
    void adminDoesNotNeedSupplierProfileToPerformAdministrativeCommands() {
        assertThat(resolver.resolve(authentication(null, "ROLE_ADMIN", "ROLE_SUPPLIER")))
                .isEqualTo(new FinanceService.Actor(101, null, null, true));
        verifyNoInteractions(identity);
    }
}
