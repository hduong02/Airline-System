package com.example.flight_service.controller;

import com.example.flight_service.service.FlightInstanceService;
import com.example.payload.request.FlightInstanceRequest;
import com.example.payload.response.FlightInstanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightInstanceValidationTests {
    private final FlightInstanceService service = mock(FlightInstanceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FlightInstanceController(service)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"scheduleId\": null,"})
    void creationRejectsMissingOrNullScheduleBeforeCallingService(String scheduleField) throws Exception {
        mvc.perform(post("/api/flight-instances").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON).content(request(scheduleField)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    MethodArgumentNotValidException exception = assertInstanceOf(
                            MethodArgumentNotValidException.class, result.getResolvedException());
                    assertEquals(1, exception.getBindingResult().getErrorCount());
                    assertEquals("Schedule ID is required", exception.getBindingResult()
                            .getFieldError("scheduleId").getDefaultMessage());
                });
        verifyNoInteractions(service);
    }

    @Test
    void creationWithSchedulePassesOwnerAndScheduleToService() throws Exception {
        when(service.createFlightInstance(eq(50105L), any(FlightInstanceRequest.class)))
                .thenReturn(FlightInstanceResponse.builder().id(501L).build());

        mvc.perform(post("/api/flight-instances").header("X-User-Id", 50105L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("\"scheduleId\": 301,")))
                .andExpect(status().isCreated());

        ArgumentCaptor<FlightInstanceRequest> captured =
                ArgumentCaptor.forClass(FlightInstanceRequest.class);
        verify(service).createFlightInstance(eq(50105L), captured.capture());
        assertEquals(301L, captured.getValue().getScheduleId());
        assertEquals(2801L, captured.getValue().getFlightId());
    }

    @Test
    void partialUpdateDoesNotRequireScheduleAgain() throws Exception {
        mvc.perform(put("/api/flight-instances/501")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());
        verify(service).updateFlightInstance(eq(501L), any(FlightInstanceRequest.class));
    }

    private String request(String scheduleField) {
        return """
                {
                  %s
                  "flightId": 2801,
                  "departureDateTime": "2026-10-14T10:00:00",
                  "arrivalDateTime": "2026-10-14T12:00:00",
                  "totalSeats": 12
                }
                """.formatted(scheduleField);
    }
}
