package com.example.invitevip.customer;

import com.example.invitevip.customer.dto.CustomerRequest;
import com.example.invitevip.customer.dto.CustomerResponse;
import com.example.invitevip.customer.dto.CustomerSearchResponse;
import com.example.invitevip.customer.sync.SearchSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final SearchSyncService searchSync;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public List<CustomerResponse> effectCustomers() {
        return customerService.findAllCustomers();
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'CUSTOMER_ADD')")
    public CustomerResponse saveCustomer(@Valid @RequestBody CustomerRequest request) {
        return customerService.save(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'CUSTOMER_EDIT')")
    public ResponseEntity<CustomerResponse> updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        CustomerResponse updated = customerService.update(id, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'CUSTOMER_DELETE')")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
        customerService.delete(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'CUSTOMER_SEARCH')")
    public List<CustomerSearchResponse> searchCustomer(@RequestParam String keyword) {
        return customerService.searchCustomers(keyword);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SearchSyncService.RebuildResult sync() {
        return searchSync.rebuild();
    }

    @GetMapping("/sync/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SearchSyncService.SyncStatus syncStatus() {
        return searchSync.status();
    }

    @PostMapping("/sync/reconcile")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public java.util.Map<String, Integer> reconcile() {
        return java.util.Map.of("queued", searchSync.reconcile());
    }

    @PostMapping("/sync/retry")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public java.util.Map<String, Integer> retry() {
        return java.util.Map.of("queued", searchSync.retryFailed());
    }
}
