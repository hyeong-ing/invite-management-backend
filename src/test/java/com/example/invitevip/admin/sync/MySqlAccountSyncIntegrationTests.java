package com.example.invitevip.admin.sync;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

@Tag("external")
@ActiveProfiles(value = {"test", "mysql-test"}, inheritProfiles = false)
class MySqlAccountSyncIntegrationTests extends AccountSyncIntegrationTests {}
