package com.security.security.domain;

import com.security.security.dto.UserDTO;
import com.security.security.exception.ApiException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Collection;

public class ApiAuthentication extends AbstractAuthenticationToken {
    private static final String PASSWORD_PROTECTED = "[PASSWORD PROTECTED]";
    private static final String EMAIL_PROTECTED ="[EMAIL PROTECTED]" ;
    private UserDTO userdto;
    private  String email;
    private  String password;
    private  boolean authenticated;



    private ApiAuthentication(String email, String password) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.password = password;
        this.email = email;
        this.authenticated = false;
    }
    private ApiAuthentication(UserDTO userDTO ,Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userdto = userDTO;
        this.password = PASSWORD_PROTECTED;
        this.email = EMAIL_PROTECTED;
        this.authenticated = true;
    }


    public static ApiAuthentication unauthenticated(String email, String password) {
       return new ApiAuthentication(email, password);
    }
    public static  ApiAuthentication authenticated(UserDTO userDTO ,Collection<? extends GrantedAuthority> authorities) {
        return new ApiAuthentication(userDTO, authorities);
    }


    @Override
    public Object getCredentials() {
        return PASSWORD_PROTECTED;
    }

    @Override
    public Object getPrincipal() {
        return  this.userdto;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        throw new ApiException("You can not set Authentication");
    }

    @Override
    public boolean isAuthenticated() {
        return this.authenticated;
    }

    public String getEmail() {
        return this.email;
    }

    public String getPassword() {
         return this.password;
    }
}
