package com.security.security.security;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dto.MeetingAiStreamEvent;
import com.security.security.service.MeetingAiService;
import io.nats.client.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    @MockBean
    private MeetingAiService meetingAiService;

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
    void streamingEndpointUsesTheSameDedicatedInternalCredential() throws Exception {
        String path = InternalMeetingAiAuthenticationFilter.PATH + "/stream";
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path)
                        .header(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER,
                                "meeting-ai-test-service-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamingEndpointEmitsTypedSseWithoutBufferingTheFirstEvent() throws Exception {
        when(meetingAiService.answerStream(any())).thenReturn(Flux.just(
                MeetingAiStreamEvent.SpeechDelta.of("turn-1", 0, "Xin chào"),
                MeetingAiStreamEvent.Done.of("turn-1", false, 1, 2)));

        MvcResult result = mockMvc.perform(post(InternalMeetingAiAuthenticationFilter.PATH + "/stream")
                        .header(InternalMeetingAiAuthenticationFilter.SERVICE_KEY_HEADER,
                                "meeting-ai-test-service-key")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"meetingSessionId":"meeting-1","chatId":"chat-1","workspaceId":"workspace-1",
                                "turnId":"turn-1","speakerUserId":"user-1","speakerName":"User One",
                                "participantIds":["user-1","user-2"],"message":"Question"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:speech.delta")))
                .andExpect(content().string(containsString("event:done")));
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
