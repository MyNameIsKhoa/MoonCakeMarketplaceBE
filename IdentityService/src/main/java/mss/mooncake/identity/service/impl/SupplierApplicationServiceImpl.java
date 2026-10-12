package mss.mooncake.identity.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import mss.mooncake.identity.exception.DuplicateResourceException;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.repository.UserRepository;
import mss.mooncake.identity.service.SupplierApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierApplicationServiceImpl implements SupplierApplicationService {

    private final SupplierProfileRepository supplierProfileRepository;
    private final UserRepository userRepository;

    public SupplierApplicationServiceImpl(SupplierProfileRepository supplierProfileRepository,
                                          UserRepository userRepository) {
        this.supplierProfileRepository = supplierProfileRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public SupplierProfileResponse registerSupplier(Long userId, SupplierProfileRequest request) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }

        if (supplierProfileRepository.existsByUserId(userId)) {
            throw new DuplicateResourceException("User is already registered as a supplier: " + userId);
        }

        String normalizedTaxCode = request.taxCode().trim();
        if (supplierProfileRepository.existsByTaxCode(normalizedTaxCode)) {
            throw new DuplicateResourceException("Tax code is already registered: " + normalizedTaxCode);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SupplierProfileEntity entity = new SupplierProfileEntity(
                userId,
                request.companyName().trim(),
                normalizedTaxCode,
                "PENDING",
                now,
                now
        );

        SupplierProfileEntity saved = supplierProfileRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierProfileResponse getSupplierProfile(Long userId) {
        SupplierProfileEntity entity = supplierProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier profile not found for user: " + userId));
        return toResponse(entity);
    }

    @Override
    @Transactional
    public SupplierProfileResponse updateSupplierProfile(Long userId, UpdateSupplierProfileRequest request) {
        SupplierProfileEntity entity = supplierProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier profile not found for user: " + userId));

        boolean updated = false;
        if (request.companyName() != null && !request.companyName().isBlank()) {
            entity.setCompanyName(request.companyName().trim());
            updated = true;
        }

        if (request.taxCode() != null && !request.taxCode().isBlank()) {
            String newTaxCode = request.taxCode().trim();
            if (!newTaxCode.equalsIgnoreCase(entity.getTaxCode())) {
                if (supplierProfileRepository.existsByTaxCode(newTaxCode)) {
                    throw new DuplicateResourceException("Tax code is already registered: " + newTaxCode);
                }
                entity.setTaxCode(newTaxCode);
                updated = true;
            }
        }

        if (updated) {
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            supplierProfileRepository.save(entity);
        }

        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierSummaryResponse getSupplierSummary(Long supplierId) {
        SupplierProfileEntity entity = supplierProfileRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + supplierId));

        return new SupplierSummaryResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getCompanyName(),
                entity.getTaxCode(),
                entity.getVerificationStatus()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierSummaryResponse getSupplierSummaryByUserId(Long userId) {
        SupplierProfileEntity entity = supplierProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier profile not found for user: " + userId));

        return new SupplierSummaryResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getCompanyName(),
                entity.getTaxCode(),
                entity.getVerificationStatus()
        );
    }

    private SupplierProfileResponse toResponse(SupplierProfileEntity entity) {
        return new SupplierProfileResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getCompanyName(),
                entity.getTaxCode(),
                entity.getVerificationStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
