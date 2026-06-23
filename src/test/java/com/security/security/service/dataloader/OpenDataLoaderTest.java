package com.security.security.service.dataloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDataLoaderTest {

    @Test
    @DisplayName("Should successfully support specific loader types and return results")
    void testMockOpenDataLoader() {
        OpenDataLoader mockLoader = new OpenDataLoader() {
            @Override
            public boolean supports(String sourceType) {
                return "MOCK".equalsIgnoreCase(sourceType);
            }

            @Override
            public DataLoaderResult load(String documentId, Map<String, Object> config) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", documentId);
                meta.put("source", "MOCK");
                return DataLoaderResult.builder()
                        .markdownContent("# Mock Document\nThis is mock content.")
                        .metadata(meta)
                        .build();
            }
        };

        assertThat(mockLoader.supports("MOCK")).isTrue();
        assertThat(mockLoader.supports("FEISHU")).isFalse();

        DataLoaderResult result = mockLoader.load("doc-123", Map.of());
        assertThat(result.getMarkdownContent()).isEqualTo("# Mock Document\nThis is mock content.");
        assertThat(result.getMetadata()).containsEntry("documentId", "doc-123");
        assertThat(result.getMetadata()).containsEntry("source", "MOCK");
    }
}
