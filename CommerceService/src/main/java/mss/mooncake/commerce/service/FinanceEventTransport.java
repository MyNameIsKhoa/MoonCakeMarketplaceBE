package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.internal.FinanceEvent;

/** Infrastructure implements this port when the team's event transport is ready.
 * publish must return only after durable acceptance. Consumers deduplicate eventId
 * and ignore stale aggregateVersion, then retrieve the authoritative status snapshot.
 */
public interface FinanceEventTransport {
    void publish(FinanceEvent event);
}
