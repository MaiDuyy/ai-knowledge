package com.security.security.provider;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class LlmFactory {
    
    private final Map<String, LlmProvider> providers;

    public LlmFactory(List<LlmProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(p -> p.getProviderName().toLowerCase(), p -> p));
    }

    public LlmProvider getProvider(String name) {
        if (name == null || name.trim().isEmpty()) {
            return providers.get("gemini");
        }
        return providers.getOrDefault(name.toLowerCase(), providers.get("gemini"));
    }
}
