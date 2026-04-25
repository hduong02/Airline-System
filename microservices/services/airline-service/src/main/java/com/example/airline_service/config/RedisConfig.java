package com.example.airline_service.config;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import java.lang.reflect.Method;
import java.util.Arrays;

@Slf4j
@Configuration
public class RedisConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator
                .builder()
                .allowIfBaseType(Object.class)
                .build();

        JsonMapper jsonMapper = JsonMapper.builder()
                .activateDefaultTypingAsProperty(
                    typeValidator,
                    DefaultTyping.NON_FINAL,
                    "@class"
                )
                .build();
    
        GenericJacksonJsonRedisSerializer jsonSerializer =
                new GenericJacksonJsonRedisSerializer(jsonMapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                // Airline data — 2 h (status changes occasionally)
                "airlines", defaults.entryTtl(Duration.ofHours(2)),
                "airlinesByOwner", defaults.entryTtl(Duration.ofHours(2)),
                "airlinesByIata", defaults.entryTtl(Duration.ofHours(2)),
                "airlinesByAlliance", defaults.entryTtl(Duration.ofHours(2)),
                // Aircraft models — 6 h (very stable)
                "aircrafts", defaults.entryTtl(Duration.ofHours(6))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults.entryTtl(Duration.ofHours(2)))
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * Stable cache key for methods whose first argument is a Collection<String>.
     * Sorts the values so ["AI","6E"] and ["6E","AI"] produce the same key.
     */
    @Bean
    public KeyGenerator sortedListKeyGenerator() {
        return (Object target, Method method, Object... params) -> {
            Object first = params[0];
            if (first instanceof Collection<?> col) {
                return col.stream()
                        .map(Object::toString)
                        .sorted()
                        .collect(Collectors.joining(","));
            }
            return Arrays.deepToString(params);
        };
    }
}