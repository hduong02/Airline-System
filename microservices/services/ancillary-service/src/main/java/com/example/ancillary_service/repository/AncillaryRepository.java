package com.example.ancillary_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ancillary_service.model.Ancillary;

import java.util.List;

public interface AncillaryRepository extends JpaRepository<Ancillary, Long> {

    List<Ancillary> findByAirlineId(Long airlineId);
}
