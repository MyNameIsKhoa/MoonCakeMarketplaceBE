package mss.mooncake.identity.service;

import java.time.LocalDateTime;
import java.util.Optional;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import mss.mooncake.identity.exception.DuplicateResourceException;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.repository.SupplierVerificationAuditRepository;
import mss.mooncake.identity.repository.UserRepository;
import mss.mooncake.identity.service.impl.SupplierApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierApplicationServiceTest {

    @Mock
    private SupplierProfileRepository supplierProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SupplierVerificationAuditRepository auditRepository;

    private SupplierApplicationServiceImpl supplierService;

    @BeforeEach
    void setUp() {
        supplierService = new SupplierApplicationServiceImpl(supplierProfileRepository, userRepository, auditRepository);
    }

    @Test
    void registerSupplier_Success() {
        SupplierProfileRequest request = new SupplierProfileRequest("Kinh Do Mooncakes", "0123456789");

        when(userRepository.existsById(1L)).thenReturn(true);
        when(supplierProfileRepository.existsByUserId(1L)).thenReturn(false);
        when(supplierProfileRepository.existsByTaxCode("0123456789")).thenReturn(false);

        when(supplierProfileRepository.save(any(SupplierProfileEntity.class))).thenAnswer(invocation -> {
            SupplierProfileEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 50L);
            return entity;
        });

        SupplierProfileResponse response = supplierService.registerSupplier(1L, request);

        assertNotNull(response);
        assertEquals(50L, response.supplierId());
        assertEquals(1L, response.userId());
        assertEquals("Kinh Do Mooncakes", response.companyName());
        assertEquals("0123456789", response.taxCode());
        assertEquals("PENDING", response.verificationStatus());
        verify(supplierProfileRepository).save(any(SupplierProfileEntity.class));
    }

    @Test
    void registerSupplier_UserNotFound_ThrowsResourceNotFoundException() {
        SupplierProfileRequest request = new SupplierProfileRequest("Kinh Do", "0123456789");
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> supplierService.registerSupplier(99L, request));
        verify(supplierProfileRepository, never()).save(any());
    }

    @Test
    void registerSupplier_UserAlreadyRegistered_ThrowsDuplicateResourceException() {
        SupplierProfileRequest request = new SupplierProfileRequest("Kinh Do", "0123456789");
        when(userRepository.existsById(1L)).thenReturn(true);
        when(supplierProfileRepository.existsByUserId(1L)).thenReturn(true);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
                () -> supplierService.registerSupplier(1L, request));
        assertTrue(ex.getMessage().contains("already registered as a supplier"));
        verify(supplierProfileRepository, never()).save(any());
    }

    @Test
    void registerSupplier_DuplicateTaxCode_ThrowsDuplicateResourceException() {
        SupplierProfileRequest request = new SupplierProfileRequest("Kinh Do", "0123456789");
        when(userRepository.existsById(1L)).thenReturn(true);
        when(supplierProfileRepository.existsByUserId(1L)).thenReturn(false);
        when(supplierProfileRepository.existsByTaxCode("0123456789")).thenReturn(true);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
                () -> supplierService.registerSupplier(1L, request));
        assertTrue(ex.getMessage().contains("Tax code is already registered"));
        verify(supplierProfileRepository, never()).save(any());
    }

    @Test
    void getSupplierProfile_Success() {
        SupplierProfileEntity entity = new SupplierProfileEntity(1L, "Kinh Do", "0123456789", "PENDING", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(entity, "id", 50L);

        when(supplierProfileRepository.findByUserId(1L)).thenReturn(Optional.of(entity));

        SupplierProfileResponse response = supplierService.getSupplierProfile(1L);

        assertNotNull(response);
        assertEquals(50L, response.supplierId());
        assertEquals("Kinh Do", response.companyName());
    }

    @Test
    void getSupplierProfile_NotFound_ThrowsResourceNotFoundException() {
        when(supplierProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> supplierService.getSupplierProfile(1L));
    }

    @Test
    void updateSupplierProfile_Success() {
        SupplierProfileEntity entity = new SupplierProfileEntity(1L, "Kinh Do", "0123456789", "PENDING", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(entity, "id", 50L);

        when(supplierProfileRepository.findByUserId(1L)).thenReturn(Optional.of(entity));
        when(supplierProfileRepository.existsByTaxCode("0987654321")).thenReturn(false);

        UpdateSupplierProfileRequest request = new UpdateSupplierProfileRequest("Kinh Do Updated", "0987654321");
        SupplierProfileResponse response = supplierService.updateSupplierProfile(1L, request);

        assertEquals("Kinh Do Updated", response.companyName());
        assertEquals("0987654321", response.taxCode());
        verify(supplierProfileRepository).save(entity);
    }

    @Test
    void getSupplierSummary_Success() {
        SupplierProfileEntity entity = new SupplierProfileEntity(1L, "Kinh Do", "0123456789", "VERIFIED", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(entity, "id", 50L);

        when(supplierProfileRepository.findById(50L)).thenReturn(Optional.of(entity));

        SupplierSummaryResponse summary = supplierService.getSupplierSummary(50L);

        assertNotNull(summary);
        assertEquals(50L, summary.supplierId());
        assertEquals(1L, summary.userId());
        assertEquals("Kinh Do", summary.companyName());
        assertEquals("0123456789", summary.taxCode());
        assertEquals("VERIFIED", summary.verificationStatus());
    }
}
