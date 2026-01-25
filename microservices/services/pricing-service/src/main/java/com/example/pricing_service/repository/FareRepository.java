package com.example.pricing_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.pricing_service.model.Fare;

import java.util.Collection;
import java.util.List;


public interface FareRepository extends JpaRepository<Fare, Long> {

    // List<Fare> findByFlightId(Long flightId);
    // Page<Fare> findByFlightId(Long flightId, Pageable pageable);

    // List<Fare> findByCabinClassId(Long cabinClassId);

    List<Fare> findByFlightIdAndCabinClassId(Long flightId, Long cabinClassId);

    // @Query("SELECT f FROM Fare f LEFT JOIN FETCH f.fareRules LEFT JOIN FETCH f.baggagePolicy WHERE f.id = :id")
    // Optional<Fare> findByIdWithDetails(@Param("id") Long id);

    // @Query(""" 
    //     SELECT f FROM Fare f
    //     LEFT JOIN FETCH f.fareRules
    //     LEFT JOIN FETCH f.baggagePolicy
    //     WHERE f.flightId = :flightId
    // """)
    // List<Fare> findByFlightIdWithDetails(@Param("flightId") Long flightId);

    boolean existsByFlightIdAndCabinClassIdAndName(Long flightId,
                                                    Long cabinClassId,
                                                    String name);

    boolean existsByFlightIdAndCabinClassIdAndNameAndIdNot(Long flightId,
                                                            Long cabinClassId,
                                                            String name,
                                                            Long id);

    List<Fare> findByFlightIdInAndCabinClassId(Collection<Long> flightIds, Long cabinClassId);

    // @Query("SELECT CONCAT(f.flightId, ':', f.cabinClassId, ':', f.name) FROM Fare f WHERE f.flightId IN :flightIds")
    // Set<String> findExistingFareKeys(@Param("flightIds") Collection<Long> flightIds);
}