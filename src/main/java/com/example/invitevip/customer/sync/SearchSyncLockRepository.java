package com.example.invitevip.customer.sync;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SearchSyncLockRepository extends JpaRepository<SearchSyncLock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SearchSyncLock l where l.id = :id")
    Optional<SearchSyncLock> lock(@Param("id") long id);
}
