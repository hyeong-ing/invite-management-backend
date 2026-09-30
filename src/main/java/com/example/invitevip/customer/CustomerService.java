package com.example.invitevip.customer;

import com.example.invitevip.customer.entity.Customer;
import com.example.invitevip.customer.entity.CustomerSearchEntity;
import com.example.invitevip.customer.entity.InviteCode;
import com.example.invitevip.customer.database.CustomerRepository;
import com.example.invitevip.customer.database.CustomerSearchRepository;
import com.example.invitevip.customer.dto.CustomerRequest;
import com.example.invitevip.customer.dto.CustomerResponse;
import com.example.invitevip.customer.dto.CustomerSearchResponse;
import com.example.invitevip.customer.mapper.CustomerSearchMapper;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomerService {


    private final CustomerRepository customerRepository;
    private final CustomerSearchRepository customerSearchRepository;
    private final CustomerSearchMapper customerSearchMapper;

    @Autowired
    public CustomerService(CustomerRepository customerRepository,
                           CustomerSearchRepository customerSearchRepository,
                           CustomerSearchMapper customerSearchMapper) {
        this.customerRepository = customerRepository;
        this.customerSearchRepository = customerSearchRepository;
        this.customerSearchMapper = customerSearchMapper;
    }


    public List<CustomerResponse> findAllCustomers() {
        return customerRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CustomerResponse save(CustomerRequest request) {

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
        customerSearchRepository.save(customerSearchMapper.toSearchEntity(saved));

        return toResponse(saved);
    }


    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
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

        customerSearchRepository.save(customerSearchMapper.toSearchEntity(customer));

        return toResponse(customer);
    }

    @Transactional
    public void delete(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customerRepository.delete(customer);
        customerSearchRepository.deleteById(id);
    }

    @Transactional
    public void syncAllToElasticsearch() {
        List<Customer> customers = customerRepository.findAll();
        Set<Long> customerIds = customers.stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());

        List<CustomerSearchEntity> staleCustomers = new ArrayList<>();
        for (CustomerSearchEntity document : customerSearchRepository.findAll()) {
            if (!customerIds.contains(document.getId())) {
                staleCustomers.add(document);
            }
        }

        // 현재 고객 정보가 모두 반영된 뒤 불필요한 검색 데이터를 삭제합니다.
        for (Customer customer : customers) {
            customerSearchRepository.save(customerSearchMapper.toSearchEntity(customer));
        }
        if (!staleCustomers.isEmpty()) {
            customerSearchRepository.deleteAll(staleCustomers);
        }
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
