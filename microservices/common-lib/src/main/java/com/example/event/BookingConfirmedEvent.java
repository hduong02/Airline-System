package com.example.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingConfirmedEvent {

    // Booking
    private Long bookingId;
    private String bookingReference;
    private LocalDateTime confirmedAt;
    private LocalDateTime bookingDate;
    private String cabinClass;  // "ECONOMY", "BUSINESS", etc.
    private boolean flexibleTicket;

    // Contact
    private Long userId;
    private String userName;
    private String contactEmail;
    private String contactPhone;

    // Passengers
    private List<PassengerNotificationData> passengers;

    // Flight
    private Long flightInstanceId;
    private String flightNumber;
    private String airlineName;
    private String airlineLogo;
    private String aircraftModel;

    // Departure
    private String departureAirportCode;
    private String departureAirportName;
    private String departureCity;
    private String departureCountry;
    private LocalDateTime departureDateTime;

    // Arrival
    private String arrivalAirportCode;
    private String arrivalAirportName;
    private String arrivalCity;
    private String arrivalCountry;
    private LocalDateTime arrivalDateTime;
    private String flightDuration;

    // Payment
    private Double totalAmount;
    private String currency;
    private String transactionId;
    private String providerPaymentId;
    private String paymentGateway;
    private LocalDateTime paidAt;

    // Fare Breakdown
    private String fareName;
    private Double baseFare;
    private Double taxesAndFees;
    private Double seatFees;
    private Double ancillaryFees;
    private Double mealFees;

    // Baggage Allowance
    private Integer checkinBaggagePieces;
    private Double checkinBaggageWeightPerPiece;
    private Integer cabinBaggagePieces;
    private Double cabinBaggageWeightPerPiece;

    // Fare Benefits
    private Boolean freeDateChange;
    private Boolean partialRefund;
    private Boolean fullRefund;
    private Boolean priorityBoarding;
    private Boolean loungeAccess;
    private Boolean complimentaryMeals;

    private List<Long> seatInstanceIds;
}