package mss.mooncake.identity.service;

import java.util.List;
import mss.mooncake.identity.dto.request.AddressRequest;
import mss.mooncake.identity.dto.request.UpdateAddressRequest;
import mss.mooncake.identity.dto.response.AddressResponse;
import mss.mooncake.identity.dto.response.AddressSummaryResponse;

public interface AddressApplicationService {
    List<AddressResponse> getCustomerAddresses(Long userId);
    AddressResponse getCustomerAddress(Long userId, Long addressId);
    AddressResponse createAddress(Long userId, AddressRequest request);
    AddressResponse updateAddress(Long userId, Long addressId, UpdateAddressRequest request);
    void deleteAddress(Long userId, Long addressId);
    AddressSummaryResponse getAddressSummary(Long addressId);
}
