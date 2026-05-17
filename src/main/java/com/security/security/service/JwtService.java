package com.security.security.service;

import com.security.security.domain.Token;
import com.security.security.domain.TokenData;
import com.security.security.dto.UserDTO;
import com.security.security.entity.enumeration.TokenType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Optional;
import java.util.function.Function;

public interface JwtService {
    String createToken(UserDTO user  , Function<Token, String > tokenFunction) ;
    Optional<String >extractToken(HttpServletRequest request, String tokenType) ;
    void addCookie(HttpServletResponse response, UserDTO user , TokenType type) ;
    <T> T getTokenData(String token, Function<TokenData, T> tokenFunction) ;
    void removeCookie(HttpServletRequest request, HttpServletResponse response, String cookieName) ;
}
