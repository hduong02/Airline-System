package com.example.flight_service.client;

import com.example.flight_service.config.FeignConverterConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AirlineClientColdStartTest {
    @Test
    void concurrentFirstAirlineResponsesDecodeThroughActualFeignClient() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
             var callers = Executors.newFixedThreadPool(20)) {
            server.setExecutor(handlers);
            server.createContext("/api/airlines/42", exchange -> {
                byte[] json = "{\"id\":42,\"name\":\"Decoder Test Airline\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                exchange.close();
            });
            server.start();
            var failures = new ArrayList<Throwable>();
            for (int round = 0; round < 3; round++) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                            "spring.cloud.openfeign.client.config.airline-service.url",
                            "http://127.0.0.1:" + server.getAddress().getPort())));
                    context.registerBean("slowConverterInitialization", ClientHttpMessageConvertersCustomizer.class,
                            () -> builder -> {
                                // Widen the real initialization race without changing the converter list.
                                try { Thread.sleep(250); }
                                catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            });
                    context.register(FeignAutoConfiguration.class, Clients.class, FeignConverterConfiguration.class);
                    context.refresh();
                    var client = context.getBean(AirlineClient.class);
                    assertSame(context.getBean(FeignHttpMessageConverters.class),
                            context.getBean(FeignClientFactory.class)
                                    .getInstance("airline-service", FeignHttpMessageConverters.class));
                    var ready = new CountDownLatch(20);
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < 20; i++) {
                        futures.add(callers.submit(() -> {
                            ready.countDown();
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            var airline = client.getAirlineById(42L);
                            assertEquals(42L, airline.getId());
                            assertEquals("Decoder Test Airline", airline.getName());
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
            assertTrue(failures.isEmpty(), () -> failures.size() + " decoder failures: "
                    + failures.stream().map(Throwable::toString).distinct().toList());
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = AirlineClient.class)
    static class Clients {}
}
