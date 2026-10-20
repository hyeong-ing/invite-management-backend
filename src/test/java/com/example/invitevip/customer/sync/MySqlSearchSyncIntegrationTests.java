package com.example.invitevip.customer.sync;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

// The same rollback, lock-order, retry and concurrency assertions run against real InnoDB.
@Tag("external")
@ActiveProfiles(value = {"test", "mysql-test"}, inheritProfiles = false)
class MySqlSearchSyncIntegrationTests extends SearchSyncIntegrationTests {}
