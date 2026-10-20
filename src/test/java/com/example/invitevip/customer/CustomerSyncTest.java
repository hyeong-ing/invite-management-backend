package com.example.invitevip.customer;

import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.entity.Customer;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import com.example.invitevip.customer.sync.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Preserve the original full-sync guarantees through the new snapshot rebuild. */
@ExtendWith(MockitoExtension.class)
class CustomerSyncTest {
    @Mock CustomerRepository customers;
    @Mock CustomerSearchMapper mapper;
    @Mock SearchOutboxRepository outbox;
    @Mock SearchSyncLockRepository locks;
    @Mock PlatformTransactionManager transactions;
    SearchSyncTestSupport.FakeIndex index;
    SearchSyncService service;

    @BeforeEach
    void setUp() {
        index = spy(new SearchSyncTestSupport.FakeIndex());
        when(locks.lock(anyLong())).thenReturn(Optional.of(mock(SearchSyncLock.class)));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new SearchSyncService(outbox, locks, customers, mapper, new ObjectMapper(),
                index, Clock.systemUTC(), transactions, 3, 50);
    }

    @Test
    void updatesExistingAndAddsMissingCustomersWithoutLosingValidDocuments() throws Exception {
        Customer first = mock(Customer.class);
        Customer second = mock(Customer.class);
        var updated = document(1L, "Updated customer");
        var added = document(2L, "New customer");
        index.write(document(1L, "Previous name"), 0);
        when(customers.findAll()).thenReturn(List.of(first, second));
        when(mapper.toSearchEntity(first)).thenReturn(updated);
        when(mapper.toSearchEntity(second)).thenReturn(added);

        assertThat(service.rebuild().customers()).isEqualTo(2);
        assertThat(index.scan()).containsOnlyKeys(1L, 2L);
        assertThat(index.get(1L).getName()).isEqualTo("Updated customer");
        assertThat(index.get(2L).getName()).isEqualTo("New customer");
    }

    @Test
    void removesOrphansWhenPublishingCurrentCustomers() throws Exception {
        Customer current = mock(Customer.class);
        index.write(document(1L, "Previous name"), 0);
        index.write(document(2L, "Orphan"), 0);
        when(customers.findAll()).thenReturn(List.of(current));
        when(mapper.toSearchEntity(current)).thenReturn(document(1L, "Current customer"));

        service.rebuild();

        assertThat(index.scan()).containsOnlyKeys(1L);
        assertThat(index.get(1L).getName()).isEqualTo("Current customer");
    }

    @Test
    void removesRemainingSearchDocumentsWhenDatabaseIsEmpty() throws Exception {
        index.write(document(2L, "Orphan"), 0);
        when(customers.findAll()).thenReturn(List.of());

        assertThat(service.rebuild().customers()).isZero();
        assertThat(index.scan()).isEmpty();
    }

    @Test
    void publishesEmptySnapshotWhenBothStoresAreEmpty() {
        when(customers.findAll()).thenReturn(List.of());

        assertThat(service.rebuild().customers()).isZero();
        assertThat(index.scan()).isEmpty();
    }

    @Test
    void keepsExistingDocumentsWhenPublishingFails() throws Exception {
        Customer current = mock(Customer.class);
        index.write(document(1L, "Previous name"), 0);
        index.write(document(2L, "Orphan"), 0);
        when(customers.findAll()).thenReturn(List.of(current));
        when(mapper.toSearchEntity(current)).thenReturn(document(1L, "Current customer"));
        doThrow(new IllegalStateException("Search unavailable")).when(index).rebuild(anyList(), anyLong());

        assertThatThrownBy(service::rebuild).isInstanceOf(SearchSyncUnavailableException.class);
        assertThat(index.scan()).containsOnlyKeys(1L, 2L);
        assertThat(index.get(1L).getName()).isEqualTo("Previous name");
    }

    @Test
    void doesNotTouchSearchWhenDatabaseReadFails() {
        when(customers.findAll()).thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(index);
    }

    private CustomerSearchEntity document(Long id, String name) {
        var document = new CustomerSearchEntity();
        document.setId(id);
        document.setName(name);
        return document;
    }
}
