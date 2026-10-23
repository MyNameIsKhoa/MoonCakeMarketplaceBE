package mss.mooncake.catalogstore.dto.response;

public record StoreBranchResponse(
    Long branchId,
    Long storeId,
    String name,
    String addressLine,
    String ward,
    String district,
    String city,
    String phone,
    String openingHours,
    boolean primary,
    String status
) { }
