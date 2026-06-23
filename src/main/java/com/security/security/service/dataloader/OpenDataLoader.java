package com.security.security.service.dataloader;

import java.util.Map;

public interface OpenDataLoader {
    boolean supports(String sourceType);
    DataLoaderResult load(String documentId, Map<String, Object> config);
}
