package com.example.api_gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;

@Configuration(proxyBeanMethods = false)
public class GatewayHttpClientConfiguration {
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> gatewayConnectionPool(
            @Value("${gateway.http.pool.max-per-route:20}") int perRoute,
            @Value("${gateway.http.pool.max-total:100}") int total) {
        if (perRoute < 1 || total < perRoute) {
            throw new IllegalArgumentException("Gateway HTTP pool requires max-total >= max-per-route >= 1");
        }
        // The MVC proxy uses Boot's RestClient. Apache's default five connections
        // per service otherwise queue booking requests before booking-service sees them.
        // Customize its builder so Boot still applies timeouts, TLS and redirects.
        return builder -> builder.withConnectionManagerCustomizer(manager -> manager
                .setMaxConnPerRoute(perRoute).setMaxConnTotal(total));
    }
}
