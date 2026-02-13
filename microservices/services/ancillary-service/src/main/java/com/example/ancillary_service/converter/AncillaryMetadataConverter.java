package com.example.ancillary_service.converter;

import com.example.domain.AncillaryMetadata;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Converter
@Slf4j
public class AncillaryMetadataConverter implements AttributeConverter<AncillaryMetadata, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(AncillaryMetadata metadata) {
        if (metadata == null)
            return null;

        return objectMapper.writeValueAsString(metadata);
    }

    @Override
    public AncillaryMetadata convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty())
            return null;

        return objectMapper.readValue(dbData, AncillaryMetadata.class);
    }
}
