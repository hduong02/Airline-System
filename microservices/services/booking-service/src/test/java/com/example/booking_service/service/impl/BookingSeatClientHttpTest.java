package com.example.booking_service.service.impl;

import com.example.booking_service.client.SeatClient;
import com.example.enums.CabinClassType;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class BookingSeatClientHttpTest {
    @Test
    void actualFeignClientPostsIdsAndDecodesCompactContract() throws Exception {
        var requests = new ConcurrentLinkedQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = ("{\"id\":40,\"flightInstanceId\":20,\"cabinClassType\":\"ECONOMY\","
                    + "\"availableSeats\":101,\"seats\":[{\"id\":100,\"flightId\":10,"
                    + "\"flightInstanceId\":20,\"flightCabinId\":40,\"price\":10.0}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("converters", new FeignHttpMessageConverters(
                    beans.getBeanProvider(ClientHttpMessageConvertersCustomizer.class),
                    beans.getBeanProvider(HttpMessageConverterCustomizer.class)));
            var converters = beans.getBeanProvider(FeignHttpMessageConverters.class);
            SeatClient client = Feign.builder().contract(new SpringMvcContract())
                    .encoder(new SpringEncoder(converters)).decoder(new SpringDecoder(converters))
                    .target(SeatClient.class, "http://127.0.0.1:" + server.getAddress().getPort());

            var selection = client.getBookingSeatSelection(20L, CabinClassType.ECONOMY, List.of(100L));

            assertEquals(List.of("POST /internal/booking-seat-selections/20/ECONOMY [100]"), List.copyOf(requests));
            assertEquals(40L, selection.getId());
            assertEquals(101, selection.getAvailableSeats());
            assertEquals(CabinClassType.ECONOMY, selection.getCabinClassType());
            assertEquals(100L, selection.getSeats().getFirst().id());
            assertEquals(10.0, selection.getSeats().getFirst().price());
        } finally {
            server.stop(0);
        }
    }
}
