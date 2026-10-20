package com.example.invitevip.admin;

import com.example.invitevip.admin.database.AdminRepository;
import com.example.invitevip.admin.database.PermissionRepository;
import com.example.invitevip.admin.dto.AdminRequest;
import com.example.invitevip.admin.dto.AdminResponse;
import com.example.invitevip.admin.entity.Admin;
import com.example.invitevip.admin.entity.AdminPermission;
import com.example.invitevip.admin.entity.AdminRole;
import com.example.invitevip.admin.entity.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.invitevip.admin.sync.AccountSyncService;
import com.example.invitevip.admin.sync.AccountOutboxRepository;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private static final String CUSTOMER_READ = "CUSTOMER_READ";

    private final AdminRepository adminRepository;
    private final PermissionRepository permissionRepository;

    private final AccountSyncService accountSync;
    private final AccountOutboxRepository accountOutbox;
    private final com.example.invitevip.admin.sync.AccountGateway accountGateway;

    public List<AdminResponse> findAllAdmins() {
        return adminRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdminResponse save(AdminRequest request) {
        accountSync.lock();
        rejectPassword(request);
        validateDuplicateUsername(null, request.getUsername());

        Admin admin = new Admin();
        admin.setName(request.getName());
        admin.setUsername(request.getUsername());
        admin.setRole(resolveRole(request.getRole()));

        replacePermissions(admin, request.getPermissions());

        admin.setState(Admin.State.PENDING_CREATE);
        Admin savedAdmin = adminRepository.save(admin);
        accountSync.enqueue(savedAdmin);

        return toResponse(savedAdmin);
    }

    @Transactional
    public AdminResponse update(Long id, AdminRequest request) {
        accountSync.lock();
        rejectPassword(request);
        Admin admin = adminRepository.findById(id)
                .orElseThrow(() -> new AdminNotFoundException(id));

        if (admin.getState() != Admin.State.ACTIVE && admin.getState() != Admin.State.PENDING_CREATE) {
            throw new IllegalArgumentException("활성 또는 생성 대기 계정만 수정할 수 있습니다.");
        }
        if (!admin.getUsername().equals(request.getUsername())) {
            throw new IllegalArgumentException("계정 연결 보호를 위해 아이디는 변경할 수 없습니다.");
        }
        if (admin.getRole() == AdminRole.SUPER_ADMIN && !"SUPER_ADMIN".equals(request.getRole())) {
            protectLastSuperAdmin(admin);
        }
        boolean profileChanged = !Objects.equals(admin.getName(), request.getName());

        admin.setName(request.getName());
        admin.setUsername(request.getUsername());
        admin.setRole(resolveRole(request.getRole()));

        replacePermissions(admin, request.getPermissions());

        if (profileChanged) accountSync.enqueue(admin);

        return toResponse(admin);
    }

    @Transactional
    public void delete(Long id) {
        accountSync.lock();
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new AdminNotFoundException(id));
        if (admin.getState() == Admin.State.DELETED || admin.getState() == Admin.State.DELETING) return;
        if (admin.getState() == Admin.State.UNLINKED) {
            throw new IllegalArgumentException("기존 계정 연결을 검증한 후 삭제할 수 있습니다.");
        }
        protectLastSuperAdmin(admin);
        admin.setState(Admin.State.DELETING);
        accountSync.enqueue(admin);
    }

    private void protectLastSuperAdmin(Admin admin) {
        if (admin.getState() == Admin.State.ACTIVE && admin.getRole() == AdminRole.SUPER_ADMIN
                && adminRepository.countByStateAndRole(Admin.State.ACTIVE, AdminRole.SUPER_ADMIN) <= 1) {
            throw new IllegalArgumentException("마지막 활성 최고 관리자는 삭제하거나 권한을 낮출 수 없습니다.");
        }
    }

    private void rejectPassword(AdminRequest request) {
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            throw new IllegalArgumentException("비밀번호는 Keycloak에서 설정하거나 변경해 주세요.");
        }
    }

    @Transactional
    public AdminResponse link(Long id, String keycloakId) {
        accountSync.lock();
        Admin admin = adminRepository.findById(id).orElseThrow(() -> new AdminNotFoundException(id));
        if (admin.getState() != Admin.State.UNLINKED) {
            throw new IllegalArgumentException("미연결 계정만 연결할 수 있습니다.");
        }
        if (adminRepository.findByKeycloakId(keycloakId).isPresent()) {
            throw new IllegalArgumentException("이미 다른 관리자에 연결된 사용자 ID입니다.");
        }
        // Explicit SUPER_ADMIN confirmation; never infer identity from a login's username.
        accountGateway.verifyLink(admin, keycloakId);
        admin.setKeycloakId(keycloakId);
        admin.setState(Admin.State.ACTIVE);
        accountSync.enqueue(admin);
        return toResponse(admin);
    }

    public List<AdminResponse> searchAdmins(String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }

        return adminRepository.findDistinctByNameContainingOrUsernameContaining(normalizedKeyword, normalizedKeyword).stream()
                .map(this::toResponse)
                .toList();
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private void validateDuplicateUsername(Long id, String username) {
        boolean exists = false;
        if (id == null) {
            exists = adminRepository.existsByUsername(username);
        } else {
            Admin existing = adminRepository.findByUsername(username).orElse(null);
            if (existing != null && !existing.getId().equals(id)) {
                exists = true;
            }
        }
        if (exists) {
            throw new IllegalArgumentException("이미 사용중인 아이디입니다.");
        }
    }

    private AdminRole resolveRole(String roleName) {
        try {
            return AdminRole.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            return AdminRole.ADMIN;
        }
    }

    private void replacePermissions(Admin admin, List<String> permissionCodes) {
        Set<String> requestedCodes = permissionCodes == null
                ? Set.of()
                : permissionCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isBlank())
                .filter(code -> !CUSTOMER_READ.equals(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requestedCodes.isEmpty()) {
            admin.getAdminPermissions().clear();
            return;
        }

        List<Permission> permissions = permissionRepository.findByCodeIn(List.copyOf(requestedCodes));

        Set<String> foundCodes = permissions.stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        List<String> missingCodes = requestedCodes.stream()
                .filter(code -> !foundCodes.contains(code))
                .toList();

        if (!missingCodes.isEmpty()) {
            throw new IllegalArgumentException("존재하지 않는 권한 코드입니다: " + String.join(", ", missingCodes));
        }

        admin.getAdminPermissions().removeIf(adminPermission -> {
            Permission permission = adminPermission.getPermission();
            return permission == null
                    || permission.getCode() == null
                    || !requestedCodes.contains(permission.getCode());
        });

        Set<String> existingCodes = admin.getAdminPermissions().stream()
                .map(AdminPermission::getPermission)
                .filter(Objects::nonNull)
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (Permission permission : permissions) {
            if (existingCodes.contains(permission.getCode())) {
                continue;
            }

            AdminPermission adminPermission = new AdminPermission();
            adminPermission.setAdmin(admin);
            adminPermission.setPermission(permission);
            admin.getAdminPermissions().add(adminPermission);
        }
    }

    private AdminResponse toResponse(Admin admin) {
        AdminResponse response = new AdminResponse();
        response.setId(admin.getId());
        response.setName(admin.getName());
        response.setUsername(admin.getUsername());
        response.setRole(admin.getRole().name());
        response.setState(admin.getState().name());
        accountOutbox.findById(admin.getId()).ifPresent(job -> {
            response.setSyncStatus(job.getStatus().name());
            response.setSyncError(job.getLastError());
            response.setSyncAttempts(job.getAttempts());
        });

        List<String> permissionCodes = admin.getAdminPermissions().stream()
                .map(AdminPermission::getPermission)
                .filter(Objects::nonNull)
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .filter(code -> !CUSTOMER_READ.equals(code))
                .sorted(Comparator.naturalOrder())
                .toList();

        response.setPermissions(permissionCodes);
        return response;
    }
}
