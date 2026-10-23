package mss.mooncake.rfqnegotiation.dto.response;

import java.time.LocalDate;
import java.util.Objects;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;

public record QuotationSummaryResponse(String quotationId, String rfqId, String supplierId, String storeId,
                                       String previousQuotationId, Integer version, String totalAmount,
                                       String depositPercent, String shippingFee, LocalDate proposedDeliveryDate,
                                       String validUntil, QuotationStatus status, String submittedAt) {
    public static QuotationSummaryResponse from(QuotationEntity q) {
        return new QuotationSummaryResponse(Objects.toString(q.getId(), null), Objects.toString(q.getRfqId(), null),
                Objects.toString(q.getSupplierIdRef(), null), Objects.toString(q.getStoreIdRef(), null),
                Objects.toString(q.getPreviousQuotationId(), null), q.getVersionNo(), q.getTotalAmount().toPlainString(),
                q.getDepositPercent().toPlainString(), q.getShippingFee().toPlainString(), q.getProposedDeliveryDate(),
                QuotationResponse.utc(q.getValidUntil()), QuotationResponse.displayStatus(q), QuotationResponse.utc(q.getSubmittedAt()));
    }
}
