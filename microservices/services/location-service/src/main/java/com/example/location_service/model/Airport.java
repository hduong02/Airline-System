package com.example.location_service.model;

import com.example.embeddable.Address;
import com.example.embeddable.GeoCode;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Airport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3, unique = true)
    private String iataCode;

    @Column(nullable = false, length = 255)
    private String name;

    @Embedded
    private Address address;

    @Embedded
    private GeoCode geoCode;

    @Column(name = "time_zone", length = 50)
    private String timeZone;

    @ManyToOne
    @JsonIgnore
    private City city;

    @Transient
    @JsonIgnore
    public String getDetailedName() {
        if (city != null && city.getCountryCode() != null) {
            return name.toUpperCase() + "/" + city.getCountryCode();
        }
        return name.toUpperCase();
    }
}
