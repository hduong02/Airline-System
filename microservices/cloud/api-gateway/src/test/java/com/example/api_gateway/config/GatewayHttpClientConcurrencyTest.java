package com.example.api_gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GatewayHttpClientConcurrencyTest {
    @Test
    void sharedGatewayClientCanForwardMoreThanFiveConcurrentRequestsToOneService() throws Exception {
        var arrived = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var upstream = Executors.newVirtualThreadPerTaskExecutor();
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(upstream);
            server.createContext("/api/bookings", exchange -> {
                arrived.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(201, 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            HttpClientAutoConfiguration.class, ImperativeHttpClientAutoConfiguration.class,
                            RestClientAutoConfiguration.class))
                    .withUserConfiguration(GatewayHttpClientConfiguration.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        // Gateway MVC's RestClientProxyExchange uses this auto-configured builder.
                        var client = context.getBean(RestClient.Builder.class).build();
                        var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/bookings";
                        var futures = new ArrayList<Future<Integer>>();
                        for (int i = 0; i < 8; i++) {
                            futures.add(callers.submit(() -> client.post().uri(url)
                                    .retrieve().toBodilessEntity().getStatusCode().value()));
                        }
                        boolean allForwarded;
                        try {
                            allForwarded = arrived.await(5, TimeUnit.SECONDS);
                        } finally {
                            release.countDown();
                        }
                        for (var future : futures) assertEquals(201, future.get(10, TimeUnit.SECONDS));
                        assertTrue(allForwarded, "Requests queued behind the default five-connection route limit");
                    });
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
