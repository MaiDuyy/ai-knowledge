package com.security.security.repository;

import com.security.security.entity.WikiLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface WikiLinkRepository extends JpaRepository<WikiLink, Long> {
    List<WikiLink> findByFromPageId(Long fromPageId);
    List<WikiLink> findByToSlug(String toSlug);

    @Modifying
    @Transactional
    @Query("delete from WikiLink w where w.fromPageId = :fromPageId")
    void deleteByFromPageId(@Param("fromPageId") Long fromPageId);

    List<WikiLink> findByFromPageIdIn(List<Long> fromPageIds);
}
