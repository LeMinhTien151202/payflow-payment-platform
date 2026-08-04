package com.payflow.payment.application.port;

import com.payflow.payment.application.audit.AuditRecord;

/** Append-only persistence boundary for privileged operations audit evidence. */
public interface AuditLogAppender {

    void append(AuditRecord record);
}
