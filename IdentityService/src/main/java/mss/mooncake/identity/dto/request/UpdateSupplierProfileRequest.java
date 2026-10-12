package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateSupplierProfileRequest(
    @Size(max = 255, message = "Company name cannot exceed 255 characters")
    String companyName,

    @Size(max = 100, message = "Tax code cannot exceed 100 characters")
    String taxCode
) { }
