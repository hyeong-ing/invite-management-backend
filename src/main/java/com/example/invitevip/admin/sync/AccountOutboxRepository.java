package com.example.invitevip.admin.sync;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface AccountOutboxRepository extends JpaRepository<AccountOutbox, Long> {
    List<AccountOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscAdminIdAsc(
            AccountOutbox.Status status, Instant now, Pageable page);
}
