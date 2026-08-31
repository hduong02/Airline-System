package com.example.booking_service.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.booking_service.model.Booking;
import com.example.booking_service.model.Passenger;
import com.example.enums.BookingStatus;
import com.example.payload.dto.PaymentDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.response.BookingResponse;
import com.example.payload.response.FareResponse;
import com.example.payload.response.FlightCabinAncillaryResponse;
import com.example.payload.response.FlightInstanceResponse;
import com.example.payload.response.FlightMealResponse;
import com.example.payload.response.FlightResponse;
import com.example.payload.response.PassengerResponse;
import com.example.payload.response.SeatInstanceResponse;
import com.example.payload.response.TicketResponse;

public class BookingMapper {

    public static Booking toEntity(
            BookingRequest request,
            Long userId,
            Set<Passenger> passengers,
            String bookingReference
    ) {
        return Booking.builder()
                .bookingReference(bookingReference)
                .userId(userId)
                .flightId(request.getFlightId())
                .flightInstanceId(request.getFlightInstanceId())
                .fareId(request.getFareId())
                .contactInfo(request.getContactInfo())
                .passengers(passengers)
                .bookingDate(Instant.now())
                .lastModified(Instant.now())
                .cabinClass(request.getCabinClass())
                .ancillaryIds(request.getAncillaryIds())
                .mealIds(request.getMealIds())
                .status(BookingStatus.PENDING)
                .build();
    }

    public static BookingResponse toResponse(
            Booking booking,
            PaymentDto paymentDto,
            FareResponse fareResponse,
            FlightResponse flightResponse,
            FlightInstanceResponse flightInstanceResponse,
            List<FlightCabinAncillaryResponse> ancillaries,
            List<FlightMealResponse> meals,
            List<SeatInstanceResponse> seats)
        {
        List<PassengerResponse> passengerResponses = booking.getPassengers() != null ?
                booking.getPassengers().stream()
                        .map(PassengerMapper::toResponse)
                        .collect(Collectors.toList()) : null;

        List<TicketResponse> ticketResponses = booking.getTickets() != null ?
                booking.getTickets().stream()
                        .map(TicketMapper::toResponse)
                        .collect(Collectors.toList()) : null;


        return BookingResponse.builder()
                .id(booking.getId())
                .bookingReference(booking.getBookingReference())
                .userId(booking.getUserId())
//                flight details
                .flightId(booking.getFlightId())
                .flightNumber(flightResponse != null ? flightResponse.getFlightNumber() : null)
                .flightName(flightResponse != null && flightResponse.getArrivalAirport() != null && flightResponse.getDepartureAirport() != null
                        ? flightResponse.getDepartureAirport().getCity().getName() + " - " + flightResponse.getArrivalAirport().getCity().getName()
                        : null)
                .departureTime(flightInstanceResponse != null ? flightInstanceResponse.getDepartureDateTime() : null)
                .arrivalTime(flightInstanceResponse != null ? flightInstanceResponse.getArrivalDateTime() : null)
                .flightDuration(flightInstanceResponse != null ? flightInstanceResponse.getFormattedDuration() : null)
//                airport details
                .departureAirport(flightResponse != null && flightResponse.getDepartureAirport() != null ? flightResponse.getDepartureAirport().getDetailedName() : null)
                .arrivalAirport(flightResponse != null && flightResponse.getArrivalAirport() != null ? flightResponse.getArrivalAirport().getName() : null)
                .status(booking.getStatus())
                .bookingDate(booking.getBookingDate())
                .lastModified(booking.getLastModified())
                .passengers(passengerResponses)
                .tickets(ticketResponses)
                .totalPassengers(booking.getPassengers() != null ? booking.getPassengers().size() : 0)
                .ancillaries(ancillaries)
                .meals(meals)
                .seatInstances(seats)
                .paymentStatus(paymentDto != null ? paymentDto.getStatus() : null)
//                fare details
                .fareId(booking.getFareId())
                .fareName(fareResponse != null ? fareResponse.getName() : null)
                .fareBaseFare(fareResponse != null ? fareResponse.getBaseFare() : null)
                .fareTaxesAndFees(fareResponse != null ? fareResponse.getTaxesAndFees() : null)
                .fareAirlineFees(fareResponse != null ? fareResponse.getAirlineFees() : null)
                .totalAmount(paymentDto != null ? paymentDto.getAmount() : null)
//                contact information
                .contactInfo(booking.getContactInfo())

                .build();
    }
}
