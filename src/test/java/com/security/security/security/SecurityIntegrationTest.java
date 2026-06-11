package com.security.security.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Security Integration Tests")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @Test
    @DisplayName("Should return 401 Unauthorized for unauthenticated requests to protected endpoints")
    void whenUnauthenticated_shouldReturnUnauthorizedForProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should allow access to public endpoints (healthz) without authentication")
    void whenUnauthenticated_shouldAllowAccessToPublicEndpoints() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk());
    }
}
