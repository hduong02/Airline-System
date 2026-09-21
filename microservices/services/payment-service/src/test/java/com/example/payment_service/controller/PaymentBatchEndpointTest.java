package com.example.payment_service.controller;

import com.example.enums.PaymentStatus;
import com.example.payment_service.model.Payment;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.impl.PaymentServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(PaymentBatchEndpointTest.Config.class)
class PaymentBatchEndpointTest {
    @Autowired private PaymentRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.saveAndFlush(payment(6L, 10L));
        repository.saveAndFlush(payment(7L, 20L));
        var service = new PaymentServiceImpl(repository, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(
                new PaymentController(service), new InternalPaymentController(service)).build();
    }

    @Test
    void customerCannotListForeignPayments() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Id", "20")
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void airlineOwnerCannotListAllPayments() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Id", "20")
                        .header("X-User-Roles", "ROLE_AIRLINE_OWNER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listingRequiresAdminRoleHeader() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Id", "20"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listingRejectsRoleContainingAdminAsSubstring() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Id", "20")
                        .header("X-User-Roles", "ROLE_SYSTEM_ADMIN_FAKE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListPaymentsAcrossCustomersWithPagination() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Id", "30")
                        .header("X-User-Roles", "ROLE_USER, ROLE_SYSTEM_ADMIN")
                        .param("size", "1").param("sortBy", "bookingId").param("sortDirection", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(10))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/payments").header("X-User-Id", "30")
                        .header("X-User-Roles", "ROLE_SYSTEM_ADMIN")
                        .param("size", "1").param("page", "1")
                        .param("sortBy", "bookingId").param("sortDirection", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(20));
    }

    @Test
    void customerCannotReadAnotherCustomersPaymentThroughPublicBatch() throws Exception {
        mvc.perform(post("/api/payments/batch/bookings").header("X-User-Id", "20")
                        .contentType(MediaType.APPLICATION_JSON).content("[6]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mixedBatchReturnsOnlyAuthenticatedCustomersPayments() throws Exception {
        mvc.perform(post("/api/payments/batch/bookings").header("X-User-Id", "20")
                        .contentType(MediaType.APPLICATION_JSON).content("[6,7,999,7]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.6").doesNotExist())
                .andExpect(jsonPath("$.7.userId").value(20))
                .andExpect(jsonPath("$.7.bookingId").value(7))
                .andExpect(jsonPath("$.999").doesNotExist());
    }

    @Test
    void batchRequiresAuthenticatedUserHeader() throws Exception {
        mvc.perform(post("/api/payments/batch/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content("[6]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyBatchReturnsEmptyMap() throws Exception {
        mvc.perform(post("/api/payments/batch/bookings").header("X-User-Id", "20")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void internalBatchSupportsBookingEnrichmentAcrossCustomers() throws Exception {
        mvc.perform(post("/internal/payments/batch/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content("[6,7]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.6.userId").value(10))
                .andExpect(jsonPath("$.7.userId").value(20));
    }

    private Payment payment(Long bookingId, Long userId) {
        return Payment.builder().bookingId(bookingId).userId(userId)
                .amount(26.0).status(PaymentStatus.SUCCESS).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = PaymentRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:payment-batch;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Payment.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
