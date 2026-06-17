package com.security.security.config;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyString;

@Configuration
@Profile("test")
public class MockRedisConfig {

    @Bean
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> mockOps = mock(ValueOperations.class);
        when(mockTemplate.opsForValue()).thenReturn(mockOps);
        return mockTemplate;
    }
}
