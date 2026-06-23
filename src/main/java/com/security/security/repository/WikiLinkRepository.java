package com.security.security.repository;

import com.security.security.entity.WikiLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WikiLinkRepository extends JpaRepository<WikiLink, Long> {
    List<WikiLink> findByFromPageId(Long fromPageId);
    List<WikiLink> findByToSlug(String toSlug);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteByFromPageId(Long fromPageId);
    List<WikiLink> findByFromPageIdIn(List<Long> fromPageIds);
}
