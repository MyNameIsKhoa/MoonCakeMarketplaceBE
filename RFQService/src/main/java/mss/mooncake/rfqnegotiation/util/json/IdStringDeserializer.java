package mss.mooncake.rfqnegotiation.util.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Keeps BIGINT IDs as opaque decimal strings at the public API boundary. */
public class IdStringDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(Long.class, "ID must be a positive decimal string");
        }
        String value = parser.getString();
        if (!value.matches("[1-9][0-9]*")) {
            return context.reportInputMismatch(Long.class, "ID must be a positive decimal string");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return context.reportInputMismatch(Long.class, "ID exceeds the supported BIGINT range");
        }
    }
}
