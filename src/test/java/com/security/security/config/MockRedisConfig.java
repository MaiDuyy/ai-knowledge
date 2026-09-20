package com.security.security.config;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito-based StringRedisTemplate for pure unit tests that exclude Redis auto-config.
 * Annotated {@link TestConfiguration} so it is NOT component-scanned by the main application
 * (avoids classpath resource load issues). Import explicitly when needed:
 * {@code @Import(MockRedisConfig.class)}.
 */
@TestConfiguration
@Profile("test")
public class MockRedisConfig {

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> mockOps = mock(ValueOperations.class);
        when(mockTemplate.opsForValue()).thenReturn(mockOps);
        return mockTemplate;
    }
}
