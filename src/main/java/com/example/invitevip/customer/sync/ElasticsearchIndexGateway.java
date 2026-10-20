package com.example.invitevip.customer.sync;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.ResponseException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ElasticsearchIndexGateway implements SearchIndexGateway {
    public static final String ALIAS = "customers_search";
    private final ElasticsearchClient client;
    private final ObjectMapper json;

    // All gateway writes/alias changes are serialized by DB lock 2 in SearchSyncService.
    private void ensureAlias() throws IOException {
        if (client.indices().existsAlias(b -> b.name(ALIAS)).value()) return;
        if (client.indices().exists(b -> b.index(ALIAS)).value()) {
            throw new IllegalStateException(ALIAS + " must be an alias, not a concrete index");
        }
        // Start a new version namespace; legacy "customers" uses unrelated internal versions.
        createIndex(newIndexName(), true);
    }

    private String newIndexName() {
        return "customers-v-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void createIndex(String index, boolean attachAlias) throws IOException {
        client.indices().create(b -> {
            b.index(index).mappings(m -> m
                    .properties("id", p -> p.long_(v -> v))
                    .properties("name", p -> p.text(v -> v.analyzer("nori")))
                    .properties("note", p -> p.text(v -> v.analyzer("nori")))
                    .properties("grade", p -> p.keyword(v -> v))
                    .properties("phone", p -> p.keyword(v -> v))
                    .properties("code", p -> p.keyword(v -> v))
                    .properties("nameChosung", p -> p.text(v -> v))
                    .properties("deleted", p -> p.boolean_(v -> v)));
            if (attachAlias) b.aliases(ALIAS, a -> a.isWriteIndex(true));
            return b;
        });
    }

    @Override
    public void write(CustomerSearchEntity document, long version) throws IOException {
        ensureAlias();
        try {
            client.index(b -> b.index(ALIAS).id(document.getId().toString())
                    .version(version).versionType(VersionType.External)
                    .refresh(Refresh.WaitFor).document(document));
        } catch (ElasticsearchException e) {
            // A newer committed snapshot already won. Never retry an older value over it.
            if (e.status() != 409 || !"version_conflict_engine_exception".equals(e.error().type())) throw e;
        } catch (ResponseException e) {
            // The REST transport can surface 409 directly instead of a typed client exception.
            if (e.getResponse().getStatusLine().getStatusCode() != 409) throw e;
            String body = EntityUtils.toString(e.getResponse().getEntity());
            if (!"version_conflict_engine_exception".equals(json.readTree(body).path("error").path("type").asText())) {
                throw e;
            }
        }
    }

    @Override
    public Map<Long, CustomerSearchEntity> scan() throws IOException {
        ensureAlias();
        Map<Long, CustomerSearchEntity> result = new HashMap<>();
        String scrollId = null;
        try {
            SearchResponse<CustomerSearchEntity> response = client.search(b -> b.index(ALIAS)
                    .size(500).scroll(t -> t.time("1m"))
                    .sort(s -> s.field(f -> f.field("_doc"))), CustomerSearchEntity.class);
            scrollId = response.scrollId();
            var hits = response.hits().hits();
            while (!hits.isEmpty()) {
                for (var hit : hits) {
                    CustomerSearchEntity document = Objects.requireNonNull(hit.source(), "Missing ES source");
                    // _id is authoritative, including for legacy documents.
                    document.setId(Long.valueOf(hit.id()));
                    result.put(document.getId(), document);
                }
                String current = scrollId;
                var next = client.scroll(b -> b.scrollId(current).scroll(t -> t.time("1m")),
                        CustomerSearchEntity.class);
                scrollId = next.scrollId();
                hits = next.hits().hits();
            }
            return result;
        } finally {
            if (scrollId != null) {
                String last = scrollId;
                client.clearScroll(b -> b.scrollId(last));
            }
        }
    }

    @Override
    public String rebuild(List<CustomerSearchEntity> documents, long version) throws IOException {
        String target = newIndexName();
        createIndex(target, false);
        // Keep unsuccessful indices for diagnosis; never delete the working index on failure.
        for (int offset = 0; offset < documents.size(); offset += 500) {
            BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
            for (CustomerSearchEntity document : documents.subList(offset, Math.min(offset + 500, documents.size()))) {
                bulk.operations(op -> op.index(i -> i.index(target).id(document.getId().toString())
                        .version(version).versionType(VersionType.External).document(document)));
            }
            var response = client.bulk(bulk.build());
            if (response.errors()) {
                var error = response.items().stream().filter(i -> i.error() != null).findFirst().orElseThrow();
                throw new IllegalStateException("Rebuild bulk item failed: " + error.error().type());
            }
        }
        client.indices().refresh(b -> b.index(target));
        long count = client.count(b -> b.index(target)).count();
        if (count != documents.size()) throw new IllegalStateException("Rebuild document count mismatch");
        // Verify source fields too, before exposing the new index.
        for (int offset = 0; offset < documents.size(); offset += 500) {
            List<CustomerSearchEntity> batch = documents.subList(offset, Math.min(offset + 500, documents.size()));
            var response = client.mget(b -> b.index(target)
                    .ids(batch.stream().map(d -> d.getId().toString()).toList()), CustomerSearchEntity.class);
            for (int i = 0; i < batch.size(); i++) {
                var item = response.docs().get(i);
                if (!item.isResult() || !item.result().found()
                        || !batch.get(i).equals(item.result().source())) {
                    throw new IllegalStateException("Rebuild content verification failed");
                }
            }
        }
        Set<String> previous = client.indices().existsAlias(b -> b.name(ALIAS)).value()
                ? client.indices().getAlias(b -> b.name(ALIAS)).result().keySet() : Set.of();
        client.indices().updateAliases(b -> {
            for (String index : previous) b.actions(a -> a.remove(r -> r.index(index).alias(ALIAS).mustExist(true)));
            return b.actions(a -> a.add(add -> add.index(target).alias(ALIAS).isWriteIndex(true)));
        });
        return target;
    }
}
