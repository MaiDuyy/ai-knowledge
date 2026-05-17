package com.security.security.dto;

import lombok.Data;


@Data
public class UserDTO {
    private Long id;
    private Long createBy;
    private Long updateBy;
    private String userId ;
    private String fullName;
    private String email;
    private String phone;
    private String bio;
    private String imageUrl;
    private String lastLogin;
    private String createAt;
    private String updateAt;
    private String role;
    private String authorities;
    private boolean accountNonExpired;
    private boolean accountNonLocked;
    private boolean  credentialsNonExpired;
    private boolean enabled;
    private boolean mfa;

}
