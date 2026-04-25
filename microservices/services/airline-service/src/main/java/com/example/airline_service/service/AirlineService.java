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
    AirlineResponse getAirlineByOwner(Long ownerId) throws Exception;
    AirlineResponse getAirlineById(Long id) throws Exception;
    Page<AirlineResponse> getAllAirlines(Pageable pageable);
    AirlineResponse updateAirline(AirlineRequest request, Long ownerId) throws Exception;
    void deleteAirline(Long id, Long ownerId) throws Exception;

    AirlineResponse changeStatusByAdmin(Long airlineId, AirlineStatus status) throws Exception;

    // ----- Dropdown -----
    List<AirlineDropdownItem> getAirlinesForDropdown();
}
