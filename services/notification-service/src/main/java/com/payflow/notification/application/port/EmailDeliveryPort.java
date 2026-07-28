package com.payflow.notification.application.port;

import com.payflow.notification.application.delivery.EmailDeliveryResult;
import com.payflow.notification.application.delivery.EmailMessage;

@FunctionalInterface
public interface EmailDeliveryPort {

    EmailDeliveryResult deliver(EmailMessage message);
}
