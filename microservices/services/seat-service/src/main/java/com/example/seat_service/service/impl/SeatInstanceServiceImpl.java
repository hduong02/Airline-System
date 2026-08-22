package com.example.seat_service.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.enums.SeatAvailabilityStatus;
import com.example.seat_service.model.CancelledBooking;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.CancelledBookingRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.service.SeatInstanceService;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional
public class SeatInstanceServiceImpl implements SeatInstanceService {

    private final SeatInstanceRepository seatInstanceRepository;
    private final FlightInstanceCabinRepository flightInstanceCabinRepository;
    private final CancelledBookingRepository cancelledBookingRepository;

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
    public void reserveBookingSeats(Long bookingId, List<Long> seatInstanceIds) {
        if (seatInstanceIds == null || seatInstanceIds.isEmpty()
                || seatInstanceIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Seat instance IDs are required");
        }
        if (cancelledBookingRepository.existsById(bookingId)) {
            throw new IllegalStateException("Booking has already been cancelled: " + bookingId);
        }
        // Lock every requested seat in a stable order before changing any of them.
        List<SeatInstance> seats = new ArrayList<>();
        for (Long seatId : distinctSeatIds(seatInstanceIds)) {
            SeatInstance seat = seatInstanceRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalStateException("Seat instance not found: " + seatId));
            if (seat.getStatus() != SeatAvailabilityStatus.AVAILABLE
                    && !(seat.getStatus() == SeatAvailabilityStatus.RESERVED
                            && Objects.equals(seat.getBookingId(), bookingId))) {
                throw new IllegalStateException("Seat is unavailable: " + seatId);
            }
            seats.add(seat);
        }
        if (seats.isEmpty() || seats.size() != seatInstanceIds.size()) {
            throw new IllegalArgumentException("A distinct seat is required for each passenger");
        }

        Map<Long, Long> newSeatsByCabin = new LinkedHashMap<>();
        for (SeatInstance seat : seats) {
            if (seat.getStatus() == SeatAvailabilityStatus.AVAILABLE) {
                newSeatsByCabin.merge(seat.getFlightInstanceCabin().getId(), 1L, Long::sum);
            }
        }
        for (Long cabinId : new TreeSet<>(newSeatsByCabin.keySet())) {
            var cabin = flightInstanceCabinRepository.findByIdForUpdate(cabinId)
                    .orElseThrow(() -> new IllegalStateException("Cabin not found: " + cabinId));
            int count = newSeatsByCabin.get(cabinId).intValue();
            if (cabin.getBookedSeats() + count > cabin.getTotalSeats()) {
                throw new IllegalStateException("Cabin capacity has been reached");
            }
            cabin.setBookedSeats(cabin.getBookedSeats() + count);
        }
        for (SeatInstance seat : seats) {
            if (seat.getStatus() == SeatAvailabilityStatus.AVAILABLE) {
                seat.setStatus(SeatAvailabilityStatus.RESERVED);
                seat.setBooked(false);
                seat.setAvailable(false);
                seat.setBookingId(bookingId);
                seatInstanceRepository.save(seat);
            }
        }
    }

    @Override
    public void confirmBookingSeats(Long bookingId, List<Long> seatInstanceIds) {
        for (Long seatId : distinctSeatIds(seatInstanceIds)) {
            SeatInstance seat = seatInstanceRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalStateException("Seat instance not found: " + seatId));
            if (cancelledBookingRepository.existsById(bookingId)) {
                return;
            }
            if (seat.getStatus() == SeatAvailabilityStatus.BOOKED) {
                if (!Objects.equals(seat.getBookingId(), bookingId)) {
                    throw new IllegalStateException("Seat already belongs to another booking: " + seatId);
                }
                continue;
            }
            if (seat.getStatus() != SeatAvailabilityStatus.RESERVED
                    || !Objects.equals(seat.getBookingId(), bookingId)) {
                throw new IllegalStateException("Seat is unavailable: " + seatId);
            }
            seat.setStatus(SeatAvailabilityStatus.BOOKED);
            seat.setBooked(true);
            seat.setAvailable(false);
            seat.setBookingId(bookingId);
            seatInstanceRepository.save(seat);
        }
    }

    @Override
    public void releaseBookingSeats(Long bookingId, List<Long> seatInstanceIds) {
        if (!cancelledBookingRepository.existsById(bookingId)) {
            cancelledBookingRepository.saveAndFlush(new CancelledBooking(bookingId));
        }
        List<SeatInstance> ownedSeats = new ArrayList<>();
        for (Long seatId : distinctSeatIds(seatInstanceIds)) {
            SeatInstance seat = seatInstanceRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalStateException("Seat instance not found: " + seatId));
            if ((seat.getStatus() != SeatAvailabilityStatus.BOOKED
                    && seat.getStatus() != SeatAvailabilityStatus.RESERVED)
                    || !Objects.equals(seat.getBookingId(), bookingId)) {
                continue;
            }
            ownedSeats.add(seat);
        }
        Map<Long, Long> seatsByCabin = new LinkedHashMap<>();
        for (SeatInstance seat : ownedSeats) {
            seatsByCabin.merge(seat.getFlightInstanceCabin().getId(), 1L, Long::sum);
        }
        for (Long cabinId : new TreeSet<>(seatsByCabin.keySet())) {
            var cabin = flightInstanceCabinRepository.findByIdForUpdate(cabinId)
                    .orElseThrow(() -> new IllegalStateException("Cabin not found: " + cabinId));
            int count = seatsByCabin.get(cabinId).intValue();
            if (cabin.getBookedSeats() < count) {
                throw new IllegalStateException("Cabin booked seat count is too low");
            }
            cabin.setBookedSeats(cabin.getBookedSeats() - count);
        }
        for (SeatInstance seat : ownedSeats) {
            seat.setStatus(SeatAvailabilityStatus.AVAILABLE);
            seat.setBooked(false);
            seat.setAvailable(true);
            seat.setBookingId(null);
            seatInstanceRepository.save(seat);
        }
    }

    private TreeSet<Long> distinctSeatIds(List<Long> seatInstanceIds) {
        TreeSet<Long> ids = new TreeSet<>();
        if (seatInstanceIds != null) {
            seatInstanceIds.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        return ids;
    }
}
