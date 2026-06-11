package com.security.security.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;

@Getter
@Setter

public class JwtConfiguration {
    @Value("${jwt.expiration:86400}")
    private Long expiration;
    @Value("${jwt.secret:default-secret-key-32-characters-or-more-for-jwt-security-1234567890}")
    private String secret;
}
