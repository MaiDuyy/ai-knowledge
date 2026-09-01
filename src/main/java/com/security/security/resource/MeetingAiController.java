package com.security.security.resource;

import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dto.MeetingAiStreamEvent;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.service.MeetingAiService;
import com.security.security.service.MeetingConversationCleanupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/internal/meeting-ai")
@RequiredArgsConstructor
public class MeetingAiController {

    private final MeetingAiService meetingAiService;
    private final MeetingConversationCleanupService cleanupService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_MEETING_AI')")
    public ResponseEntity<MeetingAiBufferedResponse> answer(@Valid @RequestBody MeetingAiRequest request) {
        return ResponseEntity.ok(meetingAiService.answer(request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_MEETING_AI')")
    public ResponseEntity<Flux<ServerSentEvent<MeetingAiStreamEvent>>> answerStream(@Valid @RequestBody MeetingAiRequest request) {
        Flux<ServerSentEvent<MeetingAiStreamEvent>> stream = meetingAiService.answerStream(request)
                .map(event -> ServerSentEvent.builder(event)
                        .event(eventName(event))
                        .id(eventId(event))
                        .build());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    @PostMapping("/meetings/{meetingSessionId}/ending")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_MEETING_AI')")
    public ResponseEntity<Map<String, Object>> beginCleanup(@PathVariable String meetingSessionId) {
        boolean found = cleanupService.endMeetingConversation(meetingSessionId);
        return ResponseEntity.ok(Map.of("status", "ending", "found", found));
    }

    @PostMapping("/meetings/{meetingSessionId}/cleanup")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_MEETING_AI')")
    public ResponseEntity<Map<String, Object>> completeCleanup(@PathVariable String meetingSessionId) {
        boolean found = cleanupService.completeMeetingConversationCleanup(meetingSessionId);
        return ResponseEntity.ok(Map.of("status", "ended", "found", found));
    }

    private String eventName(MeetingAiStreamEvent event) {
        if (event instanceof MeetingAiStreamEvent.SpeechDelta) return "speech.delta";
        if (event instanceof MeetingAiStreamEvent.DisplayDelta) return "display.delta";
        if (event instanceof MeetingAiStreamEvent.Source) return "source";
        return "done";
    }

    private String eventId(MeetingAiStreamEvent event) {
        if (event instanceof MeetingAiStreamEvent.SpeechDelta speech) {
            return speech.turnId() + ":speech.delta:" + speech.sequence();
        }
        if (event instanceof MeetingAiStreamEvent.DisplayDelta display) {
            return display.turnId() + ":display.delta:" + display.sequence();
        }
        if (event instanceof MeetingAiStreamEvent.Source source) {
            return source.turnId() + ":source:" + source.sequence();
        }
        return event.turnId() + ":done:terminal";
    }
}
