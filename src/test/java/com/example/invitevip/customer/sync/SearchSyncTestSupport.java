package com.example.invitevip.customer.sync;

import com.example.invitevip.customer.entity.CustomerSearchEntity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SearchSyncTestSupport {
    @TestConfiguration
    public static class Config {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
        @Bean @Primary
        FakeIndex fakeIndex() { return new FakeIndex(); }
    }

    public static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T00:00:00Z");
        public void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static class FakeIndex implements SearchIndexGateway {
        record Versioned(CustomerSearchEntity document, long version) {}
        final Map<Long, Versioned> documents = new HashMap<>();
        boolean failWrites;
        boolean failScan;
        boolean failRebuild;
        boolean crashAfterWrite;
        CountDownLatch entered;
        CountDownLatch release;
        int writes;

        public void reset() {
            documents.clear();
            failWrites = failScan = failRebuild = crashAfterWrite = false;
            entered = release = null;
            writes = 0;
        }

        private void blockIfRequested() throws InterruptedException {
            if (entered != null) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
            }
        }

        @Override
        public void write(CustomerSearchEntity document, long version) throws Exception {
            blockIfRequested();
            if (failWrites) throw new IllegalStateException("ES unavailable");
            writes++;
            Versioned previous = documents.get(document.getId());
            if (previous == null || previous.version() < version) {
                documents.put(document.getId(), new Versioned(document, version));
            }
            if (crashAfterWrite) {
                crashAfterWrite = false;
                throw new SimulatedProcessCrash();
            }
        }

        @Override
        public Map<Long, CustomerSearchEntity> scan() {
            if (failScan) throw new IllegalStateException("ES unavailable");
            Map<Long, CustomerSearchEntity> result = new HashMap<>();
            documents.forEach((id, v) -> result.put(id, v.document()));
            return result;
        }

        @Override
        public String rebuild(List<CustomerSearchEntity> values, long version) throws Exception {
            blockIfRequested();
            if (failRebuild) throw new IllegalStateException("Bulk item rejected");
            documents.clear();
            for (var value : values) documents.put(value.getId(), new Versioned(value, version));
            return "test-index";
        }

        public CustomerSearchEntity get(Long id) { return documents.get(id).document(); }
        public long version(Long id) { return documents.get(id).version(); }
    }

    static class SimulatedProcessCrash extends Error {}
}
