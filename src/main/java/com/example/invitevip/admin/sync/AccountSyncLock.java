package com.example.invitevip.admin.sync;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "admin_account_lock")
@NoArgsConstructor
public class AccountSyncLock {
    @Id
    private Long id;
}
