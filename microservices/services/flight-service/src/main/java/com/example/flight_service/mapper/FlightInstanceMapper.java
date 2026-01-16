package com.example.flight_service.mapper;

import com.example.enums.FlightStatus;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.payload.request.FlightInstanceRequest;
import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.FlightInstanceResponse;

public class FlightInstanceMapper {

    public static FlightInstance toEntity(FlightInstanceRequest request, Flight flight) {
        if (request == null) return null;
        return FlightInstance.builder()
                .flight(flight)
                .airlineId(request.getAirlineId() != null ?
                        request.getAirlineId() : flight.getAirlineId())
                .scheduleId(request.getScheduleId())
                .departureAirportId(request.getDepartureAirportId() != null ?
                        request.getDepartureAirportId() : flight.getDepartureAirportId())
                .arrivalAirportId(request.getArrivalAirportId() != null ?
                        request.getArrivalAirportId() : flight.getArrivalAirportId())
                .departureDateTime(request.getDepartureDateTime())
                .arrivalDateTime(request.getArrivalDateTime())
                .totalSeats(request.getTotalSeats())
                .availableSeats(request.getAvailableSeats() != null ?
                        request.getAvailableSeats() : request.getTotalSeats())
                .status(request.getStatus() != null ? request.getStatus() : FlightStatus.SCHEDULED)
                .minAdvanceBookingDays(request.getMinAdvanceBookingDays())
                .maxAdvanceBookingDays(request.getMaxAdvanceBookingDays())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
    }

    public static FlightInstanceResponse toResponse(FlightInstance fi,
                                                    AircraftResponse aircraftResponse,
                                                    AirlineResponse airline,
                                                    AirportResponse departureAirport,
                                                    AirportResponse arrivalAirport) {
        if (fi == null) return null;
        return FlightInstanceResponse.builder()
                .id(fi.getId())
                .flightId(fi.getFlight() != null ? fi.getFlight().getId() : null)
                .flightNumber(fi.getFlight() != null ? fi.getFlight().getFlightNumber() : null)
                .aircraftId(fi.getFlight().getAircraftId())
                .aircraftModal(aircraftResponse.getModel())
                .aircraftCode(aircraftResponse.getCode())
                .airlineId(fi.getAirlineId())
                .airlineName(airline.getName())
                .airlineLogo(airline.getLogoUrl())
                .departureAirport(departureAirport)
                .arrivalAirport(arrivalAirport)
                .departureDateTime(fi.getDepartureDateTime())
                .arrivalDateTime(fi.getArrivalDateTime())
                .formattedDuration(fi.getFormattedDuration())
                .totalSeats(fi.getTotalSeats())
                .availableSeats(fi.getAvailableSeats())
                .status(fi.getStatus())
                .minAdvanceBookingDays(fi.getMinAdvanceBookingDays())
                .maxAdvanceBookingDays(fi.getMaxAdvanceBookingDays())
                .isActive(fi.getIsActive())
                .build();
    }

    public static void updateEntity(FlightInstanceRequest request, FlightInstance existing) {
        if (request == null || existing == null) return;
        if (request.getDepartureAirportId() != null)
            existing.setDepartureAirportId(request.getDepartureAirportId());
        if (request.getArrivalAirportId() != null)
            existing.setArrivalAirportId(request.getArrivalAirportId());
        if (request.getDepartureDateTime() != null)
            existing.setDepartureDateTime(request.getDepartureDateTime());
        if (request.getArrivalDateTime() != null)
            existing.setArrivalDateTime(request.getArrivalDateTime());
        if (request.getTotalSeats() != null)
            existing.setTotalSeats(request.getTotalSeats());
        if (request.getAvailableSeats() != null)
            existing.setAvailableSeats(request.getAvailableSeats());
        if (request.getStatus() != null) existing.setStatus(request.getStatus());
        if (request.getMinAdvanceBookingDays() != null)
            existing.setMinAdvanceBookingDays(request.getMinAdvanceBookingDays());
        if (request.getMaxAdvanceBookingDays() != null)
            existing.setMaxAdvanceBookingDays(request.getMaxAdvanceBookingDays());
        if (request.getIsActive() != null)
            existing.setIsActive(request.getIsActive());
    }
}
