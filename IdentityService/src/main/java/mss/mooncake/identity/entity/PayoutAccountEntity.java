package mss.mooncake.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payout_account", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "owner_kind"}))
@AttributeOverride(name = "id", column = @Column(name = "payout_account_id"))
public class PayoutAccountEntity extends BaseIdentityEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "owner_kind", nullable = false, length = 10) private String ownerKind;
    @Column(name = "bank_bin", nullable = false, length = 6) private String bankBin;
    @Column(name = "bank_name", nullable = false, length = 100) private String bankName;
    @Column(name = "account_number", nullable = false, length = 19) private String accountNumber;
    @Column(name = "account_holder", nullable = false, length = 100) private String accountHolder;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getOwnerKind() { return ownerKind; }
    public void setOwnerKind(String value) { ownerKind = value; }
    public String getBankBin() { return bankBin; }
    public void setBankBin(String value) { bankBin = value; }
    public String getBankName() { return bankName; }
    public void setBankName(String value) { bankName = value; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String value) { accountNumber = value; }
    public String getAccountHolder() { return accountHolder; }
    public void setAccountHolder(String value) { accountHolder = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
