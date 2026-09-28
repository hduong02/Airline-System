package com.example.booking_service.repository;

import com.example.booking_service.model.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TicketNumberIndexTest {
    @Test
    void ticketNumberLookupUsesIndexAndDuplicateInsertFails() {
        var config = new LocalContainerEntityManagerFactoryBean();
        config.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:ticket-index;DB_CLOSE_DELAY=-1", "sa", ""));
        config.setPackagesToScan(Ticket.class.getPackageName());
        config.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        config.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        config.afterPropertiesSet();
        try (var em = config.getObject().createEntityManager()) {
            String plan = em.createNativeQuery(
                    "EXPLAIN SELECT id FROM tickets WHERE ticket_number = 'TKT-test'", String.class)
                    .getSingleResult().toString();
            assertTrue(plan.toLowerCase().contains("uk_tickets_ticket_number"), plan);
            em.getTransaction().begin();
            em.persist(Ticket.builder().ticketNumber("TKT-test").build());
            em.getTransaction().commit();
            em.getTransaction().begin();
            try {
                assertThrows(RuntimeException.class, () -> {
                    em.persist(Ticket.builder().ticketNumber("TKT-test").build());
                    em.flush();
                });
            } finally {
                em.getTransaction().rollback();
            }
        } finally {
            config.destroy();
        }
    }
}
