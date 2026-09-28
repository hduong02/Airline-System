package com.example.booking_service.client;

import com.example.booking_service.config.FeignConverterConfiguration;
import com.example.enums.PaymentGateway;
import com.example.payload.request.PaymentInitiateRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PaymentClientColdStartTest {
    @Test
    void concurrentFirstCheckoutsEncodeAndDecodeThroughActualFeignClient() throws Exception {
        var received = new ConcurrentLinkedQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
             var callers = Executors.newFixedThreadPool(20)) {
            server.setExecutor(handlers);
            server.createContext("/internal/payments/initiate", exchange -> {
                received.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestHeaders().getFirst("X-User-Id") + " "
                        + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] json = "{\"bookingId\":42,\"gateway\":\"STRIPE\",\"success\":true}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                exchange.close();
            });
            server.start();
            var failures = new ArrayList<Throwable>();
            for (int round = 0; round < 5; round++) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                            "spring.cloud.openfeign.client.config.payment-service.url",
                            "http://127.0.0.1:" + server.getAddress().getPort())));
                    context.registerBean("slowConverterInitialization", ClientHttpMessageConvertersCustomizer.class,
                            () -> builder -> {
                                // Widen the real lazy-initialization race without changing the converter list.
                                try { Thread.sleep(250); }
                                catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            });
                    context.register(FeignAutoConfiguration.class, Clients.class, FeignConverterConfiguration.class);
                    context.refresh();
                    var client = context.getBean(PaymentClient.class);
                    var ready = new CountDownLatch(20);
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < 20; i++) {
                        futures.add(callers.submit(() -> {
                            ready.countDown();
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            var request = PaymentInitiateRequest.builder().bookingId(42L).userId(7L)
                                    .gateway(PaymentGateway.STRIPE).amount(123.45).description("Checkout test").build();
                            var response = client.initiatePayment(request, 7L);
                            assertEquals(42L, response.getBookingId());
                            assertEquals(PaymentGateway.STRIPE, response.getGateway());
                            assertEquals(Boolean.TRUE, response.getSuccess());
                            return null;
                        }));
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    start.countDown();
                    for (var future : futures) {
                        try { future.get(15, TimeUnit.SECONDS); }
                        catch (java.util.concurrent.ExecutionException failure) { failures.add(failure.getCause()); }
                    }
                }
            }
            assertTrue(failures.isEmpty(), () -> failures.size() + " checkout failures: "
                    + failures.stream().map(Throwable::toString).distinct().toList());
            assertEquals(100, received.size());
            var mapper = JsonMapper.builder().build();
            for (var request : received) {
                assertTrue(request.startsWith("POST 7 application/json "), request);
                var body = mapper.readTree(request.substring(request.indexOf('{')));
                assertEquals(42L, body.get("bookingId").asLong());
                assertEquals(7L, body.get("userId").asLong());
                assertEquals("STRIPE", body.get("gateway").asText());
                assertEquals(123.45, body.get("amount").asDouble());
                assertEquals("Checkout test", body.get("description").asText());
            }
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = PaymentClient.class)
    static class Clients {}
}
