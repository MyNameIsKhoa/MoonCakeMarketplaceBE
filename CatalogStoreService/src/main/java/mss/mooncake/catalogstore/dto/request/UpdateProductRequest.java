package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Field null = giữ nguyên. Không cho đổi storeId/productCode. status: DRAFT |
 * ACTIVE | INACTIVE.
 */
public record UpdateProductRequest(
                Long categoryId,
                @Size(min = 1, max = 255) String name,
                String description,
                String status) {
}
