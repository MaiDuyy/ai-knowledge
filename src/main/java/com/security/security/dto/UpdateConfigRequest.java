package com.security.security.dto;

import java.util.Map;

/**
 * Request DTO for batch-updating AI/system configuration.
 */
public record UpdateConfigRequest(
        Map<String, String> settings
) {}
