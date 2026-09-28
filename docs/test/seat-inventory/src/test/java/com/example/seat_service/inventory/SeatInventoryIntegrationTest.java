package com.example.seat_service.inventory;

import com.example.enums.SeatAvailabilityStatus;
import com.example.enums.SeatType;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.Seat;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.CancelledBookingRepository;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.service.SeatInstanceService;
import com.example.seat_service.service.impl.SeatInstanceServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real repositories, production transaction annotations, and independent worker connections. */
@Timeout(90)
class SeatInventoryIntegrationTest {
    private LocalContainerEntityManagerFactoryBean config;
    private EntityManagerFactory factory;
    private EntityManager entityManager;
    private JpaTransactionManager transactionManager;
    private SeatInstanceRepository seats;
    private FlightInstanceCabinRepository cabins;
    private CancelledBookingRepository cancellations;
    private SeatInstanceService service;
    private final List<Long> seatIds = new ArrayList<>();

    @BeforeEach
    void openDatabase() {
        config = new LocalContainerEntityManagerFactoryBean();
        String url = System.getenv("SEAT_INVENTORY_TEST_JDBC_URL");
        if (url != null) {
            assertTrue(url.matches("jdbc:mysql://[^/]+/seat_inventory_test_[a-f0-9]{32}(\\?.*)?"),
                    "Use a disposable seat_inventory_test_<32 lowercase UUID hex digits> schema");
        }
        config.setDataSource(url == null
                ? new DriverManagerDataSource("jdbc:h2:mem:inventory-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000", "sa", "")
                : new DriverManagerDataSource(url, System.getenv("SEAT_INVENTORY_TEST_JDBC_USER"),
                        System.getenv("SEAT_INVENTORY_TEST_JDBC_PASSWORD")));
        config.setPackagesToScan("com.example.seat_service.model");
        config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        config.afterPropertiesSet();
        factory = config.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        transactionManager = new JpaTransactionManager(factory);
        var repositories = new JpaRepositoryFactory(entityManager);
        seats = repositories.getRepository(SeatInstanceRepository.class);
        cabins = repositories.getRepository(FlightInstanceCabinRepository.class);
        cancellations = repositories.getRepository(CancelledBookingRepository.class);
        service = transactionalService(seats);
    }

    @AfterEach
    void closeDatabase() {
        if (config != null) config.destroy();
    }

    @Test
    void competingBookingsForOneSeatHaveExactlyOneWinner() throws Exception {
        seed(1, 1);
        int attempts = attempts();
        var results = compete(attempts, i -> () -> reserve(100L + i, seatIds.getFirst()));
        assertEquals(1, results.stream().filter(Outcome::accepted).count());
        assertEquals(attempts - 1, results.stream().filter(o -> !o.accepted()).count());
        results.stream().filter(o -> !o.accepted()).forEach(o ->
                assertEquals("Seat is unavailable: " + seatIds.getFirst(), o.rejection()));
        assertInventory(1);
        assertEquals(results.stream().filter(Outcome::accepted).findFirst().orElseThrow().bookingId(),
                seats.findById(seatIds.getFirst()).orElseThrow().getBookingId());
        printResult("same seat", attempts, 1);
    }

    @Test
    void distinctSeatsCompetingForLimitedCapacityNeverOversell() throws Exception {
        int attempts = attempts();
        int capacity = Math.min(5, attempts - 1);
        seed(attempts, capacity);
        var results = compete(attempts, i -> () -> reserve(100L + i, seatIds.get(i)));
        assertEquals(capacity, results.stream().filter(Outcome::accepted).count());
        results.stream().filter(o -> !o.accepted()).forEach(o ->
                assertEquals("Cabin capacity has been reached", o.rejection()));
        assertInventory(capacity);
        printResult("cabin capacity", attempts, capacity);
    }

    @Test
    void concurrentDuplicateReservationRetriesConsumeCapacityOnce() throws Exception {
        seed(1, 1);
        var results = compete(attempts(), i -> () -> reserve(100L, seatIds.getFirst()));
        assertTrue(results.stream().allMatch(Outcome::accepted));
        assertInventory(1);
        service.confirmBookingSeats(100L, seatIds);
        service.confirmBookingSeats(100L, seatIds);
        assertInventory(1);
        assertEquals(SeatAvailabilityStatus.BOOKED, seats.findById(seatIds.getFirst()).orElseThrow().getStatus());
    }

    @Test
    void concurrentCancellationsOfDistinctBookingsReleaseEverySeatExactlyOnce() throws Exception {
        int count = attempts();
        seed(count, count);
        for (int i = 0; i < count; i++) service.reserveBookingSeats(100L + i, List.of(seatIds.get(i)));
        assertInventory(count);
        compete(count, i -> () -> {
            service.releaseBookingSeats(100L + i, List.of(seatIds.get(i)));
            return true;
        });
        assertInventory(0);
        assertEquals(count, cancellations.count());
    }

    @Test
    void cancellationRetriesAndLateMessagesCannotReacquireInventory() {
        seed(1, 1);
        service.reserveBookingSeats(100L, seatIds);
        service.confirmBookingSeats(100L, seatIds);
        service.releaseBookingSeats(100L, List.of(seatIds.getFirst(), seatIds.getFirst()));
        service.releaseBookingSeats(100L, seatIds);
        assertInventory(0);
        assertTrue(cancellations.existsById(100L));
        assertEquals(1, cancellations.count());
        assertEquals("Booking has already been cancelled: 100", assertThrows(IllegalStateException.class,
                () -> service.reserveBookingSeats(100L, seatIds)).getMessage());
        service.confirmBookingSeats(100L, seatIds);
        assertInventory(0);
        service.reserveBookingSeats(200L, seatIds);
        service.releaseBookingSeats(100L, seatIds);
        assertInventory(1);
        assertEquals(200L, seats.findById(seatIds.getFirst()).orElseThrow().getBookingId());
    }

    @Test
    void cancellationCommittedWhileReservationIsInFlightPreventsLateReservation() throws Exception {
        seed(1, 1);
        service.reserveBookingSeats(100L, seatIds);
        var atLock = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        // Delay only the repository boundary; the invoked repository and transaction are real.
        var delayedSeats = (SeatInstanceRepository) Proxy.newProxyInstance(
                SeatInstanceRepository.class.getClassLoader(), new Class<?>[]{SeatInstanceRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByIdForUpdate")) {
                        atLock.countDown();
                        await(cancelled);
                    }
                    try {
                        return method.invoke(seats, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        var delayedService = transactionalService(delayedSeats);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var late = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> delayedService.reserveBookingSeats(100L, seatIds)));
            await(atLock);
            service.releaseBookingSeats(100L, seatIds);
            cancelled.countDown();
            assertEquals("Booking has already been cancelled: 100", late.get(30, TimeUnit.SECONDS).getMessage());
        } finally {
            cancelled.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertInventory(0);
        assertTrue(cancellations.existsById(100L));
    }

    @Test
    void capacityFailureInSecondCabinRollsBackFirstCabinIncrement() {
        seed(2, 2);
        transaction(em -> {
            var full = FlightInstanceCabin.builder().flightInstanceId(21L).totalSeats(0).bookedSeats(0).build();
            em.persist(full);
            em.find(SeatInstance.class, seatIds.get(1)).setFlightInstanceCabin(full);
        });
        assertEquals("Cabin capacity has been reached", assertThrows(IllegalStateException.class,
                () -> service.reserveBookingSeats(100L, seatIds)).getMessage());
        assertInventory(0);
        assertTrue(cabins.findAll().stream().allMatch(c -> c.getBookedSeats() == 0));
    }

    @Test
    void failedCancellationRollsBackMarkerAndLeavesReservationIntact() {
        seed(1, 1);
        service.reserveBookingSeats(100L, seatIds);
        assertThrows(IllegalStateException.class, () ->
                service.releaseBookingSeats(100L, List.of(seatIds.getFirst(), Long.MAX_VALUE)));
        assertFalse(cancellations.existsById(100L));
        assertInventory(1);
        // The rolled-back marker must not block an otherwise valid retry.
        service.reserveBookingSeats(100L, seatIds);
        service.releaseBookingSeats(100L, seatIds);
        assertInventory(0);
    }

    @Test
    void duplicateSeatIdsAreRejectedWithoutChangingInventory() {
        seed(1, 1);
        assertThrows(IllegalArgumentException.class, () ->
                service.reserveBookingSeats(100L, List.of(seatIds.getFirst(), seatIds.getFirst())));
        assertInventory(0);
    }

    private SeatInstanceService transactionalService(SeatInstanceRepository repository) {
        var proxy = new ProxyFactory(new SeatInstanceServiceImpl(repository, cabins, cancellations));
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (SeatInstanceService) proxy.getProxy();
    }

    private void transaction(Consumer<EntityManager> action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.accept(entityManager));
    }

    private void seed(int count, int capacity) {
        transaction(em -> {
            var cabin = FlightInstanceCabin.builder().flightInstanceId(20L).totalSeats(capacity).bookedSeats(0).build();
            em.persist(cabin);
            for (int i = 0; i < count; i++) {
                var definition = Seat.builder().seatNumber((i + 1) + "A").seatRow(i + 1)
                        .columnLetter('A').seatType(SeatType.WINDOW).build();
                em.persist(definition);
                var seat = SeatInstance.builder().flightId(10L).flightInstanceId(20L)
                        .flightInstanceCabin(cabin).seat(definition).build();
                em.persist(seat);
                seatIds.add(seat.getId());
            }
        });
    }

    private record Outcome(long bookingId, boolean accepted, String rejection) {}

    private Outcome reserve(long bookingId, Long seatId) {
        try {
            service.reserveBookingSeats(bookingId, List.of(seatId));
            return new Outcome(bookingId, true, null);
        } catch (IllegalStateException rejection) {
            return new Outcome(bookingId, false, rejection.getMessage());
        }
    }

    private <T> List<T> compete(int count, java.util.function.IntFunction<Callable<T>> operation) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            var futures = IntStream.range(0, count).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                await(start);
                return operation.apply(i).call();
            })).toList();
            await(ready);
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private void assertInventory(int expectedOccupied) {
        transaction(em -> {
            var all = seats.findAll();
            long occupied = all.stream().filter(s -> s.getStatus() != SeatAvailabilityStatus.AVAILABLE).count();
            assertEquals(expectedOccupied, occupied);
            for (var cabin : cabins.findAll()) {
                long actual = all.stream().filter(s -> s.getFlightInstanceCabin().getId().equals(cabin.getId()))
                        .filter(s -> s.getStatus() != SeatAvailabilityStatus.AVAILABLE).count();
                assertEquals(actual, cabin.getBookedSeats().longValue(), "Counter must equal occupied inventory");
                assertTrue(cabin.getBookedSeats() >= 0 && cabin.getBookedSeats() <= cabin.getTotalSeats(),
                        "Zero oversold seats and no negative counters");
            }
            for (var seat : all) {
                boolean available = seat.getStatus() == SeatAvailabilityStatus.AVAILABLE;
                assertEquals(available, seat.isAvailable());
                assertEquals(seat.getStatus() == SeatAvailabilityStatus.BOOKED, seat.isBooked());
                if (available) assertNull(seat.getBookingId());
                else assertNotNull(seat.getBookingId());
            }
        });
    }

    private int attempts() {
        int count = Integer.getInteger("inventory.attempts", 32);
        assertTrue(count >= 2 && count <= 128, "inventory.attempts must be between 2 and 128");
        return count;
    }

    private void printResult(String scenario, int attempts, int winners) {
        System.out.printf("Seat inventory [%s]: attempts=%d, committed=%d, oversold=0%n", scenario, attempts, winners);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Worker did not reach the concurrency checkpoint");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
