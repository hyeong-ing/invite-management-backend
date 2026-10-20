package com.example.invitevip.admin.database;

import com.example.invitevip.admin.entity.Admin;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    long countByStateAndRole(Admin.State state, com.example.invitevip.admin.entity.AdminRole role);

    boolean existsByUsername(String username);
    @EntityGraph(attributePaths = {"adminPermissions", "adminPermissions.permission"})
    Optional<Admin> findByUsername(String username);

    @EntityGraph(attributePaths = {"adminPermissions", "adminPermissions.permission"})
    List<Admin> findDistinctByNameContainingOrUsernameContaining(String nameKeyword, String usernameKeyword);

    @EntityGraph(attributePaths = {"adminPermissions", "adminPermissions.permission"})
    Optional<Admin> findByKeycloakId(String keycloakId);

    @Override
    @EntityGraph(attributePaths = {"adminPermissions", "adminPermissions.permission"})
    List<Admin> findAll();

    @Override
    @EntityGraph(attributePaths = {"adminPermissions", "adminPermissions.permission"})
    Optional<Admin> findById(Long id);
}
