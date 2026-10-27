package mss.mooncake.rfqnegotiation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RfqResponse(
        String rfqId,
        String rfqCode,
        String customerId,
        String title,
        String description,
        Integer quantity,
        String budgetAmount,
        LocalDate requestedDeliveryDate,
        String deliveryLocation,
        String customizationRequirements,
        RfqStatus status,
        String expiresAt,
        String createdAt,
        RfqAudience audience,
        /** Detail only: the owner sees every invited store, an invited supplier only its own. */
        List<TargetStore> targetStores) {

    public record TargetStore(String storeId, String storeName) { }
}
