package com.example.airline_service.repository;

import com.example.airline_service.model.Airline;
import com.example.enums.AirlineStatus;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(AirlinePaginationTest.Config.class)
@Transactional
class AirlinePaginationTest {
    @Autowired AirlineRepository airlines;

    @Test void recordsBeyondFirstHundredAreReachableWithStableTotals() {
        for (int i = 0; i < 105; i++) insert("Airline", "C" + i, AirlineStatus.ACTIVE);
        var page = airlines.findAll(AirlineSpecifications.matching(null, null), PageRequest.of(10, 10, Sort.by("name", "id")));
        assertEquals(5, page.getNumberOfElements());
        assertEquals(105, page.getTotalElements());
        assertEquals(11, page.getTotalPages());
        var previous = airlines.findAll(AirlineSpecifications.matching(null, null), PageRequest.of(9, 10, Sort.by("name", "id")));
        assertTrue(previous.getContent().getLast().getId() < page.getContent().getFirst().getId());
        var empty = airlines.findAll(AirlineSpecifications.matching(null, null), PageRequest.of(99, 10));
        assertTrue(empty.isEmpty());
        assertEquals(105, empty.getTotalElements());
    }

    @Test void searchAndStatusAreAppliedBeforePagingAndCount() {
        var first = insert("Needle Air", "AA", AirlineStatus.ACTIVE);
        var second = insert("Needle Other", "BB", AirlineStatus.ACTIVE);
        insert("Needle Inactive", "CC", AirlineStatus.INACTIVE);
        insert("Unrelated", "DD", AirlineStatus.ACTIVE);
        var page = airlines.findAll(AirlineSpecifications.matching("  nEeDlE  ", AirlineStatus.ACTIVE), PageRequest.of(1, 1, Sort.by("name", "id")));
        assertEquals(2, page.getTotalElements());
        assertEquals(List.of(second.getId()), page.map(Airline::getId).getContent());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(4, airlines.count(AirlineSpecifications.matching("Thailand", null)));
        assertEquals(1, airlines.count(AirlineSpecifications.matching("AA", null)));
        assertEquals(1, airlines.count(AirlineSpecifications.matching("ICAOBB", null)));
    }

    @Test void wildcardCharactersAreLiteral() {
        insert("100%_! Air", "AA", AirlineStatus.ACTIVE);
        insert("100 other", "BB", AirlineStatus.ACTIVE);
        assertEquals(1, airlines.count(AirlineSpecifications.matching("%_!", null)));
        assertEquals(0, airlines.count(AirlineSpecifications.matching("missing", null)));
    }

    private Airline insert(String name, String code, AirlineStatus status) {
        return airlines.saveAndFlush(Airline.builder().name(name).iataCode(code).icaoCode("ICAO" + code)
                .ownerId(1L).country("Thailand").status(status).createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AirlineRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:airline-pagination;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Airline.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
