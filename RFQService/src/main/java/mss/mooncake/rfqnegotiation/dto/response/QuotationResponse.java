package mss.mooncake.rfqnegotiation.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;

public record QuotationResponse(String quotationId, String rfqId, String supplierId, String storeId,
                                String previousQuotationId, Integer version, String totalAmount,
                                String depositPercent, String validUntil, Integer leadTimeDays,
                                LocalDate proposedDeliveryDate, String shippingMethod, String shippingFee,
                                String paymentTerms, QuotationStatus status, List<QuotationItemResponse> items,
                                String submittedAt, String acceptedAt, String decisionReason) {
    public static QuotationResponse from(QuotationEntity quotation, List<QuotationItemEntity> items) {
        return new QuotationResponse(Objects.toString(quotation.getId(), null),
                Objects.toString(quotation.getRfqId(), null), Objects.toString(quotation.getSupplierIdRef(), null),
                Objects.toString(quotation.getStoreIdRef(), null), Objects.toString(quotation.getPreviousQuotationId(), null),
                quotation.getVersionNo(), quotation.getTotalAmount().toPlainString(),
                quotation.getDepositPercent().toPlainString(), utc(quotation.getValidUntil()), quotation.getLeadTimeDays(),
                quotation.getProposedDeliveryDate(), quotation.getShippingMethod(), quotation.getShippingFee().toPlainString(),
                quotation.getPaymentTerms(), displayStatus(quotation), items.stream().map(QuotationItemResponse::from).toList(),
                utc(quotation.getSubmittedAt()), utc(quotation.getAcceptedAt()), quotation.getDecisionReason());
    }

    /** SUBMITTED past validUntil can no longer be accepted, so it is reported as EXPIRED without a write. */
    static QuotationStatus displayStatus(QuotationEntity quotation) {
        return quotation.getStatus() == QuotationStatus.SUBMITTED && quotation.getValidUntil() != null
                && !quotation.getValidUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))
                ? QuotationStatus.EXPIRED : quotation.getStatus();
    }

    static String utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
