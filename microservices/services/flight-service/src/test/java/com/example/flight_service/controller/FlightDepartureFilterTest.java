package com.example.flight_service.controller;

import com.example.enums.FlightStatus;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightInstance;
import com.example.flight_service.repository.FlightInstanceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(FlightDepartureFilterTest.Config.class)
@Transactional
class FlightDepartureFilterTest {
    @Autowired EntityManager entityManager;
    @Autowired FlightInstanceRepository instances;

    @Test void filtersOnlyRequestedIdsWithExclusiveUpcomingAndInclusivePastBoundary() throws Exception {
        var boundary = LocalDateTime.of(2026, 10, 19, 12, 0);
        var past = insert(boundary.minusSeconds(1));
        var exact = insert(boundary);
        var future = insert(boundary.plusSeconds(1));
        insert(boundary.plusDays(1));
        var ids = java.util.List.of(past.getId(), exact.getId(), future.getId());
        assertEquals(java.util.List.of(future.getId()), instances.findIdsByDepartureWindow(ids, boundary, true));
        assertEquals(java.util.Set.of(past.getId(), exact.getId()),
                java.util.Set.copyOf(instances.findIdsByDepartureWindow(ids, boundary, false)));
        var mvc = MockMvcBuilders.standaloneSetup(new InternalFlightDepartureController(instances)).build();
        mvc.perform(post("/internal/flight-instances/departure-filter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":%s,\"boundary\":\"2026-10-19T12:00:00\",\"upcoming\":true}".formatted(ids)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(future.getId()));
    }

    @Test void endpointRejectsUnboundedOrInvalidRequests() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InternalFlightDepartureController(instances)).build();
        for (String json : java.util.List.of("{\"upcoming\":true}", "{\"ids\":[],\"boundary\":null,\"upcoming\":true}",
                "{\"ids\":[null],\"boundary\":\"2026-10-19T12:00:00\",\"upcoming\":true}",
                "{\"ids\":%s,\"boundary\":\"2026-10-19T12:00:00\",\"upcoming\":true}".formatted(java.util.Collections.nCopies(101, 1)))) {
            mvc.perform(post("/internal/flight-instances/departure-filter").contentType(MediaType.APPLICATION_JSON)
                    .content(json)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/flight-instances/departure-filter").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[],\"boundary\":\"2026-10-19T12:00:00\",\"upcoming\":true}"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    private FlightInstance insert(LocalDateTime departure) {
        var flight = Flight.builder().flightNumber(java.util.UUID.randomUUID().toString()).airlineId(1L)
                .aircraftId(1L).departureAirportId(1L).arrivalAirportId(2L)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        entityManager.persist(flight);
        return instances.saveAndFlush(FlightInstance.builder().flight(flight).airlineId(1L)
                .departureAirportId(1L).arrivalAirportId(2L).scheduleId(1L).totalSeats(10).availableSeats(10)
                .status(FlightStatus.SCHEDULED).departureDateTime(departure).build());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = FlightInstanceRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:departure-filter;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(FlightInstance.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
