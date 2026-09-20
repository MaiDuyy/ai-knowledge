package com.security.security.dtorequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Trusted service-to-service request after voice-service has verified the turn token.
 * This endpoint never accepts browser traffic directly.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MeetingAiRequest {

    @NotBlank
    @Size(max = 255)
    private String meetingSessionId;

    @NotBlank
    @Size(max = 255)
    private String chatId;

    @NotBlank
    @Size(max = 255)
    private String workspaceId;

    @NotBlank
    @Size(max = 255)
    private String turnId;

    @NotBlank
    @Size(max = 255)
    private String speakerUserId;

    @Size(max = 255)
    private String speakerName;

    @NotEmpty
    @Size(max = 100)
    private List<@NotBlank @Size(max = 255) String> participantIds;

    @NotBlank
    @Size(max = 4000)
    private String message;
}
