package com.example.pricing_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.pricing_service.model.Fare;
import com.example.pricing_service.client.SeatClient;
import com.example.payload.response.CabinClassResponse;
import com.example.enums.CabinClassType;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.service.impl.FareServiceImpl;

class FareCabinTests {
    private final FareRepository repository = mock(FareRepository.class);
    private final SeatClient seats = mock(SeatClient.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(seats.getCabinClassById(601L)).thenReturn(
                CabinClassResponse.builder().id(601L).name("ECONOMY").build());
        when(repository.save(any(Fare.class))).thenAnswer(call -> {
            Fare fare = call.getArgument(0);
            fare.setId(5601L);
            when(repository.findById(5601L)).thenReturn(Optional.of(fare));
            return fare;
        });
        mvc = MockMvcBuilders.standaloneSetup(new FareController(new FareServiceImpl(repository, seats))).build();
    }

    @Test
    void createdFareExposesCabinRequiredByBookingAndFrontend() throws Exception {
        mvc.perform(post("/api/fares").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Standard\",\"rbdCode\":\"Y\",\"flightId\":2801,"
                        + "\"cabinClassId\":601,\"baseFare\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flightId").value(2801))
                .andExpect(jsonPath("$.cabinClassId").value(601))
                .andExpect(jsonPath("$.cabinClass").value("ECONOMY"));
        ArgumentCaptor<Fare> saved = ArgumentCaptor.forClass(Fare.class);
        verify(repository).save(saved.capture());
        assertEquals(CabinClassType.ECONOMY, saved.getValue().getCabinClass());
        mvc.perform(get("/api/fares/5601")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cabinClass").value("ECONOMY"));
    }

    @Test
    void existingApiCreatedFareExposesCabinOnRead() throws Exception {
        when(repository.findById(5601L)).thenReturn(Optional.of(Fare.builder()
                .id(5601L).flightId(2801L).cabinClassId(601L).baseFare(100.0).build()));
        mvc.perform(get("/api/fares/5601")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cabinClass").value("ECONOMY"));
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(CabinClassType.class)
    void cabinTypeComesFromSelectedCabinRatherThanRbdCode(CabinClassType type) throws Exception {
        when(seats.getCabinClassById(601L)).thenReturn(
                CabinClassResponse.builder().id(601L).name(type.name()).build());
        mvc.perform(post("/api/fares").contentType(MediaType.APPLICATION_JSON).content(farePayload(601)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cabinClass").value(type.name()));
    }

    @Test
    void updatingCabinKeepsIdAndTypeInSync() throws Exception {
        Fare existing = Fare.builder().id(5601L).flightId(2801L).cabinClassId(601L)
                .cabinClass(CabinClassType.ECONOMY).baseFare(100.0).build();
        when(repository.findById(5601L)).thenReturn(Optional.of(existing));
        when(seats.getCabinClassById(602L)).thenReturn(
                CabinClassResponse.builder().id(602L).name("BUSINESS").build());
        mvc.perform(put("/api/fares/5601").contentType(MediaType.APPLICATION_JSON).content(farePayload(602)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cabinClassId").value(602))
                .andExpect(jsonPath("$.cabinClass").value("BUSINESS"));
        assertEquals(CabinClassType.BUSINESS, existing.getCabinClass());
    }

    @Test
    void legacyFaresHaveCabinInListBatchAndSearchResponses() throws Exception {
        Fare legacy = Fare.builder().id(5601L).flightId(2801L).cabinClassId(601L).baseFare(100.0).build();
        when(repository.findByFlightIdAndCabinClassId(2801L, 601L)).thenReturn(List.of(legacy));
        when(repository.findAllById(List.of(5601L))).thenReturn(List.of(legacy));
        when(repository.findByFlightIdInAndCabinClassId(List.of(2801L), 601L)).thenReturn(List.of(legacy));
        mvc.perform(get("/api/fares/flight/2801/cabin-class/601"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].cabinClass").value("ECONOMY"));
        mvc.perform(get("/api/fares/lowest/flight/2801/cabin-class/601"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cabinClass").value("ECONOMY"));
        mvc.perform(post("/api/fares/batch-by-ids").contentType(MediaType.APPLICATION_JSON).content("[5601]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.5601.cabinClass").value("ECONOMY"));
        mvc.perform(post("/api/fares/search").param("cabinClassId", "601")
                .contentType(MediaType.APPLICATION_JSON).content("[2801]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.2801.cabinClass").value("ECONOMY"));
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", ""})
    void invalidCabinTypeCannotCreateFare(String name) throws Exception {
        when(seats.getCabinClassById(601L)).thenReturn(CabinClassResponse.builder().name(name).build());
        mvc.perform(post("/api/fares").contentType(MediaType.APPLICATION_JSON).content(farePayload(601)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void unresolvedCabinCannotCreateFare() throws Exception {
        when(seats.getCabinClassById(601L)).thenReturn(null);
        mvc.perform(post("/api/fares").contentType(MediaType.APPLICATION_JSON).content(farePayload(601)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    private String farePayload(long cabinId) {
        return "{\"name\":\"Standard\",\"rbdCode\":\"Y\",\"flightId\":2801,\"cabinClassId\":"
                + cabinId + ",\"baseFare\":100}";
    }
}
