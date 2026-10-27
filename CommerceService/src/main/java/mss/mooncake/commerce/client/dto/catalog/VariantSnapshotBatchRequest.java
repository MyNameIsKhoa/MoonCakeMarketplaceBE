package mss.mooncake.commerce.client.dto.catalog;

import java.util.List;

public record VariantSnapshotBatchRequest(List<Long> variantIds) { }
