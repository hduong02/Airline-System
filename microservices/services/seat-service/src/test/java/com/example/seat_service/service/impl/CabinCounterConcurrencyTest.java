package com.example.seat_service.service.impl;

import com.example.enums.SeatAvailabilityStatus;
import com.example.enums.SeatType;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.Seat;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.CancelledBookingRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Two real transactions retain an eager cabin snapshot before either writes. */
class CabinCounterConcurrencyTest {
    private LocalContainerEntityManagerFactoryBean config;
    private EntityManagerFactory factory;
    private Long cabinId;
    private final Long[] seatIds = new Long[2];

    @BeforeEach
    void openDatabase() {
        config = new LocalContainerEntityManagerFactoryBean();
        String url = System.getenv("CABIN_COUNTER_TEST_JDBC_URL");
        if (url != null) {
            assertTrue(url.matches("jdbc:mysql://[^/]+/counter_test_[a-f0-9]{32}(\\?.*)?"),
                    "MySQL verification requires a disposable counter_test_<UUID> schema");
        }
        config.setDataSource(url == null ? new DriverManagerDataSource(
                "jdbc:h2:mem:cabin-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")
                : new DriverManagerDataSource(url, System.getenv("CABIN_COUNTER_TEST_JDBC_USER"),
                        System.getenv("CABIN_COUNTER_TEST_JDBC_PASSWORD")));
        config.setPackagesToScan("com.example.seat_service.model");
        config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        config.afterPropertiesSet();
        factory = config.getObject();
    }

    @AfterEach
    void closeDatabase() {
        config.destroy();
    }

    @Test
    void distinctSeatReservationsDoNotLoseAnIncrement() throws Exception {
        seed(0, 2, false, false);
        interleave(false, false);
        assertInventory(2, SeatAvailabilityStatus.RESERVED, SeatAvailabilityStatus.RESERVED);
    }

    @Test
    void distinctSeatCancellationsDoNotLoseADecrement() throws Exception {
        seed(2, 2, true, true);
        interleave(true, true);
        assertInventory(0, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE);
    }

    @Test
    void reservationAndCancellationPreserveTheNetCount() throws Exception {
        seed(1, 2, false, true);
        interleave(false, true);
        assertInventory(1, SeatAvailabilityStatus.RESERVED, SeatAvailabilityStatus.AVAILABLE);
    }

    @Test
    void cancellationAndReservationPreserveTheNetCount() throws Exception {
        seed(1, 2, true, false);
        interleave(true, false);
        assertInventory(1, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.RESERVED);
    }

    @Test
    void staleCapacityCannotAdmitASecondReservation() throws Exception {
        seed(0, 1, false, false);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> interleave(false, false));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Cabin capacity has been reached", failure.getCause().getMessage());
        assertInventory(1, SeatAvailabilityStatus.RESERVED, SeatAvailabilityStatus.AVAILABLE);
    }

    @Test
    void retriesAndConfirmationDoNotAdjustCapacityTwice() {
        seed(0, 2, false, false);
        inTransaction(em -> {
            var service = service(em);
            service.reserveBookingSeats(100L, List.of(seatIds[0]));
            service.reserveBookingSeats(100L, List.of(seatIds[0]));
            service.confirmBookingSeats(100L, List.of(seatIds[0]));
            service.confirmBookingSeats(100L, List.of(seatIds[0]));
        });
        assertInventory(1, SeatAvailabilityStatus.BOOKED, SeatAvailabilityStatus.AVAILABLE);
        inTransaction(em -> {
            var service = service(em);
            service.releaseBookingSeats(100L, List.of(seatIds[0], seatIds[0]));
            service.releaseBookingSeats(100L, List.of(seatIds[0]));
        });
        assertInventory(0, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE);
    }

    @Test
    void lateReservationCannotReacquireSeatsAfterCancellationCommits() throws Exception {
        seed(1, 2, true, false);
        var beforeSeatLock = new CountDownLatch(1);
        var cancellationCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var late = executor.submit(() -> inTransaction(em -> {
                    // Match the reservation endpoint's transaction isolation on MySQL as well as H2.
                    em.unwrap(org.hibernate.Session.class).doWork(connection ->
                            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED));
                    var repositories = new JpaRepositoryFactory(em);
                    var repository = repositories.getRepository(SeatInstanceRepository.class);
                    var inventory = (SeatInstanceRepository) java.lang.reflect.Proxy.newProxyInstance(
                            SeatInstanceRepository.class.getClassLoader(), new Class<?>[]{SeatInstanceRepository.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("findByIdForUpdate")) {
                                    beforeSeatLock.countDown();
                                    await(cancellationCommitted);
                                }
                                try {
                                    return method.invoke(repository, args);
                                } catch (java.lang.reflect.InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                    new SeatInstanceServiceImpl(inventory,
                            repositories.getRepository(FlightInstanceCabinRepository.class),
                            repositories.getRepository(CancelledBookingRepository.class))
                            .reserveBookingSeats(100L, List.of(seatIds[0]));
                }));
                if (!beforeSeatLock.await(10, TimeUnit.SECONDS)) {
                    late.get(1, TimeUnit.SECONDS);
                    fail("Reservation did not reach the seat lock");
                }
                inTransaction(em -> service(em).releaseBookingSeats(100L, List.of(seatIds[0])));
                cancellationCommitted.countDown();
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> late.get(5, TimeUnit.SECONDS));
                assertEquals("Booking has already been cancelled: 100", failure.getCause().getMessage());
            } finally {
                cancellationCommitted.countDown();
            }
        }
        assertInventory(0, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE);
    }

    @Test
    void failedSecondCabinRollsBackTheFirstCabinIncrementAndAllSeats() {
        seed(0, 2, false, false);
        inTransaction(em -> {
            var full = FlightInstanceCabin.builder().flightInstanceId(21L)
                    .totalSeats(0).bookedSeats(0).build();
            em.persist(full);
            em.find(SeatInstance.class, seatIds[1]).setFlightInstanceCabin(full);
        });
        assertThrows(IllegalStateException.class, () -> inTransaction(em ->
                service(em).reserveBookingSeats(100L, List.of(seatIds[0], seatIds[1]))));
        assertInventory(0, SeatAvailabilityStatus.AVAILABLE, SeatAvailabilityStatus.AVAILABLE);
        try (var em = factory.createEntityManager()) {
            assertEquals(0L, em.createQuery("SELECT SUM(c.bookedSeats) FROM FlightInstanceCabin c", Long.class)
                    .getSingleResult());
        }
    }

    private void inTransaction(java.util.function.Consumer<EntityManager> action) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                action.accept(em);
                em.getTransaction().commit();
            } catch (RuntimeException | Error failure) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw failure;
            }
        }
    }

    private void seed(int booked, int capacity, boolean firstReserved, boolean secondReserved) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var cabin = FlightInstanceCabin.builder().flightInstanceId(20L)
                    .totalSeats(capacity).bookedSeats(booked).build();
            em.persist(cabin);
            cabinId = cabin.getId();
            for (int i = 0; i < 2; i++) {
                boolean reserved = i == 0 ? firstReserved : secondReserved;
                var definition = Seat.builder().seatNumber("12" + (char) ('A' + i)).seatRow(12)
                        .columnLetter((char) ('A' + i)).seatType(SeatType.WINDOW).build();
                em.persist(definition);
                var seat = SeatInstance.builder().flightId(10L).flightInstanceId(20L)
                        .flightInstanceCabin(cabin).seat(definition)
                        .status(reserved ? SeatAvailabilityStatus.RESERVED : SeatAvailabilityStatus.AVAILABLE)
                        .bookingId(reserved ? 100L + i : null).isAvailable(!reserved).build();
                em.persist(seat);
                seatIds[i] = seat.getId();
            }
            em.getTransaction().commit();
        }
    }

    private void interleave(boolean releaseFirst, boolean releaseSecond) throws Exception {
        var preloaded = new CountDownLatch(2);
        var firstCommitted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                try {
                    mutate(0, releaseFirst, preloaded, null);
                } finally {
                    firstCommitted.countDown();
                }
            });
            var second = executor.submit(() -> mutate(1, releaseSecond, preloaded, firstCommitted));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
    }

    private void mutate(int index, boolean release, CountDownLatch preloaded, CountDownLatch previous) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                // Models the cabin already loaded through SeatInstance's eager association.
                em.find(SeatInstance.class, seatIds[index]).getFlightInstanceCabin().getBookedSeats();
                preloaded.countDown();
                await(preloaded);
                if (previous != null) await(previous);
                var service = service(em);
                if (release) service.releaseBookingSeats(100L + index, List.of(seatIds[index]));
                else service.reserveBookingSeats(100L + index, List.of(seatIds[index]));
                em.getTransaction().commit();
            } catch (RuntimeException | Error failure) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw failure;
            }
        }
    }

    private SeatInstanceServiceImpl service(EntityManager em) {
        var repositories = new JpaRepositoryFactory(em);
        return new SeatInstanceServiceImpl(repositories.getRepository(SeatInstanceRepository.class),
                repositories.getRepository(FlightInstanceCabinRepository.class),
                repositories.getRepository(CancelledBookingRepository.class));
    }

    private void assertInventory(int expected, SeatAvailabilityStatus first, SeatAvailabilityStatus second) {
        try (var em = factory.createEntityManager()) {
            assertEquals(expected, em.find(FlightInstanceCabin.class, cabinId).getBookedSeats());
            assertEquals(first, em.find(SeatInstance.class, seatIds[0]).getStatus());
            assertEquals(second, em.find(SeatInstance.class, seatIds[1]).getStatus());
            assertEquals((long) expected, em.createQuery(
                    "SELECT COUNT(s) FROM SeatInstance s WHERE s.flightInstanceCabin.id = :id "
                            + "AND s.status IN :statuses", Long.class)
                    .setParameter("id", cabinId)
                    .setParameter("statuses", List.of(SeatAvailabilityStatus.RESERVED, SeatAvailabilityStatus.BOOKED))
                    .getSingleResult());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent transaction did not reach checkpoint");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
