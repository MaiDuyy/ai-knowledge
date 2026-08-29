package com.security.security.security;

import com.security.security.client.WorkspaceServiceClient;
import io.nats.client.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "meeting.ai.internal-service-key=meeting-ai-test-service-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeetingAiControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceServiceClient workspaceServiceClient;

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private Connection natsConnection;

    @Test
    void dedicatedCredentialReachesTheInternalControllerButInvalidPayloadIsRejected() throws Exception {
        mockMvc.perform(post(InternalMeetingAiAuthenticationFilter.PATH)
                        .header(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER,
                                "meeting-ai-test-service-key")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "spoofed-user")
                        .header("x-user-role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meetingCleanupRequiresDedicatedCredentialAndIsIdempotentWhenConversationIsMissing() throws Exception {
        String path = InternalMeetingAiAuthenticationFilter.PATH + "/meetings/missing-meeting/cleanup";
        mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path)
                        .header(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER,
                                "meeting-ai-test-service-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void meetingEndingRequiresDedicatedCredentialAndIsIdempotentWhenConversationIsMissing() throws Exception {
        String path = InternalMeetingAiAuthenticationFilter.PATH + "/meetings/missing-meeting/ending";
        mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path)
                        .header(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER,
                                "meeting-ai-test-service-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
