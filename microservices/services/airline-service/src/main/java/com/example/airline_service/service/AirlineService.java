package com.example.airline_service.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.enums.AirlineStatus;
import com.example.payload.request.AirlineRequest;
import com.example.payload.response.AirlineDropdownItem;
import com.example.payload.response.AirlineResponse;

import java.util.List;

public interface AirlineService {

    // ----- CRUD -----
    AirlineResponse createAirline(AirlineRequest request, Long ownerId);
    AirlineResponse getAirlineByOwner(Long ownerId);
    AirlineResponse getAirlineById(Long id);
    Page<AirlineResponse> getAllAirlines(Pageable pageable);
    AirlineResponse updateAirline(AirlineRequest request, Long ownerId);
    void deleteAirline(Long id, Long ownerId);

    AirlineResponse changeStatusByAdmin(Long airlineId, AirlineStatus status);

    // ----- Dropdown -----
    List<AirlineDropdownItem> getAirlinesForDropdown();
}
