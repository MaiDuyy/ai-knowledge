package com.security.security.domain;

import com.security.security.dto.UserDTO;
import io.jsonwebtoken.Claims;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

@Builder
@Getter
@Setter
public class TokenData  {
    private UserDTO user;
    private Claims claims;
    private boolean valid;
    private List<GrantedAuthority> authorities;
}
