package com.security.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    @org.springframework.beans.factory.annotation.Value("${CORS_ORIGIN:http://localhost:3002}")
    private String corsOrigin;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Nếu dùng cookie/Authorization thì phải bật allowCredentials
        config.setAllowCredentials(true);

        List<String> origins = new java.util.ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://localhost:3002",
                "https://nexus-ott-chat.vercel.app"
        ));
        if (corsOrigin != null && !corsOrigin.trim().isEmpty()) {
            for (String origin : corsOrigin.split(",")) {
                origins.add(origin.trim());
            }
        }
        config.setAllowedOriginPatterns(origins);

        config.setAllowedHeaders(List.of("*"));

        // Những header sẽ được expose về client (nếu cần)
        config.setExposedHeaders(List.of(
                "Authorization",
                "Content-Disposition" // ví dụ khi tải file
        ));

        // Các phương thức cho phép
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
