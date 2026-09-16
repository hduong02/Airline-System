package com.example.pricing_service.service;

import com.example.payload.request.BaggagePolicyRequest;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.FlightResponse;
import com.example.pricing_service.client.FlightClient;
import com.example.pricing_service.model.BaggagePolicy;
import com.example.pricing_service.model.Fare;
import com.example.pricing_service.repository.BaggagePolicyRepository;
import com.example.pricing_service.repository.FareRepository;
import com.example.pricing_service.service.impl.BaggagePolicyServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BaggageListingTests {
    @Mock private BaggagePolicyRepository policies;
    @Mock private FareRepository fares;
    @Mock private FlightClient flights;
    @InjectMocks private BaggagePolicyServiceImpl service;

    @Test
    void createdPolicyAppearsInTheFaresAirlineCatalogue() throws Exception {
        when(fares.findById(10L)).thenReturn(Optional.of(Fare.builder().id(10L).flightId(20L).build()));
        when(flights.getFlightById(20L)).thenReturn(FlightResponse.builder()
                .id(20L).airline(AirlineResponse.builder().id(14L).ownerId(50020L).build()).build());
        List<BaggagePolicy> stored = new ArrayList<>();
        when(policies.save(any(BaggagePolicy.class))).thenAnswer(call -> {
            BaggagePolicy policy = call.getArgument(0);
            policy.setId(100L);
            stored.add(policy);
            return policy;
        });
        when(policies.findByAirlineId(any())).thenAnswer(call -> {
            Long airlineId = call.getArgument(0);
            return stored.stream().filter(policy -> airlineId.equals(policy.getAirlineId())).toList();
        });
        service.createBaggagePolicy(BaggagePolicyRequest.builder().name("Standard").fareId(10L).build());

        assertEquals(List.of(100L), service.getBaggagePoliciesByAirlineId(14L).stream()
                .map(policy -> policy.getId()).toList());
        assertEquals(14L, stored.getFirst().getAirlineId());
        assertTrue(service.getBaggagePoliciesByAirlineId(15L).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("unresolvedAirlines")
    void unresolvedFlightAirlineCannotCreateUnlistedPolicy(FlightResponse flight) throws Exception {
        when(fares.findById(10L)).thenReturn(Optional.of(Fare.builder().id(10L).flightId(20L).build()));
        when(flights.getFlightById(20L)).thenReturn(flight);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.createBaggagePolicy(BaggagePolicyRequest.builder().name("Standard").fareId(10L).build()));

        assertEquals(502, error.getStatusCode().value());
        verify(policies, never()).save(any());
    }

    @Test
    void duplicatePolicyIsRejectedBeforeResolvingFlight() throws Exception {
        when(fares.findById(10L)).thenReturn(Optional.of(Fare.builder().id(10L).flightId(20L).build()));
        when(policies.existsByFareId(10L)).thenReturn(true);

        assertThrows(Exception.class, () -> service.createBaggagePolicy(
                BaggagePolicyRequest.builder().name("Duplicate").fareId(10L).build()));

        verifyNoInteractions(flights);
        verify(policies, never()).save(any());
    }

    private static Stream<FlightResponse> unresolvedAirlines() {
        return Stream.of(null, FlightResponse.builder().id(20L).build(),
                FlightResponse.builder().id(20L).airline(AirlineResponse.builder().build()).build());
    }
}
