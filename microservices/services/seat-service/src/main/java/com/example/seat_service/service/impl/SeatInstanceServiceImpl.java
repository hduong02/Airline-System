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
            if (seat.getStatus() != SeatAvailabilityStatus.AVAILABLE) {
                throw new IllegalStateException("Seat is unavailable: " + seatId);
            }
            var cabin = flightInstanceCabinRepository.findByIdForUpdate(
                    seat.getFlightInstanceCabin().getId())
                    .orElseThrow(() -> new IllegalStateException("Cabin not found for seat instance"));
            if (cabin.getBookedSeats() >= cabin.getTotalSeats()) {
                throw new IllegalStateException("Cabin capacity has been reached");
            }
            cabin.setBookedSeats(cabin.getBookedSeats() + 1);
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
        for (Long seatId : distinctSeatIds(seatInstanceIds)) {
            SeatInstance seat = seatInstanceRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalStateException("Seat instance not found: " + seatId));
            if (seat.getStatus() != SeatAvailabilityStatus.BOOKED
                    || (seat.getBookingId() != null && !Objects.equals(seat.getBookingId(), bookingId))) {
                continue;
            }
            var cabin = flightInstanceCabinRepository.findByIdForUpdate(
                    seat.getFlightInstanceCabin().getId())
                    .orElseThrow(() -> new IllegalStateException("Cabin not found for seat instance"));
            if (cabin.getBookedSeats() <= 0) {
                throw new IllegalStateException("Cabin booked seat count is already zero");
            }
            cabin.setBookedSeats(cabin.getBookedSeats() - 1);
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
