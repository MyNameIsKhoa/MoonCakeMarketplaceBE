package mss.mooncake.commerce.client.dto.catalog;

public record StoreSummaryResponse(Long storeId, Long supplierIdRef, String storeCode,
                                   String name, String slug, String logoUrl, String status) { }
