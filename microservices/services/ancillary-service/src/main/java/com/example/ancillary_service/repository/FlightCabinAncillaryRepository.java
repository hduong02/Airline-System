package com.example.ancillary_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ancillary_service.model.FlightCabinAncillary;
import com.example.enums.AncillaryType;

import java.util.List;
import java.util.Optional;

public interface FlightCabinAncillaryRepository extends
        JpaRepository<FlightCabinAncillary, Long> {

    List<FlightCabinAncillary> findByFlightIdAndCabinClassId(Long flightId,
            Long cabinClassId);

    Optional<FlightCabinAncillary> findByFlightIdAndCabinClassIdAndAncillaryType(
            Long flightId, Long cabinClassId, AncillaryType type);

    List<FlightCabinAncillary> findAllByFlightIdAndCabinClassIdAndAncillaryType(
            Long flightId, Long cabinClassId, AncillaryType type);

}