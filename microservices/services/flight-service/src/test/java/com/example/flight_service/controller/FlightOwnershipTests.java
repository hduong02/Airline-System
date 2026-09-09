package com.example.flight_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.enums.FlightStatus;
import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.flight_service.model.Flight;
import com.example.flight_service.repository.FlightRepository;
import com.example.flight_service.service.impl.FlightServiceImpl;
import com.example.payload.response.AirlineResponse;

class FlightOwnershipTests {
    private final FlightRepository repository = mock(FlightRepository.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final LocationClient locations = mock(LocationClient.class);
    private final Flight flight = Flight.builder().id(2801L).airlineId(101L)
            .flightNumber("TEST01").aircraftId(201L).departureAirportId(1L).arrivalAirportId(2L).build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(repository.findById(2801L)).thenReturn(Optional.of(flight));
        when(repository.save(any(Flight.class))).thenAnswer(call -> call.getArgument(0));
        when(airlines.getAirlineById(101L)).thenReturn(
                AirlineResponse.builder().id(101L).ownerId(50105L).build());
        mvc = MockMvcBuilders.standaloneSetup(new FlightController(
                new FlightServiceImpl(repository, airlines, locations))).build();
    }

    @ParameterizedTest
    @ValueSource(longs = {50106L, 50103L})
    void foreignOwnerAndTravelerCannotUpdate(Long userId) throws Exception {
        mvc.perform(put("/api/flights/2801").header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"flightNumber\":\"CHANGED\"}"))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any());
        assertEquals("TEST01", flight.getFlightNumber());
    }

    @ParameterizedTest
    @ValueSource(longs = {50106L, 50103L})
    void foreignOwnerAndTravelerCannotChangeStatus(Long userId) throws Exception {
        mvc.perform(patch("/api/flights/2801/status").header("X-User-Id", userId)
                .param("status", "CANCELLED")).andExpect(status().isForbidden());
        verify(repository, never()).save(any());
        assertEquals(FlightStatus.SCHEDULED, flight.getStatus());
    }

    @Test
    void updateRequiresCallerIdentity() throws Exception {
        mvc.perform(put("/api/flights/2801").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository, airlines);
    }

    @Test
    void statusChangeRequiresCallerIdentity() throws Exception {
        mvc.perform(patch("/api/flights/2801/status").param("status", "CANCELLED"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository, airlines);
    }

    @Test
    void ownerCanUpdateOwnFlight() throws Exception {
        mvc.perform(put("/api/flights/2801").header("X-User-Id", 50105L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flightNumber\":\"UPDATED\",\"airlineId\":101}"))
                .andExpect(status().isOk());
        verify(repository).save(flight);
        assertEquals("UPDATED", flight.getFlightNumber());
        assertEquals(101L, flight.getAirlineId());
    }

    @Test
    void ownerCanChangeOwnFlightStatus() throws Exception {
        mvc.perform(patch("/api/flights/2801/status").header("X-User-Id", 50105L)
                .param("status", "CANCELLED")).andExpect(status().isOk());
        verify(repository).save(flight);
        assertEquals(FlightStatus.CANCELLED, flight.getStatus());
    }

    @Test
    void ownerCannotTransferFlightToAnotherAirline() throws Exception {
        mvc.perform(put("/api/flights/2801").header("X-User-Id", 50105L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"airlineId\":102,\"flightNumber\":\"CHANGED\"}"))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any());
        assertEquals(101L, flight.getAirlineId());
        assertEquals("TEST01", flight.getFlightNumber());
    }

    @Test
    void callerCannotUsePayloadAirlineToClaimForeignFlight() throws Exception {
        mvc.perform(put("/api/flights/2801").header("X-User-Id", 50106L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"airlineId\":102}"))
                .andExpect(status().isForbidden());
        verify(repository, never()).save(any());
        assertEquals(101L, flight.getAirlineId());
    }
}
