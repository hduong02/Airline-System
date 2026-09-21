package com.example.seat_service.service.impl;

import com.example.enums.CabinClassType;
import com.example.seat_service.controller.BookingSeatSelectionController;
import com.example.seat_service.model.CabinClass;
import com.example.seat_service.model.FlightInstanceCabin;
import com.example.seat_service.model.SeatInstance;
import com.example.seat_service.repository.FlightInstanceCabinRepository;
import com.example.seat_service.repository.SeatInstanceRepository;
import com.example.seat_service.service.BookingSeatSelectionService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingSeatSelectionTest {
    @Test
    void returnsOnlySelectedScalarsInTwoQueriesAndReadsCapacityFresh() throws Exception {
        var config = new LocalContainerEntityManagerFactoryBean();
        config.setDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:selection-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        config.setPackagesToScan("com.example.seat_service.model");
        config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.generate_statistics", "true"));
        config.afterPropertiesSet();
        try (var em = config.getObject().createEntityManager()) {
            em.getTransaction().begin();
            var type = CabinClass.builder().name(CabinClassType.ECONOMY).code("Y").aircraftId(1L)
                    .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now()).build();
            em.persist(type);
            var cabin = FlightInstanceCabin.builder().flightInstanceId(20L).cabinClass(type)
                    .totalSeats(108).bookedSeats(7).build();
            em.persist(cabin);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 108; i++) {
                var seat = SeatInstance.builder().flightId(10L).flightInstanceId(20L)
                        .flightInstanceCabin(cabin).premiumSurcharge(i == 0 ? null : 2.5).build();
                em.persist(seat);
                ids.add(seat.getId());
            }
            em.getTransaction().commit();
            em.clear();
            var stats = config.getObject().unwrap(SessionFactory.class).getStatistics();
            var service = service(em);
            stats.clear();

            var selected = service.getSelection(20L, CabinClassType.ECONOMY, ids.subList(0, 2));

            assertEquals(101, selected.getAvailableSeats());
            assertEquals(2, selected.getSeats().size());
            assertEquals(ids.subList(0, 2), selected.getSeats().stream().map(s -> s.id()).sorted().toList());
            assertTrue(selected.getSeats().stream().allMatch(s -> s.flightId().equals(10L)
                    && s.flightInstanceId().equals(20L) && s.flightCabinId().equals(cabin.getId())));
            assertEquals(2.5, selected.getSeats().stream().mapToDouble(s -> s.price() == null ? 0 : s.price()).sum());
            assertEquals(2, stats.getQueryExecutionCount());
            assertEquals(0, stats.getEntityLoadCount(), "Validation must not hydrate seat/cabin/map entities");

            em.getTransaction().begin();
            em.createQuery("UPDATE FlightInstanceCabin c SET c.bookedSeats = 8 WHERE c.id = :id")
                    .setParameter("id", cabin.getId()).executeUpdate();
            em.getTransaction().commit();
            assertEquals(100, service.getSelection(20L, CabinClassType.ECONOMY, List.of(ids.getFirst()))
                    .getAvailableSeats());
            assertEquals(0, service.getSelection(20L, CabinClassType.ECONOMY, List.of(Long.MAX_VALUE))
                    .getSeats().size(), "Missing IDs must remain detectable by booking validation");

            var mvc = MockMvcBuilders.standaloneSetup(new BookingSeatSelectionController(service)).build();
            var response = mvc.perform(post("/internal/booking-seat-selections/20/ECONOMY")
                            .contentType("application/json").content("[" + ids.getFirst() + "]"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.availableSeats").value(100))
                    .andExpect(jsonPath("$.seats.length()").value(1))
                    .andExpect(jsonPath("$.seats[0].flightId").value(10))
                    .andExpect(jsonPath("$.seatMap").doesNotExist())
                    .andExpect(jsonPath("$.cabinClass").doesNotExist()).andReturn().getResponse();
            assertTrue(response.getContentAsByteArray().length < 512, "One-seat payload must remain compact");
            System.out.println("Compact booking validation: " + response.getContentAsByteArray().length
                    + " response bytes; 2 scalar queries; 0 entities loaded");
            mvc.perform(post("/internal/booking-seat-selections/20/ECONOMY")
                    .contentType("application/json").content("[]")).andExpect(status().isBadRequest());
            mvc.perform(post("/internal/booking-seat-selections/21/ECONOMY")
                    .contentType("application/json").content("[1]")).andExpect(status().isNotFound());
        } finally {
            config.destroy();
        }
    }

    @Test
    void invalidIdsFailBeforeAnyDatabaseWork() {
        var service = new BookingSeatSelectionService(null, null);
        for (List<Long> ids : List.of(List.<Long>of(), List.of(1L, 1L), java.util.Collections.nCopies(501, 1L))) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.getSelection(20L, CabinClassType.ECONOMY, ids)).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.getSelection(20L, CabinClassType.ECONOMY, java.util.Arrays.asList(1L, null)))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.getSelection(20L, CabinClassType.ECONOMY, null)).getStatusCode().value());
    }

    private BookingSeatSelectionService service(EntityManager em) {
        var factory = new JpaRepositoryFactory(em);
        return new BookingSeatSelectionService(factory.getRepository(FlightInstanceCabinRepository.class),
                factory.getRepository(SeatInstanceRepository.class));
    }
}
