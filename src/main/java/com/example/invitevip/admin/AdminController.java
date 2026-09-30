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

    public AdminController(AdminService adminService) {
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
            return ResponseEntity.ok(savedAdmin);
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
        return ResponseEntity.ok("관리자가 삭제되었습니다.");
    }

    @GetMapping("/search")
    public List<AdminResponse> searchAdmins(@RequestParam String keyword) {
        return adminService.searchAdmins(keyword);
    }
}
