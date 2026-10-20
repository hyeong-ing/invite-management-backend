package com.example.invitevip.admin.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.account-sync.enabled", havingValue = "true", matchIfMissing = true)
public class AccountSyncScheduler {
    private final AccountSyncService sync;

    @Scheduled(fixedDelayString = "${app.account-sync.poll-delay-ms:2000}", initialDelayString = "10000")
    public void deliver() {
        try { sync.processPending(); }
        catch (Exception e) { log.error("Account worker transaction failed; durable jobs remain for retry ({})", e.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelayString = "${app.account-sync.reconcile-delay-ms:300000}", initialDelayString = "15000")
    public void reconcile() {
        try { sync.reconcile(); }
        catch (Exception e) { log.error("Account reconciliation failed ({})", e.getClass().getSimpleName()); }
    }
}
