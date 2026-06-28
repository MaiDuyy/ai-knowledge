package com.security.security.entity.enumeration;

public enum WikiIssueType {
    MIXED_ENTITIES,      // Page mixes multiple unrelated entities
    CONTRADICTORY_FACTS, // Page contains contradictory statements
    OUT_OF_DATE,         // Content appears outdated
    MISSING_LINKS,       // Mentions entities with wiki pages but no [[link]]
    POOR_QUALITY,        // Low quality, incomplete, or incoherent content
    HALLUCINATION        // Contains fabricated/unsupported claims
}
