package com.example.booking_service.service.impl;

import com.example.booking_service.client.*;
import com.example.booking_service.event.listener.PaymentEventListener;
import com.example.booking_service.event.publisher.BookingEventProducer;
import com.example.booking_service.model.Booking;
import com.example.booking_service.repository.*;
import com.example.booking_service.service.BookingService;
import com.example.booking_service.service.integration.FareIntegrationService;
import com.example.embeddable.ContactInfo;
import com.example.enums.*;
import com.example.event.PaymentCompletedEvent;
import com.example.payload.dto.PaymentDto;
import com.example.payload.dto.UserDto;
import com.example.payload.request.BookingRequest;
import com.example.payload.request.PassengerRequest;
import com.example.payload.request.PaymentInitiateRequest;
import com.example.payload.response.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real booking services, event handler, and JPA; only remote boundaries are mocked. */
@SpringJUnitConfig(BookingFlowIntegrationTest.Config.class)
class BookingFlowIntegrationTest {
    @Autowired private BookingService bookings;
    @Autowired private PaymentEventListener paymentEvents;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PassengerRepository passengerRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private FlightClient flights;
    @Autowired private SeatClient seats;
    @Autowired private AncillaryClient ancillaries;
    @Autowired private PricingClient pricing;
    @Autowired private PaymentClient payments;
    @Autowired private UserClient users;
    @Autowired private BookingEventProducer outgoingEvents;

    @BeforeEach
    void setUp() throws Exception {
        reset(flights, seats, ancillaries, pricing, payments, users, outgoingEvents);
        ticketRepository.deleteAll();
        bookingRepository.deleteAll();
        passengerRepository.deleteAll();

        when(flights.getFlightById(10L)).thenReturn(FlightResponse.builder().id(10L).flightNumber("VN123")
                .departureAirport(AirportResponse.builder().name("Noi Bai").detailedName("Noi Bai (HAN)")
                        .city(CityResponse.builder().name("Hanoi").build()).build())
                .arrivalAirport(AirportResponse.builder().name("Tan Son Nhat")
                        .city(CityResponse.builder().name("Ho Chi Minh City").build()).build())
                .airline(AirlineResponse.builder().id(50L).build()).build());
        when(flights.getFlightInstanceById(20L)).thenReturn(FlightInstanceResponse.builder()
                .id(20L).flightId(10L)
                .departureDateTime(java.time.LocalDateTime.of(2026, 10, 20, 9, 30))
                .arrivalDateTime(java.time.LocalDateTime.of(2026, 10, 20, 11, 30)).build());
        when(seats.getFlightInstanceCabin(20L, CabinClassType.ECONOMY))
                .thenReturn(FlightInstanceCabinResponse.builder().id(40L).flightInstanceId(20L)
                        .cabinClassType(CabinClassType.ECONOMY).availableSeats(1)
                        .seats(List.of(SeatInstanceResponse.builder().id(100L).seatNumber("12A").flightId(10L)
                                .flightInstanceId(20L).flightCabinId(40L).build())).build());
        FareResponse fare = FareResponse.builder().id(30L).flightId(10L)
                .cabinClass(CabinClassType.ECONOMY).baseFare(100.0)
                .taxesAndFees(20.0).airlineFees(5.0).build();
        when(pricing.getFareById(30L)).thenReturn(fare);
        when(pricing.getFaresByIds(List.of(30L))).thenReturn(Map.of(30L, fare));
        when(seats.calculateSeatPrice(List.of(100L))).thenReturn(10.0);
        when(ancillaries.calculateAncillariesPrice(List.of(60L))).thenReturn(15.0);
        when(ancillaries.calculateMealPrice(List.of(70L))).thenReturn(5.0);
        when(users.getUserById(1L)).thenReturn(UserDto.builder().id(1L).build());
        when(payments.initiatePayment(any(), eq(1L))).thenReturn(PaymentInitiateResponse.builder()
                .paymentId(9L).gateway(PaymentGateway.STRIPE).amount(155.0).success(true)
                .checkoutUrl("https://checkout.stripe.com/test").stripeCheckoutUrl("cs_test_fixture").build());
    }

    @Test
    void bookingIsPersistedThenPaymentConfirmsTicketAndHistory() throws Exception {
        PaymentInitiateResponse checkout = bookings.createBooking(request(), 1L);
        Long bookingId = onlyBookingId();
        BookingResponse pending = bookings.getBookingById(bookingId, 1L);
        assertTrue(checkout.getSuccess());
        assertEquals(BookingStatus.PENDING, pending.getStatus());
        assertEquals(1, pending.getPassengers().size());
        assertTrue(pending.getTickets().isEmpty());
        assertEquals(1, passengerRepository.count());
        assertTrue(bookingRepository.findById(bookingId).orElseThrow().getHoldExpiresAt().isAfter(Instant.now()));

        ArgumentCaptor<PaymentInitiateRequest> initiation = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        var order = inOrder(seats, payments);
        order.verify(seats).reserveBookingSeats(bookingId, List.of(100L));
        order.verify(payments).initiatePayment(initiation.capture(), eq(1L));
        assertEquals(bookingId, initiation.getValue().getBookingId());
        assertEquals(1L, initiation.getValue().getUserId());
        assertEquals(155.0, initiation.getValue().getAmount());

        completePayment(bookingId);
        BookingResponse confirmed = bookings.getBookingById(bookingId, 1L);
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(PaymentStatus.SUCCESS, confirmed.getPaymentStatus());
        assertEquals(155.0, confirmed.getTotalAmount());
        assertEquals(pending.getBookingReference(), confirmed.getBookingReference());
        assertEquals(1, confirmed.getTickets().size());
        assertEquals("VN123", confirmed.getFlightNumber());
        assertEquals("Noi Bai (HAN)", confirmed.getDepartureAirport());
        assertEquals("Tan Son Nhat", confirmed.getArrivalAirport());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 9, 30), confirmed.getDepartureTime());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 11, 30), confirmed.getArrivalTime());
        assertEquals(List.of("12A"), confirmed.getSeatInstances().stream()
                .map(SeatInstanceResponse::getSeatNumber).toList());
        assertEquals(100L, confirmed.getPassengers().getFirst().getSeatInstanceId());
        assertEquals("12A", bookings.getBookingsByUser(1L).getFirst().getSeatInstances().getFirst().getSeatNumber());
        assertEquals(TicketStatus.BOOKED, confirmed.getTickets().getFirst().getStatus());
        assertFalse(confirmed.getTickets().getFirst().getTicketNumber().isBlank());
        assertEquals(9L, bookingRepository.findById(bookingId).orElseThrow().getPaymentId());
        assertEquals(bookingId, bookings.getBookingsByUser(1L).getFirst().getId());
        assertTrue(bookings.getBookingsByUser(2L).isEmpty());
        assertThrows(ResponseStatusException.class, () -> bookings.getBookingById(bookingId, 2L));
        verify(outgoingEvents).sendBookingConfirmed(argThat(booking -> booking.getId().equals(bookingId)),
                any(PaymentCompletedEvent.class), any(), any(), any());
    }

    @Test
    void duplicatePaymentEventDoesNotIssueAnotherTicketOrConfirmation() throws Exception {
        bookings.createBooking(request(), 1L);
        Long bookingId = onlyBookingId();
        completePayment(bookingId);
        completePayment(bookingId);
        assertEquals(1, ticketRepository.count());
        assertEquals(1, bookings.getBookingById(bookingId, 1L).getTickets().size());
        verify(outgoingEvents, times(1)).sendBookingConfirmed(any(), any(), any(), any(), any());
    }

    @Test
    void paymentInitiationFailureRollsBackBookingAndPassengerAndReleasesSeat() throws Exception {
        when(payments.initiatePayment(any(), eq(1L))).thenThrow(new IllegalStateException("Payment unavailable"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bookings.createBooking(request(), 1L));
        assertEquals("Payment unavailable", failure.getMessage());
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        assertEquals(0, ticketRepository.count());
        ArgumentCaptor<Long> reservedId = ArgumentCaptor.forClass(Long.class);
        verify(seats).reserveBookingSeats(reservedId.capture(), eq(List.of(100L)));
        verify(seats).releaseBookingSeats(reservedId.getValue(), List.of(100L));
        verifyNoInteractions(outgoingEvents);
    }

    @Test
    void invalidSeatSelectionDoesNotPersistDataOrInitiatePayment() {
        BookingRequest invalid = request();
        invalid.getPassengers().getFirst().setSeatInstanceId(999L);
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(invalid, 1L));
        assertEquals(0, bookingRepository.count());
        assertEquals(0, passengerRepository.count());
        verify(seats, never()).reserveBookingSeats(anyLong(), anyList());
        verifyNoInteractions(payments, outgoingEvents);
    }

    private Long onlyBookingId() {
        assertEquals(1, bookingRepository.count());
        return bookingRepository.findAll().getFirst().getId();
    }

    private void completePayment(Long bookingId) {
        PaymentDto paid = new PaymentDto();
        paid.setStatus(PaymentStatus.SUCCESS);
        paid.setAmount(155.0);
        when(payments.getPaymentsByBookingIds(List.of(bookingId))).thenReturn(Map.of(bookingId, paid));
        // Invoke the real transactional listener; Kafka transport is outside this suite.
        paymentEvents.handlePaymentCompleted(PaymentCompletedEvent.builder().bookingId(bookingId)
                .paymentId(9L).userId(1L).amount(155.0).build());
    }

    private BookingRequest request() {
        return BookingRequest.builder().flightId(10L).flightInstanceId(20L).fareId(30L)
                .cabinClass(CabinClassType.ECONOMY).ancillaryIds(List.of(60L)).mealIds(List.of(70L))
                .contactInfo(ContactInfo.builder().email("booking@example.test").phone("+15555550123").build())
                .passengers(List.of(PassengerRequest.builder().firstName("Booking").lastName("Test")
                        .email("booking@example.test").dateOfBirth(LocalDate.of(1990, 1, 1))
                        .gender(Gender.MALE).seatInstanceId(100L).build())).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BookingRepository.class)
    @Import({BookingServiceImpl.class, PassengerServiceImpl.class, TicketServiceImpl.class,
            FareIntegrationService.class, PaymentEventListener.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:booking-flow;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Booking.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean FlightClient flightClient() { return mock(FlightClient.class); }
        @Bean SeatClient seatClient() { return mock(SeatClient.class); }
        @Bean AncillaryClient ancillaryClient() { return mock(AncillaryClient.class); }
        @Bean PricingClient pricingClient() { return mock(PricingClient.class); }
        @Bean PaymentClient paymentClient() { return mock(PaymentClient.class); }
        @Bean AirlineClient airlineClient() { return mock(AirlineClient.class); }
        @Bean UserClient userClient() { return mock(UserClient.class); }
        @Bean BookingEventProducer bookingEventProducer() { return mock(BookingEventProducer.class); }
    }
}
