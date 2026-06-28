package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WikiIssueDTO {
    private Long id;
    private String wikiPageSlug;
    private String issueType;
    private String status;
    private String workspaceId;
    private String description;
    private String evidence;
    private String suggestedFix;
    private String detectedBy;
    private String resolvedBy;
    private String resolvedNote;
    private String resolvedAt;
    private String createdAt;
    private String updatedAt;
}
