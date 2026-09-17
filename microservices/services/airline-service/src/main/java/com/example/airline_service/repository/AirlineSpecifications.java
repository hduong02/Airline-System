package com.example.airline_service.repository;

import com.example.airline_service.model.Airline;
import com.example.enums.AirlineStatus;
import org.springframework.data.jpa.domain.Specification;
import java.util.Locale;

public final class AirlineSpecifications {
    private AirlineSpecifications() {}

    public static Specification<Airline> matching(String search, AirlineStatus status) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (status != null) predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '!'),
                        cb.like(cb.lower(root.get("iataCode")), pattern, '!'),
                        cb.like(cb.lower(root.get("icaoCode")), pattern, '!'),
                        cb.like(cb.lower(root.get("country")), pattern, '!')));
            }
            return predicate;
        };
    }
}
