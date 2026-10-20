package com.example.invitevip.customer.sync;

import com.example.invitevip.customer.entity.CustomerSearchEntity;

import java.util.List;
import java.util.Map;

public interface SearchIndexGateway {
    void write(CustomerSearchEntity document, long version) throws Exception;
    Map<Long, CustomerSearchEntity> scan() throws Exception;
    String rebuild(List<CustomerSearchEntity> documents, long version) throws Exception;
}
