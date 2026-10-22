package com.naqqa.auth.config.redis;

import com.naqqa.auth.entity.auth.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableRedisRepositories
@EnableCaching
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.username}")
    private String redisUsername;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    public static final String articleTagsCache = "articleTagsCache";
    public static final String articleCache = "articleCache";
    public static final String userCache = "userCache";
    public static final String projectCache = "projectCache";
    public static final String projectPerEntityCache = "projectPerEntityCache";

    public static final Map<Class<?>, String> cacheNamesMap;
    public static final Map<Class<?>, String> perEntityCacheNamesMap;

    static {
        cacheNamesMap = new HashMap<>();
        perEntityCacheNamesMap = new HashMap<>();
        perEntityCacheNamesMap.put(UserEntity.class, userCache);
    }

    @Bean
    public JedisConnectionFactory jedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);

        if (redisUsername != null && !redisUsername.isEmpty()) {
            config.setUsername(redisUsername);
        }

        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }

        JedisClientConfiguration clientConfig = JedisClientConfiguration.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(10))
                .build();

        return new JedisConnectionFactory(config, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setDefaultSerializer(new GenericJackson2JsonRedisSerializer());
        return redisTemplate;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .enableTimeToIdle();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(JedisConnectionFactory jedisConnectionFactory) {
        return (builder) -> builder
                .withCacheConfiguration(userCache,
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(100)))
                .withCacheConfiguration(articleTagsCache,
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(100)))
                .withCacheConfiguration(articleCache,
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(100)))
                .withCacheConfiguration(projectCache,
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(100)))
                .withCacheConfiguration(projectPerEntityCache,
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(100)));
    }

    @Bean
    public UnifiedJedis unifiedJedis() {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .user(redisUsername)
                .password(redisPassword)
                .timeoutMillis(10000)
                .build();

        return new UnifiedJedis(new HostAndPort(redisHost, redisPort), clientConfig);
    }
}