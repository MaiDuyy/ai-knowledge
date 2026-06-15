package com.security.security.repository;

import com.security.security.entity.SourceImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceImageRepository extends JpaRepository<SourceImage, UUID> {
    List<SourceImage> findByIdIn(List<UUID> ids);
    List<SourceImage> findBySourceId(Long sourceId);
    Optional<SourceImage> findBySourceIdAndImageIndex(Long sourceId, Integer imageIndex);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteBySourceId(Long sourceId);
}
