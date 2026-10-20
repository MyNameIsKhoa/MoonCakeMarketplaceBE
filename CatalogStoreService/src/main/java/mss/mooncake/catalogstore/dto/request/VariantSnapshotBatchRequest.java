package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** IN07: tối đa 100 variant mỗi lần; ID không tồn tại trả 404, không âm thầm bỏ qua. */
public record VariantSnapshotBatchRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> variantIds) { }
