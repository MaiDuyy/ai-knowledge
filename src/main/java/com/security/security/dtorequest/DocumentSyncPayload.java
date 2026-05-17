package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSyncPayload {
    private String documentId;
    private String title;
    private String content;
    private List<ChunkPayload> chunks;
    private MetadataPayload metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkPayload {
        private String chunkId;
        private String content;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataPayload {
        private String collectionId;
        private String classification;
        private String uploadedBy;
        private List<Map<String, String>> acl;
    }
}
