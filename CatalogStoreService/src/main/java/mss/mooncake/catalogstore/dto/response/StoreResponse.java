package mss.mooncake.catalogstore.dto.response;

public record StoreResponse(
    Long storeId,
    Long supplierIdRef,
    String storeCode,
    String name,
    String slug,
    String logoUrl,
    String bannerUrl,
    String themeKey,
    String status
) { }
