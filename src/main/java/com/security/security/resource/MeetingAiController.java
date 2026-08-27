package com.security.security.resource;

import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.service.MeetingAiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/meeting-ai")
@RequiredArgsConstructor
public class MeetingAiController {

    private final MeetingAiService meetingAiService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_MEETING_AI')")
    public ResponseEntity<MeetingAiBufferedResponse> answer(@Valid @RequestBody MeetingAiRequest request) {
        return ResponseEntity.ok(meetingAiService.answer(request));
    }
}
