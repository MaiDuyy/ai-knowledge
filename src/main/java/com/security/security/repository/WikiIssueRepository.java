package com.security.security.repository;

import com.security.security.entity.WikiIssue;
import com.security.security.entity.enumeration.WikiIssueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WikiIssueRepository extends JpaRepository<WikiIssue, Long> {

    List<WikiIssue> findByWikiPageSlugAndWorkspaceId(String slug, String workspaceId);

    List<WikiIssue> findByWorkspaceIdAndStatus(String workspaceId, WikiIssueStatus status);

    long countByWikiPageSlugAndStatusNot(String slug, WikiIssueStatus status);

    List<WikiIssue> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    void deleteByWikiPageSlug(String slug);
}
