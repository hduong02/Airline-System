package com.example.booking_service.service.impl;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.AncillaryClient;
import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.client.SeatClient;
import com.example.booking_service.mapper.BookingMapper;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.service.BookingService;
import com.example.booking_service.service.PassengerService;
import com.example.booking_service.service.TicketService;
import com.example.booking_service.service.integration.FareIntegrationService;
import com.example.enums.BookingStatus;
import com.example.event.BookingCancelledEvent;
import com.example.enums.PaymentGateway;
import com.example.payload.dto.PaymentDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightCabinAncillaryResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightInstanceCabinResponse;
import com.example.payload.response.FlightMealResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.SeatInstanceResponse;

import org.springframework.data.domain.Sort;

import java.util.*;
import java.time.Instant;
import java.time.Duration;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final PassengerService passengerService;
    private final TicketService ticketService;
    private final FlightClient flightClient;
    private final SeatClient seatClient;
    private final AncillaryClient ancillaryClient;
    private final FareIntegrationService fareIntegrationService;
    private final PaymentClient paymentClient;
    private final AirlineClient airlineClient;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentInitiateResponse createBooking(BookingRequest request, Long userId)
            throws Exception {
        FlightInstanceCabinResponse cabin = validateCabinCapacity(request);
        BookingSelection selection = validateBookingSelection(request, cabin);

        // Generate unique booking reference
        String bookingReference = generateBookingReference();

        // Create passenger entities
        Set<Passenger> passengers = new HashSet<>();
        for (PassengerRequest passengerRequest : request.getPassengers()) {
            Passenger passenger = passengerService
                    .createPassenger(passengerRequest, userId);
            passengers.add(passenger);
        }

        // Create booking entity
        Booking booking = BookingMapper.toEntity(
                request, userId, passengers, bookingReference);
        
        // Airline ID from the validated flight
        booking.setAirlineId(selection.flight().getAirline().getId());

        // Set seat instance IDs from passenger requests
        List<Long> seatInstanceIds = request.getPassengers().stream()
                .map(PassengerRequest::getSeatInstanceId)
                .collect(Collectors.toList());
        booking.setSeatInstanceIds(seatInstanceIds);

        // Save booking
        booking = bookingRepository.save(booking);

        // Set booking reference on passengers
        for (Passenger passenger : passengers) {
            passenger.setBooking(booking);
        }

        // Calculate total amount
        int passengerCount = booking.getPassengers().size();
        Double fareTotal = fareIntegrationService.calculateFareTotal(
            selection.fare()) * passengerCount;
        Double seatPrice = seatClient.calculateSeatPrice(booking.getSeatInstanceIds());
        Double ancillaryPrice = ancillaryClient.calculateAncillariesPrice(
                booking.getAncillaryIds());
        Double mealPrice = ancillaryClient.calculateMealPrice(
                booking.getMealIds());

        Double totalPrice = fareTotal + seatPrice + ancillaryPrice + mealPrice;

        try {
            seatClient.reserveBookingSeats(booking.getId(), booking.getSeatInstanceIds());
        } catch (FeignException e) {
            if (e.status() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Selected seat is no longer available", e);
            }
            throw e;
        }
        booking.setHoldExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        Long reservedBookingId = booking.getId();
        List<Long> reservedSeatIds = List.copyOf(booking.getSeatInstanceIds());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            seatClient.releaseBookingSeats(reservedBookingId, reservedSeatIds);
                        } catch (Exception e) {
                            log.error("Failed to release seats for rolled back booking {}", reservedBookingId, e);
                        }
                    }
                }
            });
        }

        // Initiate payment
        PaymentInitiateRequest paymentRequest = PaymentInitiateRequest.builder()
                .userId(userId)
                .bookingId(booking.getId())
                .amount(totalPrice)
                .gateway(PaymentGateway.STRIPE)
                .description("Payment for booking reference: " + bookingReference)
                .build();
        
        return paymentClient.initiatePayment(paymentRequest, userId);
    }

    @Override
    @Transactional
    public BookingResponse updateBooking(Long id, BookingRequest request)
            throws Exception {
                return null;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long id, Long userId) throws Exception {
        Booking booking = findOwnedBooking(id, userId);
        return convertToBookingResponse(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> getAllBookingsByAirline(
            Long userId,
            String searchQuery,
            BookingStatus status,
            Long flightInstanceId,
            String sortDirection)
    {
        AirlineResponse airlineResponse = airlineClient.getAirlineByOwner(userId);
        
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ?
                Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, Booking::getBookingDate);

        List<Booking> bookings = bookingRepository.findByAirlineWithFilters(
                airlineResponse.getId(), searchQuery, status, flightInstanceId, sort);

        return convertToBookingResponses(bookings);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByUser(Long userId) {
        return convertToBookingResponses(bookingRepository.findByUserId(userId));
    }

    @Override
    @Transactional
    public BookingResponse cancelBooking(Long id, Long userId) throws Exception {
        Booking booking = bookingRepository.findOwnedByIdForUpdate(id, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Booking not found"));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Confirmed booking cannot be cancelled until refunds are supported");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed booking cannot be cancelled");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return convertToBookingResponse(booking);
        }
        ticketService.cancelTicketsForBooking(booking.getId());
        booking.setStatus(BookingStatus.CANCELLED);
        Booking updated = bookingRepository.save(booking);
        applicationEventPublisher.publishEvent(new BookingCancelledEvent(
                updated.getId(), updated.getSeatInstanceIds() == null
                        ? List.of() : new ArrayList<>(updated.getSeatInstanceIds())));
        return convertToBookingResponse(updated);
    }

    @Override
    @Transactional
    public void deleteBooking(Long id, Long userId) throws Exception {
        Booking booking = findOwnedBooking(id, userId);
        if (booking.getStatus() != BookingStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cancel the booking before deleting it");
        }
        bookingRepository.delete(booking);
    }

    private Booking findOwnedBooking(Long id, Long userId) {
        return bookingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private String generateBookingReference() {
        String reference;
        do {
            reference = "BK" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase();
        } while (bookingRepository.existsByBookingReference(reference));
        return reference;
    }

    private FlightInstanceCabinResponse validateCabinCapacity(BookingRequest request) {
        FlightInstanceCabinResponse cabin = seatClient.getFlightInstanceCabin(
                request.getFlightInstanceId(), request.getCabinClass());
        if (cabin == null) {
            throw new IllegalArgumentException("Selected cabin does not exist");
        }
        int requestedSeats = request.getPassengers().size();
        int availableSeats = Optional.ofNullable(cabin.getAvailableSeats()).orElse(0);

        if (requestedSeats > availableSeats) {
            throw new IllegalArgumentException(
                    "Requested " + requestedSeats + " seats, but only " + availableSeats
                            + " seats are available in the " + request.getCabinClass() + " cabin");
        }
        return cabin;
    }

    private BookingSelection validateBookingSelection(BookingRequest request,
            FlightInstanceCabinResponse cabin) {
        FlightResponse flight = flightClient.getFlightById(request.getFlightId());
        FlightInstanceResponse flightInstance = flightClient.getFlightInstanceById(
                request.getFlightInstanceId());
        if (flight == null || flightInstance == null
                || !Objects.equals(flightInstance.getFlightId(), request.getFlightId())) {
            throw new IllegalArgumentException("Flight instance does not belong to the selected flight");
        }

        FareResponse fare = fareIntegrationService.getFareById(request.getFareId());
        if (fare == null || !Objects.equals(fare.getFlightId(), request.getFlightId())
                || fare.getCabinClass() != request.getCabinClass()) {
            throw new IllegalArgumentException("Fare does not belong to the selected flight and cabin");
        }

        if (!Objects.equals(cabin.getFlightInstanceId(), request.getFlightInstanceId())
                || cabin.getCabinClassType() != request.getCabinClass()) {
            throw new IllegalArgumentException("Cabin does not belong to the selected flight instance");
        }

        List<Long> selectedSeatIds = request.getPassengers().stream()
                .map(PassengerRequest::getSeatInstanceId).toList();
        if (selectedSeatIds.contains(null)
                || new HashSet<>(selectedSeatIds).size() != selectedSeatIds.size()) {
            throw new IllegalArgumentException("Each passenger must select a different seat");
        }

        Map<Long, SeatInstanceResponse> cabinSeats = Optional.ofNullable(cabin.getSeats())
                .orElseGet(List::of).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SeatInstanceResponse::getId, seat -> seat, (first, second) -> first));
        for (Long seatId : selectedSeatIds) {
            SeatInstanceResponse seat = cabinSeats.get(seatId);
            if (seat == null || !Objects.equals(seat.getFlightId(), request.getFlightId())
                    || !Objects.equals(seat.getFlightInstanceId(), request.getFlightInstanceId())
                    || !Objects.equals(seat.getFlightCabinId(), cabin.getId())) {
                throw new IllegalArgumentException("Seat " + seatId + " does not belong to the selected flight instance and cabin");
            }
        }
        return new BookingSelection(flight, fare);
    }

    private record BookingSelection(FlightResponse flight, FareResponse fare) {}

    private BookingResponse convertToBookingResponse(Booking booking) {
        return convertToBookingResponses(List.of(booking)).getFirst();
    }

    private List<BookingResponse> convertToBookingResponses(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        List<Long> bookingIds = bookings.stream().map(Booking::getId).toList();
        List<Long> fareIds = bookings.stream().map(Booking::getFareId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PaymentDto> payments = Optional.ofNullable(
                paymentClient.getPaymentsByBookingIds(bookingIds)).orElseGet(Map::of);
        Map<Long, FareResponse> fares = fareIds.isEmpty() ? Map.of() : Optional.ofNullable(
                fareIntegrationService.getFaresByIds(fareIds)).orElseGet(Map::of);
        return bookings.stream()
                .map(booking -> convertToBookingResponse(booking,
                        payments.get(booking.getId()), booking.getFareId() == null
                                ? null : fares.get(booking.getFareId())))
                .toList();
    }

    private BookingResponse convertToBookingResponse(Booking booking,
            PaymentDto paymentDto, FareResponse fareResponse) {
        List<FlightCabinAncillaryResponse> ancillaryResponses = new ArrayList<>();
        List<FlightMealResponse> mealResponses = new ArrayList<>();
        FlightResponse flightResponse = new FlightResponse();
        List<SeatInstanceResponse> seatInstanceResponses = new ArrayList<>();
        FlightInstanceResponse flightInstanceResponse = new FlightInstanceResponse();

        return BookingMapper.toResponse(booking,
                paymentDto,
                fareResponse,
                flightResponse,
                flightInstanceResponse,
                ancillaryResponses,
                mealResponses,
                seatInstanceResponses);
    }
}
