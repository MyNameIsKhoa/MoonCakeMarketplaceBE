package mss.mooncake.identity.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.identity.dto.request.SavePayoutAccountRequest;
import mss.mooncake.identity.dto.response.PayoutAccountResponse;
import mss.mooncake.identity.entity.PayoutAccountEntity;
import mss.mooncake.identity.exception.ResourceNotFoundException;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.PayoutAccountRepository;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.service.PayoutAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutAccountServiceImpl implements PayoutAccountService {
    // BINs from VietQR's supported transfer list; expand only with a verified BIN.
    private static final List<Bank> BANKS = List.of(
            new Bank("970415", "VietinBank"), new Bank("970436", "Vietcombank"),
            new Bank("970418", "BIDV"), new Bank("970405", "Agribank"),
            new Bank("970422", "MBBank"), new Bank("970407", "Techcombank"),
            new Bank("970416", "ACB"), new Bank("970432", "VPBank"),
            new Bank("970423", "TPBank"), new Bank("970403", "Sacombank"),
            new Bank("971025", "MoMo"));

    private final PayoutAccountRepository accounts;
    private final CustomerProfileRepository customers;
    private final SupplierProfileRepository suppliers;

    public PayoutAccountServiceImpl(PayoutAccountRepository accounts, CustomerProfileRepository customers,
            SupplierProfileRepository suppliers) {
        this.accounts = accounts; this.customers = customers; this.suppliers = suppliers;
    }

    @Override public List<Bank> banks() { return BANKS; }

    @Override @Transactional(readOnly = true)
    public PayoutAccountResponse mine(Long userId, String ownerKind) {
        checkOwner(userId, ownerKind);
        return account(userId, ownerKind);
    }

    @Override @Transactional
    public PayoutAccountResponse save(Long userId, String ownerKind, SavePayoutAccountRequest request) {
        checkOwner(userId, ownerKind);
        Bank bank = BANKS.stream().filter(b -> b.bin().equals(request.bankBin())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ngân hàng hoặc ví chưa được hỗ trợ."));
        String holder = request.accountHolder().strip().replaceAll("\\s+", " ");
        if (holder.isEmpty()) throw new IllegalArgumentException("Tên chủ tài khoản không được để trống.");
        if (bank.name().equals("MoMo") && !request.accountNumber().matches("[0-9]{10}"))
            throw new IllegalArgumentException("Số điện thoại MoMo phải có 10 chữ số.");
        PayoutAccountEntity account = accounts.findByUserIdAndOwnerKind(userId, ownerKind)
                .orElseGet(PayoutAccountEntity::new);
        account.setUserId(userId); account.setOwnerKind(ownerKind); account.setBankBin(bank.bin());
        account.setBankName(bank.name()); account.setAccountNumber(request.accountNumber());
        account.setAccountHolder(holder); account.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return response(accounts.save(account));
    }

    @Override @Transactional(readOnly = true)
    public PayoutAccountResponse customer(Long customerId) {
        var customer = customers.findById(customerId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người mua."));
        return account(customer.getUserId(), "CUSTOMER");
    }

    @Override @Transactional(readOnly = true)
    public PayoutAccountResponse supplier(Long supplierId) {
        var supplier = suppliers.findById(supplierId).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy nhà cung cấp."));
        return account(supplier.getUserId(), "SUPPLIER");
    }

    private void checkOwner(Long userId, String ownerKind) {
        switch (ownerKind) {
            case "CUSTOMER" -> customers.findByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Chưa có hồ sơ người mua."));
            case "SUPPLIER" -> suppliers.findByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Chưa có hồ sơ nhà cung cấp."));
            default -> throw new IllegalArgumentException("Loại tài khoản nhận tiền không hợp lệ.");
        }
    }

    private PayoutAccountResponse account(Long userId, String ownerKind) {
        return accounts.findByUserIdAndOwnerKind(userId, ownerKind).map(PayoutAccountServiceImpl::response)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa cấu hình tài khoản nhận tiền."));
    }

    private static PayoutAccountResponse response(PayoutAccountEntity account) {
        return new PayoutAccountResponse(account.getOwnerKind(), account.getBankBin(), account.getBankName(),
                account.getAccountNumber(), account.getAccountHolder(), account.getUpdatedAt());
    }
}
