package mss.mooncake.rfqnegotiation.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import mss.mooncake.rfqnegotiation.util.json.DecimalStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.IdStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.StrictIntegerDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record QuotationItemRequest(
        @Positive @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = IdStringDeserializer.class) Long productId,
        @Positive @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = IdStringDeserializer.class) Long variantId,
        @Size(max = 255) @Schema(description = "Required for custom items; Catalog supplies the name for variants") String itemNameSnapshot,
        @Size(max = 100) String skuSnapshot,
        @NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = DecimalStringDeserializer.class) BigDecimal unitPrice,
        @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING) @JsonDeserialize(using = DecimalStringDeserializer.class) BigDecimal discountAmount,
        @Size(max = 10000) String customizationNote) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported quotation item field: " + field);
    }
}
