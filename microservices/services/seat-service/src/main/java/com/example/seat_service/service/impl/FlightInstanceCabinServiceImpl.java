package com.example.seat_service.service.impl;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.enums.SeatAvailabilityStatus;
import com.example.enums.SeatType;
import com.example.payload.request.FlightInstanceCabinRequest;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.seat_service.mapper.FlightInstanceCabinMapper;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.model.SeatMap;
import com.example.seat_service.repository.CabinClassRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.repository.SeatMapRepository;
import com.example.seat_service.service.FlightInstanceCabinService;


@Service
@RequiredArgsConstructor
@Transactional
public class FlightInstanceCabinServiceImpl implements FlightInstanceCabinService {

    private final FlightInstanceCabinRepository flightInstanceCabinRepository;
    private final CabinClassRepository cabinClassRepository;
    private final SeatMapRepository seatMapRepository;
    private final SeatInstanceRepository seatInstanceRepository;

    @Override
    public FlightInstanceCabinResponse createFlightInstanceCabin(
            FlightInstanceCabinRequest request) {
        CabinClass cabinClass = cabinClassRepository.findById(request.getCabinClassId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cabin class not found with id: " + request.getCabinClassId())
                );

        // Set total seats from the seat map
        SeatMap seatMap = seatMapRepository.findByCabinClassId(cabinClass.getId());

        if (seatMap == null) {
            throw new EntityNotFoundException(
                    "seat map not found with cabin class id: " + cabinClass.getId()
            );
        }

        if (seatMap.getSeats() == null || seatMap.getSeats().isEmpty()) {
            throw new EntityNotFoundException(
                    "No seats found in the seat map for cabin class id " + cabinClass.getId()
            );
        }
        int totalSeats = seatMap.getSeats().size();

        FlightInstanceCabin fic = FlightInstanceCabin.builder()
                .flightInstanceId(request.getFlightInstanceId())
                .cabinClass(cabinClass)
                .totalSeats(totalSeats)
                .bookedSeats(0)
                .build();

        FlightInstanceCabin savedFlightInstanceCabin = flightInstanceCabinRepository.save(fic);

        // generate SeatInstances
        List<SeatInstance> seatInstances = seatMap.getSeats().stream()
                .map(seat -> {
                        Double premiumSurcharge = getPremiumSurcharge(
                            seat.getSeatType(),
                            1000.0,
                            500.0
                        );
                        
                        return SeatInstance.builder()
                                .flightId(request.getFlightId())
                                .status(SeatAvailabilityStatus.AVAILABLE)
                                .flightInstanceId(request.getFlightInstanceId())
                                .flightInstanceCabin(savedFlightInstanceCabin)
                                .seat(seat)
                                .isAvailable(true)
                                .isBooked(false)
                                .premiumSurcharge(premiumSurcharge)
                                .build();
                })
                .toList();
        seatInstanceRepository.saveAll(seatInstances);
        savedFlightInstanceCabin.setSeats(seatInstances);
    
        return FlightInstanceCabinMapper.toResponse(savedFlightInstanceCabin);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightInstanceCabinResponse getFlightInstanceCabinById(Long id) {
        FlightInstanceCabin fic = flightInstanceCabinRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance cabin not found with id: " + id));
        return FlightInstanceCabinMapper.toResponse(fic);
    }

    
    @Override
    @Transactional(readOnly = true)
    public Page<FlightInstanceCabinResponse> getByFlightInstanceId(
            Long flightInstanceId, Pageable pageable) {
        return flightInstanceCabinRepository.findByFlightInstanceId(flightInstanceId, pageable)
                .map(FlightInstanceCabinMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightInstanceCabinResponse getByFlightInstanceIdAndCabinClassId(
            Long flightInstanceId, Long cabinClassId) {
        FlightInstanceCabin cabin =
                flightInstanceCabinRepository.findByFlightInstanceIdAndCabinClassId(
                        flightInstanceId,
                        cabinClassId
                );
        return FlightInstanceCabinMapper.toResponse(cabin);
    }

    @Override
    public FlightInstanceCabinResponse updateFlightInstanceCabin(
            Long id, FlightInstanceCabinRequest request) {
        FlightInstanceCabin existing = flightInstanceCabinRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance cabin not found with id: " + id));

        if (request.getCabinClassId() != null) {
            CabinClass cabinClass = cabinClassRepository.findById(request.getCabinClassId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Cabin class not found with id: " + request.getCabinClassId()));
            existing.setCabinClass(cabinClass);
        }

        FlightInstanceCabin updated = flightInstanceCabinRepository.save(existing);
        return FlightInstanceCabinMapper.toResponse(updated);
    }

    @Override
    public void deleteFlightInstanceCabin(Long id) {
        FlightInstanceCabin fic = flightInstanceCabinRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Flight instance cabin not found with id: " + id));
        flightInstanceCabinRepository.delete(fic);
    }

    private Double getPremiumSurcharge(SeatType seatType, Double windowSurcharge,
            Double aisleSurcharge) {
        if (seatType == null) return 0.0;

        return switch (seatType) {
            case WINDOW -> windowSurcharge != null ? windowSurcharge : 0.0;
            case AISLE -> aisleSurcharge != null ? aisleSurcharge : 0.0;
            default -> 0.0;
    };
    }
}
