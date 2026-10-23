package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.response.StoreBranchResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.StoreBranchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chi nhánh (cửa hàng vật lý) của Store.
 *
 * GET /api/stores/{storeId}/branches — public; owner thấy mọi trạng thái
 * POST /api/stores/{storeId}/branches — supplier owner của store
 * PATCH /api/stores/{storeId}/branches/{branchId} — supplier owner của store
 * DELETE /api/stores/{storeId}/branches/{branchId} — supplier owner của store
 */
@RestController
@RequestMapping("/api/stores/{storeId}/branches")
public class StoreBranchController {

    private final StoreBranchService branchService;

    public StoreBranchController(StoreBranchService branchService) {
        this.branchService = branchService;
    }

    @GetMapping
    public ResponseEntity<List<StoreBranchResponse>> getBranches(@PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(branchService.getBranches(storeId, principal == null ? null : principal.getUserId()));
    }

    @PostMapping
    public ResponseEntity<StoreBranchResponse> createBranch(@PathVariable Long storeId,
            @Valid @RequestBody CreateStoreBranchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        StoreBranchResponse response = branchService.createBranch(storeId, request, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/stores/" + storeId + "/branches/" + response.branchId()))
                .body(response);
    }

    @PatchMapping("/{branchId}")
    public ResponseEntity<StoreBranchResponse> updateBranch(@PathVariable Long storeId,
            @PathVariable Long branchId,
            @Valid @RequestBody UpdateStoreBranchRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(branchService.updateBranch(storeId, branchId, request, principal.getUserId()));
    }

    @DeleteMapping("/{branchId}")
    public ResponseEntity<Void> deleteBranch(@PathVariable Long storeId,
            @PathVariable Long branchId,
            @AuthenticationPrincipal UserPrincipal principal) {
        branchService.deleteBranch(storeId, branchId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
