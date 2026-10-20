package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.entity.Admin;
import com.example.invitevip.customer.sync.SearchSyncTestSupport.MutableClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.concurrent.*;

class AccountSyncTestSupport {
    @TestConfiguration
    static class Config {
        @Bean @Primary MutableClock accountTestClock() { return new MutableClock(); }
        @Bean @Primary FakeAccounts fakeAccounts() { return new FakeAccounts(); }
    }
    static class Crash extends Error {}
    static class FakeAccounts implements AccountGateway {
        record Remote(String id, String ref, String username, String name, boolean enabled) {}
        final Map<String, Remote> users = new ConcurrentHashMap<>();
        boolean unavailable, crashAfterCreate, crashAfterDelete, timeoutAfterCreate, rejectProfile;
        int calls;
        CountDownLatch entered, release;
        void reset() {
            users.clear(); unavailable = crashAfterCreate = crashAfterDelete = timeoutAfterCreate = rejectProfile = false;
            calls = 0; entered = release = null;
        }
        void start() {
            calls++;
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
            if (unavailable) throw new IllegalStateException("offline password=DO_NOT_PERSIST");
        }
        public String ensureCreated(Admin a) {
            start();
            var existing = users.values().stream().filter(u -> u.username().equals(a.getUsername())).findFirst();
            if (existing.isPresent()) {
                if (!existing.get().ref().equals(a.getAccountRef())) throw new AccountSyncFailure("다른 소유자의 계정", false);
                return existing.get().id();
            }
            String id = UUID.randomUUID().toString();
            users.put(id, new Remote(id, a.getAccountRef(), a.getUsername(), a.getName(), true));
            if (crashAfterCreate) { crashAfterCreate = false; throw new Crash(); }
            if (timeoutAfterCreate) { timeoutAfterCreate = false; throw new IllegalStateException("response timed out"); }
            return id;
        }
        public void updateProfile(Admin a) {
            start();
            Remote u = users.get(a.getKeycloakId());
            if (u == null || !u.enabled()) throw new AccountSyncFailure("계정 없음 또는 비활성", true);
            if (rejectProfile) throw new AccountSyncFailure("프로필 설정 오류", false);
            users.put(u.id(), new Remote(u.id(), u.ref(), u.username(), a.getName(), u.enabled()));
        }
        public void delete(Admin a) {
            start();
            users.values().removeIf(u -> Objects.equals(u.id(), a.getKeycloakId()) || u.ref().equals(a.getAccountRef()));
            if (crashAfterDelete) { crashAfterDelete = false; throw new Crash(); }
        }
        public void verifyLink(Admin a, String id) {
            start();
            var user = users.get(id);
            if (user == null || !user.enabled() || !user.username().equals(a.getUsername())) throw new IllegalArgumentException("잘못된 연결");
        }
    }
}
