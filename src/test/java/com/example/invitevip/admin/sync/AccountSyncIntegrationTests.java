package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.*;
import com.example.invitevip.admin.database.AdminRepository;
import com.example.invitevip.admin.dto.AdminRequest;
import com.example.invitevip.admin.entity.*;
import com.example.invitevip.customer.sync.SearchSyncTestSupport.MutableClock;
import com.example.invitevip.security.CustomJwtAuthenticationConverter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.example.invitevip.admin.entity.Admin.State.*;
import static com.example.invitevip.admin.sync.AccountOutbox.Status.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(AccountSyncTestSupport.Config.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountSyncIntegrationTests {
    @Autowired AdminService service;
    @Autowired AdminRepository admins;
    @Autowired AccountSyncService sync;
    @Autowired AccountOutboxRepository jobs;
    @Autowired AccountSyncTestSupport.FakeAccounts remote;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomJwtAuthenticationConverter converter;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void reset() {
        jobs.deleteAll(); admins.deleteAll();
        jdbc.update("DELETE FROM admin_account_lock");
        jdbc.update("INSERT INTO admin_account_lock (id) VALUES (1)");
        remote.reset();
    }
    static AdminRequest request(String name, String username, String role) {
        var r = new AdminRequest(); r.setName(name); r.setUsername(username); r.setRole(role); r.setPermissions(List.of()); return r;
    }
    long create(String username) { return service.save(request("원래 이름", username, "ADMIN")).getId(); }
    Admin admin(long id) { return admins.findById(id).orElseThrow(); }
    AccountOutbox job(long id) { return jobs.findById(id).orElseThrow(); }
    long active(String username) { long id = create(username); sync.processPending(); assertThat(admin(id).getState()).isEqualTo(ACTIVE); return id; }
    static Jwt token(String subject, String username) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN"))).build();
    }
    @Test void savesDurableWorkWithoutCallingKeycloak() {
        remote.unavailable = true; long id = create("new1");
        assertThat(admin(id).getState()).isEqualTo(PENDING_CREATE); assertThat(job(id).getStatus()).isEqualTo(PENDING); assertThat(remote.calls).isZero();
    }
    @Test void transactionRollbackRemovesAdminAndJob() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> { create("new1"); tx.setRollbackOnly(); });
        assertThat(admins.count()).isZero(); assertThat(jobs.count()).isZero();
    }
    @Test void missingMigrationFailsWithoutSavingAdmin() {
        jdbc.update("DELETE FROM admin_account_lock");
        assertThatThrownBy(() -> create("new1")).isInstanceOf(IllegalStateException.class);
        assertThat(admins.count()).isZero();
    }
    @Test void createCompletesAndCanBeRepeated() {
        long id = active("new1"); String subject = admin(id).getKeycloakId();
        sync.retry(id); sync.processPending();
        assertThat(remote.users).hasSize(1); assertThat(admin(id).getKeycloakId()).isEqualTo(subject); assertThat(job(id).getStatus()).isEqualTo(DONE);
    }
    @Test void processCrashAfterRemoteCreationRecoversWithoutDuplicate() {
        long id = create("new1"); remote.crashAfterCreate = true;
        assertThatThrownBy(sync::processOne).isInstanceOf(AccountSyncTestSupport.Crash.class);
        assertThat(admin(id).getKeycloakId()).isNull(); assertThat(job(id).getStatus()).isEqualTo(PENDING);
        sync.processOne(); assertThat(remote.users).hasSize(1); assertThat(admin(id).getState()).isEqualTo(ACTIVE);
    }
    @Test void responseTimeoutAfterRemoteSuccessRecoversAfterBackoff() {
        long id = create("new1"); remote.timeoutAfterCreate = true;
        sync.processOne();
        assertThat(job(id).getAttempts()).isEqualTo(1);
        assertThat(admin(id).getKeycloakId()).isNull();
        assertThat(remote.users).hasSize(1);
        clock.advance(10); sync.processOne();
        assertThat(admin(id).getState()).isEqualTo(ACTIVE);
        assertThat(remote.users).hasSize(1);
    }
    @Test void profileFailureAfterCreatePreservesIdentityForRepair() {
        long id = create("new1"); remote.rejectProfile = true; sync.processOne();
        assertThat(admin(id).getKeycloakId()).isNotNull();
        assertThat(admin(id).getState()).isEqualTo(PENDING_CREATE);
        assertThat(job(id).getStatus()).isEqualTo(FAILED);
        remote.rejectProfile = false; sync.retry(id); sync.processOne();
        assertThat(admin(id).getState()).isEqualTo(ACTIVE); assertThat(remote.users).hasSize(1);
    }
    @Test void retriesBackOffStopAndCanBeManuallyRestartedWithoutLeakingErrors() {
        long id = create("new1"); remote.unavailable = true;
        sync.processPending(); assertThat(job(id).getAttempts()).isEqualTo(1); assertThat(sync.processOne()).isFalse();
        clock.advance(10); sync.processOne(); clock.advance(10); sync.processOne();
        assertThat(job(id).getStatus()).isEqualTo(FAILED); assertThat(job(id).getLastError()).doesNotContain("DO_NOT_PERSIST");
        sync.reconcile(); assertThat(job(id).getStatus()).isEqualTo(FAILED);
        remote.unavailable = false; sync.retry(id); sync.processOne(); assertThat(admin(id).getState()).isEqualTo(ACTIVE);
    }
    @Test void roleChangesDoNotCallOrQueueKeycloak() {
        long id = active("new1"); int calls = remote.calls;
        service.update(id, request("원래 이름", "new1", "SUPER_ADMIN"));
        assertThat(job(id).getStatus()).isEqualTo(DONE); assertThat(remote.calls).isEqualTo(calls);
        assertThat(admin(id).getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
    }
    @Test void changesCoalesceAndDeletionWinsOverOldProfileUpdates() {
        long id = active("new1");
        service.update(id, request("새 이름", "new1", "ADMIN")); service.delete(id);
        sync.processPending(); assertThat(remote.users).isEmpty(); assertThat(admin(id).getState()).isEqualTo(DELETED);
    }
    @Test void deleteDuringOutageBlocksExistingTokenBeforeRemoteDeletion() {
        long id = active("new1"); var jwt = token(admin(id).getKeycloakId(), "new1");
        service.delete(id); remote.unavailable = true; sync.processOne();
        assertThat(admin(id).getState()).isEqualTo(DELETING); assertThat(remote.users).hasSize(1);
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(OAuth2AuthenticationException.class);
        clock.advance(10); remote.unavailable = false; sync.processPending(); assertThat(admin(id).getState()).isEqualTo(DELETED);
    }
    @Test void deleteAfterUnacknowledgedCreateFindsOwnedAccount() {
        long id = create("new1"); remote.crashAfterCreate = true;
        assertThatThrownBy(sync::processOne).isInstanceOf(AccountSyncTestSupport.Crash.class);
        service.delete(id); sync.processOne(); assertThat(remote.users).isEmpty(); assertThat(admin(id).getState()).isEqualTo(DELETED);
    }
    @Test void crashAfterDeleteCanRepeatDelete() {
        long id = active("new1"); service.delete(id); remote.crashAfterDelete = true;
        assertThatThrownBy(sync::processOne).isInstanceOf(AccountSyncTestSupport.Crash.class);
        sync.processOne(); assertThat(admin(id).getState()).isEqualTo(DELETED);
    }
    @Test void auditCleansUpLateCreationAfterDelete() {
        long id = create("new1"); service.delete(id); sync.processOne();
        var a = admin(id); remote.users.put("late", new AccountSyncTestSupport.FakeAccounts.Remote("late", a.getAccountRef(), a.getUsername(), "late", true));
        clock.advance(301); sync.reconcile(); sync.processOne(); assertThat(remote.users).isEmpty();
    }
    @Test void manualDisableIsDetectedAndNeverReenabled() {
        long id = active("new1"); var a = admin(id);
        remote.users.put(a.getKeycloakId(), new AccountSyncTestSupport.FakeAccounts.Remote(a.getKeycloakId(), a.getAccountRef(), a.getUsername(), a.getName(), false));
        clock.advance(301); sync.reconcile(); sync.processOne();
        assertThat(admin(id).getState()).isEqualTo(BLOCKED); assertThat(job(id).getStatus()).isEqualTo(FAILED);
        assertThat(remote.users.get(a.getKeycloakId()).enabled()).isFalse();
    }
    @Test void missingRemoteAccountBlocksInsteadOfRecreating() {
        long id = active("new1"); remote.users.clear(); clock.advance(301); sync.reconcile(); sync.processOne();
        assertThat(admin(id).getState()).isEqualTo(BLOCKED); assertThat(remote.users).isEmpty();
    }
    @Test void unrelatedSameUsernameIsNotAdopted() {
        remote.users.put("other", new AccountSyncTestSupport.FakeAccounts.Remote("other", "other-owner", "new1", "other", true));
        long id = create("new1"); sync.processOne(); assertThat(job(id).getStatus()).isEqualTo(FAILED);
        assertThat(admin(id).getKeycloakId()).isNull(); assertThat(remote.users).hasSize(1);
    }
    @Test void passwordAndUsernameChangeAreRejectedWithoutWork() {
        var r = request("원래 이름", "new1", "ADMIN"); r.setPassword("do-not-save");
        assertThatThrownBy(() -> service.save(r)).isInstanceOf(IllegalArgumentException.class); assertThat(jobs.count()).isZero();
        long id = active("new1"); assertThatThrownBy(() -> service.update(id, request("원래 이름", "new2", "ADMIN"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void usernameFallbackAndJwtRealmRolesAreNeverUsed() {
        long id = active("new1");
        assertThatThrownBy(() -> converter.convert(token("different-subject", "new1"))).isInstanceOf(OAuth2AuthenticationException.class);
        var authentication = converter.convert(token(admin(id).getKeycloakId(), "new1"));
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }
    @Test void existingTokenReflectsDbRoleDowngrade() {
        long id = active("new1"); long second = active("new2");
        service.update(id, request("원래 이름", "new1", "SUPER_ADMIN")); service.update(second, request("원래 이름", "new2", "SUPER_ADMIN"));
        var jwt = token(admin(id).getKeycloakId(), "new1");
        service.update(id, request("원래 이름", "new1", "ADMIN"));
        assertThat(converter.convert(jwt).getAuthorities()).extracting("authority").doesNotContain("ROLE_SUPER_ADMIN");
    }
    @Test void lastActiveSuperAdminIsProtected() {
        long id = active("new1"); service.update(id, request("원래 이름", "new1", "SUPER_ADMIN"));
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(id, request("원래 이름", "new1", "ADMIN"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pendingAndUnlinkedAccountsCannotUseProtectedApis() throws Exception {
        long id = create("new1");
        when(decoder.decode("token")).thenReturn(token("unknown", "new1"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers").header("Authorization", "Bearer token")).andExpect(status().isUnauthorized());
        assertThat(admin(id).getState()).isEqualTo(PENDING_CREATE);
    }
    @Test void ordinaryAdminCannotRetryOrLinkAccountsDespiteRealmSuperRole() throws Exception {
        long id = active("new1"); when(decoder.decode("token")).thenReturn(token(admin(id).getKeycloakId(), "new1"));
        mvc.perform(post("/api/admins/"+id+"/sync/retry").header("Authorization", "Bearer token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admins/"+id+"/link").header("Authorization", "Bearer token").contentType("application/json").content("{\"keycloakId\":\"other\"}")).andExpect(status().isForbidden());
    }
    @Test void explicitLinkRequiresMatchingEnabledAccountAndUniqueSubject() {
        var a = new Admin(); a.setName("기존"); a.setUsername("legacy"); a.setRole(AdminRole.ADMIN); a = admins.save(a); long id = a.getId();
        remote.users.put("verified", new AccountSyncTestSupport.FakeAccounts.Remote("verified", "legacy", "legacy", "기존", true));
        assertThatThrownBy(() -> service.link(id, "wrong")).isInstanceOf(IllegalArgumentException.class);
        service.link(id, "verified"); assertThat(admin(id).getState()).isEqualTo(ACTIVE);
        assertThat(converter.convert(token("verified", "legacy"))).isNotNull();
        assertThatThrownBy(() -> service.link(id, "verified")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentWorkersDeliverOnlyOnce() throws Exception {
        long id = create("new1"); remote.entered = new CountDownLatch(1); remote.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(sync::processOne);
            assertThat(remote.entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = pool.submit(sync::processOne);
            remote.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue(); assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(remote.users).hasSize(1); assertThat(admin(id).getState()).isEqualTo(ACTIVE);
        } finally { remote.release.countDown(); pool.shutdownNow(); }
    }
    @Test void concurrentDemotionsCannotRemoveLastSuperAdmin() throws Exception {
        long first = active("new1"); long second = active("new2");
        service.update(first, request("원래 이름", "new1", "SUPER_ADMIN"));
        service.update(second, request("원래 이름", "new2", "SUPER_ADMIN"));
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var tasks = List.of(
                    pool.submit(() -> { start.await(); try { service.update(first, request("원래 이름", "new1", "ADMIN")); return true; } catch (IllegalArgumentException e) { return false; } }),
                    pool.submit(() -> { start.await(); try { service.update(second, request("원래 이름", "new2", "ADMIN")); return true; } catch (IllegalArgumentException e) { return false; } }));
            start.countDown();
            assertThat(List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            assertThat(admins.countByStateAndRole(ACTIVE, AdminRole.SUPER_ADMIN)).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void databaseRejectsDuplicateSubject() {
        long id = active("new1");
        var duplicate = new Admin(); duplicate.setUsername("duplicate"); duplicate.setName("중복");
        duplicate.setRole(AdminRole.ADMIN); duplicate.setKeycloakId(admin(id).getKeycloakId());
        assertThatThrownBy(() -> admins.saveAndFlush(duplicate)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(admins.count()).isEqualTo(1);
    }
}
