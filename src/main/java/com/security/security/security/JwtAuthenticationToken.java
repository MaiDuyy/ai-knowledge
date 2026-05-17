package com.security.security.security;

import com.security.security.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private final Object credentials;
    private final HttpServletRequest request;

    public JwtAuthenticationToken(Object principal ,Object authorities, HttpServletRequest request) {
        super((Collection<? extends GrantedAuthority>) authorities);
        this.principal = principal;
        this.credentials = null; // JWT tokens don't expose credentials
        this.request = request;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public HttpServletRequest getRequest() {
        return request;
    }

    @Override
    public String getName() {
        if (principal instanceof UserDTO) {
            return ((UserDTO) principal).getUserId();
        }
        return super.getName();
    }
}