package com.security.security.service.dataloader;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DataLoaderResult {
    String markdownContent;
    Map<String, Object> metadata;
}
