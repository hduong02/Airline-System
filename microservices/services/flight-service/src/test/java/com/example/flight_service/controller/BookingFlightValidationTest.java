package com.example.flight_service.controller;

import com.example.enums.FlightStatus;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.repository.FlightInstanceRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(FlightDepartureFilterTest.Config.class)
@Transactional
class BookingFlightValidationTest {
    @Autowired EntityManager entityManager;
    @Autowired FlightInstanceRepository instances;

    @Test
    void returnsOnlyBookingIdentityFromOneQueryUsingTheFlightAirline() throws Exception {
        var flight = Flight.builder().flightNumber("VN123").airlineId(50L).aircraftId(1L)
                .departureAirportId(1L).arrivalAirportId(2L)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        entityManager.persist(flight);
        var instance = instances.saveAndFlush(FlightInstance.builder().flight(flight).airlineId(99L)
                .departureAirportId(1L).arrivalAirportId(2L).scheduleId(1L)
                .totalSeats(10).availableSeats(10).status(FlightStatus.SCHEDULED).build());
        Long flightId = flight.getId();
        Long instanceId = instance.getId();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new InternalBookingFlightController(instances)).build();
            mvc.perform(get("/internal/flight-instances/{id}/booking-validation", instanceId))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"flightId":%d,"flightInstanceId":%d,"airlineId":50}
                            """.formatted(flightId, instanceId), org.springframework.test.json.JsonCompareMode.STRICT));
            assertEquals(1, statistics.getPrepareStatementCount());
            assertEquals(0, statistics.getEntityLoadCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void missingInstanceReturnsNotFoundAndMalformedIdReturnsBadRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InternalBookingFlightController(instances)).build();
        mvc.perform(get("/internal/flight-instances/999999/booking-validation"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/flight-instances/invalid/booking-validation"))
                .andExpect(status().isBadRequest());
    }
}
