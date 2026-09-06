package com.example.flight_service.service.impl;

import com.example.enums.CabinClassType;
import com.example.enums.SeatAvailabilityStatus;
import com.example.flight_service.controller.FlightSearchController;
import com.example.flight_service.service.FlightSearchService;
import com.example.flight_service.client.*;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.payload.request.FlightSearchRequest;
import com.example.payload.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightSearchServiceImplTest {
    private final FlightInstanceRepository repository = mock(FlightInstanceRepository.class);
    private final LocationClient locations = mock(LocationClient.class);
    private final AirlineClient airlines = mock(AirlineClient.class);
    private final PricingClient pricing = mock(PricingClient.class);
    private final SeatClient seats = mock(SeatClient.class);
    private final FlightSearchServiceImpl service =
            new FlightSearchServiceImpl(repository, locations, airlines, pricing, seats);

    @Test
    void priceFilterFillsPagesFromAllCandidatesAndCountsOnlyMatches() {
        candidates(instance(1), instance(2), instance(3), instance(4));
        fare(1, 900); fare(2, 100); fare(3, 120); fare(4, 130);
        FlightSearchRequest request = request();
        request.setMaxPrice(150.0);

        Page<FlightInstanceResponse> first = service.searchFlights(request, PageRequest.of(0, 2));
        Page<FlightInstanceResponse> second = service.searchFlights(request, PageRequest.of(1, 2));
        Page<FlightInstanceResponse> beyond = service.searchFlights(request, PageRequest.of(2, 2));

        assertEquals(List.of(2L, 3L), ids(first));
        assertEquals(List.of(4L), ids(second));
        assertEquals(3, first.getTotalElements());
        assertEquals(3, second.getTotalElements());
        assertTrue(beyond.isEmpty());
        assertEquals(3, beyond.getTotalElements());
    }

    @Test
    void priceSortOrdersAllCandidatesBeforeSelectingEachPage() {
        candidates(instance(1), instance(2), instance(3), instance(4));
        fare(1, 300); fare(2, 100); fare(3, 200); fare(4, 100);
        FlightSearchRequest request = request();
        request.setSortBy("price");

        assertEquals(List.of(2L, 4L), ids(service.searchFlights(request, PageRequest.of(0, 2))));
        assertEquals(List.of(3L, 1L), ids(service.searchFlights(request, PageRequest.of(1, 2))));
        request.setSortOrder("desc");
        assertEquals(List.of(1L, 3L), ids(service.searchFlights(request, PageRequest.of(0, 2))));
    }

    @Test
    void allianceAliasesFilterBeforePaginationAndKeepFilteredTotals() {
        candidates(instance(1), instance(2), instance(3), instance(4));
        for (long id = 1; id <= 4; id++) fare(id, 100);
        when(airlines.getAirlineById(1L)).thenReturn(AirlineResponse.builder().alliance("SkyTeam").build());
        when(airlines.getAirlineById(2L)).thenReturn(AirlineResponse.builder().alliance("Star Alliance").build());
        when(airlines.getAirlineById(3L)).thenReturn(AirlineResponse.builder().alliance("Oneworld").build());
        when(airlines.getAirlineById(4L)).thenReturn(AirlineResponse.builder().alliance("STAR ALLIANCE").build());
        FlightSearchRequest request = request();
        for (String alias : List.of("star", " Star Alliance ", "STAR_ALLIANCE")) {
            request.setAlliance(alias);
            Page<FlightInstanceResponse> result = service.searchFlights(request, PageRequest.of(0, 1));
            assertEquals(List.of(2L), ids(result));
            assertEquals(2, result.getTotalElements());
            assertEquals(List.of(4L), ids(service.searchFlights(request, PageRequest.of(1, 1))));
        }
        request.setAlliance("skyteam");
        assertEquals(List.of(1L), ids(service.searchFlights(request, PageRequest.of(0, 2))));
        request.setAlliance("oneworld");
        assertEquals(List.of(3L), ids(service.searchFlights(request, PageRequest.of(0, 2))));
        request.setAlliance("any");
        assertEquals(4, service.searchFlights(request, PageRequest.of(0, 2)).getTotalElements());
    }

    @Test
    void excludesMissingCabinsAndFaresWithoutSparsePagesOrExceptions() {
        candidates(instance(1), instance(2), instance(3), instance(4));
        when(seats.getCabinClassByAircraftIdAndName(CabinClassType.ECONOMY, 1L)).thenReturn(null);
        // Flight 2 has no fare, flight 3 has a fare with no price.
        when(pricing.getLowestFareForFlightAndCabinClass(3L, 3L))
                .thenReturn(FareResponse.builder().build());
        fare(4, 100);
        Page<FlightInstanceResponse> result = service.searchFlights(request(), PageRequest.of(0, 2));
        assertEquals(List.of(4L), ids(result));
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void countsOnlyAvailableSeatsInRequestedCabinAndReturnsThatCount() {
        candidates(instance(1), instance(2), instance(3));
        fare(1, 100); fare(2, 120); fare(3, 130);
        when(seats.getFlightInstanceCabin(1L, 1L)).thenReturn(cabin(
                SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.RESERVED, SeatAvailabilityStatus.BOOKED));
        when(seats.getFlightInstanceCabin(2L, 2L)).thenReturn(cabin(
                SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.RESERVED));
        when(seats.getFlightInstanceCabin(3L, 3L)).thenReturn(null);
        Page<FlightInstanceResponse> result = service.searchFlights(request(), PageRequest.of(0, 2));
        assertEquals(List.of(2L), ids(result));
        assertEquals(1, result.getTotalElements());
        assertEquals(2, result.getContent().getFirst().getAvailableSeats());
    }

    @Test
    void durationAndArrivalSortApplyGloballyAndUnpagedSearchIsSupported() {
        FlightInstance first = instance(1);
        first.setArrivalDateTime(first.getDepartureDateTime().plusHours(5));
        FlightInstance second = instance(2);
        second.setArrivalDateTime(second.getDepartureDateTime().plusHours(1));
        candidates(first, second, instance(3));
        fare(1, 100); fare(2, 100); fare(3, 100);
        FlightSearchRequest request = request();
        request.setSortBy("duration");
        assertEquals(List.of(2L), ids(service.searchFlights(request, PageRequest.of(0, 1))));
        request.setSortOrder("desc");
        assertEquals(List.of(1L), ids(service.searchFlights(request, PageRequest.of(0, 1))));
        request.setSortBy("arrival");
        request.setSortOrder("asc");
        assertEquals(List.of(2L, 3L, 1L), ids(service.searchFlights(request, Pageable.unpaged())));
    }

    @Test
    void controllerForwardsAllianceParameter() {
        FlightSearchService search = mock(FlightSearchService.class);
        Pageable pageable = PageRequest.of(0, 2);
        when(search.searchFlights(any(), eq(pageable))).thenReturn(Page.empty(pageable));
        new FlightSearchController(search).searchFlights(1L, 2L, java.time.LocalDate.of(2027, 3, 15),
                2, CabinClassType.ECONOMY, null, null, null, null, null, null,
                "price", "asc", "star", pageable);
        verify(search).searchFlights(argThat(request -> "star".equals(request.getAlliance())), eq(pageable));
    }

    @Test
    void priceBoundsAreInclusiveAndEmptyMatchesHaveZeroTotal() {
        candidates(instance(1), instance(2), instance(3), instance(4));
        fare(1, 99); fare(2, 100); fare(3, 200); fare(4, 201);
        FlightSearchRequest request = request();
        request.setMinPrice(100.0);
        request.setMaxPrice(200.0);
        Page<FlightInstanceResponse> result = service.searchFlights(request, PageRequest.of(0, 2));
        assertEquals(List.of(2L, 3L), ids(result));
        assertEquals(2, result.getTotalElements());
        request.setMinPrice(300.0);
        request.setMaxPrice(400.0);
        result = service.searchFlights(request, PageRequest.of(0, 2));
        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
    }

    private FlightInstanceCabinResponse cabin(SeatAvailabilityStatus... statuses) {
        return FlightInstanceCabinResponse.builder().availableSeats(100)
                .seats(java.util.Arrays.stream(statuses)
                        .map(status -> SeatInstanceResponse.builder().status(status).build()).toList()).build();
    }

    private FlightSearchRequest request() {
        return FlightSearchRequest.builder().cabinClass(CabinClassType.ECONOMY)
                .passengers(2).sortBy("departure").build();
    }

    private FlightInstance instance(long id) {
        return FlightInstance.builder().id(id).airlineId(id).availableSeats(10)
                .departureAirportId(1L).arrivalAirportId(2L)
                .departureDateTime(LocalDateTime.of(2027, 3, 15, 8, 0).plusHours(id))
                .arrivalDateTime(LocalDateTime.of(2027, 3, 15, 10, 0).plusHours(id))
                .flight(Flight.builder().id(id).aircraftId(id).build()).build();
    }

    @SuppressWarnings("unchecked")
    private void candidates(FlightInstance... instances) {
        List<FlightInstance> all = List.of(instances);
        when(repository.findAll(any(Specification.class), any(Sort.class))).thenReturn(all);
        // Model the original DB pagination as well, so these tests expose the bug.
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(call -> {
            Pageable page = call.getArgument(1);
            int start = (int) Math.min(page.getOffset(), all.size());
            int end = Math.min(start + page.getPageSize(), all.size());
            return new PageImpl<>(all.subList(start, end), page, all.size());
        });
        when(airlines.getAirlineById(anyLong())).thenAnswer(call ->
                AirlineResponse.builder().id(call.getArgument(0)).name("Airline").build());
        when(airlines.getAircraftById(anyLong())).thenReturn(AircraftResponse.builder().build());
        when(locations.getAirportById(anyLong())).thenReturn(AirportResponse.builder().build());
        when(seats.getCabinClassByAircraftIdAndName(eq(CabinClassType.ECONOMY), anyLong()))
                .thenAnswer(call -> CabinClassResponse.builder().id(call.getArgument(1)).build());
        when(seats.getFlightInstanceCabin(anyLong(), anyLong())).thenReturn(cabin(
                SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE,
                SeatAvailabilityStatus.AVAILABLE));
    }

    private void fare(long flightId, double price) {
        when(pricing.getLowestFareForFlightAndCabinClass(flightId, flightId))
                .thenReturn(FareResponse.builder().totalPrice(price).build());
    }

    private List<Long> ids(Page<FlightInstanceResponse> page) {
        return page.getContent().stream().map(FlightInstanceResponse::getId).toList();
    }
}
