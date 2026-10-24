package mss.mooncake.commerce.service.impl;

import java.time.Instant;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.FinanceOutboxDispatcher;
import mss.mooncake.commerce.service.FinanceEventTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FinanceOutboxDispatcherImpl implements FinanceOutboxDispatcher {
    private final FinanceRepository repository;
    private final ObjectProvider<FinanceEventTransport> transport;

    public FinanceOutboxDispatcherImpl(FinanceRepository repository, ObjectProvider<FinanceEventTransport> transport) {
        this.repository = repository;
        this.transport = transport;
    }

    @Scheduled(fixedDelayString = "${finance.outbox.delay-ms:10000}")
    public void dispatch() {
        var publisher = transport.getIfAvailable();
        if (publisher == null) { return; }
        for (var event : repository.pendingEvents()) {
            try {
                publisher.publish(event);
                repository.update("UPDATE finance_outbox SET published_at=? WHERE event_id=? AND published_at IS NULL",
                        FinanceRepository.utc(Instant.now()), event.eventId());
            } catch (RuntimeException ex) {
                // Keep the event durable. At-least-once transport may redeliver after an uncertain ACK.
                return;
            }
        }
    }
}
