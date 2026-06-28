package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateWikiIssueRequest {
    private String status;       // maps to WikiIssueStatus (FIXED, IGNORED, IN_PROGRESS, OPEN)
    private String resolvedNote;
}
