package com.example.flight_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.flight_service.client.AirlineClient;
import com.example.flight_service.client.LocationClient;
import com.example.flight_service.client.PricingClient;
import com.example.flight_service.client.SeatClient;
import com.example.flight_service.mapper.FlightInstanceMapper;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.repository.FlightInstanceRepository;
import com.example.flight_service.service.FlightSearchService;
import com.example.flight_service.service.specification.FlightInstanceSpecification;
import com.example.payload.request.FlightSearchRequest;
import com.example.payload.response.AircraftResponse;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.CabinClassResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.enums.SeatAvailabilityStatus;

import java.time.Duration;
import java.util.function.Function;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightSearchServiceImpl implements FlightSearchService {

    private final FlightInstanceRepository flightInstanceRepository;
    private final LocationClient locationClient;
    private final AirlineClient airlineClient;
    private final PricingClient pricingClient;
    private final SeatClient seatClient;

    @Override
    @Transactional(readOnly = true)
    public Page<FlightInstanceResponse> searchFlights(
            FlightSearchRequest request,
            Pageable pageable) {

        Specification<FlightInstance> spec = FlightInstanceSpecification.buildSearchSpec(request);
        // Fares, alliances and cabin availability live in other services. Evaluate
        // all database candidates before selecting a page or reporting its total.
        List<FlightInstance> candidates = flightInstanceRepository.findAll(spec, Sort.by("id"));
        Map<Long, AirlineResponse> airlineCache = new HashMap<>();
        Map<Long, Optional<CabinClassResponse>> cabinCache = new HashMap<>();
        Map<Long, Optional<FareResponse>> fareCache = new HashMap<>();
        List<SearchMatch> matches = new ArrayList<>();

        for (FlightInstance instance : candidates) {
            if (hasAllianceFilter(request.getAlliance())) {
                AirlineResponse airline = airlineCache.computeIfAbsent(
                        instance.getAirlineId(), airlineClient::getAirlineById);
                if (airline == null || !normalizeAlliance(request.getAlliance())
                        .equals(normalizeAlliance(airline.getAlliance()))) continue;
            }

            FareResponse fare = null;
            Integer availableSeats = instance.getAvailableSeats();
            if (request.getCabinClass() != null) {
                CabinClassResponse cabin = cabinCache.computeIfAbsent(
                        instance.getFlight().getAircraftId(), aircraftId -> Optional.ofNullable(
                                seatClient.getCabinClassByAircraftIdAndName(request.getCabinClass(), aircraftId)))
                        .orElse(null);
                if (cabin == null || cabin.getId() == null) continue;

                FlightInstanceCabinResponse instanceCabin = seatClient.getFlightInstanceCabin(
                        instance.getId(), cabin.getId());
                if (instanceCabin == null || instanceCabin.getSeats() == null) continue;
                availableSeats = (int) instanceCabin.getSeats().stream()
                        .filter(seat -> seat.getStatus() == SeatAvailabilityStatus.AVAILABLE).count();
                int passengers = request.getPassengers() == null ? 1 : request.getPassengers();
                if (availableSeats < passengers) continue;

                fare = fareCache.computeIfAbsent(instance.getFlight().getId(), flightId ->
                        Optional.ofNullable(pricingClient.getLowestFareForFlightAndCabinClass(
                                flightId, cabin.getId()))).orElse(null);
                if (fare == null || fare.getTotalPrice() == null) continue;
            }

            if (request.getMinPrice() != null || request.getMaxPrice() != null) {
                if (fare == null || fare.getTotalPrice() == null) continue;
                double price = fare.getTotalPrice();
                if (request.getMinPrice() != null && price < request.getMinPrice()) continue;
                if (request.getMaxPrice() != null && price > request.getMaxPrice()) continue;
            }
            matches.add(new SearchMatch(instance, fare, availableSeats));
        }

        matches.sort(matchComparator(request));
        long total = matches.size();
        int start = pageable.isUnpaged() ? 0 : (int) Math.min(pageable.getOffset(), total);
        int end = pageable.isUnpaged() ? matches.size()
                : (int) Math.min((long) start + pageable.getPageSize(), total);
        List<SearchMatch> pageMatches = matches.subList(start, end);
        List<FlightInstanceResponse> responses = enrichWithExternalData(pageMatches, airlineCache);
        return new PageImpl<>(responses, resultPageable(pageable, request), total);
    }

    private record SearchMatch(FlightInstance instance, FareResponse fare, Integer availableSeats) {}

    private boolean hasAllianceFilter(String alliance) {
        return alliance != null && !alliance.isBlank() && !"any".equalsIgnoreCase(alliance.trim());
    }

    private String normalizeAlliance(String alliance) {
        if (alliance == null) return "";
        String normalized = alliance.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        return "staralliance".equals(normalized) ? "star" : normalized;
    }

    private String sortProperty(String sortBy) {
        if (sortBy == null) return "departure";
        return switch (sortBy.trim().toLowerCase(Locale.ROOT)) {
            case "price", "duration", "arrival" -> sortBy.trim().toLowerCase(Locale.ROOT);
            default -> "departure";
        };
    }

    private Comparator<SearchMatch> matchComparator(FlightSearchRequest request) {
        Comparator<SearchMatch> comparator = switch (sortProperty(request.getSortBy())) {
            case "price" -> compare(match -> match.fare() == null ? null : match.fare().getTotalPrice(), request);
            case "duration" -> compare(match -> Duration.between(match.instance().getDepartureDateTime(),
                    match.instance().getArrivalDateTime()), request);
            case "arrival" -> compare(match -> match.instance().getArrivalDateTime(), request);
            default -> compare(match -> match.instance().getDepartureDateTime(), request);
        };
        return comparator.thenComparing(match -> match.instance().getDepartureDateTime())
                .thenComparing(match -> match.instance().getId());
    }

    private <T extends Comparable<? super T>> Comparator<SearchMatch> compare(
            Function<SearchMatch, T> key, FlightSearchRequest request) {
        Comparator<T> values = "desc".equalsIgnoreCase(request.getSortOrder())
                ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(key, Comparator.nullsLast(values));
    }

    private Pageable resultPageable(Pageable pageable, FlightSearchRequest request) {
        if (pageable.isUnpaged()) return pageable;
        String property = switch (sortProperty(request.getSortBy())) {
            case "departure" -> "departureDateTime";
            case "arrival" -> "arrivalDateTime";
            case "price" -> "fare.totalPrice";
            default -> "duration";
        };
        Sort.Direction direction = "desc".equalsIgnoreCase(request.getSortOrder())
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(direction, property).and(Sort.by("departureDateTime", "id")));
    }

    // ========================= Private helpers =================================
    private List<FlightInstanceResponse> enrichWithExternalData(
            List<SearchMatch> matches,
            Map<Long, AirlineResponse> airlineCache) {

        Map<Long, AirportResponse> airportCache  = new HashMap<>();
        Map<Long, AircraftResponse> aircraftCache = new HashMap<>();
        List<FlightInstanceResponse> results = new ArrayList<>(matches.size());

        for (SearchMatch match : matches) {
            FlightInstance fi = match.instance();
            AircraftResponse aircraft = aircraftCache.computeIfAbsent(
                    fi.getFlight().getAircraftId(), airlineClient::getAircraftById);

            AirlineResponse airline = airlineCache.computeIfAbsent(
                    fi.getAirlineId(), airlineClient::getAirlineById);

            AirportResponse depAirport = airportCache.computeIfAbsent(
                    fi.getDepartureAirportId(), locationClient::getAirportById);

            AirportResponse arrAirport = airportCache.computeIfAbsent(
                    fi.getArrivalAirportId(), locationClient::getAirportById);

            FlightInstanceResponse response = FlightInstanceMapper.toResponse(
                    fi, aircraft, airline, depAirport, arrAirport);

            response.setFare(match.fare());
            response.setAvailableSeats(match.availableSeats());

            results.add(response);
        }
        return results;
    }

}
