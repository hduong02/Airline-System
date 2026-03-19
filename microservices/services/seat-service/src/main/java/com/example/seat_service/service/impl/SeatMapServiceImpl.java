package com.example.seat_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payload.request.SeatMapRequest;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.SeatMapResponse;
import com.example.seat_service.client.AirlineClient;
import com.example.seat_service.mapper.SeatMapMapper;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.model.SeatMap;
import com.example.seat_service.repository.CabinClassRepository;
import com.example.seat_service.repository.SeatMapRepository;
import com.example.seat_service.service.SeatMapService;
import com.example.seat_service.service.SeatService;

@Service
@RequiredArgsConstructor
@Transactional
public class
SeatMapServiceImpl implements SeatMapService {

    private final SeatMapRepository seatMapRepository;
    private final CabinClassRepository cabinClassRepository;
    private final SeatService seatService;
    private final AirlineClient airlineClient;

    @Override
    public SeatMapResponse createSeatMap(Long userId, SeatMapRequest request)
            throws Exception {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);
        
        CabinClass cabinClass = cabinClassRepository.findById(request.getCabinClassId())
                    .orElseThrow(() -> new Exception("Cabin class not found with id: "
                            + request.getCabinClassId()));

        if (seatMapRepository.existsByAirlineIdAndCabinClassIdAndName(
                airlineResponse.getId(),
                request.getCabinClassId(),
                request.getName())
        ) {
            throw new Exception("Seat map with name '" + request.getName()
                    + "' already exists for this airline and cabin class");
        }

        SeatMap seatMap = SeatMapMapper.toEntity(request, cabinClass);
        seatMap.setAirlineId(airlineResponse.getId());
        SeatMap savedSeatMap = seatMapRepository.save(seatMap);

        // generate seats for the seat map
        seatService.generateSeats(savedSeatMap.getId());

        return SeatMapMapper.toResponse(savedSeatMap);
    }


    @Override
    public SeatMapResponse getSeatMapById(Long id) throws Exception {
        SeatMap seatMap = seatMapRepository.findById(id)
                .orElseThrow(() -> new Exception("Seat map not found with id: " + id));
        return SeatMapMapper.toResponse(seatMap);
    }

    @Override
    public SeatMapResponse getSeatMapsByCabinClass(Long cabinClassId) {
        SeatMap seatMap = seatMapRepository.findByCabinClassId(cabinClassId);
        return SeatMapMapper.toResponse(seatMap);
    }

    @Override
    public SeatMapResponse updateSeatMap(Long id, SeatMapRequest request) throws Exception {
        SeatMap existing = seatMapRepository.findById(id)
                .orElseThrow(() -> new Exception("Seat map not found with id: " + id));

        SeatMapMapper.updateEntity(request, existing);
        SeatMap saved = seatMapRepository.save(existing);
        return SeatMapMapper.toResponse(saved);
    }

    @Override
    public void deleteSeatMap(Long id) throws Exception {
        if (!seatMapRepository.existsById(id)) {
            throw new Exception("Seat map not found with id: " + id);
        }
        seatMapRepository.deleteById(id);
    }
}