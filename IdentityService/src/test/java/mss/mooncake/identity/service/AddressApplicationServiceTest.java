package mss.mooncake.identity.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import mss.mooncake.identity.dto.request.AddressRequest;
import mss.mooncake.identity.dto.request.UpdateAddressRequest;
import mss.mooncake.identity.dto.response.AddressResponse;
import mss.mooncake.identity.dto.response.AddressSummaryResponse;
import mss.mooncake.identity.entity.AddressEntity;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import mss.mooncake.identity.exception.ForbiddenException;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.repository.AddressRepository;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.service.impl.AddressApplicationServiceImpl;
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
class AddressApplicationServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    private AddressApplicationServiceImpl addressService;

    private CustomerProfileEntity customerProfileA;

    @BeforeEach
    void setUp() {
        addressService = new AddressApplicationServiceImpl(addressRepository, customerProfileRepository);

        customerProfileA = new CustomerProfileEntity(1L, "User A", "0901234567", LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(customerProfileA, "id", 100L);
    }

    @Test
    void createAddress_FirstAddress_AutomaticallyBecomesDefault() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));
        when(addressRepository.countByCustomerId(100L)).thenReturn(0L);

        AddressRequest request = new AddressRequest(
                "Nguyen Van A", "0901234567", "123 Le Loi", "Ben Nghe", "District 1", "TP Ho Chi Minh", false
        );

        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(invocation -> {
            AddressEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 10L);
            return entity;
        });

        AddressResponse response = addressService.createAddress(1L, request);

        assertNotNull(response);
        assertEquals(10L, response.addressId());
        assertEquals(100L, response.customerId());
        assertTrue(response.isDefault(), "First address must be set to default");
        verify(addressRepository, never()).resetDefaultAddressByCustomerId(any());
        verify(addressRepository).save(any(AddressEntity.class));
    }

    @Test
    void createAddress_WithIsDefaultTrue_ResetsExistingDefaults() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));
        when(addressRepository.countByCustomerId(100L)).thenReturn(1L);

        AddressRequest request = new AddressRequest(
                "Nguyen Van A", "0901234567", "456 Nguyen Hue", "Ben Nghe", "District 1", "TP Ho Chi Minh", true
        );

        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(invocation -> {
            AddressEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 11L);
            return entity;
        });

        AddressResponse response = addressService.createAddress(1L, request);

        assertNotNull(response);
        assertEquals(11L, response.addressId());
        assertTrue(response.isDefault());
        verify(addressRepository).resetDefaultAddressByCustomerId(100L);
        verify(addressRepository).save(any(AddressEntity.class));
    }

    @Test
    void getCustomerAddresses_ReturnsList() {
        when(customerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(customerProfileA));

        AddressEntity addr1 = new AddressEntity(100L, "A", "0901", "123", "W", "D", "City", true);
        ReflectionTestUtils.setField(addr1, "id", 1L);

        when(addressRepository.findByCustomerIdOrderByDefaultAddressDescIdDesc(100L)).thenReturn(List.of(addr1));

        List<AddressResponse> responses = addressService.getCustomerAddresses(1L);

        assertEquals(1, responses.size());
        assertEquals(1L, responses.get(0).addressId());
        assertTrue(responses.get(0).isDefault());
    }

    @Test
    void updateAddress_Success() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));

        AddressEntity addr1 = new AddressEntity(100L, "A", "0901", "123", "W", "D", "City", false);
        ReflectionTestUtils.setField(addr1, "id", 1L);

        when(addressRepository.findById(1L)).thenReturn(Optional.of(addr1));
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAddressRequest request = new UpdateAddressRequest("A Updated", "0999", null, null, null, null, true);

        AddressResponse response = addressService.updateAddress(1L, 1L, request);

        assertEquals("A Updated", response.recipientName());
        assertEquals("0999", response.recipientPhone());
        assertTrue(response.isDefault());
        verify(addressRepository).resetDefaultAddressByCustomerId(100L);
    }

    @Test
    void updateAddress_UserANotOwnerOfAddressB_ThrowsForbiddenException() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));

        // Address belongs to customerId 200L (User B), not 100L (User A)
        AddressEntity addressB = new AddressEntity(200L, "B", "0902", "456", "W", "D", "City", true);
        ReflectionTestUtils.setField(addressB, "id", 2L);

        when(addressRepository.findById(2L)).thenReturn(Optional.of(addressB));

        UpdateAddressRequest request = new UpdateAddressRequest("A hacker", null, null, null, null, null, null);

        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> addressService.updateAddress(1L, 2L, request));
        assertTrue(ex.getMessage().contains("do not have permission"));
        verify(addressRepository, never()).save(any());
    }

    @Test
    void deleteAddress_UserANotOwnerOfAddressB_ThrowsForbiddenException() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));

        AddressEntity addressB = new AddressEntity(200L, "B", "0902", "456", "W", "D", "City", true);
        ReflectionTestUtils.setField(addressB, "id", 2L);

        when(addressRepository.findById(2L)).thenReturn(Optional.of(addressB));

        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> addressService.deleteAddress(1L, 2L));
        assertTrue(ex.getMessage().contains("do not have permission"));
        verify(addressRepository, never()).delete(any());
    }

    @Test
    void deleteAddress_Success_PromotesRemainingAddressIfDeletedWasDefault() {
        when(customerProfileRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(customerProfileA));

        AddressEntity addr1 = new AddressEntity(100L, "A1", "0901", "123", "W", "D", "City", true);
        ReflectionTestUtils.setField(addr1, "id", 1L);

        AddressEntity addr2 = new AddressEntity(100L, "A2", "0901", "456", "W", "D", "City", false);
        ReflectionTestUtils.setField(addr2, "id", 2L);

        when(addressRepository.findById(1L)).thenReturn(Optional.of(addr1));
        when(addressRepository.findFirstByCustomerIdOrderByIdAsc(100L)).thenReturn(Optional.of(addr2));

        addressService.deleteAddress(1L, 1L);

        verify(addressRepository).delete(addr1);
        verify(addressRepository).save(addr2);
        assertTrue(addr2.isDefaultAddress(), "Remaining address should be promoted to default");
    }

    @Test
    void getAddressSummary_Success() {
        AddressEntity addr = new AddressEntity(100L, "Recipient", "0901234567", "123 Street", "Ward 1", "Dist 1", "City", true);
        ReflectionTestUtils.setField(addr, "id", 1L);

        when(addressRepository.findById(1L)).thenReturn(Optional.of(addr));

        AddressSummaryResponse summary = addressService.getAddressSummary(1L);

        assertNotNull(summary);
        assertEquals(1L, summary.addressId());
        assertEquals(100L, summary.customerId());
        assertEquals("Recipient", summary.recipientName());
        assertEquals("123 Street", summary.addressLine());
    }
}
