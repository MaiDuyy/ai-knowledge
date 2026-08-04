package com.security.security.repository.mongo;

import com.security.security.model.mongo.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String> {
    
    // Core function: Find all chunks in a workspace that the user has permission to read based on their roles
    List<DocumentChunk> findByWorkspaceIdAndAllowedRolesIn(String workspaceId, List<String> roles);
    
    void deleteByDocumentId(String documentId);
}
