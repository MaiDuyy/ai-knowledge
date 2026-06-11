package com.security.security.service;

import com.security.security.entity.AppConfig;
import com.security.security.repository.AppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AppConfigService GCM Encryption and Fallback Tests")
class AppConfigServiceTest {

    private AppConfigRepository configRepository;
    private Environment environment;
    private AppConfigService appConfigService;

    @BeforeEach
    void setUp() {
        configRepository = mock(AppConfigRepository.class);
        environment = mock(Environment.class);
        appConfigService = new AppConfigService(configRepository, environment);
        
        // Setup secret key and initialize AES key
        ReflectionTestUtils.setField(appConfigService, "secretKey", "test-secret-key-at-least-32-chars-long!");
        ReflectionTestUtils.invokeMethod(appConfigService, "initKey");
    }

    @Test
    @DisplayName("Should encrypt using AES GCM and produce different ciphertext for identical inputs")
    void shouldEncryptWithGcmAndProduceDifferentCiphertexts() {
        // Encrypt same value twice
        String plainText = "my-secret-api-key-12345";
        String cipherText1 = ReflectionTestUtils.invokeMethod(appConfigService, "encrypt", plainText);
        String cipherText2 = ReflectionTestUtils.invokeMethod(appConfigService, "encrypt", plainText);

        assertThat(cipherText1).isNotNull();
        assertThat(cipherText2).isNotNull();
        // Since GCM uses unique random IVs, ciphertexts MUST be different
        assertThat(cipherText1).isNotEqualTo(cipherText2);

        // Both should decrypt back to the same plaintext
        String decrypted1 = ReflectionTestUtils.invokeMethod(appConfigService, "decrypt", cipherText1);
        String decrypted2 = ReflectionTestUtils.invokeMethod(appConfigService, "decrypt", cipherText2);
        assertThat(decrypted1).isEqualTo(plainText);
        assertThat(decrypted2).isEqualTo(plainText);
    }

    @Test
    @DisplayName("Should successfully set and get sensitive config value in DB")
    void shouldSetAndGetSensitiveConfigValue() {
        String key = AppConfigService.LLM_API_KEY_KEY;
        String secretValue = "gemini-api-key-xyz";

        // Mock repository save & findByConfigKey
        AppConfig savedConfig = new AppConfig();
        ArgumentCaptor<AppConfig> configCaptor = ArgumentCaptor.forClass(AppConfig.class);
        
        appConfigService.set(key, secretValue);
        
        verify(configRepository).save(configCaptor.capture());
        AppConfig captured = configCaptor.getValue();
        
        assertThat(captured.getConfigKey()).isEqualTo(key);
        assertThat(captured.getConfigValue()).isNotEqualTo(secretValue); // encrypted in DB

        // When reading, simulate DB returning the encrypted value
        when(configRepository.findByConfigKey(key)).thenReturn(Optional.of(captured));

        Optional<String> retrieved = appConfigService.get(key);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(secretValue); // Decrypted back
    }

    @Test
    @DisplayName("Should decrypt legacy ECB-encrypted values successfully (Backward Compatibility)")
    void shouldDecryptLegacyEcbValues() throws Exception {
        String key = AppConfigService.LLM_API_KEY_KEY;
        String rawValue = "legacy-key-value";

        // Manually generate a legacy ECB-encrypted ciphertext using the same key
        String secretKeyStr = "test-secret-key-at-least-32-chars-long!";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secretKeyStr.getBytes(StandardCharsets.UTF_8));
        SecretKeySpec aesKey = new SecretKeySpec(digest, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey);
        byte[] encryptedBytes = cipher.doFinal(rawValue.getBytes(StandardCharsets.UTF_8));
        String legacyEcbCipherText = Base64.getEncoder().encodeToString(encryptedBytes);

        // Verify the decrypt method can handle it
        String decrypted = ReflectionTestUtils.invokeMethod(appConfigService, "decrypt", legacyEcbCipherText);
        assertThat(decrypted).isEqualTo(rawValue);
    }
}
