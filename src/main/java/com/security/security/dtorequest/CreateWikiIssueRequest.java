package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWikiIssueRequest {
    private String wikiPageSlug; // required
    private String issueType;    // required — maps to WikiIssueType
    private String workspaceId;
    private String description;
    private String evidence;
    private String suggestedFix;
}
