package com.example.invitevip.customer.sync;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_search_lock")
@NoArgsConstructor
public class SearchSyncLock {
    @Id
    private Long id;
}
