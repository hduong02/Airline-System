package com.example.seat_service.service.impl;

import com.example.enums.SeatType;
import com.example.seat_service.model.*;
import com.example.seat_service.repository.*;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReservationConnectionTest {
    @Test
    void reservationLocksOnlyInventoryAndMeasuresConnectionUsage() {
        var metrics = new SimpleMeterRegistry();
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:reservation-connection;DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(1);
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(metrics));
            var config = new LocalContainerEntityManagerFactoryBean();
            config.setDataSource(pool);
            config.setPackagesToScan(SeatInstance.class.getPackageName());
            config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.generate_statistics", "true"));
            config.afterPropertiesSet();
            try {
                var factory = config.getObject();
                Long id;
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    var cabinClass = CabinClass.builder().name(com.example.enums.CabinClassType.ECONOMY)
                            .code("Y").aircraftId(1L).createdAt(java.time.Instant.now())
                            .updatedAt(java.time.Instant.now()).build();
                    em.persist(cabinClass);
                    var map = SeatMap.builder().name("Economy").airlineId(1L).cabinClass(cabinClass).build();
                    em.persist(map);
                    var cabin = FlightInstanceCabin.builder().flightInstanceId(20L).totalSeats(10)
                            .cabinClass(cabinClass).build();
                    em.persist(cabin);
                    var seat = Seat.builder().seatNumber("12A").seatRow(12).seatType(SeatType.WINDOW)
                            .cabinClass(cabinClass).seatMap(map).build();
                    em.persist(seat);
                    var instance = SeatInstance.builder().flightId(10L).flightInstanceCabin(cabin).seat(seat).build();
                    em.persist(instance);
                    id = instance.getId();
                    em.getTransaction().commit();
                }
                var stats = factory.unwrap(SessionFactory.class).getStatistics();
                var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
                var repos = new JpaRepositoryFactory(shared);
                var service = new SeatInstanceServiceImpl(repos.getRepository(SeatInstanceRepository.class),
                        repos.getRepository(FlightInstanceCabinRepository.class),
                        repos.getRepository(CancelledBookingRepository.class));
                var transaction = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.orm.jpa.JpaTransactionManager(factory));
                boolean[] detailsLoaded = {false};
                var usage = metrics.get("hikaricp.connections.usage").timer();
                long initialCount = 0;
                double initialTime = 0;
                // Warm the query compiler before collecting actual JDBC checkout durations.
                for (int i = 0; i < 101; i++) {
                    if (i == 1) {
                        stats.clear();
                        initialCount = usage.count();
                        initialTime = usage.totalTime(TimeUnit.MILLISECONDS);
                    }
                    transaction.executeWithoutResult(status -> {
                        service.reserveBookingSeats(100L, List.of(id));
                        var reserved = shared.find(SeatInstance.class, id);
                        detailsLoaded[0] |= Hibernate.isInitialized(reserved.getSeat())
                                || Hibernate.isInitialized(reserved.getFlightInstanceCabin());
                        shared.flush();
                        status.setRollbackOnly();
                    });
                }
                System.out.printf("Reservation measurement: statements/operation=%.1f, checkout count=%d, mean=%.3f ms%n",
                        stats.getPrepareStatementCount() / 100.0, usage.count() - initialCount,
                        (usage.totalTime(TimeUnit.MILLISECONDS) - initialTime) / (usage.count() - initialCount));
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                assertFalse(detailsLoaded[0], "Reservation must not load or lock seat definitions/cabin graphs");
                assertEquals(400, stats.getPrepareStatementCount(), "Four statements per one-seat reservation");
            } finally {
                config.destroy();
            }
        } finally { metrics.close(); }
    }
}
