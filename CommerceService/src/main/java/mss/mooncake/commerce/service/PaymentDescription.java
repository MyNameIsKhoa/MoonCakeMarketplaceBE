package mss.mooncake.commerce.service;

/** A short merchant reference for the payOS description, distinct from its numeric order code. */
public final class PaymentDescription {
    private static final int MAX_LENGTH = 25;

    private PaymentDescription() { }

    public static String customer(String orderNo, long orderId) {
        return reference("DH ", orderNo, "DH " + orderId);
    }

    public static String seller(String sellerOrderNo, long sellerOrderId, String paymentType) {
        String prefix = switch (paymentType) {
            case "DEPOSIT" -> "COC ";
            case "REMAINING" -> "CON ";
            default -> "DH ";
        };
        return reference(prefix, sellerOrderNo.replaceFirst("^MSS-", ""), prefix + sellerOrderId);
    }

    private static String reference(String prefix, String orderNo, String fallback) {
        String description = prefix + orderNo;
        return description.length() <= MAX_LENGTH ? description : fallback;
    }
}
