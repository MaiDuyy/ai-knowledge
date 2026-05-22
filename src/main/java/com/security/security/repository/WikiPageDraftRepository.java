package com.security.security.repository;

import com.security.security.entity.WikiPageDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WikiPageDraftRepository extends JpaRepository<WikiPageDraft, Long> {
    List<WikiPageDraft> findByStatus(String status);
    Page<WikiPageDraft> findByStatus(String status, Pageable pageable);
    List<WikiPageDraft> findByWorkspaceId(String workspaceId);
    List<WikiPageDraft> findByWikiPageId(Long wikiPageId);
    List<WikiPageDraft> findBySlugAndWorkspaceId(String slug, String workspaceId);
}
