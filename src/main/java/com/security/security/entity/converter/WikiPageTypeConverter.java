package com.security.security.entity.converter;

import com.security.security.entity.enumeration.WikiPageType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class WikiPageTypeConverter implements AttributeConverter<WikiPageType, String> {

    @Override
    public String convertToDatabaseColumn(WikiPageType attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getValue();
    }

    @Override
    public WikiPageType convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return WikiPageType.fromValue(dbData);
    }
}
