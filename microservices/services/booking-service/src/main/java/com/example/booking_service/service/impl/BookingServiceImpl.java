package com.example.booking_service.service.impl;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.example.booking_service.client.AirlineClient;
import com.example.booking_service.client.AncillaryClient;
import com.example.booking_service.client.FlightClient;
import com.example.booking_service.client.PaymentClient;
import com.example.booking_service.client.SeatClient;
import com.example.booking_service.mapper.BookingMapper;
import com.example.booking_service.payload.BookingConfirmationStatusResponse;
import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.service.BookingService;
import com.example.booking_service.service.BookingSelection;
import com.example.booking_service.service.PassengerService;
import com.example.booking_service.service.TicketService;
import com.example.booking_service.service.integration.FareIntegrationService;
import com.example.enums.BookingStatus;
import com.example.event.BookingCancelledEvent;
import com.example.enums.PaymentGateway;
import com.example.payload.dto.PaymentDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.FlightDepartureFilterRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.AirlineResponse;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.BookingSeatSelectionResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightCabinAncillaryResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightMealResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.SeatInstanceResponse;

import org.springframework.data.domain.Sort;

import java.util.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;


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
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(readOnly = true)
    public Page<BookingResponse> getTravelerBookingPage(Long userId, String tab, int page, int size) {
        if (page < 0 || size < 1 || size > 100 ||
                !Set.of("all", "upcoming", "past", "cancelled").contains(tab)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use page >= 0, size between 1 and 100, and tab all, upcoming, past or cancelled");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "bookingDate", "id"));
        var now = LocalDateTime.now();
        Page<Booking> bookings;
        if ("all".equals(tab)) {
            bookings = bookingRepository.findByUserId(userId, pageable);
        } else if ("cancelled".equals(tab)) {
            bookings = bookingRepository.findByUserIdAndStatus(userId, BookingStatus.CANCELLED, pageable);
        } else {
            // Only IDs cross the service boundary; full booking details are enriched
            // after the database selects this page. Use live flight dates after reschedules.
            var candidates = bookingRepository.findHistoryFlightIds(userId, BookingStatus.CANCELLED);
            var matchingIds = new ArrayList<Long>();
            for (int start = 0; start < candidates.size(); start += 100) {
                matchingIds.addAll(flightClient.filterDepartures(
                        new FlightDepartureFilterRequest(
                                candidates.subList(start, Math.min(start + 100, candidates.size())),
                                now, "upcoming".equals(tab))));
            }
            bookings = matchingIds.isEmpty() ? Page.empty(pageable) :
                    bookingRepository.findHistoryByFlightIds(userId, BookingStatus.CANCELLED, matchingIds, pageable);
        }
        var responses = convertToBookingResponses(bookings.getContent());
        for (var response : responses) {
            boolean active = response.getStatus() != BookingStatus.CANCELLED && response.getDepartureTime() != null;
            response.setIsUpcoming(active && response.getDepartureTime().isAfter(now));
            response.setIsPast(active && !response.getDepartureTime().isAfter(now));
        }
        return new PageImpl<>(responses, pageable, bookings.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BookingResponse> getAirlineBookingPage(
            Long userId, String search, BookingStatus status, Long flightInstanceId,
            String sortDirection, int page, int size) {
        if (page < 0 || size < 1 || size > 100 ||
                !("ASC".equalsIgnoreCase(sortDirection) || "DESC".equalsIgnoreCase(sortDirection))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use page >= 0, size between 1 and 100, and sortDirection ASC or DESC");
        }
        var airline = airlineClient.getAirlineByOwner(userId);
        var direction = Sort.Direction.fromString(sortDirection);
        var pageable = PageRequest.of(page, size,
                Sort.by(direction, "bookingDate", "id"));
        var bookings = bookingRepository.findAirlinePage(airline.getId(),
                search == null || search.isBlank() ? null : search.trim(), status, flightInstanceId, pageable);
        return new PageImpl<>(
                convertToBookingResponses(bookings.getContent()), pageable, bookings.getTotalElements());
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentInitiateResponse createBooking(BookingRequest request, Long userId) throws Exception {
        BookingSelection selection = validateBooking(request);
        // Preserve legacy rollback semantics if payment initiation fails.
        return inBookingTransaction(() -> paymentClient.initiatePayment(
                persistBooking(request, userId, selection), userId));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentInitiateRequest prepareBooking(BookingRequest request, Long userId) throws Exception {
        BookingSelection selection = validateBooking(request);
        return inBookingTransaction(() -> persistBooking(request, userId, selection));
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public BookingSelection validateBooking(BookingRequest request) {
        BookingSeatSelectionResponse cabin = validateCabinCapacity(request);
        return validateBookingSelection(request, cabin);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public PaymentInitiateRequest prepareValidatedBooking(
            BookingRequest request, Long userId, BookingSelection selection) throws Exception {
        return persistBooking(request, userId, selection);
    }

    private <T> T inBookingTransaction(Callable<T> action) throws Exception {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                try {
                    return action.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedFailure(e);
                }
            });
        } catch (CheckedFailure e) {
            throw (Exception) e.getCause();
        }
    }

    private static class CheckedFailure extends RuntimeException {
        CheckedFailure(Exception cause) { super(cause); }
    }

    private PaymentInitiateRequest persistBooking(
            BookingRequest request, Long userId, BookingSelection selection) throws Exception {
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
        double seatPrice = selection.seatPrice();
        double ancillaryPrice = booking.getAncillaryIds() == null || booking.getAncillaryIds().isEmpty()
                ? 0.0 : ancillaryClient.calculateAncillariesPrice(booking.getAncillaryIds());
        double mealPrice = booking.getMealIds() == null || booking.getMealIds().isEmpty()
                ? 0.0 : ancillaryClient.calculateMealPrice(booking.getMealIds());

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
        
        return paymentRequest;
    }

    @Override
    @Transactional
    public BookingResponse updateBooking(Long id, BookingRequest request)
            throws Exception {
                return null;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentInitiateResponse resumeCheckout(Long id, Long userId) throws Exception {
        Booking booking = findOwnedBooking(id, userId);
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is no longer pending");
        }
        if (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking hold has expired");
        }
        PaymentDto payment = paymentClient.getPaymentsByBookingIds(List.of(id)).get(id);
        if (payment == null || payment.getStatus() != com.example.enums.PaymentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is no longer pending");
        }
        return paymentClient.initiatePayment(PaymentInitiateRequest.builder()
                .userId(userId).bookingId(id).amount(payment.getAmount()).gateway(payment.getGateway())
                .description("Payment for booking reference: " + booking.getBookingReference())
                .build(), userId);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long id, Long userId) throws Exception {
        Booking booking = findOwnedBooking(id, userId);
        return convertToBookingResponse(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingConfirmationStatusResponse getConfirmationStatus(Long id, Long userId) {
        var state = bookingRepository.findConfirmationStatus(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        boolean paymentReceived = state.getPaymentId() != null;
        boolean confirmed = state.getStatus() == BookingStatus.CONFIRMED && paymentReceived
                && state.getTicketIssued() && state.getTicketCount() > 0;
        return new BookingConfirmationStatusResponse(state.getBookingId(), state.getStatus(),
                paymentReceived, state.getTicketIssued(), state.getTicketCount(), confirmed);
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

    private BookingSeatSelectionResponse validateCabinCapacity(BookingRequest request) {
        List<Long> selectedSeatIds = request.getPassengers().stream()
                .map(PassengerRequest::getSeatInstanceId).toList();
        if (selectedSeatIds.contains(null) || new HashSet<>(selectedSeatIds).size() != selectedSeatIds.size()) {
            throw new IllegalArgumentException("Each passenger must select a different seat");
        }
        if (selectedSeatIds.isEmpty() || selectedSeatIds.size() > 500) {
            throw new IllegalArgumentException("Select between 1 and 500 seats");
        }
        BookingSeatSelectionResponse cabin = seatClient.getBookingSeatSelection(
                request.getFlightInstanceId(), request.getCabinClass(), selectedSeatIds);
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
            BookingSeatSelectionResponse cabin) {
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
        Map<Long, BookingSeatSelectionResponse.Seat> cabinSeats = Optional.ofNullable(cabin.getSeats())
                .orElseGet(List::of).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(BookingSeatSelectionResponse.Seat::id, seat -> seat, (first, second) -> first));
        for (Long seatId : selectedSeatIds) {
            BookingSeatSelectionResponse.Seat seat = cabinSeats.get(seatId);
            if (seat == null || !Objects.equals(seat.flightId(), request.getFlightId())
                    || !Objects.equals(seat.flightInstanceId(), request.getFlightInstanceId())
                    || !Objects.equals(seat.flightCabinId(), cabin.getId())) {
                throw new IllegalArgumentException("Seat " + seatId + " does not belong to the selected flight instance and cabin");
            }
        }
        double seatPrice = selectedSeatIds.stream().map(cabinSeats::get)
                .mapToDouble(seat -> seat.price() == null ? 0.0 : seat.price()).sum();
        return new BookingSelection(flight, fare, seatPrice);
    }

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
        Map<Long, FlightResponse> flights = new HashMap<>();
        Map<Long, FlightInstanceResponse> instances = new HashMap<>();
        List<Long> selectedSeatIds = bookings.stream()
                .filter(booking -> booking.getSeatInstanceIds() != null)
                .flatMap(booking -> booking.getSeatInstanceIds().stream()).distinct().toList();
        Map<Long, SeatInstanceResponse> selectedSeats = new HashMap<>();
        // Bound requests for large booking lists; never fetch an entire cabin for details.
        for (int offset = 0; offset < selectedSeatIds.size(); offset += 500) {
            List<SeatInstanceResponse> seats = seatClient.getSelectedSeats(
                    selectedSeatIds.subList(offset, Math.min(offset + 500, selectedSeatIds.size())));
            if (seats != null) seats.forEach(seat -> selectedSeats.put(seat.getId(), seat));
        }
        return bookings.stream()
                .map(booking -> convertToBookingResponse(booking,
                        payments.get(booking.getId()), booking.getFareId() == null
                                ? null : fares.get(booking.getFareId()),
                        booking.getFlightId() == null ? null : flights.computeIfAbsent(
                                booking.getFlightId(), flightClient::getFlightById),
                        booking.getFlightInstanceId() == null ? null : instances.computeIfAbsent(
                                booking.getFlightInstanceId(), flightClient::getFlightInstanceById), selectedSeats))
                .toList();
    }

    private BookingResponse convertToBookingResponse(Booking booking,
            PaymentDto paymentDto, FareResponse fareResponse,
            FlightResponse flightResponse, FlightInstanceResponse flightInstanceResponse,
            Map<Long, SeatInstanceResponse> selectedSeats) {
        List<FlightCabinAncillaryResponse> ancillaryResponses = new ArrayList<>();
        List<FlightMealResponse> mealResponses = new ArrayList<>();
        List<SeatInstanceResponse> seatInstanceResponses = List.of();
        if (booking.getFlightInstanceId() != null && booking.getSeatInstanceIds() != null
                && !booking.getSeatInstanceIds().isEmpty()) {
            seatInstanceResponses = booking.getSeatInstanceIds().stream()
                    .map(selectedSeats::get).filter(Objects::nonNull)
                    .filter(seat -> Objects.equals(seat.getFlightInstanceId(), booking.getFlightInstanceId()))
                    .toList();
        }

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
