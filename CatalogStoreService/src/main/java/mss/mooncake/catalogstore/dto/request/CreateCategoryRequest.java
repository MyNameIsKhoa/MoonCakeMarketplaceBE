package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "slug chỉ gồm a-z, 0-9 và dấu '-'") String slug,
        Long parentCategoryId) {
}
