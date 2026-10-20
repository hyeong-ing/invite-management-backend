package com.example.invitevip.customer.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.search-sync.enabled", havingValue = "true", matchIfMissing = true)
public class SearchSyncScheduler {
    private final SearchSyncService sync;

    @Scheduled(fixedDelayString = "${app.search-sync.poll-delay-ms:2000}", initialDelayString = "2000")
    public void deliver() {
        try {
            sync.processPending();
        } catch (Exception e) {
            log.error("Search outbox processing failed; committed work will be retried", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.search-sync.reconcile-delay-ms:300000}", initialDelayString = "5000")
    public void reconcile() {
        try {
            int repairs = sync.reconcile();
            if (repairs > 0) log.info("Queued {} search index repairs", repairs);
        } catch (Exception e) {
            log.error("Search reconciliation failed; next scheduled run will retry", e);
        }
    }
}
