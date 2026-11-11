package mss.mooncake.rfqnegotiation.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * An unpaid RFQ order is cancelled 48 hours after it is placed, so the earliest delivery date depends on the payment split.
 * <ul>
 * <li>Deposit with a remaining balance (0% &lt; deposit &lt; 100%): TV5 bills the remainder 3 days before delivery
 * (00:00 Vietnam time), and that deadline must fall after the 48h deposit window: today + 6 days in Vietnam.</li>
 * <li>One FULL payment (0% or 100%): goods are prepared only once it is paid, so delivery falls after the
 * 48h payment window: today + 3 days in Vietnam.</li>
 * </ul>
 * Both count from the end of today, the worst case for an acceptance made late in the day.
 */
public final class DeliveryDateRule {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MIN_DAYS_WITH_REMAINING = 6;
    private static final int MIN_DAYS_FULL_PAYMENT = 3;
    private static final BigDecimal FULL_DEPOSIT = new BigDecimal("100");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DeliveryDateRule() {
    }

    public static boolean hasRemaining(BigDecimal depositPercent) {
        return depositPercent != null && depositPercent.signum() > 0 && depositPercent.compareTo(FULL_DEPOSIT) < 0;
    }

    /** Earliest delivery date for the quotation's payment split; utcNow is a UTC wall-clock time. */
    public static LocalDate earliest(BigDecimal depositPercent, LocalDateTime utcNow) {
        return utcNow.atOffset(ZoneOffset.UTC).atZoneSameInstant(VIETNAM).toLocalDate().plusDays(minDays(depositPercent));
    }

    public static boolean tooSoon(BigDecimal depositPercent, LocalDate delivery, LocalDateTime utcNow) {
        return delivery.isBefore(earliest(depositPercent, utcNow));
    }

    public static String errorCode(BigDecimal depositPercent) {
        return hasRemaining(depositPercent) ? "DELIVERY_TOO_SOON_FOR_REMAINING" : "DELIVERY_TOO_SOON_FOR_PAYMENT";
    }

    /** The rule as one sentence, naming the earliest allowed date and why. */
    public static String requirement(BigDecimal depositPercent, LocalDateTime utcNow) {
        String from = " cần ngày giao từ " + format(earliest(depositPercent, utcNow)) + " trở đi (cách hôm nay ít nhất "
                + minDays(depositPercent) + " ngày): ";
        return hasRemaining(depositPercent)
                ? "Báo giá có tiền cọc" + from + "người mua có 48 giờ để đặt cọc và phần còn lại đến hạn 3 ngày trước ngày giao."
                : "Báo giá thanh toán một lần" + from + "người mua có 48 giờ để thanh toán và hàng chỉ được chuẩn bị sau khi đã thu đủ tiền.";
    }

    public static String format(LocalDate day) {
        return day.format(DAY);
    }

    private static int minDays(BigDecimal depositPercent) {
        return hasRemaining(depositPercent) ? MIN_DAYS_WITH_REMAINING : MIN_DAYS_FULL_PAYMENT;
    }
}
