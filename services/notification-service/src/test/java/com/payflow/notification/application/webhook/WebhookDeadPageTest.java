package com.payflow.notification.application.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookDeadPageTest {

    @Test
    void calculatesTotalPagesWithoutAnExtraEmptyPage() {
        assertThat(WebhookDeadPage.of(List.of(), 0, 20, 40).totalPages()).isEqualTo(2);
        assertThat(WebhookDeadPage.of(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
        assertThat(WebhookDeadPage.of(List.of(), 0, 20, 0).totalPages()).isZero();
    }

    @Test
    void resultItemsCannotBeMutatedByApiCallers() {
        WebhookDeadPage page = WebhookDeadPage.of(List.of(), 0, 20, 0);

        assertThatThrownBy(() -> page.items().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidPaginationBeforeCalculatingPages() {
        assertThatThrownBy(() -> WebhookDeadPage.of(List.of(), -1, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookDeadPage.of(List.of(), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookDeadPage.of(List.of(), 0, 101, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookDeadPage.of(List.of(), 0, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
