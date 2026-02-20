package com.example.payload.response;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.example.embeddable.ContactInfo;
import com.example.enums.BookingStatus;
import com.example.enums.PaymentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponse {

    private Long id;
    private String bookingReference;

    private Long userId;
    private String userName;
    private String userEmail;

    private Long flightId;
    private String flightNumber;
    private String flightName;
    private String departureAirport;
    private String arrivalAirport;
    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;

    private BookingStatus status;
    private Instant bookingDate;
    private Instant lastModified;

    private List<PassengerResponse> passengers;
    private List<SeatInstanceResponse> seatInstances;
    private PaymentLinkResponse payment;
    private List<FlightCabinAncillaryResponse> ancillaries;
    private List<FlightMealResponse> meals;
    private List<TicketResponse> tickets;

    // Payment details
    private PaymentStatus paymentStatus;
    private String paymentLink;

    private Long fareId;
    private String fareName;
    private Double fareBaseFare;
    private Double fareTaxesAndFees;
    private Double fareAirlineFees;

    private Integer totalPassengers;
    private Double totalAmount;

    // Flight duration and other derived information
    private String flightDuration;
    private Boolean isUpcoming;
    private Boolean isPast;

    // Contact information
    private ContactInfo contactInfo;
}
