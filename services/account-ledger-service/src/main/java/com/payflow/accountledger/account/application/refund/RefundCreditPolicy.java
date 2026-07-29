package com.payflow.accountledger.account.application.refund;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.AccountStatus;
import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.accountledger.account.domain.model.RefundCredit;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pure idempotent Account credit policy; persistence supplies the existing refund fact. */
public final class RefundCreditPolicy {

    public RefundCreditResult credit(
            Account account,
            RefundCredit existing,
            AccountRefundCreditRequestedData command,
            UUID creditId,
            Instant occurredAt) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireAccountIntent(account, command);

        if (existing != null) {
            requireSameIntent(existing, command);
            return result(existing, true);
        }
        if (account.status() == AccountStatus.CLOSED) {
            throw new AccountInvariantViolationException(
                    "closed account cannot receive an automatic refund credit");
        }

        Money amount = new Money(command.amount(), command.currency());
        account.creditRefund(amount);
        RefundCredit credit = new RefundCredit(
                creditId,
                command.refundId(),
                command.paymentId(),
                command.accountId(),
                command.journalId(),
                amount,
                occurredAt);
        return result(credit, false);
    }

    private static RefundCreditResult result(RefundCredit credit, boolean duplicate) {
        return new RefundCreditResult(
                credit,
                new AccountRefundCreditedData(
                        credit.refundId(),
                        credit.paymentId(),
                        credit.accountId(),
                        credit.journalId(),
                        credit.id(),
                        credit.amount().amount(),
                        credit.amount().currency()),
                duplicate);
    }

    private static void requireAccountIntent(
            Account account, AccountRefundCreditRequestedData command) {
        if (!account.id().equals(command.accountId())) {
            throw new AccountInvariantViolationException(
                    "refund credit command belongs to another account");
        }
        if (!account.currency().equals(command.currency())) {
            throw new AccountInvariantViolationException(
                    "refund credit currency does not match account");
        }
    }

    private static void requireSameIntent(
            RefundCredit existing, AccountRefundCreditRequestedData command) {
        boolean same = existing.refundId().equals(command.refundId())
                && existing.paymentId().equals(command.paymentId())
                && existing.accountId().equals(command.accountId())
                && existing.journalId().equals(command.journalId())
                && existing.amount().amount().compareTo(command.amount()) == 0
                && existing.amount().currency().equals(command.currency());
        if (!same) {
            throw new AccountInvariantViolationException(
                    "existing refund credit does not match duplicate intent");
        }
    }
}
