package com.payflow.accountledger.account.application.port;

import com.payflow.accountledger.account.domain.model.Account;
import com.payflow.accountledger.account.domain.model.Reservation;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for Account reservation transactions. */
public interface AccountReservationStore {

    Optional<Account> findAccountForUpdate(UUID accountId);

    Optional<Reservation> findReservationByPaymentId(UUID paymentId);

    Optional<Reservation> findReservationForUpdate(UUID reservationId);

    void updateAccount(Account account);

    void saveReservation(Reservation reservation);

    void updateReservation(Reservation reservation);
}
