package mss.mooncake.rfqnegotiation.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * TV5 bills the remaining balance 3 days before delivery (00:00 Vietnam time) and an unpaid RFQ order is cancelled
 * 48 hours after it is placed. A quotation whose deposit leaves a remaining balance (0% < deposit < 100%) therefore
 * needs delivery at least 6 days after today in Vietnam, so the remaining deadline falls after the deposit window.
 */
public final class DeliveryDateRule {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MIN_DAYS_WITH_REMAINING = 6;
    private static final BigDecimal FULL_DEPOSIT = new BigDecimal("100");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DeliveryDateRule() {
    }

    public static boolean hasRemaining(BigDecimal depositPercent) {
        return depositPercent != null && depositPercent.signum() > 0 && depositPercent.compareTo(FULL_DEPOSIT) < 0;
    }

    /** Earliest delivery date for a quotation with a remaining balance; utcNow is a UTC wall-clock time. */
    public static LocalDate earliestWithRemaining(LocalDateTime utcNow) {
        return utcNow.atOffset(ZoneOffset.UTC).atZoneSameInstant(VIETNAM).toLocalDate().plusDays(MIN_DAYS_WITH_REMAINING);
    }

    public static boolean tooSoon(BigDecimal depositPercent, LocalDate delivery, LocalDateTime utcNow) {
        return hasRemaining(depositPercent) && delivery.isBefore(earliestWithRemaining(utcNow));
    }

    public static String format(LocalDate day) {
        return day.format(DAY);
    }
}
