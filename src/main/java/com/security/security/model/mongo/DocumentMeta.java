package com.security.security.model.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "documents")
public class DocumentMeta {

    @Id
    private String id;

    @Field("workspace_id")
    private String workspaceId;

    private String title;
    
    @Field("file_path")
    private String filePath;
    
    @Field("owner_id")
    private String ownerId;
    
    private String status;
    
    @Field("file_metadata")
    private Map<String, Object> fileMetadata;
    
    @Field("allowed_roles")
    private List<String> allowedRoles;
    
    @Field("created_at")
    private Instant createdAt;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getFileMetadata() { return fileMetadata; }
    public void setFileMetadata(Map<String, Object> fileMetadata) { this.fileMetadata = fileMetadata; }

    public List<String> getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(List<String> allowedRoles) { this.allowedRoles = allowedRoles; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
