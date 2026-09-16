package com.example.booking_service.repository;

import com.example.booking_service.model.Booking;
import com.example.enums.BookingStatus;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(BookingStatisticsRepositoryTest.Config.class)
@Transactional
class BookingStatisticsRepositoryTest {
    @Autowired BookingRepository bookings;
    @Autowired DataSource dataSource;

    @Test void queryLimitsRowsToOwnerAirlineAndHalfOpenDateRange() {
        Long included = insert(50L, "2026-01-01T00:00:00Z");
        insert(99L, "2026-01-01T00:00:00Z");
        insert(50L, "2025-12-31T23:59:59Z");
        insert(50L, "2026-02-01T00:00:00Z");
        var rows = bookings.findStatisticsRows(50L, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));
        assertEquals(1, rows.size());
        assertEquals(included, rows.getFirst().getId());
        assertEquals(1L, rows.getFirst().getFlightId());
        assertEquals(BookingStatus.CONFIRMED, rows.getFirst().getStatus());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), rows.getFirst().getBookingDate());
    }

    @Test void transactionBookingIdsAreScopedByAirlineAcrossDates() {
        Long old = insert(50L, "2020-01-01T00:00:00Z");
        insert(99L, "2026-01-01T00:00:00Z");
        Long recent = insert(50L, "2026-01-01T00:00:00Z");
        assertEquals(java.util.List.of(old, recent), bookings.findIdsByAirlineId(50L));
        assertTrue(bookings.findIdsByAirlineId(777L).isEmpty());
    }

    private Long insert(Long airline, String date) {
        var booking = bookings.saveAndFlush(Booking.builder().bookingReference(java.util.UUID.randomUUID().toString())
                .userId(1L).airlineId(airline).flightId(1L).flightInstanceId(1L).fareId(1L)
                .status(BookingStatus.CONFIRMED).build());
        new JdbcTemplate(dataSource).update("UPDATE booking SET bookingDate = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse(date)), booking.getId());
        return booking.getId();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BookingRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:booking-statistics;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
    }
}
