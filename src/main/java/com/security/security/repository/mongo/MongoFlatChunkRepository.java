package com.security.security.repository.mongo;

import com.security.security.entity.mongo.MongoFlatChunk;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MongoFlatChunkRepository extends MongoRepository<MongoFlatChunk, String> {

    List<MongoFlatChunk> findByDocumentIdOrderByChunkIndexAsc(String documentId);

    List<MongoFlatChunk> findByPostgresDocumentId(Long postgresDocumentId);

    List<MongoFlatChunk> findByWorkspaceId(String workspaceId);

    void deleteByDocumentId(String documentId);

    void deleteByPostgresDocumentId(Long postgresDocumentId);
}
