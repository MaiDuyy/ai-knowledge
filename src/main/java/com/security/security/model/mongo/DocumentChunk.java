package com.security.security.model.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "document_chunks")
@CompoundIndex(name = "workspace_role_idx", def = "{'workspace_id': 1, 'allowed_roles': 1}")
@CompoundIndex(name = "document_chunk_idx", def = "{'document_id': 1, 'chunk_index': 1}")
public class DocumentChunk {

    @Id
    private String id;

    @Field("document_id")
    private String documentId;

    @Field("workspace_id")
    private String workspaceId;

    @Field("chunk_index")
    private Integer chunkIndex;

    @Field("chunk_type")
    private String chunkType;

    @Field("content_summary")
    private String contentSummary;

    @Field("allowed_roles")
    private List<String> allowedRoles;

    private Map<String, Object> metadata;
    
    @Field("created_at")
    private Instant createdAt;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public String getContentSummary() { return contentSummary; }
    public void setContentSummary(String contentSummary) { this.contentSummary = contentSummary; }

    public List<String> getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(List<String> allowedRoles) { this.allowedRoles = allowedRoles; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
