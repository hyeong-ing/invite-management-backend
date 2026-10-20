package com.example.invitevip.admin;

import com.example.invitevip.admin.dto.AdminRequest;
import com.example.invitevip.admin.dto.AdminResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admins")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final com.example.invitevip.admin.sync.AccountSyncService accountSync;

    public AdminController(AdminService adminService, com.example.invitevip.admin.sync.AccountSyncService accountSync) {
        this.accountSync = accountSync;
        this.adminService = adminService;
    }

    @GetMapping
    public List<AdminResponse> findAllAdmins() {
        return adminService.findAllAdmins();
    }

    @PostMapping
    public ResponseEntity<?> saveAdmin(@Valid @RequestBody AdminRequest request) {
        try {
            AdminResponse savedAdmin = adminService.save(request);
            return ResponseEntity.accepted().body(savedAdmin);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAdmin(@PathVariable Long id, @Valid @RequestBody AdminRequest request) {
        try {
            AdminResponse updatedAdmin = adminService.update(id, request);
            return ResponseEntity.ok(updatedAdmin);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAdmin(@PathVariable Long id) {
        adminService.delete(id);
        return ResponseEntity.accepted().body("서비스 접근을 차단했습니다. Keycloak 계정 삭제를 처리합니다.");
    }

    @PostMapping("/{id}/sync/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        try {
            accountSync.retry(id);
            return ResponseEntity.accepted().body("계정 동기화를 다시 요청했습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public record LinkRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 100) String keycloakId) {}

    @PostMapping("/{id}/link")
    public ResponseEntity<?> link(@PathVariable Long id, @Valid @RequestBody LinkRequest request) {
        try {
            return ResponseEntity.ok(adminService.link(id, request.keycloakId().trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/search")
    public List<AdminResponse> searchAdmins(@RequestParam String keyword) {
        return adminService.searchAdmins(keyword);
    }
}
