package mss.mooncake.rfqnegotiation.util.json;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Applies the public API decimal-string contract without changing Feign response parsing. */
public class DecimalStringDeserializer extends ValueDeserializer<BigDecimal> {
    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(BigDecimal.class, "Amount must be a decimal string");
        }
        String value = parser.getString();
        if (!value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            return context.reportInputMismatch(BigDecimal.class, "Amount must have at most two decimal places and be non-negative");
        }
        return new BigDecimal(value);
    }
}
