package mss.mooncake.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mss.mooncake.identity.dto.request.SavePayoutAccountRequest;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import mss.mooncake.identity.entity.PayoutAccountEntity;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.PayoutAccountRepository;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.service.impl.PayoutAccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PayoutAccountServiceTest {
    @Test void supplierAccountCanBeReadOnlyByItsSupplierId() {
        var accounts = mock(PayoutAccountRepository.class);
        var suppliers = mock(SupplierProfileRepository.class);
        var service = new PayoutAccountServiceImpl(accounts, mock(CustomerProfileRepository.class), suppliers);
        var supplier = new SupplierProfileEntity(7L, "Shop", "TAX-7", "VERIFIED",
                LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(supplier, "id", 12L);
        when(suppliers.findByUserId(7L)).thenReturn(Optional.of(supplier));
        when(suppliers.findById(12L)).thenReturn(Optional.of(supplier));
        var persisted = new AtomicReference<PayoutAccountEntity>();
        when(accounts.findByUserIdAndOwnerKind(7L, "SUPPLIER")).thenAnswer(call -> Optional.ofNullable(persisted.get()));
        when(accounts.save(any(PayoutAccountEntity.class))).thenAnswer(call -> {
            PayoutAccountEntity account = call.getArgument(0);
            persisted.set(account);
            return account;
        });
        var saved = service.save(7L, "SUPPLIER", new SavePayoutAccountRequest("971025", "0912345678", "Shop"));
        assertThat(saved.bankName()).isEqualTo("MoMo");
        assertThat(service.supplier(12L).accountNumber()).isEqualTo("0912345678");
        assertThatThrownBy(() -> service.supplier(13L)).hasMessageContaining("nhà cung cấp");
    }
    @Test void savesOnlyOwnCustomerAccountWithServerBankName() {
        var accounts = mock(PayoutAccountRepository.class);
        var customers = mock(CustomerProfileRepository.class);
        var service = new PayoutAccountServiceImpl(accounts, customers, mock(SupplierProfileRepository.class));
        var customer = new CustomerProfileEntity(7L, "Buyer", null, LocalDateTime.now(), LocalDateTime.now());
        ReflectionTestUtils.setField(customer, "id", 9L);
        when(customers.findByUserId(7L)).thenReturn(Optional.of(customer));
        when(customers.findById(9L)).thenReturn(Optional.of(customer));
        when(accounts.save(any(PayoutAccountEntity.class))).thenAnswer(call -> call.getArgument(0));
        var saved = service.save(7L, "CUSTOMER", new SavePayoutAccountRequest("970436", "123456789", "Buyer"));
        assertThat(saved.bankName()).isEqualTo("Vietcombank");
        assertThat(saved.accountNumber()).isEqualTo("123456789");
        assertThatThrownBy(() -> service.mine(8L, "CUSTOMER")).hasMessageContaining("hồ sơ người mua");
        assertThatThrownBy(() -> service.save(7L, "SUPPLIER",
                new SavePayoutAccountRequest("970436", "123456789", "Buyer"))).hasMessageContaining("nhà cung cấp");
        assertThatThrownBy(() -> service.save(7L, "CUSTOMER",
                new SavePayoutAccountRequest("123456", "123456789", "Buyer"))).hasMessageContaining("chưa được hỗ trợ");
    }
}
