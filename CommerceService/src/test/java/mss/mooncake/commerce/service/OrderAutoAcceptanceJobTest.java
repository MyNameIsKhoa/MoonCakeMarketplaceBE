package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.impl.OrderAutoAcceptanceJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderAutoAcceptanceJobTest {
    private final FinanceRepository repository = mock(FinanceRepository.class);
    private final FulfillmentApplicationService fulfillment = mock(FulfillmentApplicationService.class);

    @Test
    void acceptsEveryCandidatePastTheSevenDayWindowAndSurvivesAFailure() {
        when(repository.autoAcceptableSellerOrderIds(any())).thenReturn(List.of(1L, 2L));
        when(fulfillment.autoAcceptDelivered(eq(1L), any())).thenThrow(new IllegalStateException("locked"));

        new OrderAutoAcceptanceJob(repository, fulfillment).acceptOverdueDeliveries();

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).autoAcceptableSellerOrderIds(cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), Instant.now())).isBetween(
                Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7).plusMinutes(1));
        verify(fulfillment).autoAcceptDelivered(2L, cutoff.getValue());
    }
}
