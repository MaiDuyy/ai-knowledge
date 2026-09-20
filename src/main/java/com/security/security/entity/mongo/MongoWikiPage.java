package com.security.security.entity.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Wiki page with inline outbound graph edges ({@code outboundSlugs})
 * instead of a separate {@code wiki_links} join table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "wiki_pages")
public class MongoWikiPage {

    @Id
    private String id;

    @Indexed
    private Long postgresWikiPageId;

    private String title;

    @Indexed
    private String slug;

    private String content;

    @Indexed
    private String workspaceId;

    private String departmentId;
    private String allowedRoles;
    private String securityClassification;
    private String tags;
    private String pageType;
    private String summary;
    private Long sourceDocumentId;
    private Integer version;

    /** Directed edges for $graphLookup (connectFromField). */
    @Builder.Default
    private List<String> outboundSlugs = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}
