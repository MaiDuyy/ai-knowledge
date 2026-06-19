package com.security.security.entity.enumeration;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonCreator;

public enum WikiPageType {
    ENTITY("entity"),
    CONCEPT("concept"),
    TOPIC("topic"),
    SOURCE("source");

    private final String value;

    WikiPageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WikiPageType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (WikiPageType type : WikiPageType.values()) {
            if (type.value.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
