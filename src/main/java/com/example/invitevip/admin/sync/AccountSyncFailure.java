package com.example.invitevip.admin.sync;

/** Only fixed, user-safe messages; never store HTTP bodies or credentials in the outbox. */
public class AccountSyncFailure extends RuntimeException {
    private final boolean blockAccess;

    public AccountSyncFailure(String message, boolean blockAccess) {
        super(message);
        this.blockAccess = blockAccess;
    }

    public boolean blocksAccess() { return blockAccess; }
}
