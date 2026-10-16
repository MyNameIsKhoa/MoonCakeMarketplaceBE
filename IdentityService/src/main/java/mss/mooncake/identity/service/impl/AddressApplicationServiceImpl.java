package mss.mooncake.identity.service.impl;

import java.util.List;
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
import mss.mooncake.identity.service.AddressApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AddressApplicationServiceImpl implements AddressApplicationService {

    private final AddressRepository addressRepository;
    private final CustomerProfileRepository customerProfileRepository;

    public AddressApplicationServiceImpl(AddressRepository addressRepository,
                                         CustomerProfileRepository customerProfileRepository) {
        this.addressRepository = addressRepository;
        this.customerProfileRepository = customerProfileRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> getCustomerAddresses(Long userId) {
        CustomerProfileEntity customerProfile = getCustomerProfile(userId);
        List<AddressEntity> addresses = addressRepository
                .findByCustomerIdOrderByDefaultAddressDescIdDesc(customerProfile.getId());
        return addresses.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse getCustomerAddress(Long userId, Long addressId) {
        Long customerId = getCustomerProfile(userId).getId();
        AddressEntity address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));
        if (!address.getCustomerId().equals(customerId)) {
            throw new ForbiddenException("You do not have permission to view this address");
        }
        return toResponse(address);
    }

    @Override
    @Transactional
    public AddressResponse createAddress(Long userId, AddressRequest request) {
        CustomerProfileEntity customerProfile = getCustomerProfileForUpdate(userId);
        Long customerId = customerProfile.getId();

        long count = addressRepository.countByCustomerId(customerId);
        boolean shouldBeDefault = Boolean.TRUE.equals(request.isDefault()) || count == 0;

        if (shouldBeDefault && count > 0) {
            addressRepository.resetDefaultAddressByCustomerId(customerId);
        }

        AddressEntity entity = new AddressEntity(
                customerId,
                request.recipientName().trim(),
                request.recipientPhone().trim(),
                request.addressLine().trim(),
                request.ward() != null ? request.ward().trim() : null,
                request.district() != null ? request.district().trim() : null,
                request.city().trim(),
                shouldBeDefault
        );

        AddressEntity saved = addressRepository.save(entity);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public AddressResponse updateAddress(Long userId, Long addressId, UpdateAddressRequest request) {
        CustomerProfileEntity customerProfile = getCustomerProfileForUpdate(userId);
        Long customerId = customerProfile.getId();

        AddressEntity address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        if (!address.getCustomerId().equals(customerId)) {
            throw new ForbiddenException("You do not have permission to modify this address");
        }

        if (request.recipientName() != null && !request.recipientName().isBlank()) {
            address.setRecipientName(request.recipientName().trim());
        }
        if (request.recipientPhone() != null && !request.recipientPhone().isBlank()) {
            address.setRecipientPhone(request.recipientPhone().trim());
        }
        if (request.addressLine() != null && !request.addressLine().isBlank()) {
            address.setAddressLine(request.addressLine().trim());
        }
        if (request.ward() != null) {
            address.setWard(request.ward().trim());
        }
        if (request.district() != null) {
            address.setDistrict(request.district().trim());
        }
        if (request.city() != null && !request.city().isBlank()) {
            address.setCity(request.city().trim());
        }
        if (request.isDefault() != null) {
            if (Boolean.TRUE.equals(request.isDefault())) {
                addressRepository.resetDefaultAddressByCustomerId(customerId);
                address.setDefaultAddress(false);
                addressRepository.flush();
                address.setDefaultAddress(true);
            } else {
                if (address.isDefaultAddress()) {
                    throw new mss.mooncake.identity.exception.InvalidStateTransitionException(
                            "The default address cannot be unset; select another default address instead");
                }
                address.setDefaultAddress(false);
            }
        }

        AddressEntity updated = addressRepository.save(address);
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteAddress(Long userId, Long addressId) {
        CustomerProfileEntity customerProfile = getCustomerProfileForUpdate(userId);
        Long customerId = customerProfile.getId();

        AddressEntity address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        if (!address.getCustomerId().equals(customerId)) {
            throw new ForbiddenException("You do not have permission to delete this address");
        }

        boolean wasDefault = address.isDefaultAddress();
        addressRepository.delete(address);

        if (wasDefault) {
            addressRepository.findFirstByCustomerIdOrderByIdAsc(customerId)
                    .ifPresent(remaining -> {
                        remaining.setDefaultAddress(true);
                        addressRepository.save(remaining);
                    });
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AddressSummaryResponse getAddressSummary(Long addressId) {
        AddressEntity address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found with id: " + addressId));

        return new AddressSummaryResponse(
                address.getId(),
                address.getCustomerId(),
                address.getRecipientName(),
                address.getRecipientPhone(),
                address.getAddressLine(),
                address.getWard(),
                address.getDistrict(),
                address.getCity()
        );
    }

    private CustomerProfileEntity getCustomerProfile(Long userId) {
        return customerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found for user id: " + userId));
    }

    private CustomerProfileEntity getCustomerProfileForUpdate(Long userId) {
        return customerProfileRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile not found for user id: " + userId));
    }

    private AddressResponse toResponse(AddressEntity entity) {
        return new AddressResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getRecipientName(),
                entity.getRecipientPhone(),
                entity.getAddressLine(),
                entity.getWard(),
                entity.getDistrict(),
                entity.getCity(),
                entity.isDefaultAddress()
        );
    }
}
