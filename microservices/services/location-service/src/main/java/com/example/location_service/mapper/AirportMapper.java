package com.example.location_service.mapper;

import com.example.location_service.model.Airport;
import com.example.payload.request.AirportRequest;
import com.example.payload.response.AirportResponse;

public class AirportMapper {

    public static Airport toEntity(AirportRequest request) {
        if (request == null) return null;

        return Airport.builder()
                .iataCode(request.getIataCode())
                .name(request.getName())
                .timeZone(request.getTimeZone() != null ? request.getTimeZone().getId() : null)
                .address(request.getAddress())
                .geoCode(request.getGeoCode())
                .build();
    }

    public static AirportResponse toResponse(Airport airport) {
        if (airport == null) return null;

        return AirportResponse.builder()
                .id(airport.getId())
                .iataCode(airport.getIataCode())
                .name(airport.getName())
                .detailedName(airport.getDetailedName())
                // .timeZone(airport.getTimeZone())
                .address(airport.getAddress())
                .city(CityMapper.toResponse(airport.getCity()))
                .geoCode(airport.getGeoCode())
                .build();
    }

    public static void updateEntity(AirportRequest request, Airport existingAirport) {
        if (request == null || existingAirport == null) return;

        if (request.getIataCode() != null) {
            existingAirport.setIataCode(request.getIataCode());
        }
        if (request.getName() != null) {
            existingAirport.setName(request.getName());
        }
        if (request.getAddress() != null) {
            existingAirport.setAddress(request.getAddress());
        }
        if (request.getGeoCode() != null) {
            existingAirport.setGeoCode(request.getGeoCode());
        }
    }
}