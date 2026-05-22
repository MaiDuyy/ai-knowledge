package com.security.security.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.security.security.service.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.reactive.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
@RequiredArgsConstructor
public class FilterChainConfiguration {
    private final JwtService jwtService;
    private final JwtAuthorizationFilter jwtAuthorizationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.boot.autoconfigure.security.servlet.PathRequest
                                .toStaticResources().atCommonLocations())
                        .permitAll()
                        .requestMatchers(
                                "/api/mrp/**", "/skills/**",
                                "/api/settings/**",
                                "/chat/ai/**", "/rag/**", "/chat/**",
                                "/api/rag/**", "/api/documents/**",
                                "/agent/**", "/documents/**",
                                "/healthz", "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
//                        .anyRequest().authenticated()

                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"error\":\"Unauthorized access\",\"message\":\"" + ex.getMessage() + "\"}");
                        }))
                .addFilterBefore(jwtAuthorizationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new UnsupportedOperationException(
                    "Local authentication is disabled in favor of JWT from Identity Service");
        };
    }
}

// @Bean
// public UserDetailsService userDetailsService() {
// var user1 =
// User.withDefaultPasswordEncoder().username("user1").password("{noop}password1").roles("USER").build();
// var user2 =
// User.withDefaultPasswordEncoder().username("user2").password("{noop}password2").roles("USER").build();
// return new InMemoryUserDetailsManager(List.of(user1, user2));
// }

// @Bean
// public InMemoryUserDetailsManager inMemoryUserDetailsManager() {
// return new InMemoryUserDetailsManager(
// User.withUsername("user1").password("password1").roles("USER").build(),
// User.withUsername("user2").password("password1").roles("USER").build()
// );
// }
