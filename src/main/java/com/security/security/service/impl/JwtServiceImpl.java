package com.security.security.service.impl;

import com.security.security.domain.Token;
import com.security.security.domain.TokenData;
import com.security.security.dto.UserDTO;
import com.security.security.entity.enumeration.TokenType;
import com.security.security.function.TriConsumer;
import com.security.security.security.JwtConfiguration;
import com.security.security.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.security.security.constants.Constants.*;
import static com.security.security.entity.enumeration.TokenType.ACCESS;
import static com.security.security.entity.enumeration.TokenType.REFRESH;
import static io.jsonwebtoken.Header.JWT_TYPE;
import static io.jsonwebtoken.Header.TYPE;
import static java.util.Arrays.stream;
import static java.util.Optional.empty;
import static org.apache.tomcat.util.http.SameSiteCookies.NONE;
import static org.springframework.security.core.authority.AuthorityUtils.commaSeparatedStringToAuthorityList;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtServiceImpl extends JwtConfiguration implements JwtService {


    private final Supplier<SecretKey> key = () -> Keys.hmacShaKeyFor(Decoders.BASE64.decode(getSecret()));

    private Function<String, Claims> claimsFunction = token ->

    Jwts.parser()
            .verifyWith(key.get())
            .build()
            .parseSignedClaims(token)
            .getPayload();

    private final Function<String, String> subject = token -> getClaimsValue(token, Claims::getSubject);

    private final BiFunction<HttpServletRequest, String, Optional<String>> extractToken = (request,
            cookieName) -> stream(request.getCookies() == null ? new Cookie[] { new Cookie(EMPTY_VALUE, EMPTY_VALUE) }
                    : request.getCookies())
                    .filter(cookie -> Objects.equals(cookieName, cookie.getName()))
                    .map(Cookie::getValue)
                    .findAny();

    private final BiFunction<HttpServletRequest, String, Optional<Cookie>> extractCookie = (request,
            cookieName) -> Optional
                    .of(stream(request.getCookies() == null ? new Cookie[] { new Cookie(EMPTY_VALUE, EMPTY_VALUE) }
                            : request.getCookies())
                            .filter(cookie -> Objects.equals(cookieName, cookie.getName()))
                            .findAny())
                    .orElse(empty());

    private Supplier<JwtBuilder> builder = () -> Jwts.builder()
            .header().add(Map.of(TYPE, JWT_TYPE))
            .and()
            .audience().add(GET_ARRAYS_LLC)
            .and()
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(Instant.now()))
            .notBefore(new Date())
            .signWith(key.get(), Jwts.SIG.HS512);

    private final BiFunction<UserDTO, TokenType, String> buildToken = (user, type) -> Objects.equals(type,
            ACCESS) ? builder.get()
                    .subject(user.getUserId())
                    .claim(AUTHORITIES, user.getAuthorities())
                    .claim(ROLE, user.getRole())
                    .expiration(Date.from(Instant.now().plusSeconds(getExpiration())))
                    .compact()
                    : builder.get()
                            .subject(user.getUserId())
                            .expiration(Date.from(Instant.now().plusSeconds(getExpiration())))
                            .compact();

    private final TriConsumer<HttpServletResponse, UserDTO, TokenType> addCookie = (response, user, type) -> {
        switch (type) {
            case ACCESS -> {
                var accessToken = createToken(user, Token::getAccess);
                var cookie = new Cookie(type.getValue(), accessToken);
                cookie.setHttpOnly(true);
                cookie.setSecure(true);
                // access nên dẻ 20 phút là để bảo mật
                // cookie.setMaxAge(20 * 60); // 20 phút
                cookie.setMaxAge(3 * 24 * 60 * 60); // chuyển thành 3 ngày để code
                cookie.setPath("/");
                cookie.setAttribute("SameSite", NONE.name());
                response.addCookie(cookie);
            }
            case REFRESH -> {
                var refreshToken = createToken(user, Token::getRefresh);
                var cookie = new Cookie(type.getValue(), refreshToken);
                cookie.setHttpOnly(true);
                cookie.setSecure(true);
                // cookie.setMaxAge(14 * 24 * 60 * 60); // Cũ: 14 ngày
                cookie.setMaxAge(3 * 24 * 60 * 60); // Mới: 3 ngày
                cookie.setPath("/");
                cookie.setAttribute("SameSite", NONE.name());
                response.addCookie(cookie);
            }
        }
    };

    private <T> T getClaimsValue(String token, Function<Claims, T> claims) {
        return claimsFunction.andThen(claims).apply(token);
    }

    public Function<String, List<GrantedAuthority>> authorities = token -> commaSeparatedStringToAuthorityList(
            new StringJoiner(AUTHORITY_DELIMITER)
                    .add(claimsFunction.apply(token).get(AUTHORITIES, String.class))
                    .add(ROLE_PREFIX + claimsFunction.apply(token).get(ROLE, String.class)).toString());

    @Override
    public String createToken(UserDTO user, Function<Token, String> tokenFunction) {
        var token = Token.builder().access(buildToken.apply(user, ACCESS))
                .refresh(buildToken.apply(user, REFRESH)).build();
        return tokenFunction.apply(token);
    }

    @Override
    public Optional<String> extractToken(HttpServletRequest request, String cookieName) {
        // Sửa lỗi: sử dụng BiFunction extractToken thay vì gọi chính method này
        return extractToken.apply(request, cookieName);
    }

    @Override
    public void addCookie(HttpServletResponse response, UserDTO user, TokenType type) {
        addCookie.accept(response, user, type);

    }

    // @Override
    // public <T> T getTokenData(String token, Function<TokenData, T> tokenFunction)
    // {
    // return tokenFunction.apply(
    // TokenData.builder()
    // .valid(Objects.equals(userService.getUserByUserId(subject.apply(token)).getUserId(),
    // claimsFunction.apply(token)))
    // .authorities(authorities.apply(token))
    // .claims(claimsFunction.apply(token))
    // .user(userService.getUserByUserId(subject.apply(token)))
    // .build());
    //
    // }

    @Override
    public <T> T getTokenData(String token, Function<TokenData, T> tokenFunction) {
        try {
            Claims claims = claimsFunction.apply(token);
            String tokenSubject = claims.getSubject();
            
            // Map claims to UserDTO instead of fetching from DB
            UserDTO user = new UserDTO();
            user.setUserId(tokenSubject);
            user.setEmail(tokenSubject); // Often email is used as subject
            user.setRole(claims.get(ROLE, String.class));
            user.setAuthorities(claims.get(AUTHORITIES, String.class));
            user.setEnabled(true);
            user.setAccountNonLocked(true);
            user.setAccountNonExpired(true);
            user.setCredentialsNonExpired(true);

            // Validate token: check if token is not expired
            boolean isValid = claims.getExpiration().after(new Date());

            return tokenFunction.apply(
                    TokenData.builder()
                            .valid(isValid)
                            .authorities(authorities.apply(token))
                            .claims(claims)
                            .user(user)
                            .build());
        } catch (Exception e) {
            log.error("Error parsing JWT token: {}", e.getMessage());
            return tokenFunction.apply(
                    TokenData.builder()
                            .valid(false)
                            .authorities(List.of())
                            .claims(null)
                            .user(null)
                            .build());
        }
    }

    @Override
    public void removeCookie(HttpServletRequest request, HttpServletResponse response, String cookieName) {
        var optionalCookie = extractCookie.apply(request, cookieName);
        if (optionalCookie.isPresent()) {
            var cookie = optionalCookie.get();
            cookie.setMaxAge(0);
            response.addCookie(cookie);
        }

    }
}