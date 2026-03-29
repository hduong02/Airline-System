package com.example.flight_service.service.impl;

import com.example.event.FlightInstanceCreatedEvent;
import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.flight_service.event.FlightInstanceEventProducer;
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
    private final AirlineClient airlineClient;
    private final LocationClient locationClient;
    private final FlightInstanceEventProducer flightInstanceEventProducer;

    @Override
    public FlightInstanceResponse createFlightInstance(Long userId,
            FlightInstanceRequest request) throws Exception {
        Flight flight = flightRepository.findById(request.getFlightId())
                .orElseThrow(() -> new Exception("Flight not found"));

        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);
        AircraftResponse aircraft = airlineClient.getAircraftById(flight.getAircraftId());

        FlightInstance instance = FlightInstanceMapper.toEntity(request, flight);
        instance.setAirlineId(airlineResponse.getId());
        instance.setFlight(flight);
        instance.setDepartureAirportId(request.getDepartureAirportId());
        instance.setArrivalAirportId(request.getArrivalAirportId());
        instance.setTotalSeats(aircraft.getTotalSeats());
        instance.setAvailableSeats(aircraft.getTotalSeats());
        FlightInstance saved = flightInstanceRepository.save(instance);
        
        // publish kafka event, seat service consume that and create seat instance
        FlightInstanceCreatedEvent event = FlightInstanceCreatedEvent.builder()
                .flightInstanceId(instance.getId())
                .aircraftId(flight.getAircraftId())
                .flightId(flight.getId())
                .build();
        flightInstanceEventProducer.sendFlightInstanceCreated(event);

        return convertToFlightInstanceResponse(saved);
    }


    @Override
    public FlightInstanceResponse getFlightInstanceById(Long id) throws Exception {
        FlightInstance fi = flightInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance not found with id: " + id));

        return convertToFlightInstanceResponse(fi);
    }

    @Override
    public Page<FlightInstanceResponse> getByAirlineId(
            Long userId,
            Long departureAirportId,
            Long arrivalAirportId,
            Long flightId,
            LocalDate onDate,
            Pageable pageable)
        {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);

        LocalDateTime start = onDate != null ? onDate.atStartOfDay() : null;
        LocalDateTime end   = onDate != null ? onDate.plusDays(1).atStartOfDay() : null;

        return flightInstanceRepository.findByAirlineId(
                airlineResponse.getId(),
                departureAirportId,
                arrivalAirportId,
                flightId,
                start,
                end,
                pageable
        ).map(fi -> {
            try {
                return convertToFlightInstanceResponse(fi);
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
        return convertToFlightInstanceResponse(flightInstanceRepository.save(existing));
    }

    @Override
    public void deleteFlightInstance(Long id) {
        FlightInstance fi = flightInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance not found with id: " + id));
        flightInstanceRepository.delete(fi);
    }


    private FlightInstanceResponse convertToFlightInstanceResponse(
            FlightInstance fi) throws Exception {
        
        //service to service communication
        AirlineResponse airline = airlineClient.getAirlineById(fi.getAirlineId());
        AirportResponse departureAirport = locationClient.getAirportById(
                fi.getDepartureAirportId());
        AirportResponse arrivalAirport = locationClient.getAirportById(
                fi.getArrivalAirportId());
        AircraftResponse aircraftResponse = airlineClient.getAircraftById(
                fi.getFlight().getAircraftId());
                
        return FlightInstanceMapper.toResponse(
                fi,
                aircraftResponse,
                airline,
                departureAirport,
                arrivalAirport
        );
    }
}