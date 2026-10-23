package mss.mooncake.rfqnegotiation.dto.request;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import mss.mooncake.rfqnegotiation.util.json.IdStringDeserializer;
import mss.mooncake.rfqnegotiation.util.json.StrictIntegerDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
public record AcceptQuotationRequest(@JsonProperty("quotationVersion") @NotNull @Positive
                                     @JsonDeserialize(using=StrictIntegerDeserializer.class) Integer expectedVersion,
                                     @JsonProperty("addressId") @NotNull @Positive
                                     @JsonDeserialize(using=IdStringDeserializer.class) Long deliveryAddressId) {
    @JsonAnySetter public void rejectUnknownField(String field,Object value) {
        throw new IllegalArgumentException("Unsupported acceptance field: "+field);
    }
}
