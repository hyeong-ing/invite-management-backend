package com.example.invitevip.common;

import com.example.invitevip.admin.AdminNotFoundException;
import com.example.invitevip.customer.CustomerNotFoundException;
import com.example.invitevip.customer.DuplicateCodeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateCodeException.class)
    public ResponseEntity<String> handleDuplicateCode(DuplicateCodeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<String> handleCustomerNotFound(CustomerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(AdminNotFoundException.class)
    public ResponseEntity<String> handleAdminNotFound(AdminNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (isCustomerCodeUniqueViolation(e)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("초대코드가 중복되었습니다.");
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body("데이터 중복 또는 제약 조건 위반이 발생했습니다.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("\n"));

        return ResponseEntity.badRequest().body(message.isBlank() ? "입력값이 올바르지 않습니다." : message);
    }

    private boolean isCustomerCodeUniqueViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause == null ? e.getMessage() : cause.getMessage();
        return message != null && message.toLowerCase().contains("uk_customer_code");
    }
}
