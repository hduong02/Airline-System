package com.example.flight_service.service.impl;

import com.example.flight_service.mapper.FlightInstanceMapper;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.flight_service.repository.FlightRepository;
import com.example.flight_service.service.FlightInstanceService;
import com.example.payload.request.FlightInstanceRequest;
import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.FlightInstanceResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class FlightInstanceServiceImpl implements FlightInstanceService {

    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightRepository flightRepository;

    @Override
    public FlightInstanceResponse createFlightInstance(Long airlineId,
            FlightInstanceRequest request) throws Exception {

        Flight flight = flightRepository.findById(request.getFlightId())
                .orElseThrow(() -> new Exception("Flight not found"));

        AircraftResponse aircraft = AircraftResponse
                .builder()
                .id(1L)
                .totalSeats(90)
                .build();
        FlightInstance instance = FlightInstanceMapper.toEntity(request, flight);
        instance.setAirlineId(airlineId);
        instance.setFlight(flight);
        instance.setDepartureAirportId(request.getDepartureAirportId());
        instance.setArrivalAirportId(request.getArrivalAirportId());
        instance.setTotalSeats(aircraft.getTotalSeats());
        instance.setAvailableSeats(aircraft.getTotalSeats());

        FlightInstance saved = flightInstanceRepository.save(instance);

        return getFlightInstanceResponse(saved);
    }


    @Override
    public FlightInstanceResponse getFlightInstanceById(Long id) throws Exception {
        FlightInstance fi = flightInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance not found with id: " + id));

        return getFlightInstanceResponse(fi);
    }

    @Override
    public Page<FlightInstanceResponse> getByAirlineId(Long airlineId,
                                                        Long departureAirportId,
                                                        Long arrivalAirportId,
                                                        Long flightId,
                                                        LocalDate onDate,
                                                        Pageable pageable) {
        LocalDateTime start = onDate != null ? onDate.atStartOfDay() : null;
        LocalDateTime end   = onDate != null ? onDate.plusDays(1).atStartOfDay() : null;

        return flightInstanceRepository.findByAirlineId(
                airlineId, departureAirportId, arrivalAirportId, flightId, start, end, pageable
        ).map(fi -> {
            try {
                return getFlightInstanceResponse(fi);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public FlightInstanceResponse updateFlightInstance(Long id, FlightInstanceRequest request)
            throws Exception {
        FlightInstance existing = flightInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance not found with id: " + id));
        FlightInstanceMapper.updateEntity(request, existing);
        return getFlightInstanceResponse(flightInstanceRepository.save(existing));
    }

    @Override
    public void deleteFlightInstance(Long id) {
        FlightInstance fi = flightInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance not found with id: " + id));
        flightInstanceRepository.delete(fi);
    }


    private FlightInstanceResponse getFlightInstanceResponse(FlightInstance fi) throws Exception {
        AirlineResponse airline = AirlineResponse.builder()
                .id(fi.getAirlineId())
                .build();
        AirportResponse departureAirport = AirportResponse.builder()
                .id(fi.getDepartureAirportId())
                .build();
        AirportResponse arrivalAirport = AirportResponse.builder()
                .id(fi.getArrivalAirportId())
                .build();
        AircraftResponse aircraftResponse = AircraftResponse.builder()
                .id(fi.getFlight().getAircraftId())
                .build();
        return FlightInstanceMapper.toResponse(
                fi,
                aircraftResponse,
                airline,
                departureAirport,
                arrivalAirport
        );
    }
}