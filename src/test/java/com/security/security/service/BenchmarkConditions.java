package com.security.security.service;

import java.io.InputStream;
import java.util.Properties;

/**
 * JUnit 5 enabled conditions for benchmark suite execution.
 */
public final class BenchmarkConditions {

    private BenchmarkConditions() {}

    static boolean apiKeyAvailable() {
        String key = resolveGeminiKey();
        if (key == null || key.isBlank()) {
            key = loadTestProperty("API_KEY");
        }
        return key != null && !key.isBlank() && !key.startsWith("dummy");
    }

    static boolean openAiKeyAvailable() {
        String key = resolveOpenAiKey();
        return key != null && !key.isBlank() && !key.startsWith("dummy") && !key.startsWith("sk-dummy");
    }

    static boolean evaluationBenchmarkEnabled() {
        return apiKeyAvailable();
    }

    /**
     * Golden evaluation on Mongo: needs Gemini API_KEY + Docker (Testcontainers Mongo).
     */
    static boolean mongoEvaluationBenchmarkEnabled() {
        return apiKeyAvailable() && dockerAvailable();
    }

    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    static String resolveGeminiKey() {
        String key = System.getenv("API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = loadTestProperty("API_KEY");
        }
        return key;
    }

    static String resolveOpenAiKey() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("OPENAI_API_KEY");
        }
        return key;
    }

    private static String loadTestProperty(String name) {
        try (InputStream is = BenchmarkConditions.class.getClassLoader()
                .getResourceAsStream("application-test.properties")) {
            if (is == null) return null;
            Properties props = new Properties();
            props.load(is);
            return props.getProperty(name);
        } catch (Exception ignored) {
            return null;
        }
    }
}