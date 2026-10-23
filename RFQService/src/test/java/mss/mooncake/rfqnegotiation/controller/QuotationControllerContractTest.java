package mss.mooncake.rfqnegotiation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.security.RfqPrincipal;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class QuotationControllerContractTest {
    private final QuotationApplicationService service = mock(QuotationApplicationService.class);
    private final QuotationController controller = new QuotationController(service);

    @Test
    void forwardsPathIdAndAuthenticatedCustomerInsteadOfClientControlledIds() {
        var request = new AcceptQuotationRequest(3, 17L);
        var principal = new RfqPrincipal(5L, 9L, "buyer@example.test");

        controller.accept(21L, principal, request);

        verify(service).acceptQuotation(21L, 9L, request);
    }

    @Test
    void rejectsTokenWithoutCustomerId() {
        assertThatThrownBy(() -> controller.accept(21L, new RfqPrincipal(5L, null, "x"),
                new AcceptQuotationRequest(3, 17L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service);
    }
}
