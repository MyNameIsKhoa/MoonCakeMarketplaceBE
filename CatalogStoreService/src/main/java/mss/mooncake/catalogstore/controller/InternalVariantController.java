package mss.mooncake.catalogstore.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.VariantSnapshotBatchRequest;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/variants")
@Hidden
public class InternalVariantController {
    private final CatalogApplicationService catalog;

    public InternalVariantController(CatalogApplicationService catalog) { this.catalog = catalog; }

    @PostMapping("/snapshots")
    public List<VariantSnapshotResponse> snapshots(@Valid @RequestBody VariantSnapshotBatchRequest request) {
        return catalog.getVariantSnapshots(request.variantIds());
    }
}
