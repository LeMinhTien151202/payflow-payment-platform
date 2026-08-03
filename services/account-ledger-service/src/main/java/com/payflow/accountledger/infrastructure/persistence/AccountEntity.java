package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.AccountStatus;
import com.payflow.accountledger.account.domain.model.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accounts", schema = "account")
class AccountEntity {

    @Id
    private UUID id;

    // CHAR(3) trong DDL migration, do đó mapping ở đây cũng phải là CHAR. Nếu không có điều này, Hibernate
    // sẽ mong đợi varchar(3), tìm thấy bpchar trong DB, và ddl-auto=validate từ chối khởi chạy service.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Version
    private long version;

    protected AccountEntity() {}

    Account toAccount() {
        return Account.rehydrate(
                id,
                currency,
                new Money(availableBalance, currency),
                new Money(reservedBalance, currency),
                status);
    }

    void apply(Account account) {
        if (!id.equals(account.id())) {
            throw new IllegalArgumentException("cannot apply a different Account aggregate");
        }
        availableBalance = account.availableBalance().amount();
        reservedBalance = account.reservedBalance().amount();
        status = account.status();
    }
}
