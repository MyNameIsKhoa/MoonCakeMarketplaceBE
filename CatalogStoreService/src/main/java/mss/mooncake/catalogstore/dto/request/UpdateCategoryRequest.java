package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Field null = giữ nguyên. status: ACTIVE | INACTIVE. */
public record UpdateCategoryRequest(
                @Size(min = 1, max = 255) String name,
                @Size(min = 1, max = 255) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "slug chỉ gồm a-z, 0-9 và dấu '-'") String slug,
                Long parentCategoryId,
                String status) {
}
