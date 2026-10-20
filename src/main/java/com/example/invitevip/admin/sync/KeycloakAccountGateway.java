package com.example.invitevip.admin.sync;

import com.example.invitevip.admin.entity.Admin;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class KeycloakAccountGateway implements AccountGateway {
    public static final String OWNER_ATTRIBUTE = "invitevip_admin_ref";
    private final Keycloak keycloak;
    @Value("${keycloak.realm}")
    private String realm;

    private UsersResource users() { return keycloak.realm(realm).users(); }

    @Override
    public String ensureCreated(Admin admin) {
        UserRepresentation existing = findOwned(admin);
        if (existing != null) {
            requireEnabled(existing);
            return existing.getId();
        }
        UserRepresentation user = new UserRepresentation();
        user.setUsername(admin.getUsername());
        user.setFirstName(admin.getName());
        user.setEnabled(true);
        user.setRequiredActions(List.of("UPDATE_PASSWORD"));
        user.setAttributes(Map.of(OWNER_ATTRIBUTE, List.of(admin.getAccountRef())));
        try (Response response = users().create(user)) {
            if (response.getStatus() != 201 && response.getStatus() != 409) {
                if (response.getStatus() < 500 && response.getStatus() != 429) {
                    throw new AccountSyncFailure("Keycloak 계정 생성 설정을 확인하세요 (HTTP " + response.getStatus() + ").", false);
                }
                throw new IllegalStateException("Keycloak temporarily unavailable");
            }
        }
        // Do not trust a 409 or a matching username alone. Also verify marker persistence.
        UserRepresentation created = findOwned(admin);
        if (created == null) throw new IllegalStateException("Created account not yet visible");
        requireEnabled(created);
        return created.getId();
    }

    private UserRepresentation findOwned(Admin admin) {
        List<UserRepresentation> matches = users().search(admin.getUsername(), true).stream()
                .filter(u -> admin.getUsername().equals(u.getUsername())).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1 || !hasOwner(matches.get(0), admin)) {
            throw new AccountSyncFailure("동일 아이디의 다른 계정이 있거나 연계 속성이 없습니다. 계정 연결을 확인하세요.", false);
        }
        return matches.get(0);
    }

    private boolean hasOwner(UserRepresentation user, Admin admin) {
        return user.getAttributes() != null
                && List.of(admin.getAccountRef()).equals(user.getAttributes().get(OWNER_ATTRIBUTE));
    }

    private void requireEnabled(UserRepresentation user) {
        if (!Boolean.TRUE.equals(user.isEnabled())) {
            throw new AccountSyncFailure("Keycloak에서 비활성화한 계정입니다. 확인 후 재시도하세요.", true);
        }
    }

    @Override
    public void updateProfile(Admin admin) {
        UserRepresentation current;
        try {
            current = users().get(admin.getKeycloakId()).toRepresentation();
        } catch (NotFoundException e) {
            throw new AccountSyncFailure("연결된 Keycloak 계정이 없습니다. 자동 재생성하지 않습니다.", true);
        }
        if (!Objects.equals(current.getUsername(), admin.getUsername())) {
            throw new AccountSyncFailure("연결된 Keycloak 계정의 아이디가 변경되었습니다. 연결을 확인하세요.", true);
        }
        requireEnabled(current);
        if (!Objects.equals(current.getFirstName(), admin.getName())) {
            // Partial update: never replay enabled, credentials, roles, or a stale full representation.
            UserRepresentation patch = new UserRepresentation();
            patch.setFirstName(admin.getName());
            users().get(admin.getKeycloakId()).update(patch);
        }
    }

    @Override
    public void delete(Admin admin) {
        String id = admin.getKeycloakId();
        if (id != null) deleteId(id);
        // A timed-out create may arrive after another create was acknowledged and deleted.
        // Check the immutable creation marker even when the original ID is already known.
        UserRepresentation owned = findOwned(admin);
        if (owned != null) deleteId(owned.getId());
    }

    private void deleteId(String id) {
        try {
            UserRepresentation patch = new UserRepresentation();
            patch.setEnabled(false);
            users().get(id).update(patch);
            users().get(id).logout();
            users().get(id).remove();
        } catch (NotFoundException ignored) {
            // Only a known linked ID (or verified owned creation) can be considered deleted.
        }
    }

    @Override
    public void verifyLink(Admin admin, String keycloakId) {
        UserRepresentation user;
        try {
            user = users().get(keycloakId).toRepresentation();
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("입력한 사용자 ID의 Keycloak 계정이 없습니다.");
        }
        if (!Objects.equals(user.getUsername(), admin.getUsername())) {
            throw new IllegalArgumentException("Keycloak 계정 아이디가 기존 관리자 아이디와 다릅니다.");
        }
        if (!Boolean.TRUE.equals(user.isEnabled())) {
            throw new IllegalArgumentException("Keycloak에서 비활성 상태인 계정은 연결할 수 없습니다.");
        }
    }
}
