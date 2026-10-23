package mss.mooncake.rfqnegotiation.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import mss.mooncake.rfqnegotiation.util.json.DecimalStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.IdStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.StrictIntegerDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateQuotationRequest(
        @NotNull @Positive @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonDeserialize(using = IdStringDeserializer.class) Long storeId,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = DecimalStringDeserializer.class) BigDecimal depositPercent,
        @NotNull OffsetDateTime validUntil,
        @PositiveOrZero @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer leadTimeDays,
        @NotNull LocalDate proposedDeliveryDate,
        @Size(max = 100) String shippingMethod,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = DecimalStringDeserializer.class) BigDecimal shippingFee,
        @Size(max = 10000) String paymentTerms,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid QuotationItemRequest> items) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported quotation field: " + field);
    }
}
