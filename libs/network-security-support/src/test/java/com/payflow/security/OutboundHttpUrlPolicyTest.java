package com.payflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OutboundHttpUrlPolicyTest {

    @Test
    void productionAcceptsPublicHttpsAndRejectsInternalTargets() {
        assertThat(OutboundHttpUrlPolicy.requireAllowed("https://8.8.8.8/payflow", false).getHost())
                .isEqualTo("8.8.8.8");

        for (String blocked : new String[] {
            "http://8.8.8.8/hook",
            "https://127.0.0.1/hook",
            "https://10.1.2.3/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://user:password@8.8.8.8/hook",
            "file:///etc/passwd"
        }) {
            assertThatThrownBy(() -> OutboundHttpUrlPolicy.requireAllowed(blocked, false))
                    .as(blocked)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void localEscapeHatchIsExplicitAndStillRestrictsTheProtocol() {
        assertThat(OutboundHttpUrlPolicy.requireAllowed("http://127.0.0.1:9090/hook", true)
                        .getScheme())
                .isEqualTo("http");
        assertThatThrownBy(() -> OutboundHttpUrlPolicy.requireAllowed("ftp://127.0.0.1/x", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
