package com.example.flight_service.service.impl;

import com.example.enums.FlightStatus;
import com.example.event.FlightInstanceCreatedEvent;
import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.flight_service.event.FlightInstanceEventProducer;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.model.FlightSchedule;
import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.flight_service.repository.FlightRepository;
import com.example.flight_service.repository.FlightScheduleRepository;
import com.example.payload.request.FlightScheduleRequest;
import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.FlightScheduleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightScheduleServiceImplTest {
    private static final Long OWNER_ID = 50105L;
    private static final Long AIRLINE_ID = 101L;
    private static final Long FLIGHT_ID = 2801L;
    private static final Long SCHEDULE_ID = 301L;
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);

    private final FlightScheduleRepository schedules = mock(FlightScheduleRepository.class);
    private final FlightRepository flights = mock(FlightRepository.class);
    private final FlightInstanceRepository instances = mock(FlightInstanceRepository.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final LocationClient locations = mock(LocationClient.class);
    private final FlightInstanceEventProducer events = mock(FlightInstanceEventProducer.class);
    // Exercise the real downstream owner lookup, rather than mocking instance creation.
    private final FlightInstanceServiceImpl instanceService =
            new FlightInstanceServiceImpl(instances, flights, airlines, locations, events);
    private final FlightScheduleServiceImpl service =
            new FlightScheduleServiceImpl(schedules, flights, instanceService, locations, airlines);

    @BeforeEach
    void setUp() {
        Flight flight = Flight.builder().id(FLIGHT_ID).airlineId(AIRLINE_ID)
                .aircraftId(201L).departureAirportId(401L).arrivalAirportId(402L).build();
        AirlineResponse airline = AirlineResponse.builder().id(AIRLINE_ID).build();
        when(flights.findById(FLIGHT_ID)).thenReturn(Optional.of(flight));
        when(airlines.getAirlineByOwner(anyLong())).thenAnswer(invocation -> {
            Long ownerId = invocation.getArgument(0);
            if (!OWNER_ID.equals(ownerId)) {
                throw new IllegalArgumentException("Airline not found for owner: " + ownerId);
            }
            return airline;
        });
        when(airlines.getAirlineById(AIRLINE_ID)).thenReturn(airline);
        when(airlines.getAircraftById(201L)).thenReturn(
                AircraftResponse.builder().id(201L).totalSeats(12).build());
        when(schedules.save(any(FlightSchedule.class))).thenAnswer(invocation -> {
            FlightSchedule schedule = invocation.getArgument(0);
            schedule.setId(SCHEDULE_ID);
            return schedule;
        });
        when(instances.save(any(FlightInstance.class))).thenAnswer(invocation -> {
            FlightInstance instance = invocation.getArgument(0);
            instance.setId(501L);
            return instance;
        });
    }

    @Test
    void oneDayWednesdayRecurrenceCreatesDepartureUsingOwnerIdentity() throws Exception {
        FlightScheduleResponse response = service.createFlightSchedule(
                OWNER_ID, request(WEDNESDAY, List.of(DayOfWeek.WEDNESDAY)));

        assertEquals(SCHEDULE_ID, response.getId());
        ArgumentCaptor<FlightInstance> captured = ArgumentCaptor.forClass(FlightInstance.class);
        verify(instances).save(captured.capture());
        FlightInstance departure = captured.getValue();
        assertEquals(AIRLINE_ID, departure.getAirlineId());
        assertEquals(FLIGHT_ID, departure.getFlight().getId());
        assertEquals(SCHEDULE_ID, departure.getScheduleId());
        assertEquals(401L, departure.getDepartureAirportId());
        assertEquals(402L, departure.getArrivalAirportId());
        assertEquals(WEDNESDAY.atTime(10, 0), departure.getDepartureDateTime());
        assertEquals(WEDNESDAY.atTime(12, 0), departure.getArrivalDateTime());
        assertEquals(FlightStatus.SCHEDULED, departure.getStatus());
        assertEquals(12, departure.getTotalSeats());
        assertEquals(12, departure.getAvailableSeats());
        verify(airlines, times(2)).getAirlineByOwner(OWNER_ID);
        verify(airlines, never()).getAirlineByOwner(AIRLINE_ID);
        ArgumentCaptor<FlightInstanceCreatedEvent> event =
                ArgumentCaptor.forClass(FlightInstanceCreatedEvent.class);
        verify(events).sendFlightInstanceCreated(event.capture());
        assertEquals(departure.getId(), event.getValue().getFlightInstanceId());
        assertEquals(FLIGHT_ID, event.getValue().getFlightId());
        assertEquals(201L, event.getValue().getAircraftId());
    }

    @Test
    void recurrenceCreatesOnlyMatchingDaysIncludingBothRangeEndpoints() throws Exception {
        service.createFlightSchedule(OWNER_ID,
                request(WEDNESDAY.plusDays(7), List.of(DayOfWeek.WEDNESDAY)));

        ArgumentCaptor<FlightInstance> captured = ArgumentCaptor.forClass(FlightInstance.class);
        verify(instances, times(2)).save(captured.capture());
        assertEquals(List.of(WEDNESDAY.atTime(10, 0), WEDNESDAY.plusDays(7).atTime(10, 0)),
                captured.getAllValues().stream().map(FlightInstance::getDepartureDateTime).toList());
        assertTrue(captured.getAllValues().stream()
                .allMatch(instance -> SCHEDULE_ID.equals(instance.getScheduleId())
                        && AIRLINE_ID.equals(instance.getAirlineId())));
        verify(airlines, times(3)).getAirlineByOwner(OWNER_ID);
        verify(airlines, never()).getAirlineByOwner(AIRLINE_ID);
        verify(events, times(2)).sendFlightInstanceCreated(any(FlightInstanceCreatedEvent.class));
    }

    @Test
    void emptyRecurrenceStillCreatesScheduleWithoutDepartures() throws Exception {
        FlightScheduleResponse response = service.createFlightSchedule(
                OWNER_ID, request(WEDNESDAY, List.of()));

        assertEquals(SCHEDULE_ID, response.getId());
        verifyNoInteractions(instances, events);
        verify(airlines).getAirlineByOwner(OWNER_ID);
    }

    private FlightScheduleRequest request(LocalDate endDate, List<DayOfWeek> operatingDays) {
        return FlightScheduleRequest.builder().flightId(FLIGHT_ID)
                .startDate(WEDNESDAY).endDate(endDate).operatingDays(operatingDays)
                .departureTime(LocalTime.of(10, 0)).arrivalTime(LocalTime.of(12, 0))
                .isActive(true).build();
    }
}
