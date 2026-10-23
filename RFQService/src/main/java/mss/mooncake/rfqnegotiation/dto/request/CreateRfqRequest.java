package mss.mooncake.rfqnegotiation.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import mss.mooncake.rfqnegotiation.util.json.DecimalStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.StrictIntegerDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateRfqRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 20000) String description,
        @NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        @Schema(type = "string", example = "3500000.00") BigDecimal budgetAmount,
        @FutureOrPresent LocalDate requestedDeliveryDate,
        @Size(max = 500) String deliveryLocation,
        @Size(max = 20000) String customizationRequirements,
        @Future OffsetDateTime expiresAt) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported RFQ field: " + field);
    }
}
