package com.example.flight_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.JpaSort;
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

        // Phase 1: paginated DB query with dynamic Specification
        Pageable sortedPageable = applySort(
            pageable,
            request.getSortBy(),
            request.getSortOrder());

        Specification<FlightInstance> spec =
                FlightInstanceSpecification.buildSearchSpec(request);

        Page<FlightInstance> dbPage = flightInstanceRepository.findAll(spec, sortedPageable);

        if (dbPage.isEmpty())
            return Page.empty(sortedPageable);
        
        List<FlightInstance> instances = new ArrayList<>(dbPage.getContent());

        // Phase 2: cabin-class + price filtering via pricing-service
        Map<Long, FareResponse> fareMap = Collections.emptyMap();

        if (request.getCabinClass() != null) {
            final boolean hasPriceFilter = request.getMinPrice() != null
                    || request.getMaxPrice() != null;

            Map<Long, FareResponse> mergedFareMap = new HashMap<>();
            List<FlightInstance> filtered = new ArrayList<>();

            for (FlightInstance fi : instances) {
                // 1. get cabinClassId for this specific aircraft
                CabinClassResponse cabinClassResponse = seatClient
                        .getCabinClassByAircraftIdAndName(
                                request.getCabinClass(),
                                fi.getFlight().getAircraftId()
                );

                Long cabinClassId = cabinClassResponse.getId();
                if (cabinClassId == null) continue;

                // 2. fetch fare for this specific flight + cabinClass
                FareResponse fare = pricingClient.getLowestFareForFlightAndCabinClass(
                        fi.getFlight().getId(),
                        cabinClassId
                );

                if (fare == null) continue; // no fare available

                // 3. apply price filter
                if (hasPriceFilter) {
                    Double price = fare.getTotalPrice();
                    if (price == null) continue;
                    if (request.getMinPrice() != null
                            && price < request.getMinPrice()) continue;
                    if (request.getMaxPrice() != null
                            && price > request.getMaxPrice()) continue;
                }

                mergedFareMap.put(fi.getFlight().getId(), fare);
                filtered.add(fi);
            }

            fareMap = mergedFareMap;
            instances = filtered;

            if (instances.isEmpty()) return Page.empty(sortedPageable);
        }

        // Enrichment: airline + airport (per-request cache), fare already fetched
        List<FlightInstanceResponse> responses = enrichWithExternalData(instances, fareMap);

        return new PageImpl<>(responses, sortedPageable, dbPage.getTotalElements());
    }

    // ========================= Private helpers =================================
    private List<FlightInstanceResponse> enrichWithExternalData(
            List<FlightInstance> instances,
            Map<Long, FareResponse> fareMap) {

        Map<Long, AirlineResponse> airlineCache  = new HashMap<>();
        Map<Long, AirportResponse> airportCache  = new HashMap<>();
        Map<Long, AircraftResponse> aircraftCache = new HashMap<>();
        List<FlightInstanceResponse> results = new ArrayList<>(instances.size());

        for (FlightInstance fi : instances) {
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

            // Attach the pre-fetched fare (null if no cabin filter was applied)
            response.setFare(fareMap.get(fi.getFlight().getId()));

            results.add(response);
        }
        return results;
    }

    private Pageable applySort(Pageable pageable, String sortBy, String sortOrder) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortOrder)
                ? Sort.Direction.DESC : Sort.Direction.ASC;

        Sort sort = (sortBy == null || sortBy.isBlank())
                ? Sort.by(direction, "departureDateTime")
                : switch (sortBy.toLowerCase()) {
                    case "arrival"-> Sort.by(direction, "arrivalDateTime");
                    case "duration" -> JpaSort.unsafe(direction,
                            "TIMESTAMPDIFF(MINUTE, departure_date_time, arrival_date_time)");
                    default -> Sort.by(direction, "departureDateTime");
                };

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
