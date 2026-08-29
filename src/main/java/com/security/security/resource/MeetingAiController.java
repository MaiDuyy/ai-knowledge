package com.security.security.resource;

import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.service.MeetingAiService;
import com.security.security.service.MeetingConversationCleanupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

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
}
