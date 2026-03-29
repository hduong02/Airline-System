package com.example.seat_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.enums.SeatAvailabilityStatus;
import com.example.payload.response.SeatInstanceResponse;
import com.example.seat_service.mapper.SeatInstanceMapper;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.service.SeatInstanceService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatInstanceServiceImpl implements SeatInstanceService {

    private final SeatInstanceRepository seatInstanceRepository;

    @Override
    public Double calculateSeatPrice(List<Long> seatInstanceIds) {
        List<SeatInstance> seatInstances = seatInstanceRepository.findAllById(seatInstanceIds);
        double total = 0.0;
        for (SeatInstance si : seatInstances) {
            double seatPremium = si.getPremiumSurcharge() != null
                    ? si.getPremiumSurcharge()
                    : 0.0;
            total += seatPremium;
        }
        return total;
    }

    @Override
    public SeatInstanceResponse updateSeatInstanceStatus(long seatInstanceId,
            SeatAvailabilityStatus status) {
        SeatInstance seatInstance = seatInstanceRepository.findById(seatInstanceId)
                .orElse(null);

        if (seatInstance == null)
            return null;
        
        seatInstance.setStatus(status);
        SeatInstance updatedSeatInstance = seatInstanceRepository.save(seatInstance);
        return SeatInstanceMapper.toResponse(updatedSeatInstance);
    }
}
