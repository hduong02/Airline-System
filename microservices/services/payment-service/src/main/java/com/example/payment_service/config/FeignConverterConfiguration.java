package com.example.payment_service.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FeignConverterConfiguration {
    @Bean
    public FeignHttpMessageConverters feignHttpMessageConverters(
            ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers,
            ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers) {
        var converters = new FeignHttpMessageConverters(customizers, cloudCustomizers);
        // OpenFeign 5.0.2 exposes an empty list during lazy initialization.
        // Finish initialization before Spring publishes the bean to client contexts.
        converters.getConverters();
        return converters;
    }
}
