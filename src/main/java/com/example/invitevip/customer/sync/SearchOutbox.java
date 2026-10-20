package com.example.invitevip.customer.sync;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "customer_search_outbox", indexes = {
        @Index(name = "ix_search_outbox_due", columnList = "status,nextAttemptAt,id"),
        @Index(name = "ix_search_outbox_customer", columnList = "customerId,id")
})
@Getter
@NoArgsConstructor
public class SearchOutbox {
    public enum Status { PENDING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    // Immutable snapshot: the outbox ID is also its Elasticsearch external version.
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant processedAt;
    @Column(length = 1000)
    private String lastError;

    public SearchOutbox(Long customerId, String payload, Instant now) {
        this.customerId = customerId;
        this.payload = payload;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void complete(Instant now) {
        status = Status.DONE;
        processedAt = now;
        lastError = null;
    }

    public void fail(Exception failure, Instant now, int maxAttempts) {
        attempts++;
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        lastError = message.substring(0, Math.min(1000, message.length()));
        status = attempts >= maxAttempts ? Status.FAILED : Status.PENDING;
        nextAttemptAt = now.plusSeconds(Math.min(300, 1L << Math.min(attempts, 9)));
    }

    public void retry(Instant now) {
        status = Status.PENDING;
        attempts = 0;
        nextAttemptAt = now;
        lastError = null;
        processedAt = null;
    }
}
