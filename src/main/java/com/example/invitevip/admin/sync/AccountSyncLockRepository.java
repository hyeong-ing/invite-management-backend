package com.example.invitevip.admin.sync;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface AccountSyncLockRepository extends JpaRepository<AccountSyncLock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AccountSyncLock l where l.id = 1")
    Optional<AccountSyncLock> lock();
}
