package mss.mooncake.rfqnegotiation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuotationControllerContractTest {
    private final QuotationApplicationService service = mock(QuotationApplicationService.class);
    private final QuotationController controller = new QuotationController(service);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void forwardsPathIdBodyAndIdempotencyKeyInsteadOfClientControlledIds() {
        var request = new AcceptQuotationRequest(3, 17L);
        when(service.acceptQuotation(21L, request, "accept-1")).thenReturn(accepted("COMPLETED"));

        var response = controller.accept(21L, request, "accept-1");

        verify(service).acceptQuotation(21L, request, "accept-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void returnsAcceptedWhileCommerceConversionIsPending() {
        var request = new AcceptQuotationRequest(3, 17L);
        when(service.acceptQuotation(21L, request, "accept-1")).thenReturn(accepted("PENDING"));

        assertThat(controller.accept(21L, request, "accept-1").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void rejectsAuthenticatedUserWithoutCustomerProfile() {
        var identity = mock(IdentityServiceClient.class);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(5L, "x", authorities), null, authorities));
        when(identity.getUserSummary(5L)).thenReturn(new UserSummaryResponse(5L, null, "X", "x", null));

        assertThatThrownBy(() -> new ActorContext(identity).customerId())
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCode()).isEqualTo("CUSTOMER_REQUIRED");
                });
    }

    private static QuotationAcceptedResponse accepted(String conversionStatus) {
        return new QuotationAcceptedResponse("21", "1", "9", "4", "2", 3, null, null, conversionStatus);
    }
}
