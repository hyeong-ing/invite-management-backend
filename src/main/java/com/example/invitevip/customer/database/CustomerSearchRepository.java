package com.example.invitevip.customer.database;

import com.example.invitevip.customer.entity.CustomerSearchEntity;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface CustomerSearchRepository extends ElasticsearchRepository<CustomerSearchEntity, Long> {
    @Query("""
    {
      "bool": {
        "must_not": [{ "term": { "deleted": true } }],
        "should": [
          { "match": { "name": { "query": "?0" } } },
          { "match": { "note": { "query": "?0" } } },

          { "wildcard": { "grade": { "value": "*?0*" } } },
          { "wildcard": { "phone": { "value": "*?0*" } } },
          { "wildcard": { "code":  { "value": "*?0*" } } },
          { "wildcard": { "nameChosung": { "value": "*?0*" } } }
        ],
        "minimum_should_match": 1
      }
    }
    """)
    List<CustomerSearchEntity> searchAll(String keyword);
}
