package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import org.junit.jupiter.api.Test;

class AcceptedQuotationContractTest {
    @Test
    void validatesNestedSnapshotsAtInternalTrustBoundary() {
        CreateOrderFromQuotationRequest request = new CreateOrderFromQuotationRequest(1L, 2L, 1, LocalDateTime.now(),
                3L, 4L, 5L, "accept-key-1",
                new CreateOrderFromQuotationRequest.StoreSnapshot("Store", "Supplier"),
                new CreateOrderFromQuotationRequest.AddressSnapshot(6L, "Buyer", "0123",
                        "Street", null, null, "City"),
                new CreateOrderFromQuotationRequest.Terms(BigDecimal.TEN, LocalDate.now(),
                        "DELIVERY", BigDecimal.ZERO, null),
                List.of(new CreateOrderFromQuotationRequest.Item(null, null, "Custom cake", null,
                        null, null, 1, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)),
                BigDecimal.TEN, BigDecimal.TEN);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(request)).isEmpty();
            CreateOrderFromQuotationRequest invalid = new CreateOrderFromQuotationRequest(request.rfqId(), request.quotationId(),
                    request.quotationVersion(), request.acceptedAt(), request.customerId(), request.supplierId(),
                    request.storeId(), request.idempotencyKey(), request.storeSnapshot(),
                    request.deliveryAddressSnapshot(), request.terms(),
                    List.of(new CreateOrderFromQuotationRequest.Item(null, null, "", null, null, null,
                            0, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)),
                    request.subtotal(), request.totalAmount());
            assertThat(validator.validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                    .contains("items[0].productNameSnapshot", "items[0].quantity");
        }
    }
}
