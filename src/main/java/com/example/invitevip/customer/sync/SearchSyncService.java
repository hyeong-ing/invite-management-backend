package com.example.invitevip.customer.sync;

import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.entity.Customer;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SearchSyncService {
    private final SearchOutboxRepository outbox;
    private final SearchSyncLockRepository locks;
    private final CustomerRepository customers;
    private final CustomerSearchMapper mapper;
    private final ObjectMapper json;
    private final SearchIndexGateway index;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final int batchSize;

    public SearchSyncService(SearchOutboxRepository outbox, SearchSyncLockRepository locks,
                             CustomerRepository customers, CustomerSearchMapper mapper,
                             ObjectMapper json, SearchIndexGateway index, Clock clock,
                             PlatformTransactionManager transactionManager,
                             @Value("${app.search-sync.max-attempts:10}") int maxAttempts,
                             @Value("${app.search-sync.batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.locks = locks;
        this.customers = customers;
        this.mapper = mapper;
        this.json = json;
        this.index = index;
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.batchSize = Math.max(1, batchSize);
        this.transaction = new TransactionTemplate(transactionManager);
        // A fresh DB snapshot after obtaining the gate is essential under MySQL's RR default.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Gate 1 serializes customer changes and assigns outbox versions in commit order.
    // Gate 2 serializes ES writes and maintenance. Maintenance ALWAYS takes 1 then 2.
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeCustomerChange() {
        lock(1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Customer customer) {
        enqueue(mapper.toSearchEntity(customer));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDeletion(Long id) {
        enqueue(CustomerSearchEntity.tombstone(id));
    }

    private void lock(long id) {
        locks.lock(id).orElseThrow(() -> new IllegalStateException(
                "Search sync migration is missing: apply db/search-sync-migration.sql"));
    }

    private SearchOutbox enqueue(CustomerSearchEntity document) {
        try {
            return outbox.save(new SearchOutbox(document.getId(), json.writeValueAsString(document), clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize search snapshot", e);
        }
    }

    public int processPending() {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            Boolean found = transaction.execute(status -> {
                lock(2);
                List<SearchOutbox> due = outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        SearchOutbox.Status.PENDING, clock.instant(), PageRequest.of(0, 1));
                if (due.isEmpty()) return false;
                SearchOutbox event = due.get(0);
                try {
                    CustomerSearchEntity document = json.readValue(event.getPayload(), CustomerSearchEntity.class);
                    index.write(document, Math.multiplyExact(event.getId(), 2L));
                    event.complete(clock.instant());
                } catch (Exception e) {
                    event.fail(e, clock.instant(), maxAttempts);
                    if (event.getStatus() == SearchOutbox.Status.FAILED) {
                        log.error("Search job {} for customer {} exhausted {} attempts",
                                event.getId(), event.getCustomerId(), event.getAttempts(), e);
                    }
                }
                // No PROCESSING state: a crash rolls back this transaction, leaving the event due.
                return true;
            });
            if (!Boolean.TRUE.equals(found)) break;
            processed++;
        }
        return processed;
    }

    public int reconcile() {
        return Objects.requireNonNull(transaction.execute(status -> {
            lock(1);
            lock(2);
            Map<Long, CustomerSearchEntity> actual;
            try {
                actual = index.scan();
            } catch (Exception e) {
                log.error("Cannot scan search index for reconciliation", e);
                throw new SearchSyncUnavailableException("검색 정합성 점검에 실패했습니다. ES 연결과 로그를 확인해주세요.", e);
            }
            Map<Long, CustomerSearchEntity> expected = customers.findAll().stream()
                    .map(mapper::toSearchEntity).collect(Collectors.toMap(CustomerSearchEntity::getId, d -> d));
            // Preserve deletion versions even if an entire index was lost.
            Set<Long> knownIds = new HashSet<>(outbox.customerIds());
            knownIds.addAll(actual.keySet());
            for (Long id : knownIds) expected.putIfAbsent(id, CustomerSearchEntity.tombstone(id));
            int repairs = 0;
            for (var entry : expected.entrySet()) {
                if (entry.getValue().equals(actual.get(entry.getKey()))) continue;
                Optional<SearchOutbox> latest = outbox.findFirstByCustomerIdOrderByIdDesc(entry.getKey());
                String payload;
                try {
                    payload = json.writeValueAsString(entry.getValue());
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
                // A matching failed event remains visible for manual retry; don't reset its budget.
                if (latest.isPresent() && latest.get().getStatus() != SearchOutbox.Status.DONE
                        && latest.get().getPayload().equals(payload)) continue;
                enqueue(entry.getValue());
                repairs++;
            }
            return repairs;
        }));
    }

    public RebuildResult rebuild() {
        return transaction.execute(status -> {
            lock(1);
            lock(2);
            Map<Long, CustomerSearchEntity> documents = customers.findAll().stream()
                    .map(mapper::toSearchEntity).collect(Collectors.toMap(CustomerSearchEntity::getId, d -> d));
            long activeCount = documents.size();
            for (Long id : outbox.customerIds()) documents.putIfAbsent(id, CustomerSearchEntity.tombstone(id));
            // Odd barrier > all committed events; the next event's even version will still win.
            long version = Math.addExact(Math.multiplyExact(outbox.watermark(), 2L), 1L);
            try {
                String target = index.rebuild(new ArrayList<>(documents.values()), version);
                return new RebuildResult(target, activeCount, documents.size() - activeCount);
            } catch (Exception e) {
                log.error("Cannot confirm search index rebuild; check alias before retrying", e);
                throw new SearchSyncUnavailableException(
                        "검색 인덱스 재구축 결과를 확인하지 못했습니다. 상태 확인 후 재시도해주세요.", e);
            }
            // Alias swap can succeed even when the response is lost. Retrying rebuild is safe.
            // No outbox acknowledgement is coupled to the alias swap.
        });
    }

    public int retryFailed() {
        return Objects.requireNonNull(transaction.execute(status -> {
            lock(2);
            List<SearchOutbox> failed = outbox.findByStatusOrderByIdAsc(
                    SearchOutbox.Status.FAILED, PageRequest.of(0, batchSize));
            failed.forEach(event -> event.retry(clock.instant()));
            return failed.size();
        }));
    }

    @Transactional(readOnly = true)
    public SyncStatus status() {
        return new SyncStatus(outbox.countByStatus(SearchOutbox.Status.PENDING),
                outbox.countByStatus(SearchOutbox.Status.FAILED),
                outbox.findFirstByStatusOrderByCreatedAtAsc(SearchOutbox.Status.PENDING)
                        .map(SearchOutbox::getCreatedAt).orElse(null),
                outbox.findFirstByStatusOrderByProcessedAtDesc(SearchOutbox.Status.DONE)
                        .map(SearchOutbox::getProcessedAt).orElse(null),
                outbox.findByStatusOrderByIdAsc(SearchOutbox.Status.FAILED, PageRequest.of(0, 10))
                        .stream().map(e -> new FailedJob(e.getId(), e.getCustomerId(), e.getAttempts(), e.getLastError())).toList());
    }

    public record RebuildResult(String index, long customers, long tombstones) {}
    public record FailedJob(Long id, Long customerId, int attempts, String error) {}
    public record SyncStatus(long pending, long failed, Instant oldestPendingAt,
                             Instant lastProcessedAt, List<FailedJob> failures) {}
}
