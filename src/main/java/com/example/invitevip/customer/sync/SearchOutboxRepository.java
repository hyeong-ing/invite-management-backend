package com.example.invitevip.customer.sync;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SearchOutboxRepository extends JpaRepository<SearchOutbox, Long> {
    List<SearchOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            SearchOutbox.Status status, Instant now, Pageable pageable);
    List<SearchOutbox> findByStatusOrderByIdAsc(SearchOutbox.Status status, Pageable pageable);
    Optional<SearchOutbox> findFirstByCustomerIdOrderByIdDesc(Long customerId);
    Optional<SearchOutbox> findFirstByStatusOrderByCreatedAtAsc(SearchOutbox.Status status);
    Optional<SearchOutbox> findFirstByStatusOrderByProcessedAtDesc(SearchOutbox.Status status);
    long countByStatus(SearchOutbox.Status status);
    @Query("select coalesce(max(o.id), 0) from SearchOutbox o")
    long watermark();
    @Query("select distinct o.customerId from SearchOutbox o")
    List<Long> customerIds();
}
