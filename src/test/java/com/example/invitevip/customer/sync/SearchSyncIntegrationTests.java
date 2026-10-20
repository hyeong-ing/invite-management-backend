package com.example.invitevip.customer.sync;

import com.example.invitevip.customer.CustomerService;
import com.example.invitevip.customer.CustomerNotFoundException;
import com.example.invitevip.customer.DuplicateCodeException;
import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.dto.CustomerRequest;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static com.example.invitevip.customer.sync.SearchOutbox.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(SearchSyncTestSupport.Config.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchSyncIntegrationTests {
    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired CustomerService customers;
    @Autowired CustomerRepository repository;
    @Autowired SearchSyncService sync;
    @Autowired SearchOutboxRepository outbox;
    @Autowired SearchSyncLockRepository locks;
    @Autowired CustomerSearchMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired SearchSyncTestSupport.FakeIndex index;
    @Autowired SearchSyncTestSupport.MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        repository.deleteAll();
        jdbc.update("DELETE FROM customer_search_lock");
        jdbc.update("INSERT INTO customer_search_lock (id) VALUES (1), (2)");
        index.reset();
    }

    static CustomerRequest request(String name, String code) {
        CustomerRequest request = new CustomerRequest();
        request.setName(name);
        request.setCode(code);
        request.setGrade("VIP");
        request.setPhone("010-0000-0000");
        request.setNote("메모");
        return request;
    }

    @Test void createRecordsWorkWithoutContactingEs() {
        index.failWrites = true;
        var saved = customers.save(request("홍길동", "1000"));
        assertThat(repository.findById(saved.getId())).isPresent();
        assertThat(outbox.countByStatus(PENDING)).isEqualTo(1);
        assertThat(index.writes).isZero();
    }

    @Test void rollbackRemovesBothCustomerAndOutbox() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            customers.save(request("홍길동", "1001"));
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test void missingMigrationRollsBackCustomerChange() {
        jdbc.update("DELETE FROM customer_search_lock WHERE id = 1");
        assertThatThrownBy(() -> customers.save(request("홍길동", "1002")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test void duplicateCodeDoesNotQueueAnEvent() {
        customers.save(request("홍길동", "1003"));
        assertThatThrownBy(() -> customers.save(request("김철수", "1003")))
                .isInstanceOf(DuplicateCodeException.class);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test void missingCustomerDoesNotQueueDeletion() {
        assertThatThrownBy(() -> customers.delete(-1L)).isInstanceOf(CustomerNotFoundException.class);
        assertThat(outbox.count()).isZero();
    }

    @Test void updateAndDeletePersistWhileEsIsUnavailable() {
        var saved = customers.save(request("홍길동", "1004"));
        index.failWrites = true;
        customers.update(saved.getId(), request("김철수", "1004"));
        customers.delete(saved.getId());
        assertThat(repository.count()).isZero();
        assertThat(outbox.countByStatus(PENDING)).isEqualTo(3);
        sync.processPending();
        assertThat(outbox.countByStatus(PENDING)).isEqualTo(3);
        index.failWrites = false;
        clock.advance(5);
        sync.processPending();
        assertThat(index.get(saved.getId()).isDeleted()).isTrue();
        assertThat(index.get(saved.getId()).getName()).isNull();
        assertThat(outbox.countByStatus(DONE)).isEqualTo(3);
    }

    @Test void retryUsesBackoffAndRecoversAutomatically() {
        var saved = customers.save(request("홍길동", "1005"));
        index.failWrites = true;
        assertThat(sync.processPending()).isEqualTo(1);
        var event = outbox.findAll().get(0);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("ES unavailable");
        assertThat(sync.processPending()).isZero();
        index.failWrites = false;
        clock.advance(2);
        assertThat(sync.processPending()).isEqualTo(1);
        assertThat(index.get(saved.getId()).getName()).isEqualTo("홍길동");
        assertThat(sync.status().pending()).isZero();
    }

    @Test void exhaustedFailuresRemainVisibleAndCanBeRetried() {
        customers.save(request("홍길동", "1006"));
        index.failWrites = true;
        for (int i = 0; i < 3; i++) {
            sync.processPending();
            clock.advance(1000);
        }
        assertThat(sync.status().failed()).isEqualTo(1);
        assertThat(sync.status().failures().get(0).attempts()).isEqualTo(3);
        assertThat(sync.reconcile()).isZero();
        index.failWrites = false;
        assertThat(sync.retryFailed()).isEqualTo(1);
        sync.processPending();
        assertThat(sync.status().failed()).isZero();
        assertThat(outbox.countByStatus(DONE)).isEqualTo(1);
    }

    @Test void crashAfterEsWriteBeforeAckIsSafeToReplayWithNewWorker() {
        var saved = customers.save(request("홍길동", "1007"));
        index.crashAfterWrite = true;
        assertThatThrownBy(sync::processPending).isInstanceOf(SearchSyncTestSupport.SimulatedProcessCrash.class);
        assertThat(index.get(saved.getId()).getName()).isEqualTo("홍길동");
        assertThat(outbox.countByStatus(PENDING)).isEqualTo(1);
        var restarted = new SearchSyncService(outbox, locks, repository, mapper, json, index, clock, manager, 3, 50);
        restarted.processPending();
        assertThat(index.documents).hasSize(1);
        assertThat(outbox.countByStatus(DONE)).isEqualTo(1);
    }

    @Test void staleUpdateCannotResurrectDeletedCustomer() throws Exception {
        var saved = customers.save(request("홍길동", "1008"));
        var old = outbox.findAll().get(0);
        customers.delete(saved.getId());
        sync.processPending();
        index.write(json.readValue(old.getPayload(), CustomerSearchEntity.class), old.getId() * 2);
        assertThat(index.get(saved.getId()).isDeleted()).isTrue();
    }

    @Test void staleUpdateCannotOverwriteNewerUpdate() throws Exception {
        var saved = customers.save(request("홍길동", "1009"));
        var old = outbox.findAll().get(0);
        customers.update(saved.getId(), request("김철수", "1009"));
        sync.processPending();
        index.write(json.readValue(old.getPayload(), CustomerSearchEntity.class), old.getId() * 2);
        assertThat(index.get(saved.getId()).getName()).isEqualTo("김철수");
    }

    @Test void reconciliationRepairsMissingChangedAndOrphanDocuments() {
        var first = customers.save(request("홍길동", "1010"));
        var second = customers.save(request("김철수", "1011"));
        sync.processPending();
        index.documents.remove(first.getId());
        var corrupt = new CustomerSearchEntity();
        corrupt.setId(second.getId());
        corrupt.setName("잘못된값");
        index.documents.put(second.getId(), new SearchSyncTestSupport.FakeIndex.Versioned(corrupt, 1));
        var orphan = new CustomerSearchEntity();
        orphan.setId(9999L);
        orphan.setName("유령");
        index.documents.put(9999L, new SearchSyncTestSupport.FakeIndex.Versioned(orphan, 1));
        assertThat(sync.reconcile()).isEqualTo(3);
        assertThat(sync.reconcile()).isZero(); // pending repairs aren't duplicated
        sync.processPending();
        assertThat(index.get(first.getId()).getName()).isEqualTo("홍길동");
        assertThat(index.get(second.getId()).getName()).isEqualTo("김철수");
        assertThat(index.get(9999L).isDeleted()).isTrue();
        assertThat(sync.reconcile()).isZero();
    }

    @Test void indexLossRecoversBothActiveCustomersAndDeletionMarkers() {
        var first = customers.save(request("홍길동", "1012"));
        var second = customers.save(request("김철수", "1013"));
        customers.delete(second.getId());
        sync.processPending();
        index.documents.clear();
        assertThat(sync.reconcile()).isEqualTo(2);
        sync.processPending();
        assertThat(index.get(first.getId()).isDeleted()).isFalse();
        assertThat(index.get(second.getId()).isDeleted()).isTrue();
    }

    @Test void failedScanDoesNotClaimSuccessfulReconciliation() {
        customers.save(request("홍길동", "1014"));
        index.failScan = true;
        assertThatThrownBy(sync::reconcile).isInstanceOf(SearchSyncUnavailableException.class);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test void rebuildBarrierRejectsOldJobsAndAllowsNextChange() {
        var first = customers.save(request("홍길동", "1015"));
        var second = customers.save(request("김철수", "1016"));
        customers.delete(second.getId());
        var result = sync.rebuild();
        assertThat(result.customers()).isEqualTo(1);
        assertThat(result.tombstones()).isEqualTo(1);
        long barrier = index.version(first.getId());
        sync.processPending(); // still-pending jobs from before the rebuild must not win
        assertThat(index.version(first.getId())).isEqualTo(barrier);
        assertThat(index.get(second.getId()).isDeleted()).isTrue();
        customers.update(first.getId(), request("박영희", "1015"));
        sync.processPending();
        assertThat(index.version(first.getId())).isGreaterThan(barrier);
        assertThat(index.get(first.getId()).getName()).isEqualTo("박영희");
    }

    @Test void emptyDatabaseCanBeRebuilt() {
        assertThat(sync.rebuild().customers()).isZero();
        assertThat(index.documents).isEmpty();
    }

    @Test void rebuildFailureKeepsWorkingIndexAndPendingWork() {
        var saved = customers.save(request("홍길동", "1017"));
        sync.processPending();
        customers.update(saved.getId(), request("김철수", "1017"));
        index.failRebuild = true;
        assertThatThrownBy(sync::rebuild).isInstanceOf(SearchSyncUnavailableException.class);
        assertThat(index.get(saved.getId()).getName()).isEqualTo("홍길동");
        assertThat(outbox.countByStatus(PENDING)).isEqualTo(1);
    }

    @Test void customerWritesCanCommitWhileDeliveryIsWaitingOnEs() throws Exception {
        var saved = customers.save(request("홍길동", "1018"));
        index.entered = new CountDownLatch(1);
        index.release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> delivery = executor.submit(sync::processPending);
            assertThat(index.entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> customers.delete(saved.getId())).get(3, TimeUnit.SECONDS);
            assertThat(repository.findById(saved.getId())).isEmpty();
            index.release.countDown();
            delivery.get(5, TimeUnit.SECONDS);
            assertThat(index.get(saved.getId()).isDeleted()).isTrue();
        } finally {
            index.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void rebuildBlocksConcurrentChangesUntilSnapshotIsPublished() throws Exception {
        var saved = customers.save(request("홍길동", "1019"));
        index.entered = new CountDownLatch(1);
        index.release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> rebuild = executor.submit(sync::rebuild);
            assertThat(index.entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> update = executor.submit(() -> customers.update(saved.getId(), request("김철수", "1019")));
            assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            index.release.countDown();
            rebuild.get(5, TimeUnit.SECONDS);
            update.get(5, TimeUnit.SECONDS);
            sync.processPending();
            assertThat(index.get(saved.getId()).getName()).isEqualTo("김철수");
        } finally {
            index.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void twoWorkersDoNotProcessTheSameJobConcurrently() throws Exception {
        customers.save(request("홍길동", "1020"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(sync::processPending);
            Future<Integer> second = executor.submit(sync::processPending);
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(index.writes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void syncEndpointsRequireSuperAdmin() throws Exception {
        for (String endpoint : new String[]{"/api/customers/sync", "/api/customers/sync/reconcile", "/api/customers/sync/retry"}) {
            mvc.perform(post(endpoint).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/customers/sync/status").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/sync/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/sync/status").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(0));
    }

    @Test void maintenanceFailureReturns503() throws Exception {
        index.failRebuild = true;
        mvc.perform(post("/api/customers/sync").with(user("root").roles("SUPER_ADMIN")))
                .andExpect(status().isServiceUnavailable());
    }
}
