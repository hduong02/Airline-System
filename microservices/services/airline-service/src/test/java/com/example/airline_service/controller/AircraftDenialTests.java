package com.example.airline_service.controller;

import com.example.airline_service.Controller.AircraftController;
import com.example.airline_service.model.Aircraft;
import com.example.airline_service.model.Airline;
import com.example.airline_service.repository.AircraftRepository;
import com.example.airline_service.repository.AirlineRepository;
import com.example.airline_service.service.impl.AircraftServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AircraftDenialTests {
    private final AircraftRepository aircrafts = mock(AircraftRepository.class);
    private final AirlineRepository airlines = mock(AirlineRepository.class);
    private final Airline airline = Airline.builder().id(101L).ownerId(50105L).build();
    private final Aircraft aircraft = Aircraft.builder().id(201L).code("TEST01")
            .airline(airline).seatingCapacity(12).build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(airlines.findByOwnerId(50105L)).thenReturn(Optional.of(airline));
        when(airlines.findByOwnerId(50106L)).thenReturn(
                Optional.of(Airline.builder().id(102L).ownerId(50106L).build()));
        when(aircrafts.findByIdAndAirlineId(201L, 101L)).thenReturn(aircraft);
        when(aircrafts.save(any(Aircraft.class))).thenAnswer(call -> call.getArgument(0));
        mvc = MockMvcBuilders.standaloneSetup(new AircraftController(
                new AircraftServiceImpl(aircrafts, airlines))).build();
    }

    @Test
    void foreignOwnerUpdateReturnsNotFoundWithoutChangingAircraft() throws Exception {
        mvc.perform(put("/api/aircrafts/201").header("X-User-Id", 50106L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"CHANGED\"}"))
                .andExpect(status().isNotFound());
        verify(aircrafts).findByIdAndAirlineId(201L, 102L);
        verify(aircrafts, never()).save(any());
        assertEquals("TEST01", aircraft.getCode());
    }

    @Test
    void foreignOwnerDeleteReturnsNotFoundWithoutDeletingAircraft() throws Exception {
        mvc.perform(delete("/api/aircrafts/201").header("X-User-Id", 50106L))
                .andExpect(status().isNotFound());
        verify(aircrafts, never()).delete(any());
    }

    @Test
    void missingAircraftReturnsNotFound() throws Exception {
        when(aircrafts.findById(999L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/aircrafts/999")).andExpect(status().isNotFound());
    }

    @Test
    void callerWithoutAirlineReturnsNotFoundBeforeAircraftLookup() throws Exception {
        when(airlines.findByOwnerId(50103L)).thenReturn(Optional.empty());
        mvc.perform(put("/api/aircrafts/201").header("X-User-Id", 50103L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(aircrafts);
    }

    @Test
    void duplicateAircraftCreationReturnsConflict() throws Exception {
        when(aircrafts.existsByCode("TEST01")).thenReturn(true);
        mvc.perform(post("/api/aircrafts").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TEST01\",\"seatingCapacity\":12}"))
                .andExpect(status().isConflict());
        verify(aircrafts, never()).save(any());
    }

    @Test
    void duplicateAircraftCodeUpdateReturnsConflictWithoutChangingAircraft() throws Exception {
        when(aircrafts.existsByCode("DUPLICATE")).thenReturn(true);
        mvc.perform(put("/api/aircrafts/201").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"DUPLICATE\"}"))
                .andExpect(status().isConflict());
        verify(aircrafts, never()).save(any());
        assertEquals("TEST01", aircraft.getCode());
    }

    @Test
    void nonPositiveSeatingCapacityReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/aircrafts").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NEW01\",\"seatingCapacity\":0}"))
                .andExpect(status().isBadRequest());
        verify(aircrafts, never()).save(any());
    }

    @Test
    void ownerCanStillUpdateAndDeleteAircraft() throws Exception {
        mvc.perform(put("/api/aircrafts/201").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"UPDATED\"}"))
                .andExpect(status().isOk());
        verify(aircrafts).save(aircraft);
        assertEquals("UPDATED", aircraft.getCode());
        mvc.perform(delete("/api/aircrafts/201").header("X-User-Id", 50105L))
                .andExpect(status().isNoContent());
        verify(aircrafts).delete(aircraft);
    }
}
