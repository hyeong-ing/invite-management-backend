package com.example.invitevip.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AdminRequest {

    @NotBlank(message = "이름은 필수입니다.")
    @Size(max = 30, message = "이름은 30자 이하로 입력해야 합니다.")
    private String name;

    @NotBlank(message = "아이디는 필수입니다.")
    @Size(max = 50, message = "아이디는 50자 이하로 입력해야 합니다.")
    private String username;

    @Size(max = 100, message = "비밀번호는 100자 이하로 입력해야 합니다.")
    private String password;

    @NotBlank(message = "관리자 역할은 필수입니다.")
    @Pattern(regexp = "ADMIN|SUPER_ADMIN", message = "관리자 역할이 올바르지 않습니다.")
    private String role;

    private List<@Pattern(
            regexp = "CUSTOMER_SEARCH|CUSTOMER_ADD|CUSTOMER_EDIT|CUSTOMER_DELETE",
            message = "권한 코드가 올바르지 않습니다."
    ) String> permissions;
}
