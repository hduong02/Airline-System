package com.example.booking_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
import com.example.payload.response.FlightMealResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payload.response.SeatInstanceResponse;

import org.springframework.data.domain.Sort;

import java.util.*;
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

    @Override
    public PaymentInitiateResponse createBooking(BookingRequest request, Long userId)
            throws Exception {
        // Generate unique booking reference
        String bookingReference = generateBookingReference();

        // Create passenger entities
        Set<Passenger> passengers = new HashSet<>();
        for (PassengerRequest passengerRequest : request.getPassengers()) {
            Passenger passenger = passengerService
                    .createPassenger(passengerRequest, userId);
            passengers.add(passenger);
        }

        // Check if flight exists
        FlightResponse flightResponse = flightClient.getFlightById(request.getFlightId());

        // Create booking entity
        Booking booking = BookingMapper.toEntity(
                request, userId, passengers, bookingReference);
        
        // airline id from flightResponse
        booking.setAirlineId(flightResponse.getAirline().getId());

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

        // Generate tickets
        ticketService.generateTicketsForBooking(booking);

        // Calculate total amount
        int passengerCount = booking.getPassengers().size();
        Double fareTotal = fareIntegrationService.calculateFareTotal(
            booking.getFareId()) * passengerCount;
        Double seatPrice = seatClient.calculateSeatPrice(booking.getSeatInstanceIds());
        Double ancillaryPrice = ancillaryClient.calculateAncillariesPrice(
                booking.getAncillaryIds());
        Double mealPrice = ancillaryClient.calculateMealPrice(
                booking.getMealIds());

        Double totalPrice = fareTotal + seatPrice + ancillaryPrice + mealPrice;

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
    public BookingResponse updateBooking(Long id, BookingRequest request)
            throws Exception {
                return null;
    }

    @Override
    public BookingResponse getBookingById(Long id) throws Exception {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Booking not found with ID: " + id));
        return convertToBookingResponse(booking);
    }

    @Override
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

        return bookings.stream()
                .map(this::convertToBookingResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<BookingResponse> getBookingsByUser(Long userId) {
        return bookingRepository.findByUserId(userId).stream()
                .map(this::convertToBookingResponse)
                .collect(Collectors.toList());
    }

    @Override
    public BookingResponse cancelBooking(Long id) throws Exception {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Booking not found with ID: " + id));

        booking.setStatus(BookingStatus.CANCELLED);
        Booking updated = bookingRepository.save(booking);
        return convertToBookingResponse(updated);
    }

    @Override
    public void deleteBooking(Long id) throws Exception {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Booking not found with ID: " + id));
        bookingRepository.delete(booking);
    }

    private String generateBookingReference() {
        String reference;
        do {
            reference = "BK" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase();
        } while (bookingRepository.existsByBookingReference(reference));
        return reference;
    }

    private BookingResponse convertToBookingResponse(Booking booking) {
        List<FlightCabinAncillaryResponse> ancillaryResponses = new ArrayList<>();
        List<FlightMealResponse> mealResponses = new ArrayList<>();
        PaymentDto paymentDto = new PaymentDto();
        FareResponse fareResponse = new FareResponse();
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
