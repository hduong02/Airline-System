package com.example.location_service.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.payload.request.CityRequest;
import com.example.payload.response.CityResponse;

public interface CityService {
    CityResponse createCity(CityRequest request) throws Exception;
    CityResponse getCityById(Long id) throws Exception;

    CityResponse updateCity(Long id, CityRequest request) throws Exception;
    void deleteCity(Long id) throws Exception;
    Page<CityResponse> getAllCities(Pageable pageable);

    Page<CityResponse> searchCities(String keyword, Pageable pageable);
    Page<CityResponse> getCitiesByCountryCode(String countryCode, Pageable pageable);

    boolean cityExists(String cityCode);
}
