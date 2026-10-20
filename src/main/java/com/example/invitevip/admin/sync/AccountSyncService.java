package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.AdminNotFoundException;
import com.example.invitevip.admin.database.AdminRepository;
import com.example.invitevip.admin.entity.Admin;
import jakarta.ws.rs.WebApplicationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Service
public class AccountSyncService {
    private final AdminRepository admins;
    private final AccountOutboxRepository jobs;
    private final AccountSyncLockRepository locks;
    private final AccountGateway gateway;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final int maxAttempts;

    public AccountSyncService(AdminRepository admins, AccountOutboxRepository jobs,
                              AccountSyncLockRepository locks, AccountGateway gateway, Clock clock,
                              PlatformTransactionManager manager,
                              @Value("${app.account-sync.max-attempts:10}") int maxAttempts) {
        this.admins = admins;
        this.jobs = jobs;
        this.locks = locks;
        this.gateway = gateway;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        locks.lock().orElseThrow(() -> new IllegalStateException("Keycloak 동기화 스키마 이관이 필요합니다."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Admin admin) {
        var job = jobs.findById(admin.getId()).orElseGet(() -> new AccountOutbox(admin.getId(), clock.instant()));
        job.retry(clock.instant());
        jobs.save(job);
    }

    @Transactional
    public void retry(Long adminId) {
        lock();
        var admin = admins.findById(adminId).orElseThrow(() -> new AdminNotFoundException(adminId));
        if (admin.getState() == Admin.State.UNLINKED) {
            throw new IllegalArgumentException("기존 계정의 사용자 ID 연결을 먼저 검증하세요.");
        }
        enqueue(admin);
    }

    /** Network I/O runs only in the worker, under a bounded DB lock, never in request transactions. */
    public boolean processOne() {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            lock();
            var due = jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscAdminIdAsc(
                    AccountOutbox.Status.PENDING, clock.instant(), PageRequest.of(0, 1));
            if (due.isEmpty()) return false;
            var job = due.get(0);
            var admin = admins.findById(job.getAdminId()).orElseThrow();
            try {
                switch (admin.getState()) {
                    case PENDING_CREATE -> {
                        admin.setKeycloakId(gateway.ensureCreated(admin));
                        // Creation may be a recovered older request; bring the profile up to date.
                        gateway.updateProfile(admin);
                        admin.setState(Admin.State.ACTIVE);
                    }
                    case ACTIVE, BLOCKED -> {
                        if (admin.getKeycloakId() == null) {
                            throw new AccountSyncFailure("Keycloak 사용자 ID 연결을 확인하세요.", true);
                        }
                        gateway.updateProfile(admin);
                        admin.setState(Admin.State.ACTIVE);
                    }
                    case DELETING, DELETED -> {
                        gateway.delete(admin);
                        admin.setState(Admin.State.DELETED);
                    }
                    case UNLINKED -> throw new AccountSyncFailure("기존 계정 연결 검증이 필요합니다.", false);
                }
                job.complete(clock.instant());
            } catch (AccountSyncFailure e) {
                if (e.blocksAccess() && admin.getState() == Admin.State.ACTIVE) {
                    admin.setState(Admin.State.BLOCKED);
                }
                job.fail(e.getMessage(), true, maxAttempts, clock.instant());
            } catch (WebApplicationException e) {
                int code = e.getResponse() == null ? 503 : e.getResponse().getStatus();
                job.fail("Keycloak 요청 실패 (HTTP " + code + "). 설정 또는 서버 상태를 확인하세요.",
                        code < 500 && code != 429, maxAttempts, clock.instant());
            } catch (Exception e) {
                job.fail("Keycloak 연결 또는 처리에 실패했습니다. 재시도 예정 또는 관리자 확인이 필요합니다.",
                        false, maxAttempts, clock.instant());
            }
            return true;
        }));
    }

    public void processPending() {
        for (int i = 0; i < 20 && processOne(); i++) { /* each job commits independently */ }
    }

    @Transactional
    public void reconcile() {
        lock();
        for (Admin admin : admins.findAll()) {
            if (admin.getState() == Admin.State.UNLINKED || admin.getState() == Admin.State.BLOCKED) continue;
            var job = jobs.findById(admin.getId()).orElse(null);
            // Preserve failure evidence and backoff. Deleted tombstones are checked for late remote writes.
            if (job == null || job.getStatus() == AccountOutbox.Status.DONE) {
                enqueue(admin);
            }
        }
    }
}
