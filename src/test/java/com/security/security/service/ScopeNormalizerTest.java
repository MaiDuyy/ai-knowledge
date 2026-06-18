package com.security.security.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ScopeNormalizerTest {

    @Test
    public void testNormalizeWorkspace() {
        assertThat(ScopeNormalizer.normalizeWorkspace(null)).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("   ")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("default-workspace")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("workspace-default")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("DEFAULT-WORKSPACE")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("all")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeWorkspace("WS_123")).isEqualTo("WS_123");
        assertThat(ScopeNormalizer.normalizeWorkspace("  WS_456  ")).isEqualTo("WS_456");
    }

    @Test
    public void testNormalizeDepartment() {
        assertThat(ScopeNormalizer.normalizeDepartment(null)).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeDepartment("")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeDepartment("   ")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeDepartment("all")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeDepartment("ALL")).isEqualTo("GLOBAL");
        assertThat(ScopeNormalizer.normalizeDepartment("DEPT_A")).isEqualTo("DEPT_A");
        assertThat(ScopeNormalizer.normalizeDepartment("  DEPT_B  ")).isEqualTo("DEPT_B");
    }
}
