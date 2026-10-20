package com.example.invitevip.customer.sync;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.invitevip.InviteVipApplication;
import com.example.invitevip.customer.CustomerService;
import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.database.CustomerSearchRepository;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static com.example.invitevip.customer.sync.SearchSyncIntegrationTests.request;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test", "mysql-test", "elastic-test"})
@Tag("external")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ElasticsearchSyncIntegrationTests {
    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired CustomerService customers;
    @Autowired CustomerRepository repository;
    @Autowired CustomerSearchRepository search;
    @Autowired SearchSyncService sync;
    @Autowired SearchOutboxRepository outbox;
    @Autowired ElasticsearchIndexGateway index;
    @Autowired ElasticsearchClient client;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws Exception {
        // This profile is hardwired to isolated test ports. Never use production endpoints here.
        outbox.deleteAll();
        repository.deleteAll();
        jdbc.update("DELETE FROM customer_search_lock");
        jdbc.update("INSERT INTO customer_search_lock (id) VALUES (1), (2)");
        if (client.indices().exists(b -> b.index("customers-v-*")).value()) {
            var names = client.indices().get(b -> b.index("customers-v-*")).result().keySet();
            for (String name : names) client.indices().delete(b -> b.index(name));
        }
    }

    @Test void actualSearchSupportsKoreanChosungAndFiltersTombstones() {
        var saved = customers.save(request("홍길동", "2000"));
        sync.processPending();
        assertThat(search.searchAll("홍길동")).hasSize(1);
        assertThat(search.searchAll("ㅎㄱㄷ")).hasSize(1);
        assertThat(search.searchAll("2000")).hasSize(1);
        customers.delete(saved.getId());
        sync.processPending();
        assertThat(search.searchAll("홍길동")).isEmpty();
        assertThat(search.searchAll("2000")).isEmpty();
        assertThat(indexScan(saved.getId()).isDeleted()).isTrue();
        assertThat(indexScan(saved.getId()).getName()).isNull();
    }

    @Test void actualExternalVersionsRejectOlderAndDuplicateWrites() throws Exception {
        var document = new CustomerSearchEntity();
        document.setId(30L);
        document.setName("최신");
        index.write(document, 6);
        document.setName("과거");
        index.write(document, 4);
        index.write(document, 6);
        assertThat(index.scan().get(30L).getName()).isEqualTo("최신");
        index.write(CustomerSearchEntity.tombstone(30L), 8);
        index.write(document, 7);
        assertThat(index.scan().get(30L).isDeleted()).isTrue();
    }

    @Test void actualIndexWriteBlockRetainsWorkAndRetryRecovers() throws Exception {
        var saved = customers.save(request("홍길동", "2001"));
        sync.processPending();
        client.indices().putSettings(b -> b.index(ElasticsearchIndexGateway.ALIAS)
                .settings(s -> s.blocks(bb -> bb.write(true))));
        try {
            customers.update(saved.getId(), request("김철수", "2001"));
            sync.processPending();
            assertThat(sync.status().pending()).isEqualTo(1);
            assertThat(repository.findById(saved.getId()).orElseThrow().getName()).isEqualTo("김철수");
        } finally {
            client.indices().putSettings(b -> b.index(ElasticsearchIndexGateway.ALIAS)
                    .settings(s -> s.blocks(bb -> bb.write(false))));
        }
        jdbc.update("UPDATE customer_search_outbox SET next_attempt_at = '2000-01-01' WHERE status = 'PENDING'");
        sync.processPending();
        assertThat(search.searchAll("김철수")).hasSize(1);
        assertThat(sync.status().pending()).isZero();
    }

    @Test void rebuildSwitchesAliasAndRetainsPreviousIndex() throws Exception {
        var saved = customers.save(request("홍길동", "2002"));
        sync.processPending();
        String old = aliasTarget();
        customers.update(saved.getId(), request("김철수", "2002"));
        var rebuilt = sync.rebuild();
        assertThat(aliasTarget()).isEqualTo(rebuilt.index()).isNotEqualTo(old);
        assertThat(client.indices().exists(b -> b.index(old)).value()).isTrue();
        assertThat(search.searchAll("김철수")).hasSize(1);
        sync.processPending();
        assertThat(search.searchAll("김철수")).hasSize(1);
        assertThat(sync.status().pending()).isZero();
        customers.update(saved.getId(), request("박영희", "2002"));
        sync.processPending();
        assertThat(search.searchAll("박영희")).hasSize(1);
    }

    @Test void failedBulkNeverReplacesAlias() throws Exception {
        var valid = new CustomerSearchEntity();
        valid.setId(40L);
        valid.setName("정상");
        index.write(valid, 2);
        String old = aliasTarget();
        // A keyword term > Lucene's byte limit produces an individual bulk-item failure.
        var invalid = new CustomerSearchEntity();
        invalid.setId(41L);
        invalid.setCode("x".repeat(40000));
        assertThatThrownBy(() -> index.rebuild(List.of(invalid), 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bulk item");
        assertThat(aliasTarget()).isEqualTo(old);
        assertThat(index.scan().get(40L).getName()).isEqualTo("정상");
    }

    @Test void bulkAndScrollProcessMoreThanOnePage() throws Exception {
        List<CustomerSearchEntity> documents = new ArrayList<>();
        for (long id = 1; id <= 1001; id++) {
            var document = new CustomerSearchEntity();
            document.setId(id);
            document.setName("홍길동");
            document.setCode(String.format("%04d", id));
            documents.add(document);
        }
        index.rebuild(documents, 1);
        assertThat(index.scan()).hasSize(1001);
        assertThat(client.indices().stats(b -> b.index(ElasticsearchIndexGateway.ALIAS))
                .all().total().search().openContexts()).isZero();
    }

    @Test void reconciliationRecoversDeletedIndex() throws Exception {
        var saved = customers.save(request("홍길동", "2003"));
        sync.processPending();
        String previous = aliasTarget();
        client.indices().delete(b -> b.index(previous));
        assertThat(sync.reconcile()).isEqualTo(1);
        sync.processPending();
        assertThat(search.searchAll("2003")).hasSize(1);
        assertThat(index.scan().get(saved.getId()).getName()).isEqualTo("홍길동");
    }

    @Test void actualRebuildOfEmptyDatabaseWorks() throws Exception {
        assertThat(sync.rebuild().customers()).isZero();
        assertThat(index.scan()).isEmpty();
    }

    @Test void migrationCanRunTwiceAndPreservesDeletedCustomerJobs() {
        jdbc.execute("CREATE DATABASE IF NOT EXISTS invitevip_migration_test");
        var datasource = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:13316/invitevip_migration_test?allowPublicKeyRetrieval=true&useSSL=false",
                "root", "sync-test-only");
        try {
            var script = new ResourceDatabasePopulator(new FileSystemResource("db/search-sync-migration.sql"));
            script.execute(datasource);
            script.execute(datasource);
            var isolated = new JdbcTemplate(datasource);
            // Supply the pre-existing business schema, then boot with ddl-auto=validate.
            for (String table : List.of("customer", "admin", "permission", "admin_permission")) {
                var ddl = jdbc.queryForMap("SHOW CREATE TABLE " + table);
                isolated.execute((String) ddl.get("Create Table"));
            }
            var accountScript = new ResourceDatabasePopulator(new FileSystemResource("db/keycloak-account-tables.sql"));
            accountScript.execute(datasource);
            accountScript.execute(datasource);
            assertThat(isolated.queryForObject("SELECT COUNT(*) FROM admin_account_lock", Long.class)).isEqualTo(1);
            assertThat(isolated.queryForObject("SELECT COUNT(*) FROM customer_search_lock", Long.class)).isEqualTo(2);
            isolated.update("""
                    INSERT INTO customer_search_outbox
                    (customer_id, payload, status, attempts, next_attempt_at, created_at)
                    VALUES (999999, '{}', 'PENDING', 0, NOW(6), NOW(6))
                    """);
            assertThat(isolated.queryForObject("SELECT COUNT(*) FROM customer_search_outbox", Long.class)).isEqualTo(1);
            try (var application = new SpringApplicationBuilder(InviteVipApplication.class, MigrationJwtConfig.class)
                    .profiles("test", "mysql-test", "elastic-test")
                    .run("--server.port=0", "--spring.jpa.hibernate.ddl-auto=validate",
                            "--spring.datasource.url=jdbc:mysql://127.0.0.1:13316/invitevip_migration_test?allowPublicKeyRetrieval=true&useSSL=false")) {
                assertThat(application.isActive()).isTrue();
            }
        } finally {
            jdbc.execute("DROP DATABASE invitevip_migration_test");
        }
    }

    @TestConfiguration
    static class MigrationJwtConfig {
        @Bean JwtDecoder jwtDecoder() { return token -> { throw new IllegalArgumentException("Test only"); }; }
    }

    private String aliasTarget() throws Exception {
        return client.indices().getAlias(b -> b.name(ElasticsearchIndexGateway.ALIAS))
                .result().keySet().iterator().next();
    }

    private CustomerSearchEntity indexScan(Long id) {
        try { return index.scan().get(id); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
