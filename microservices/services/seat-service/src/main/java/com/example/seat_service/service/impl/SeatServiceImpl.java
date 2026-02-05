package com.example.seat_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.enums.SeatType;
import com.example.payload.response.SeatResponse;
import com.example.seat_service.mapper.SeatMapper;
import com.example.seat_service.model.Seat;
import com.example.seat_service.model.SeatMap;
import com.example.seat_service.repository.SeatMapRepository;
import com.example.seat_service.repository.SeatRepository;
import com.example.seat_service.service.SeatService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class SeatServiceImpl implements SeatService {

    private final SeatRepository seatRepository;
    private final SeatMapRepository seatMapRepository;

    public void generateSeats(Long seatMapId) throws Exception {
        boolean exists = seatRepository.existsBySeatMapId(seatMapId);
        if (exists) {
            throw new Exception("Seats already created for seat map id " + seatMapId);
        }

        SeatMap seatMap = seatMapRepository.findById(seatMapId).orElseThrow(
                ()->  new Exception("seat map not found")
        );

        int leftSeatsPerRow = seatMap.getLeftSeatsPerRow();
        int rightSeatsPerRow = seatMap.getRightSeatsPerRow();
        int rows = seatMap.getTotalRows();
        int seatsPerRow = leftSeatsPerRow + rightSeatsPerRow;

        List<Seat> seats = new ArrayList<>();

        for (int row = 1; row <= rows; row++) {
            for (int col = 0; col < seatsPerRow; col++) {
                String seatNum = row + getSeatLetter(col);
                SeatType type = getSeatType(col, leftSeatsPerRow, rightSeatsPerRow);

                seats.add(Seat.builder()
                        .seatNumber(seatNum)
                        .seatRow(row)
                        .columnLetter(getSeatLetter(col).charAt(0))
                        .seatType(type)
                        .seatMap(seatMap)
                        .build());
            }
        }
        seatRepository.saveAll(seats);
    }

    private String getSeatLetter(int col) {
        StringBuilder sb = new StringBuilder();
        while (col >= 0) {
            sb.insert(0, (char) ('A' + (col % 26)));
            col = col / 26 - 1;
        }
        return sb.toString();
    }

    private SeatType getSeatType(int seatIndex, int leftBlockSeats, int rightBlockSeats) {
        int totalSeats = leftBlockSeats + rightBlockSeats;

        // Windows
        if (seatIndex == 0 || seatIndex == totalSeats - 1) return SeatType.WINDOW;

        // Left aisle
        if (seatIndex == leftBlockSeats - 1) return SeatType.AISLE;

        // Right aisle
        if (seatIndex == leftBlockSeats) return SeatType.AISLE;

        // Everything else
        return SeatType.MIDDLE;
    }

    @Override
    public SeatResponse getSeatById(Long id) throws Exception {
        Seat seat = seatRepository.findById(id)
                .orElseThrow(() -> new Exception("Seat not found with id: " + id));
        return SeatMapper.toResponse(seat);
    }

    @Override
    public List<SeatResponse> getAll() {
        return seatRepository.findAll().stream()
                .map(SeatMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public SeatResponse updateSeat(Long id, com.example.seat_service.service.SeatRequest request) {
        throw new UnsupportedOperationException("Unimplemented method 'updateSeat'");
    }
}