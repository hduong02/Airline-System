package com.example.seat_service.service.impl;

import com.example.enums.SeatType;
import com.example.seat_service.model.*;
import com.example.seat_service.repository.SeatInstanceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SelectedSeatRepositoryTest {
    @Test
    void selectedLookupUsesRealJpaWithoutInitializingCabinSeatCollection() {
        var config = new LocalContainerEntityManagerFactoryBean();
        config.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:selected-seats;DB_CLOSE_DELAY=-1", "sa", ""));
        config.setPackagesToScan("com.example.seat_service.model");
        config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        config.afterPropertiesSet();
        EntityManagerFactory factory = config.getObject();
        try (EntityManager em = factory.createEntityManager()) {
            Number indexes = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                            + "WHERE INDEX_NAME = 'IDX_FLIGHT_INSTANCE_CABIN_FLIGHT_CLASS'", Long.class)
                    .getSingleResult();
            assertEquals(1, indexes.intValue());
            em.getTransaction().begin();
            var cabin = FlightInstanceCabin.builder().flightInstanceId(20L).totalSeats(108).build();
            em.persist(cabin);
            Long selected = null;
            for (int i = 0; i < 2; i++) {
                var seat = Seat.builder().seatNumber("12" + (char)('A' + i)).seatRow(12)
                        .columnLetter((char)('A' + i)).seatType(SeatType.WINDOW).build();
                em.persist(seat);
                var instance = SeatInstance.builder().flightId(10L).flightInstanceId(20L)
                        .seat(seat).flightInstanceCabin(cabin).build();
                em.persist(instance);
                if (i == 0) selected = instance.getId();
            }
            em.flush(); em.clear();
            SeatInstanceRepository repository = new JpaRepositoryFactory(em).getRepository(SeatInstanceRepository.class);
            var results = repository.findSelectedWithDetails(List.of(selected));
            assertEquals(1, results.size());
            assertEquals(selected, results.getFirst().getId());
            assertEquals("12A", results.getFirst().getSeat().getSeatNumber());
            assertFalse(Hibernate.isInitialized(results.getFirst().getFlightInstanceCabin().getSeats()));
            em.getTransaction().rollback();
        } finally { config.destroy(); }
    }
}
