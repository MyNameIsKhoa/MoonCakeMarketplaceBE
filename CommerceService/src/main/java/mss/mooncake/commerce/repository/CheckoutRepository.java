package mss.mooncake.commerce.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.commerce.client.dto.identity.AddressSummaryResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;

/** Persistence contract for the retail checkout aggregate. */
public interface CheckoutRepository {
    record Attempt(long id, long customerId, long cartId, long addressId, String key,
                   String fingerprint, String reservationKey, LocalDateTime expiresAt,
                   String status, Long orderId, String selectionRequest) { }

    record Store(CartPricingResponse.StorePrice price, BigDecimal fee, String supplierName) { }

    Attempt byKey(long customerId, String key);
    List<Attempt> pending();
    void purgeExpiredKeys();
    Attempt start(long customerId, long cartId, long addressId, String key, String fingerprint, String selectionRequest);
    boolean failCreated(Attempt attempt);
    void persistPending(Attempt attempt, AddressSummaryResponse address, List<Store> stores, String fingerprint);
    void complete(Attempt attempt);
    void expire(Attempt attempt);
    RetailCheckoutResponse response(long orderId);
    String cartFingerprint(long cartId);
}
