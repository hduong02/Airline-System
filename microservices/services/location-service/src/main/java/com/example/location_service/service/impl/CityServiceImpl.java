package com.example.location_service.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.example.location_service.mapper.CityMapper;
import com.example.location_service.model.City;
import com.example.location_service.repository.CityRepository;
import com.example.location_service.service.CityService;
import com.example.payload.request.CityRequest;
import com.example.payload.response.CityResponse;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CityServiceImpl implements CityService {

    private final CityRepository cityRepository;

    // ---------- Core CRUD ----------

    @Override
    public CityResponse createCity(CityRequest request) throws Exception{
        if (cityRepository.existsByCityCode(request.getCityCode())) {
            throw new Exception("City with code " + request.getCityCode() + " already exists");
        }

        City city = CityMapper.toEntity(request);
        City savedCity = cityRepository.save(city);

        return CityMapper.toResponse(savedCity);
    }

    @Override
    @Cacheable(cacheNames = "cities", key = "#id")
    public CityResponse getCityById(Long id) throws Exception {
        City city = cityRepository.findById(id)
                .orElseThrow(() -> new Exception("City not found with id: " + id));
        return CityMapper.toResponse(city);
    }

    @Override
    @Caching(evict = {
        @CacheEvict(cacheNames = "cities", key = "#id"),
        @CacheEvict(cacheNames = "citiesByCode", allEntries = true),
    })
    public CityResponse updateCity(Long id, CityRequest request) throws Exception {
        City city = cityRepository.findById(id)
                .orElseThrow(() -> new Exception("City not found with id: " + id));

        if (cityRepository.existsByCityCodeAndIdNot(request.getCityCode(), id)) {
            throw new Exception("City with code " + request.getCityCode() + " already exists");
        }

        City updatedCity = cityRepository.save(CityMapper.updateEntity(city, request));

        return CityMapper.toResponse(updatedCity);
    }

    @Override
    @Caching(evict = {
        @CacheEvict(cacheNames = "cities", key = "#id"),
        @CacheEvict(cacheNames = "citiesByCode", allEntries = true),
    })
    public void deleteCity(Long id) throws Exception {
        City city = cityRepository.findById(id)
                .orElseThrow(() -> new Exception("City not found with id: " + id));
        cityRepository.delete(city);
    }

    @Override
    public Page<CityResponse> getAllCities(Pageable pageable) {
        return cityRepository.findAll(pageable).map(CityMapper::toResponse);
    }

    // ---------- Search & Query ----------

    @Override
    public Page<CityResponse> searchCities(String keyword, Pageable pageable) {
        return cityRepository.searchByKeyword(keyword, pageable)
                .map(CityMapper::toResponse);
    }

    @Override
    public Page<CityResponse> getCitiesByCountryCode(String countryCode, Pageable pageable) {
        return cityRepository.findByCountryCodeIgnoreCase(countryCode, pageable).map(CityMapper::toResponse);
    }

    // ---------- Validation ----------

    @Override
    public boolean cityExists(String cityCode) {
        return cityRepository.existsByCityCode(cityCode);
    }
}
