package mss.mooncake.identity.service;

import java.util.List;
import mss.mooncake.identity.dto.request.SavePayoutAccountRequest;
import mss.mooncake.identity.dto.response.PayoutAccountResponse;

public interface PayoutAccountService {
    record Bank(String bin, String name) { }
    List<Bank> banks();
    PayoutAccountResponse mine(Long userId, String ownerKind);
    PayoutAccountResponse save(Long userId, String ownerKind, SavePayoutAccountRequest request);
    PayoutAccountResponse customer(Long customerId);
    PayoutAccountResponse supplier(Long supplierId);
}
