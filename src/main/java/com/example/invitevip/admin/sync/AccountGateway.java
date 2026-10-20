package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.entity.Admin;

public interface AccountGateway {
    String ensureCreated(Admin admin);
    void updateProfile(Admin admin);
    void delete(Admin admin);
    void verifyLink(Admin admin, String keycloakId);
}
