package mss.mooncake.rfqnegotiation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
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
        String createdAt) {
}
