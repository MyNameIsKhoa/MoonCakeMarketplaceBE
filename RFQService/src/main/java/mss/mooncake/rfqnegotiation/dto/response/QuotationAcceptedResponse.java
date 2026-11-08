package mss.mooncake.rfqnegotiation.dto.response;
public record QuotationAcceptedResponse(String quotationId, String rfqId, String customerId,
        String supplierId, String storeId, int versionNo, String orderId, String sellerOrderId, String conversionStatus,
        /** Last order status read from Commerce (e.g. CANCELLED); null until first synced. */
        String orderStatus) { }
