package com.security.security.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authentication boundary for voice-service -> ai-knowledge calls. Browser JWTs
 * and the legacy gateway user headers cannot authenticate this endpoint.
 */
@Component
public class InternalMeetingAiAuthenticationFilter extends OncePerRequestFilter {

    public static final String PATH = "/internal/meeting-ai";
    public static final String SERVICE_KEY_HEADER = "X-Meeting-Ai-Service-Key";

    private final byte[] expectedServiceKey;

    public InternalMeetingAiAuthenticationFilter(
            @Value("${meeting.ai.internal-service-key:}") String serviceKey
    ) {
        this.expectedServiceKey = serviceKey == null
                ? new byte[0]
                : serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedKey = request.getHeader(SERVICE_KEY_HEADER);
        if (!isValid(suppliedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error\":\"Invalid internal meeting AI credential\"}");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "voice-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_MEETING_AI"))
        ));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isValid(String suppliedKey) {
        return expectedServiceKey.length > 0
                && suppliedKey != null
                && MessageDigest.isEqual(expectedServiceKey, suppliedKey.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(PATH.equals(path) || path.startsWith(PATH + "/"));
    }
}
