package com.example.seat_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.enums.CabinClassType;
import com.example.seat_service.model.CabinClass;

import java.util.List;

public interface CabinClassRepository extends JpaRepository<CabinClass, Long> {
    boolean existsByCode(String code);

    boolean existsByCodeAndAircraftId(String code, Long aircraftId);

    boolean existsByCodeAndAircraftIdAndIdNot(String code, Long aircraftId, Long id);

    List<CabinClass> findByAircraftId(Long aircraftId);

    CabinClass findByAircraftIdAndName(Long aircraftId, CabinClassType name);
}
