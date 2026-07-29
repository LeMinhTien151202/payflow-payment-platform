package com.payflow.payment.application.saga;

import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.payment.domain.model.PaymentSagaStep;
import java.util.Objects;

/** Deterministic next action chosen by the recovery scheduler/consumer. */
public sealed interface SagaRecoveryAction
        permits SagaRecoveryAction.RetryStep,
                SagaRecoveryAction.ReleaseFunds,
                SagaRecoveryAction.ManualReview,
                SagaRecoveryAction.NoAction {

    record RetryStep(PaymentSagaStep step, int attempt, String reasonCode)
            implements SagaRecoveryAction {
        public RetryStep {
            Objects.requireNonNull(step, "step");
            if (attempt <= 0) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }

    record ReleaseFunds(AccountReleaseRequestedData command) implements SagaRecoveryAction {
        public ReleaseFunds {
            Objects.requireNonNull(command, "command");
        }
    }

    record ManualReview(PaymentManualReviewRequiredData eventData)
            implements SagaRecoveryAction {
        public ManualReview {
            Objects.requireNonNull(eventData, "eventData");
        }
    }

    record NoAction(String reason) implements SagaRecoveryAction {
        public NoAction {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

