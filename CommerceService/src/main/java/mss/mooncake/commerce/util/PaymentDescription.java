package mss.mooncake.commerce.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** A short merchant reference for the payOS description, distinct from its numeric order code. */
public final class PaymentDescription {
    private static final int MAX_LENGTH = 25;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyMMddHHmm").withZone(ZoneOffset.UTC);

    private PaymentDescription() { }

    public static String customer(String orderNo, long orderId) {
        return reference("DH ", orderNo, "DH " + orderId);
    }

    public static String seller(long supplierId, String paymentType) {
        String prefix = switch (paymentType) {
            case "DEPOSIT" -> "COC ";
            case "REMAINING" -> "CON ";
            default -> "DH ";
        };
        StringBuilder letters = new StringBuilder(4);
        for (int i = 0; i < 4; i++) letters.append((char) ('A' + RANDOM.nextInt(26)));
        Instant now = Instant.now();
        String description = prefix + "S" + supplierId + " " + TIME.format(now) + " " + letters;
        if (description.length() <= MAX_LENGTH) return description;
        return prefix + "S" + Long.toString(supplierId, 36).toUpperCase() + " "
                + Long.toString(now.getEpochSecond(), 36).toUpperCase() + " " + letters;
    }

    private static String reference(String prefix, String orderNo, String fallback) {
        String description = prefix + orderNo;
        return description.length() <= MAX_LENGTH ? description : fallback;
    }
}
