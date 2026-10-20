package com.example.invitevip.admin;

import com.example.invitevip.admin.database.AdminRepository;
import com.example.invitevip.admin.entity.Admin;
import com.example.invitevip.admin.entity.AdminPermission;
import com.example.invitevip.admin.entity.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuthorityService {

    private static final String CUSTOMER_READ = "CUSTOMER_READ";

    private final AdminRepository adminRepository;

    public Set<GrantedAuthority> loadAuthorities(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        Admin admin = keycloakId == null ? null : adminRepository.findByKeycloakId(keycloakId).orElse(null);
        if (admin == null || admin.getState() != Admin.State.ACTIVE) {
            throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"),
                    "활성 관리자 계정이 아닙니다.");
        }

        Set<GrantedAuthority> authorities = new HashSet<>();

        if (admin.getRole() != null) {
            authorities.add(new SimpleGrantedAuthority(
                    "ROLE_" + admin.getRole().name()
            ));
        }

        for (AdminPermission adminPermission : admin.getAdminPermissions()) {
            Permission permission = adminPermission.getPermission();

            if (permission != null
                    && permission.getCode() != null
                    && !permission.getCode().isBlank()
                    && !CUSTOMER_READ.equals(permission.getCode().trim().toUpperCase())) {
                authorities.add(new SimpleGrantedAuthority(
                        permission.getCode().trim().toUpperCase()
                ));
            }
        }

        return authorities;
    }
}
