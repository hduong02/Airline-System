package com.example.seat_service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.enums.SeatAvailabilityStatus;
import com.example.event.FlightInstanceCreatedEvent;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.Seat;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.CabinClassRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.repository.SeatRepository;

import jakarta.transaction.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightInstanceEventConsumer {

    private final CabinClassRepository cabinClassRepository;
    private final SeatRepository seatRepository;
    private final FlightInstanceCabinRepository flightInstanceCabinRepository;
    private final SeatInstanceRepository seatInstanceRepository;

    @KafkaListener(topics = "flight-instance-created", groupId = "seat-service-group")
    @Transactional
    public void handleFlightInstanceCreated(FlightInstanceCreatedEvent event) {
        List<CabinClass> cabinClasses = cabinClassRepository.findByAircraftId(
                event.getAircraftId());

        for (CabinClass cabinClass : cabinClasses) {
            List<Seat> seats = cabinClass.getSeatMap() != null
                    ? seatRepository.findBySeatMapId(cabinClass.getSeatMap().getId())
                    : List.of();

            FlightInstanceCabin fic = FlightInstanceCabin.builder()
                    .flightInstanceId(event.getFlightInstanceId())
                    .cabinClass(cabinClass)
                    .totalSeats(seats.size())
                    .bookedSeats(0)
                    .build();

            FlightInstanceCabin savedFic = flightInstanceCabinRepository.save(fic);

            // Generate SeatInstances for each seat in this cabin
            List<SeatInstance> seatInstances = seats.stream()
                    .map(seat -> SeatInstance.builder()
                            .flightId(event.getFlightId())
                            .flightInstanceId(event.getFlightInstanceId())
                            .flightInstanceCabin(savedFic)
                            .seat(seat)
                            .status(SeatAvailabilityStatus.AVAILABLE)
                            .isAvailable(true)
                            .isBooked(false)
                            .premiumSurcharge(seat.getPremiumSurcharge())
                            .build())
                    .toList();

            seatInstanceRepository.saveAll(seatInstances);
        }
    }
}