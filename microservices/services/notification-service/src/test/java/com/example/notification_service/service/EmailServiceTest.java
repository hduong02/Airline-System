package com.example.notification_service.service;

import com.example.event.BookingConfirmedEvent;
import com.example.event.PassengerNotificationData;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private EmailService service;

    @BeforeEach
    void setUp() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        service = new EmailService(sender, engine);
        ReflectionTestUtils.setField(service, "fromEmail", "bookings@example.test");
        ReflectionTestUtils.setField(service, "fromName", "VNAirline");
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void bookingConfirmationRendersRealTemplateAndReachesMailSender() throws Exception {
        service.sendBookingConfirmation(booking());

        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        MimeMessage message = captured.getValue();
        assertEquals("traveler@example.test", message.getAllRecipients()[0].toString());
        assertEquals("Booking Confirmed | BK-EMAIL-TEST | HAN→SGN | 20 Oct 2026", message.getSubject());
        String html = htmlPart(message);
        assertNotNull(html);
        assertTrue(html.contains("BK-EMAIL-TEST"));
        assertTrue(html.contains("Test Airline"));
        assertTrue(html.contains("Alex Nguyen"));
        assertTrue(html.contains("TICKET-TEST-1"));
        assertTrue(html.contains("USD 26.00"));
        assertFalse(html.contains("th:text"));
    }

    @Test
    void missingOptionalBookingDetailsStillProduceConfirmation() throws Exception {
        BookingConfirmedEvent booking = booking();
        booking.setAirlineLogo(null);
        booking.setAirlineName(null);
        booking.setPassengers(null);
        booking.setDepartureDateTime(null);
        booking.setArrivalDateTime(null);
        booking.setBookingDate(null);
        booking.setPaidAt(null);
        booking.setBaseFare(null);
        booking.setTaxesAndFees(null);
        booking.setAncillaryFees(null);
        booking.setCheckinBaggagePieces(null);
        booking.setCheckinBaggageWeightPerPiece(null);
        booking.setFullRefund(null);
        booking.setPartialRefund(null);
        booking.setFreeDateChange(null);
        booking.setPriorityBoarding(null);
        booking.setLoungeAccess(null);
        booking.setComplimentaryMeals(null);

        service.sendBookingConfirmation(booking);

        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        String html = htmlPart(captured.getValue());
        assertNotNull(html);
        assertTrue(html.contains("BK-EMAIL-TEST"));
        assertTrue(html.contains("VNAirline"));
        assertTrue(html.contains("N/A"));
        assertTrue(html.contains("Non-Refundable"));
        assertFalse(html.contains("Passenger Details"));
        assertFalse(html.contains("alt=\"Airline Logo\""));
    }

    @ParameterizedTest
    @CsvSource({"true,false,Full Refund", "false,true,Partial Refund"})
    void refundBenefitsRenderForRefundableFares(boolean fullRefund, boolean partialRefund,
            String benefit) throws Exception {
        BookingConfirmedEvent booking = booking();
        booking.setFullRefund(fullRefund);
        booking.setPartialRefund(partialRefund);

        service.sendBookingConfirmation(booking);

        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        String html = htmlPart(captured.getValue());
        assertNotNull(html);
        assertTrue(html.contains(benefit));
        assertTrue(html.contains("Refund applicable"));
        assertFalse(html.contains("Non-Refundable"));
    }

    private BookingConfirmedEvent booking() {
        return BookingConfirmedEvent.builder()
                .bookingId(6L).bookingReference("BK-EMAIL-TEST")
                .userName("Alex Nguyen").contactEmail("traveler@example.test")
                .airlineName("Test Airline").airlineLogo("https://example.test/logo.png")
                .flightNumber("VN123").departureAirportCode("HAN").arrivalAirportCode("SGN")
                .departureDateTime(LocalDateTime.of(2026, 10, 20, 9, 30))
                .arrivalDateTime(LocalDateTime.of(2026, 10, 20, 11, 30))
                .bookingDate(LocalDateTime.of(2026, 10, 16, 0, 30))
                .paidAt(LocalDateTime.of(2026, 10, 16, 0, 35))
                .cabinClass("ECONOMY").currency("USD").baseFare(20.0).taxesAndFees(3.0)
                .ancillaryFees(3.0).totalAmount(26.0).paymentGateway("STRIPE")
                .transactionId("TXN-TEST").providerPaymentId("pi_test")
                .checkinBaggagePieces(1).checkinBaggageWeightPerPiece(23.0)
                .fullRefund(false).partialRefund(false).freeDateChange(false)
                .priorityBoarding(false).loungeAccess(false).complimentaryMeals(false)
                .passengers(List.of(PassengerNotificationData.builder()
                        .firstName("Alex").lastName("Nguyen").adult(true)
                        .ticketNumber("TICKET-TEST-1").build()))
                .build();
    }

    private String htmlPart(Part part) throws Exception {
        // Finalize MIME headers as the real JavaMailSender does before transport.
        if (part instanceof MimeMessage message) message.saveChanges();
        if (part.isMimeType("text/html")) return (String) part.getContent();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String html = htmlPart(multipart.getBodyPart(i));
                if (html != null) return html;
            }
        }
        return null;
    }
}
