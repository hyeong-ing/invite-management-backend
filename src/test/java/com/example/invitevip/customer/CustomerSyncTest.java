package com.example.invitevip.customer;

import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.database.CustomerSearchRepository;
import com.example.invitevip.customer.entity.Customer;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerSyncTest {
    @Mock CustomerRepository customers;
    @Mock CustomerSearchRepository search;
    @Mock CustomerSearchMapper mapper;
    @InjectMocks CustomerService service;

    @Test
    void updatesExistingAndAddsMissingCustomersWithoutDeletingValidDocuments() {
        Customer first = customer(1L);
        Customer second = customer(2L);
        CustomerSearchEntity updated = document(1L);
        CustomerSearchEntity added = document(2L);
        when(customers.findAll()).thenReturn(List.of(first, second));
        when(search.findAll()).thenReturn(List.of(document(1L)));
        when(mapper.toSearchEntity(first)).thenReturn(updated);
        when(mapper.toSearchEntity(second)).thenReturn(added);

        service.syncAllToElasticsearch();

        verify(search).save(updated);
        verify(search).save(added);
        verify(search, never()).deleteAll(any(Iterable.class));
    }

    @Test
    void deletesOnlyOrphanDocumentsAfterSavingCurrentCustomers() {
        Customer current = customer(1L);
        CustomerSearchEntity updated = document(1L);
        CustomerSearchEntity orphan = document(2L);
        when(customers.findAll()).thenReturn(List.of(current));
        when(search.findAll()).thenReturn(List.of(document(1L), orphan));
        when(mapper.toSearchEntity(current)).thenReturn(updated);

        service.syncAllToElasticsearch();

        var order = inOrder(search);
        order.verify(search).save(updated);
        order.verify(search).deleteAll(List.of(orphan));
    }

    @Test
    void deletesRemainingSearchDocumentsWhenDatabaseIsEmpty() {
        CustomerSearchEntity orphan = document(2L);
        when(customers.findAll()).thenReturn(List.of());
        when(search.findAll()).thenReturn(List.of(orphan));

        service.syncAllToElasticsearch();

        verify(search).deleteAll(List.of(orphan));
        verify(search, never()).save(any(CustomerSearchEntity.class));
    }

    @Test
    void doesNothingWhenBothStoresAreEmpty() {
        when(customers.findAll()).thenReturn(List.of());
        when(search.findAll()).thenReturn(List.of());

        service.syncAllToElasticsearch();

        verify(search, never()).save(any(CustomerSearchEntity.class));
        verify(search, never()).deleteAll(any(Iterable.class));
    }

    @Test
    void doesNotDeleteOrphansWhenSavingFails() {
        Customer current = customer(1L);
        CustomerSearchEntity updated = document(1L);
        when(customers.findAll()).thenReturn(List.of(current));
        when(search.findAll()).thenReturn(List.of(document(2L)));
        when(mapper.toSearchEntity(current)).thenReturn(updated);
        when(search.save(updated)).thenThrow(new IllegalStateException("Search unavailable"));

        assertThrows(IllegalStateException.class, service::syncAllToElasticsearch);

        verify(search, never()).deleteAll(any(Iterable.class));
    }

    @Test
    void doesNotTouchSearchWhenDatabaseReadFails() {
        when(customers.findAll()).thenThrow(new IllegalStateException("Database unavailable"));

        assertThrows(IllegalStateException.class, service::syncAllToElasticsearch);

        verifyNoInteractions(search);
    }

    private Customer customer(Long id) {
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(id);
        return customer;
    }

    private CustomerSearchEntity document(Long id) {
        CustomerSearchEntity document = new CustomerSearchEntity();
        document.setId(id);
        return document;
    }
}
