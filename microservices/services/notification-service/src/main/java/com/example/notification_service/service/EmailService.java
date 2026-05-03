package com.example.notification_service.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.example.event.BookingConfirmedEvent;

import java.io.UnsupportedEncodingException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${notification.from-email}")
    private String fromEmail;

    @Value("${notification.from-name}")
    private String fromName;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter
            .ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    public void sendBookingConfirmation(BookingConfirmedEvent event)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail, fromName);
        helper.setTo(event.getContactEmail());
        helper.setSubject(buildSubject(event));
        helper.setText(buildHtmlBody(event), true);

        mailSender.send(message);
    }

    private String buildSubject(BookingConfirmedEvent booking) {
        String depDate = booking.getDepartureDateTime() != null
                ? booking.getDepartureDateTime().format(DATE_FMT) : "";
        return String.format("Booking Confirmed | %s | %s\u2192%s | %s",
                booking.getBookingReference(),
                booking.getDepartureAirportCode(),
                booking.getArrivalAirportCode(),
                depDate);
    }

    private String buildHtmlBody(BookingConfirmedEvent booking) {
        Context context = new Context(Locale.ENGLISH);

        context.setVariable("event", booking);
        context.setVariable("passengerCount",
                booking.getPassengers() != null ? booking.getPassengers().size() : 1);

        // Formatted dates / times
        context.setVariable("depDate",booking.getDepartureDateTime() != null ?
                booking.getDepartureDateTime().format(DATE_FMT) : "N/A");
        context.setVariable("depTime",booking.getDepartureDateTime() != null ?
                booking.getDepartureDateTime().format(TIME_FMT) : "N/A");
        context.setVariable("arrDate",booking.getArrivalDateTime() != null ?
                booking.getArrivalDateTime().format(DATE_FMT) : "N/A");
        context.setVariable("arrTime",booking.getArrivalDateTime() != null ?
                booking.getArrivalDateTime().format(TIME_FMT) : "N/A");
        context.setVariable("paidAt",booking.getPaidAt() != null ?
                booking.getPaidAt().format(DT_FMT) : "N/A");
        context.setVariable("bookingDate",booking.getBookingDate() != null ?
                booking.getBookingDate().format(DT_FMT) : "N/A");

        double base = orZero(booking.getBaseFare());
        double taxes = orZero(booking.getTaxesAndFees());
        double seats = orZero(booking.getSeatFees());
        double ancillary = orZero(booking.getAncillaryFees());
        double meals = orZero(booking.getMealFees());
        double total = orZero(booking.getTotalAmount());

        // Formatted prices
        context.setVariable("baseFareTotal", format(base));
        context.setVariable("taxes", format(taxes));
        context.setVariable("seatFees", format(seats));
        context.setVariable("ancillaryFees", format(ancillary));
        context.setVariable("mealFees", format(meals));
        context.setVariable("totalAmount", format(total));

        // Formatted baggage labels
        context.setVariable("hasBaggage",booking.getCheckinBaggagePieces() != null
                || booking.getCabinBaggagePieces() != null);
        context.setVariable("checkinBaggage", baggageLabel(booking.getCheckinBaggagePieces(),
                booking.getCheckinBaggageWeightPerPiece()));
        context.setVariable("cabinBaggage", baggageLabel(booking.getCabinBaggagePieces(),
                booking.getCabinBaggageWeightPerPiece()));

        // Cabin class display name
        context.setVariable("cabinClassDisplay", cabinDisplayName(booking.getCabinClass()));

        return templateEngine.process("email/booking-confirmation", context);
    }

    private static String format(double v) {
        return String.format("%.2f", v);
    }

    private static double orZero(Double v) {
        return v != null ? v : 0.0;
    }

    /*
     * Both are provided → "2 × 23 kg"
     * Only pieces is provided → "2 piece(s)".
     * Only weightPer is provided → "23 kg"
     */
    private static String baggageLabel(Integer pieces, Double weightPer) {
        if (pieces == null && weightPer == null) return "Not included";
        if (pieces != null && weightPer != null)
            return pieces + " \u00d7 " + weightPer.intValue() + " kg";
        if (pieces != null) return pieces + " piece(s)";
        return weightPer.intValue() + " kg";
    }

    private static String cabinDisplayName(String cabinClass) {
        if (cabinClass == null) return "Economy";
        return switch (cabinClass) {
            case "ECONOMY" -> "Economy";
            case "PREMIUM_ECONOMY" -> "Premium Economy";
            case "BUSINESS" -> "Business";
            case "FIRST" -> "First Class";
            default -> cabinClass;
        };
    }
}