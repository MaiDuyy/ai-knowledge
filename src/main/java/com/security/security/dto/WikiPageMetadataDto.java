package com.security.security.dto;

import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.entity.enumeration.SecurityClassification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiPageMetadataDto {
    private Long id;
    private String title;
    private String slug;
    private String workspaceId;
    private String tags;
    private WikiPageType pageType;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> links;
    private String departmentId;
    private String allowedRoles;
    private SecurityClassification securityClassification;

    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]");

    public static List<String> extractLinks(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> linksList = new ArrayList<>();
        Matcher matcher = WIKILINK_PATTERN.matcher(content);
        while (matcher.find()) {
            linksList.add(matcher.group(1).trim());
        }
        return linksList;
    }
}
