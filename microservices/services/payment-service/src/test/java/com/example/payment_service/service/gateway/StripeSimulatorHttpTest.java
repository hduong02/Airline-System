package com.example.payment_service.service.gateway;

import com.stripe.Stripe;
import com.example.payload.dto.UserDto;
import com.example.payment_service.model.Payment;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class StripeSimulatorHttpTest {
    @AfterEach void reset() { Stripe.overrideApiBase("https://api.stripe.com"); Stripe.apiKey = null; }

    private StripeService service(String url, boolean testProfile) {
        var service = new StripeService();
        var env = new StandardEnvironment();
        if (testProfile) env.setActiveProfiles("load-test");
        ReflectionTestUtils.setField(service, "environment", env);
        ReflectionTestUtils.setField(service, "apiBaseUrl", url);
        ReflectionTestUtils.setField(service, "stripeApiKey", "sk_test_simulator");
        return service;
    }

    @Test void refusesRealStripeInLoadTestAndCustomHostOutsideLoadTest() {
        assertThrows(IllegalStateException.class, () -> service("https://api.stripe.com", true).init());
        assertThrows(IllegalStateException.class, () -> service("https://api.stripe.com/", true).init());
        assertThrows(IllegalStateException.class, () -> service("http://localhost:8080", false).init());
    }

    @Test void checkoutRetriesSendTheSameIdempotencyKeyAndParameters() throws Exception {
        var keys = new CopyOnWriteArrayList<String>();
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/checkout/sessions", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"cs_sim\",\"object\":\"checkout.session\",\"url\":\"https://example.invalid/checkout\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var service = service("http://127.0.0.1:" + server.getAddress().getPort(), true);
            ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://example.invalid");
            service.init();
            var payment = Payment.builder().id(9L).bookingId(1L).amount(100.0)
                    .transactionId("TXN_stable").createdAt(LocalDateTime.now()).build();
            var user = UserDto.builder().id(2L).email("test@example.invalid").build();
            assertEquals("cs_sim", service.createPaymentLink(user, payment).getPayment_link_id());
            assertEquals("cs_sim", service.createPaymentLink(user, payment).getPayment_link_id());
            assertEquals(java.util.List.of("checkout-TXN_stable", "checkout-TXN_stable"), keys);
            assertEquals(2, requests.size());
            assertEquals(requests.get(0), requests.get(1));
            payment.setCreatedAt(LocalDateTime.now().minusDays(1));
            assertThrows(IllegalStateException.class, () -> service.createPaymentLink(user, payment));
            assertEquals(2, requests.size(), "An old claim must not create a new session after key expiry");
        } finally { server.stop(0); }
    }

    @Test void sdkRetrievesPaidCheckoutOverHttpWithoutCallingStripe() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/checkout/sessions/cs_sim", exchange -> {
            assertEquals("Bearer sk_test_simulator", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"id\":\"cs_sim\",\"object\":\"checkout.session\",\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_sim\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var service = service("http://127.0.0.1:" + server.getAddress().getPort(), true);
            service.init();
            var state = service.fetchCheckoutState("cs_sim");
            assertEquals(StripeService.CheckoutOutcome.PAID, state.outcome());
            assertEquals("pi_sim", state.paymentIntentId());
        } finally { server.stop(0); }
    }
}
