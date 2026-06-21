package com.security.security.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ScopeNormalizerTest {

    @Test
    public void testNormalizeWorkspace() {
        assertThat(ScopeNormalizer.normalizeWorkspace(null)).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("   ")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("default-workspace")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("workspace-default")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("DEFAULT-WORKSPACE")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("all")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeWorkspace("WS_123")).isEqualTo("WS_123");
        assertThat(ScopeNormalizer.normalizeWorkspace("  WS_456  ")).isEqualTo("WS_456");
    }

    @Test
    public void testNormalizeDepartment() {
        assertThat(ScopeNormalizer.normalizeDepartment(null)).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeDepartment("")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeDepartment("   ")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeDepartment("all")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeDepartment("ALL")).isEqualTo("ALL");
        assertThat(ScopeNormalizer.normalizeDepartment("DEPT_A")).isEqualTo("DEPT_A");
        assertThat(ScopeNormalizer.normalizeDepartment("  DEPT_B  ")).isEqualTo("DEPT_B");
    }
}
