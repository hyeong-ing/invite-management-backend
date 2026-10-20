package com.example.invitevip.customer.sync;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchOutboxTests {
    @Test void retryDelayIsBoundedAndErrorsAreTruncated() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        SearchOutbox event = new SearchOutbox(1L, "{}", now);
        for (int i = 0; i < 15; i++) event.fail(new IllegalStateException("x".repeat(2000)), now, 20);
        assertThat(event.getStatus()).isEqualTo(SearchOutbox.Status.PENDING);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(300));
        assertThat(event.getLastError()).hasSize(1000);
    }
}
