package com.example.ancillary_service.model;

import com.example.ancillary_service.converter.AncillaryMetadataConverter;
import com.example.domain.AncillaryMetadata;
import com.example.enums.AncillaryType;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ancillary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AncillaryType type;

    private String subType;

    private String rfisc;

    @Column(nullable = false)
    private String name;
    
    private String description;

    @Convert(converter = AncillaryMetadataConverter.class)
    private AncillaryMetadata metadata;

    private Integer displayOrder;

    @Column(name = "airline_id", nullable = false)
    private Long airlineId;
}