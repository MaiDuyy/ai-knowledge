package com.security.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTests {

	@MockBean
	private org.springframework.ai.vectorstore.VectorStore vectorStore;

	@MockBean
	private io.nats.client.Connection natsConnection;

	@Test
	void contextLoads() {
	}

}
