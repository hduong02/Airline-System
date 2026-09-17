package com.example.flight_service.controller;

import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightSchedule;
import com.example.flight_service.repository.FlightScheduleRepository;
import com.example.flight_service.service.FlightScheduleService;
import com.example.flight_service.service.impl.FlightScheduleServiceImpl;
import com.example.payload.response.AirlineResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(FlightDepartureFilterTest.Config.class)
@Transactional
class FlightSchedulePaginationTest {
    @Autowired EntityManager entityManager;
    @Autowired FlightScheduleRepository schedules;

    @Test void pagesAndCountsOnlyTheOwnersAirlineAndEnrichesOnlySelectedRows() throws Exception {
        var own = flight(1L);
        var other = flight(2L);
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 105; i++) {
            ids.add(schedule(own).getId());
            if (i % 10 == 0) schedule(other);
        }
        entityManager.flush();
        entityManager.clear();
        var airline = mock(AirlineClient.class);
        var location = mock(LocationClient.class);
        var owner = new AirlineResponse();
        owner.setId(1L);
        when(airline.getAirlineByOwner(7L)).thenReturn(owner);
        var service = new FlightScheduleServiceImpl(schedules, null, null, location, airline);
        var page = service.getFlightSchedulePage(7L, 5, 20);
        assertEquals(105, page.getTotalElements());
        assertEquals(6, page.getTotalPages());
        assertEquals(5, page.getNumber());
        assertEquals(ids.subList(100, 105), page.getContent().stream().map(s -> s.getId()).toList());
        verify(location, times(5)).getAirportById(1L);
        verify(location, times(5)).getAirportById(2L);
        clearInvocations(location);
        var empty = service.getFlightSchedulePage(7L, 6, 20);
        assertTrue(empty.isEmpty());
        assertEquals(105, empty.getTotalElements());
        verifyNoInteractions(location);
        var missing = schedules.findByFlightAirlineId(99L, PageRequest.of(0, 20, Sort.by("id")));
        assertEquals(0, missing.getTotalElements());
        var second = schedules.findByFlightAirlineId(1L, PageRequest.of(1, 20, Sort.by("id")));
        assertEquals(ids.subList(20, 40), second.getContent().stream().map(FlightSchedule::getId).toList());
    }

    @Test void controllerValidatesBoundsHeaderAndPreservesLegacyList() throws Exception {
        var service = mock(FlightScheduleService.class);
        when(service.getFlightSchedulePage(7L, 0, 20)).thenReturn(org.springframework.data.domain.Page.empty(PageRequest.of(0, 20)));
        when(service.getFlightScheduleByAirline(7L)).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new FlightScheduleController(service)).build();
        mvc.perform(get("/api/flight-schedules/page").header("X-User-Id", 7))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(service).getFlightSchedulePage(7L, 0, 20);
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=abc")) {
            mvc.perform(get("/api/flight-schedules/page" + query).header("X-User-Id", 7))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/flight-schedules/page")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/flight-schedules").header("X-User-Id", 7))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).getFlightScheduleByAirline(7L);
        verifyNoMoreInteractions(service);
    }

    private Flight flight(Long airlineId) {
        var flight = Flight.builder().flightNumber("TEST-" + airlineId).airlineId(airlineId)
                .aircraftId(1L).departureAirportId(1L).arrivalAirportId(2L)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        entityManager.persist(flight);
        return flight;
    }

    private FlightSchedule schedule(Flight flight) {
        var schedule = FlightSchedule.builder().flight(flight)
                .departureAirportId(1L).arrivalAirportId(2L)
                .departureTime(LocalTime.of(9, 0)).arrivalTime(LocalTime.of(11, 0))
                .startDate(LocalDate.of(2026, 10, 19)).endDate(LocalDate.of(2026, 10, 20))
                .operatingDays(List.of(java.time.DayOfWeek.MONDAY)).build();
        entityManager.persist(schedule);
        return schedule;
    }
}
