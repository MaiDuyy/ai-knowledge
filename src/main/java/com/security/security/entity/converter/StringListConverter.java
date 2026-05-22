package com.security.security.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    private static final String SPLIT_CHAR = ",";

    @Override
    public String convertToDatabaseColumn(List<String> stringList) {
        if (stringList == null || stringList.isEmpty()) {
            return "";
        }
        return String.join(SPLIT_CHAR, stringList);
    }

    @Override
    public List<String> convertToEntityAttribute(String string) {
        if (string == null || string.trim().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return Arrays.stream(string.split(SPLIT_CHAR))
                     .map(String::trim)
                     .collect(Collectors.toCollection(java.util.ArrayList::new));
    }
}
