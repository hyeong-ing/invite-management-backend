package com.example.invitevip.admin.sync;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** One durable, coalescing desired-state job per account. Never contains credentials. */
@Entity
@Table(name = "admin_account_outbox", indexes = @Index(name = "idx_account_due", columnList = "status,nextAttemptAt"))
@Getter
@NoArgsConstructor
public class AccountOutbox {
    public enum Status { PENDING, DONE, FAILED }
    @Id
    private Long adminId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(length = 500)
    private String lastError;

    public AccountOutbox(Long adminId, Instant now) {
        this.adminId = adminId;
        retry(now);
    }

    public void retry(Instant now) {
        status = Status.PENDING;
        attempts = 0;
        nextAttemptAt = updatedAt = now;
        lastError = null;
    }

    public void complete(Instant now) {
        status = Status.DONE;
        updatedAt = now;
        lastError = null;
    }

    public void fail(String safeMessage, boolean permanent, int maxAttempts, Instant now) {
        attempts++;
        status = permanent || attempts >= maxAttempts ? Status.FAILED : Status.PENDING;
        lastError = safeMessage;
        updatedAt = now;
        nextAttemptAt = now.plusSeconds(Math.min(300, 1L << Math.min(attempts, 9)));
    }
}
