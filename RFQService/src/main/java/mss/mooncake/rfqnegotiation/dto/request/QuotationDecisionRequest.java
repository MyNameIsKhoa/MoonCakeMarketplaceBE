package mss.mooncake.rfqnegotiation.dto.request;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;

public record QuotationDecisionRequest(@Size(max = 2000) String reason) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported quotation decision field: " + field);
    }
}
