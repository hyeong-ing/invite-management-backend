package com.example.invitevip.customer;

import com.example.invitevip.customer.entity.Customer;
import com.example.invitevip.customer.entity.InviteCode;
import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.database.CustomerSearchRepository;
import com.example.invitevip.customer.dto.CustomerRequest;
import com.example.invitevip.customer.dto.CustomerResponse;
import com.example.invitevip.customer.dto.CustomerSearchResponse;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import com.example.invitevip.customer.sync.SearchSyncService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {


    private final CustomerRepository customerRepository;
    private final CustomerSearchRepository customerSearchRepository;
    private final CustomerSearchMapper customerSearchMapper;
    private final SearchSyncService searchSync;

    @Autowired
    public CustomerService(CustomerRepository customerRepository,
                           CustomerSearchRepository customerSearchRepository,
                           CustomerSearchMapper customerSearchMapper, SearchSyncService searchSync) {
        this.customerRepository = customerRepository;
        this.customerSearchRepository = customerSearchRepository;
        this.customerSearchMapper = customerSearchMapper;
        this.searchSync = searchSync;
    }


    public List<CustomerResponse> findAllCustomers() {
        return customerRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerResponse save(CustomerRequest request) {
        searchSync.beforeCustomerChange();
        InviteCode inviteCode = InviteCode.of(request.getCode());

        if (customerRepository.existsByInviteCode(inviteCode)) {
            throw new DuplicateCodeException("초대코드가 중복되었습니다.");
        }

        Customer customer = Customer.create(
                request.getName(),
                request.getGrade(),
                request.getPhone(),
                inviteCode,
                request.getNote()
        );

        Customer saved = customerRepository.save(customer);
        searchSync.record(saved);

        return toResponse(saved);
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerResponse update(Long id, CustomerRequest request) {
        searchSync.beforeCustomerChange();
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        InviteCode newInviteCode = InviteCode.of(request.getCode());

        customerRepository.findByInviteCode(newInviteCode).ifPresent(found -> {
            if (!found.getId().equals(id)) {
                throw new DuplicateCodeException("초대코드가 중복되었습니다.");
            }
        });

        customer.update(
                request.getName(),
                request.getGrade(),
                request.getPhone(),
                newInviteCode,
                request.getNote()
        );

        searchSync.record(customer);

        return toResponse(customer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long id) {
        searchSync.beforeCustomerChange();
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customerRepository.delete(customer);
        searchSync.recordDeletion(id);
    }

    public List<CustomerSearchResponse> searchCustomers(String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword.isBlank()) {
            return List.of();
        }

        return customerSearchRepository.searchAll(normalizedKeyword).stream()
                .map(customerSearchMapper::toResponse)
                .toList();
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    public CustomerResponse toResponse(Customer customer) {
        CustomerResponse response = new CustomerResponse();
        response.setId(customer.getId());
        response.setName(customer.getName());
        response.setGrade(customer.getGrade());
        response.setPhone(customer.getPhone());
        response.setCode(customer.getCode());
        response.setNote(customer.getNote());
        return response;
    }

}
