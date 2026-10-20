package com.example.invitevip.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "admin",uniqueConstraints = @UniqueConstraint(name = "uk_admin_username", columnNames = "username"))
@Getter
@Setter
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_id", length = 100, unique = true)
    private String keycloakId;

    public enum State { UNLINKED, PENDING_CREATE, ACTIVE, BLOCKED, DELETING, DELETED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private State state = State.UNLINKED;

    @Column(nullable = false, unique = true, length = 36)
    private String accountRef = java.util.UUID.randomUUID().toString();

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(nullable = false, length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminRole role;

    @OneToMany(mappedBy = "admin", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<AdminPermission> adminPermissions = new LinkedHashSet<>();
}
