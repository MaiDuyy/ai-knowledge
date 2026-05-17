package com.security.security.security;

import com.security.security.domain.RequestContext;
import com.security.security.domain.TokenData;
import com.security.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import com.security.security.dto.UserDTO;

import static com.security.security.constants.Constants.TOKEN_PREFIX;
import static com.security.security.entity.enumeration.TokenType.ACCESS;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.security.core.authority.AuthorityUtils.commaSeparatedStringToAuthorityList;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthorizationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // ── Priority 1: x-user-id trusted internal header (from api-gateway) ──
            String internalUserId = request.getHeader("x-user-id");
            if (internalUserId != null && !internalUserId.trim().isEmpty()) {
                String internalRole = request.getHeader("x-user-role");
                String internalRoles = request.getHeader("x-user-roles");
                
                log.debug("[Auth] Internal request: userId={}, role={}, roles={}", internalUserId, internalRole, internalRoles);

                UserDTO user = new UserDTO();
                user.setUserId(internalUserId);
                user.setEmail(internalUserId);
                
                String role = internalRole != null ? internalRole : "WORKSPACE_MEMBER";
                user.setRole(role);
                
                // 1. Xử lý Roles từ Gateway (loại bỏ ký tự JSON)
                String cleanedRoles = (internalRoles != null && !internalRoles.trim().isEmpty()) 
                    ? internalRoles.replace("[", "").replace("]", "").replace("\"", "").replace(" ", "")
                    : "";

                // 2. Xây dựng danh sách quyền (Authorities)
                StringBuilder authBuilder = new StringBuilder();
                
                // Thêm các quyền mặc định cho Document
                authBuilder.append("document:create,document:read,document:update,document:delete");
                
                // Thêm các roles từ Gateway (nếu có)
                if (!cleanedRoles.isEmpty()) {
                    authBuilder.append(",").append(cleanedRoles);
                }
                
                // Thêm Role hiện tại với prefix ROLE_ (Bắt buộc cho hasRole() trong Spring Security)
                authBuilder.append(",ROLE_").append(role);
                
                // Xử lý đặc biệt cho ADMIN / SUPER_ADMIN
                if ("SUPER_ADMIN".equals(role) || "ADMIN".equals(role) || cleanedRoles.contains("ADMIN")) {
                    authBuilder.append(",system:admin,kb:manage,user:manage");
                }
                
                String finalAuthorities = authBuilder.toString();
                user.setAuthorities(finalAuthorities);
                user.setEnabled(true);
                user.setAccountNonLocked(true);
                user.setAccountNonExpired(true);
                user.setCredentialsNonExpired(true);

                log.debug("[Auth] Final authorities for {}: {}", internalUserId, finalAuthorities);

                RequestContext.setUserId(internalUserId);
                var authorities = commaSeparatedStringToAuthorityList(finalAuthorities);
                Authentication authentication = getAuthentication(user, authorities, request);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                return;
            }

            // ── Priority 2: JWT cookie or Authorization Bearer ──
            String token = getToken(request);

            if (token != null && !token.trim().isEmpty()) {
                log.debug("Processing JWT token for request: {}", request.getRequestURI());

                var user = jwtService.getTokenData(token, TokenData::getUser);
                var authorities = jwtService.getTokenData(token, TokenData::getAuthorities);
                var isValid = jwtService.getTokenData(token, TokenData::isValid);

                if (isValid && user != null) {
                    RequestContext.setUserId(user.getUserId());
                    Authentication authentication = getAuthentication(user, authorities, request);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Successfully authenticated user: {}", user.getEmail());
                } else {
                    log.warn("Invalid JWT token for user: {}", user != null ? user.getEmail() : "unknown");
                    SecurityContextHolder.clearContext();
                }
            } else {
                log.debug("No JWT token found for request: {}", request.getRequestURI());
            }
        } catch (Exception e) {
            log.error("JWT Authorization failed for request {}: {}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        } finally {
            try {
                filterChain.doFilter(request, response);
            } finally {
                RequestContext.start();
            }
        }
    }

    private String getToken(HttpServletRequest request) {
        var tokenFromCookie = jwtService.extractToken(request, ACCESS.getValue());
        if (tokenFromCookie.isPresent()) {
            return tokenFromCookie.get();
        }

        String authorizationHeader = request.getHeader(AUTHORIZATION);
        if (authorizationHeader != null && authorizationHeader.startsWith(TOKEN_PREFIX)) {
            return authorizationHeader.substring(TOKEN_PREFIX.length());
        }

        return null;
    }

    private Authentication getAuthentication(Object user, Object authorities, HttpServletRequest request) {
        return new JwtAuthenticationToken(user, authorities, request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        List<String> publicPaths = List.of(
                "/user/register",
                "/user/verify/account",
                "/user/forgot-password",
                "/user/reset-password",
                "/user/login",
                "/user/logout",
                "/error"
        );

        return publicPaths.stream().anyMatch(path::startsWith);
    }
}
