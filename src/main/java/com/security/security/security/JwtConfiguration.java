package com.security.security.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;

@Getter
@Setter

public class JwtConfiguration {
    @Value("${JWT_EXPIRATION}")
    private Long expiration;
    @Value("${JWT_SECRET}")
    private String secret;
}
