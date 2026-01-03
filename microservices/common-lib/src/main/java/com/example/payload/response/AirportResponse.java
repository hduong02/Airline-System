package com.example.payload.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneId;

import com.example.embeddable.Address;
import com.example.embeddable.GeoCode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirportResponse {

    private Long id;
    private String iataCode;
    private String name;
    private String detailedName;
    private ZoneId timeZone;
    private Address address;
    private CityResponse city;
    private GeoCode geoCode;
}