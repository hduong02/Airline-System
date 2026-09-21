package com.example.payment_service.client;

import com.example.payment_service.config.FeignConverterConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UserClientColdStartTest {
    @Test
    void concurrentFirstResponsesDecodeWithFreshConverters() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor();
             var callers = Executors.newFixedThreadPool(24)) {
            server.setExecutor(requests);
            server.createContext("/api/users/", exchange -> {
                byte[] json = "{\"id\":42,\"email\":\"decoder-test@example.invalid\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                exchange.close();
            });
            server.start();
            List<Throwable> failures = new ArrayList<>();
            for (int round = 0; round < 20; round++) {
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                            "spring.cloud.openfeign.client.config.user-service.url",
                            "http://127.0.0.1:" + server.getAddress().getPort())));
                    var bootCustomizations = new AtomicInteger();
                    var cloudCustomizations = new AtomicInteger();
                    context.registerBean("bootCustomizer", ClientHttpMessageConvertersCustomizer.class,
                            () -> builder -> bootCustomizations.incrementAndGet());
                    context.registerBean("cloudCustomizer", HttpMessageConverterCustomizer.class,
                            () -> converters -> cloudCustomizations.incrementAndGet());
                    context.register(FeignAutoConfiguration.class, Clients.class, FeignConverterConfiguration.class);
                    context.refresh();
                    var client = context.getBean(UserClient.class);
                    assertSame(context.getBean(FeignHttpMessageConverters.class),
                            context.getBean(FeignClientFactory.class)
                                    .getInstance("user-service", FeignHttpMessageConverters.class));
                    var ready = new CountDownLatch(24);
                    var start = new CountDownLatch(1);
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < 24; i++) {
                        futures.add(callers.submit(() -> {
                            ready.countDown();
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            var user = client.getUserById(42L);
                            assertEquals(42L, user.getId());
                            assertEquals("decoder-test@example.invalid", user.getEmail());
                            return null;
                        }));
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    start.countDown();
                    for (var future : futures) {
                        try { future.get(15, TimeUnit.SECONDS); }
                        catch (java.util.concurrent.ExecutionException exception) { failures.add(exception.getCause()); }
                    }
                    assertEquals(1, bootCustomizations.get());
                    assertEquals(1, cloudCustomizations.get());
                }
            }
            assertTrue(failures.isEmpty(), () -> failures.size() + " decoder failures: " + failures.stream()
                    .map(Throwable::toString).distinct().toList());
        } finally {
            server.stop(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = UserClient.class)
    static class Clients {}
}
