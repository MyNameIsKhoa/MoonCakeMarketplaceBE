package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierProfileRequest(
    @NotBlank(message = "Company name is required")
    @Size(max = 255, message = "Company name cannot exceed 255 characters")
    String companyName,

    @NotBlank(message = "Tax code is required")
    @Size(max = 100, message = "Tax code cannot exceed 100 characters")
    String taxCode
) { }
