package com.example.seat_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.enums.CabinClassType;
import com.example.payload.request.CabinClassRequest;
import com.example.payload.response.CabinClassResponse;
import com.example.seat_service.mapper.CabinClassMapper;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.repository.CabinClassRepository;
import com.example.seat_service.service.CabinClassService;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CabinClassServiceImpl implements CabinClassService {

    private final CabinClassRepository cabinClassRepository;

    @Override
    public CabinClassResponse createCabinClass(CabinClassRequest request) throws Exception {
        if (cabinClassRepository.existsByCodeAndAircraftId(request.getCode(),
                request.getAircraftId())) {
            throw new Exception("Cabin class with code '" + request.getCode()
                    + "' already exists for this aircraft");
        }

        CabinClass cabinClass = CabinClassMapper.toEntity(request);
        CabinClass saved = cabinClassRepository.save(cabinClass);
        return CabinClassMapper.toResponse(saved, null);
    }

    @Override
    public CabinClassResponse getCabinClassById(Long id) throws Exception {
        CabinClass cabinClass = cabinClassRepository.findById(id)
                .orElseThrow(() -> new Exception("Cabin class not found with id: " + id));
        return CabinClassMapper.toResponse(cabinClass, cabinClass.getSeatMap());
    }

    @Override
    public List<CabinClassResponse> getCabinClassesByAircraftId(Long aircraftId) {
        return cabinClassRepository.findByAircraftId(aircraftId).stream()
                .map(cc -> CabinClassMapper.toResponse(cc, cc.getSeatMap()))
                .collect(Collectors.toList());
    }

    @Override
    public CabinClassResponse getByAircraftIdAndName(Long aircraftId, CabinClassType name) {
        CabinClass cabinClass = cabinClassRepository
                .findByAircraftIdAndName(aircraftId, name);
        return CabinClassMapper.toResponse(cabinClass, null);
    }

    @Override
    public CabinClassResponse updateCabinClass(Long id, CabinClassRequest request) throws Exception {
        CabinClass existing = cabinClassRepository.findById(id)
                .orElseThrow(() -> new Exception("Cabin class not found with id: " + id));

        if (cabinClassRepository.existsByCodeAndAircraftIdAndIdNot(
                request.getCode().toUpperCase(), existing.getAircraftId(), id)) {
            throw new Exception("Cabin class with code '"
                    + request.getCode() + "' already exists for this aircraft");
        }

        CabinClassMapper.updateEntity(request, existing);
        CabinClass updated = cabinClassRepository.save(existing);
        return CabinClassMapper.toResponse(updated, updated.getSeatMap());
    }

    @Override
    public void deleteCabinClass(Long id) throws Exception {
        CabinClass cabinClass = cabinClassRepository.findById(id)
                .orElseThrow(() -> new Exception("Cabin class not found with id: " + id));
        cabinClassRepository.delete(cabinClass);
    }
}
