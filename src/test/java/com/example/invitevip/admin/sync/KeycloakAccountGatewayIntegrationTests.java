package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.entity.Admin;
import com.example.invitevip.security.KeycloakConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.*;
import org.keycloak.admin.client.*;
import org.keycloak.representations.idm.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("external")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakAccountGatewayIntegrationTests {
    static final String BASE = "http://127.0.0.1:18116";
    static final String REALM = "invitevip-account-integration";
    Keycloak client;
    KeycloakAccountGateway gateway;
    final ObjectMapper json = new ObjectMapper();

    @BeforeAll void setup() throws Exception {
        var config = new KeycloakConfig();
        ReflectionTestUtils.setField(config, "serverUrl", BASE);
        ReflectionTestUtils.setField(config, "realm", "master");
        ReflectionTestUtils.setField(config, "clientId", "admin-cli");
        ReflectionTestUtils.setField(config, "adminUsername", "account-test-admin");
        ReflectionTestUtils.setField(config, "adminPassword", "account-test-only");
        client = config.keycloakAdminClient();
        try { client.realm(REALM).remove(); } catch (NotFoundException ignored) {}
        var realm = new RealmRepresentation(); realm.setRealm(REALM); realm.setEnabled(true); realm.setEditUsernameAllowed(true);
        client.realms().create(realm);
        // Keycloak 26 rejects/drops unknown user-profile attributes unless explicitly configured.
        var http = HttpClient.newHttpClient();
        String url = BASE + "/admin/realms/" + REALM + "/users/profile";
        String token = client.tokenManager().getAccessTokenString();
        var result = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        ObjectNode profile = (ObjectNode) json.readTree(result.body());
        profile.withArray("attributes").add(json.readTree("""
                {"name":"invitevip_admin_ref","permissions":{"view":["admin"],"edit":["admin"]},"validations":{"length":{"min":36,"max":36}}}
                """));
        var put = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(profile))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isBetween(200, 299);
        gateway = new KeycloakAccountGateway(client); ReflectionTestUtils.setField(gateway, "realm", REALM);
    }
    @AfterAll void cleanup() {
        if (client != null) { try { client.realm(REALM).remove(); } finally { client.close(); } }
    }
    @BeforeEach void clearUsers() { client.realm(REALM).users().list().forEach(u -> client.realm(REALM).users().get(u.getId()).remove()); }
    Admin admin() {
        var a = new Admin(); a.setUsername("testuser"); a.setName("테스트 이름"); a.setState(Admin.State.PENDING_CREATE); return a;
    }
    UserRepresentation remote(Admin a) { return client.realm(REALM).users().get(a.getKeycloakId()).toRepresentation(); }
    Admin create() { var a = admin(); a.setKeycloakId(gateway.ensureCreated(a)); return a; }

    @Test void createsWithoutPasswordAndPersistsAdminOnlyOwnershipMarker() {
        var a = create(); var user = remote(a);
        assertThat(user.getAttributes().get(KeycloakAccountGateway.OWNER_ATTRIBUTE)).containsExactly(a.getAccountRef());
        assertThat(user.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(client.realm(REALM).users().get(a.getKeycloakId()).credentials()).isEmpty();
        assertThat(user.isEnabled()).isTrue();
    }
    @Test void lostAcknowledgementRecoversByMarkerRatherThanCreatingDuplicate() {
        var a = create(); String first = a.getKeycloakId(); a.setKeycloakId(null);
        assertThat(gateway.ensureCreated(a)).isEqualTo(first);
        assertThat(client.realm(REALM).users().list()).hasSize(1);
    }
    @Test void existingUsernameWithDifferentOwnerIsNeverAdoptedOrDeleted() {
        var existing = create(); var other = admin();
        assertThatThrownBy(() -> gateway.ensureCreated(other)).isInstanceOf(AccountSyncFailure.class);
        assertThatThrownBy(() -> gateway.delete(other)).isInstanceOf(AccountSyncFailure.class);
        assertThat(remote(existing)).isNotNull();
    }
    @Test void partialProfileUpdatePreservesCredentialsAndRequiredActions() {
        var a = create(); var credential = new CredentialRepresentation(); credential.setType("password");
        credential.setValue("Test-only-password-928!"); credential.setTemporary(true);
        client.realm(REALM).users().get(a.getKeycloakId()).resetPassword(credential);
        a.setName("수정된 이름"); gateway.updateProfile(a);
        var user = remote(a); assertThat(user.getFirstName()).isEqualTo("수정된 이름");
        assertThat(user.isEnabled()).isTrue(); assertThat(user.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(client.realm(REALM).users().get(a.getKeycloakId()).credentials()).hasSize(1);
        assertThat(user.getAttributes().get(KeycloakAccountGateway.OWNER_ATTRIBUTE)).containsExactly(a.getAccountRef());
    }
    @Test void manuallyDisabledUserIsNeverReenabledByProfileUpdate() {
        var a = create(); var patch = new UserRepresentation(); patch.setEnabled(false);
        client.realm(REALM).users().get(a.getKeycloakId()).update(patch);
        a.setName("수정"); assertThatThrownBy(() -> gateway.updateProfile(a)).isInstanceOf(AccountSyncFailure.class);
        assertThat(remote(a).isEnabled()).isFalse();
    }
    @Test void missingLinkedAccountIsNotRecreated() {
        var a = create(); client.realm(REALM).users().get(a.getKeycloakId()).remove();
        assertThatThrownBy(() -> gateway.updateProfile(a)).isInstanceOf(AccountSyncFailure.class);
        assertThat(client.realm(REALM).users().list()).isEmpty();
    }
    @Test void remoteRenameRequiresReviewRatherThanRebinding() {
        var a = create(); var patch = new UserRepresentation(); patch.setUsername("renamed");
        client.realm(REALM).users().get(a.getKeycloakId()).update(patch);
        assertThatThrownBy(() -> gateway.updateProfile(a)).isInstanceOf(AccountSyncFailure.class);
    }
    @Test void deleteCanBeRepeatedAndRecoverUnacknowledgedCreation() {
        var a = create(); a.setKeycloakId(null); gateway.delete(a); gateway.delete(a);
        assertThat(client.realm(REALM).users().list()).isEmpty();
    }
    @Test void tombstoneFindsLateCreateWithNewIdEvenWhenOldIdWasKnown() {
        var a = create(); String original = a.getKeycloakId();
        gateway.delete(a);
        String late = gateway.ensureCreated(a);
        assertThat(late).isNotEqualTo(original);
        assertThat(a.getKeycloakId()).isEqualTo(original);
        gateway.delete(a);
        assertThat(client.realm(REALM).users().list()).isEmpty();
    }
    @Test void linkedLegacyAccountNeedsNoOwnershipMarkerForProfileUpdate() {
        var a = admin(); var user = new UserRepresentation(); user.setUsername(a.getUsername()); user.setFirstName("기존"); user.setEnabled(true);
        try (var r = client.realm(REALM).users().create(user)) { assertThat(r.getStatus()).isEqualTo(201); }
        String id = client.realm(REALM).users().search(a.getUsername(), true).get(0).getId();
        gateway.verifyLink(a, id); a.setKeycloakId(id); gateway.updateProfile(a);
        assertThat(remote(a).getFirstName()).isEqualTo(a.getName());
    }
}
