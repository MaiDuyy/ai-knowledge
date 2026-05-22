package com.security.security.repository;

import com.security.security.entity.WikiPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {
    List<WikiPage> findBySourceDocumentId(Long sourceDocumentId);
    List<WikiPage> findByWorkspaceId(String workspaceId);
    Page<WikiPage> findByWorkspaceId(String workspaceId, Pageable pageable);
    Optional<WikiPage> findBySlugAndWorkspaceId(String slug, String workspaceId);

    // Lightweight projection excluding large content and summary fields
    interface WikiPageMetadata {
        Long getId();
        String getTitle();
        String getSlug();
        String getWorkspaceId();
        String getTags();
        String getPageType();
        Integer getVersion();
        java.time.LocalDateTime getCreatedAt();
        java.time.LocalDateTime getUpdatedAt();
    }

    List<WikiPageMetadata> findProjectedByWorkspaceId(String workspaceId);
}
