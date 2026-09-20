package com.security.security.repository.mongo;

import com.security.security.entity.mongo.MongoWikiPage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MongoWikiPageRepository extends MongoRepository<MongoWikiPage, String> {

    Optional<MongoWikiPage> findBySlug(String slug);

    List<MongoWikiPage> findByWorkspaceId(String workspaceId);

    Optional<MongoWikiPage> findByPostgresWikiPageId(Long postgresWikiPageId);

    List<MongoWikiPage> findBySlugIn(List<String> slugs);
}
