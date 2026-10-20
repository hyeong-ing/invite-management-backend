package com.example.invitevip.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AdminResponse {

    private Long id;
    private String state;
    private String syncStatus;
    private String syncError;
    private int syncAttempts;
    private String name;
    private String username;
    private String role;
    private List<String> permissions;
}
