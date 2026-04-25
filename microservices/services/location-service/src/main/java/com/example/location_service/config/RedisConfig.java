package com.example.location_service.config;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

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
                // Airports change extremely rarely — long TTL
                "airports", defaults.entryTtl(Duration.ofHours(6)),
                "airportsByCity", defaults.entryTtl(Duration.ofHours(6)),
                "allAirports", defaults.entryTtl(Duration.ofHours(2)),
                // Cities also static reference data
                "cities", defaults.entryTtl(Duration.ofHours(6)),
                "citiesByCode", defaults.entryTtl(Duration.ofHours(6))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults.entryTtl(Duration.ofHours(6)))
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
