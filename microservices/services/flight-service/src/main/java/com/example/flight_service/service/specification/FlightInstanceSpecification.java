package com.example.flight_service.service.specification;

import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import com.example.enums.FlightStatus;
import com.example.flight_service.model.FlightInstance;
import com.example.payload.request.FlightSearchRequest;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FlightInstanceSpecification {

    private static final Set<FlightStatus> EXCLUDED_STATUSES = Set.of(
            FlightStatus.CANCELLED,
            FlightStatus.COMPLETED,
            FlightStatus.DIVERTED
    );

    private FlightInstanceSpecification() {}

    public static Specification<FlightInstance> buildSearchSpec(
            FlightSearchRequest request) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Only active instances
            predicates.add(cb.isTrue(root.get("isActive")));

            // 2. Exclude terminal statuses
            predicates.add(root.get("status").in(EXCLUDED_STATUSES).not());

            // 3. Flight must still be in the future
            predicates.add(cb.greaterThan(root.get("departureDateTime"),
                    LocalDateTime.now()));

            // 4. Origin airport
            predicates.add(cb.equal(root.get("departureAirportId"),
                    request.getDepartureAirportId()));

            // 5. Destination airport
            predicates.add(cb.equal(root.get("arrivalAirportId"),
                    request.getArrivalAirportId()));

            // Optional filters

            // 6. Departure date: entire calendar day (startOfDay … endOfDay inclusive)
            if (request.getDepartureDate() != null) {
                LocalDateTime startOfDay = request.getDepartureDate().atStartOfDay();
                LocalDateTime endOfDay   = request.getDepartureDate().atTime(LocalTime.MAX);
                predicates.add(cb.between(root.get("departureDateTime"), startOfDay, endOfDay));
            }

            // 7. Seat availability guard - total available seats >= passengers
            //    (per-cabin availability is checked at booking time via seat-service)
            if (request.getPassengers() != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("availableSeats"), request.getPassengers()));
            }

            // 8. Airline IDs
            if (request.getAirlines() != null && !request.getAirlines().isEmpty()) {
                predicates.add(root.get("airlineId").in(request.getAirlines()));
            }

            // 9. Departure time-range bucket
            if (isFilterableTimeRange(request.getDepartureTimeRange())) {
                applyTimeRangePredicate(predicates, root, cb,
                        "departureDateTime", request.getDepartureTimeRange());
            }

            // 10. Arrival time-range bucket
            if (isFilterableTimeRange(request.getArrivalTimeRange())) {
                applyTimeRangePredicate(predicates, root, cb,
                        "arrivalDateTime", request.getArrivalTimeRange());
            }

            // 11. Maximum flight duration in minutes
            if (request.getMaxDuration() != null) {
                Expression<Integer> durationMinutes = cb.function(
                        "TIMESTAMPDIFF",
                        Integer.class,
                        cb.literal("MINUTE"),
                        root.get("departureDateTime"),
                        root.get("arrivalDateTime")
                );
                predicates.add(cb.lessThanOrEqualTo(durationMinutes, request.getMaxDuration()));
            }

            query.distinct(true);
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ======================= Private helpers =================================

    private static boolean isFilterableTimeRange(String range) {
        return range != null && !range.isBlank() && !range.equalsIgnoreCase("any");
    }

    private static void applyTimeRangePredicate(
            List<Predicate> predicates,
            Root<FlightInstance> root,
            CriteriaBuilder cb,
            String dateTimeField,
            String timeRange) {

        Expression<Integer> hour = cb.function("HOUR", Integer.class, root.get(dateTimeField));

        switch (timeRange.toLowerCase()) {
            case "morning" -> predicates.add(cb.between(hour, 6,  11));
            case "afternoon" -> predicates.add(cb.between(hour, 12, 17));
            case "evening" -> predicates.add(cb.between(hour, 18, 20));
            case "night" -> predicates.add(cb.or(
                    cb.greaterThanOrEqualTo(hour, 21),
                    cb.lessThanOrEqualTo(hour, 5)));
            default -> { }
        }
    }
}