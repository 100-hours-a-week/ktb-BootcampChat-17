package com.ktb.chatapp.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;


import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.LZ4Codec;
import org.redisson.config.Config;
import org.redisson.config.ReplicatedServersConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@EnableCaching
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisConfig {

    @Value("${REDIS_MASTER_HOST:localhost}")
    private String masterHost;

    @Value("${REDIS_MASTER_PORT:6379}")
    private int masterPort;

    @Value("${REDIS_SLAVE_HOST:localhost}")
    private String slaveHost;

    @Value("${REDIS_SLAVE_HOST2:localhost}")
    private String slaveHost2;

    @Value("${REDIS_SLAVE_PORT:6379}")
    private int slavePort;

    @Value("${REDIS_THREADS:4}")
    private int threads;

    @Value("${REDIS_NETTY_THREADS:4}")
    private int nettyThreads;

    @Value("${cache.user.ttl:600}")
    private long userCacheTtlSeconds;

    @Value("${cache.room-message-count.ttl:60}")
    private long roomMessageCountTtlSeconds;

    @jakarta.annotation.PostConstruct
    public void printRedisInfo() {
        System.out.println("=== [RedisConfig] Master: " + masterHost + ":" + masterPort + " ===");
        System.out.println("=== [RedisConfig] Slaves: " + slaveHost + ":" + slavePort + ", " + slaveHost2 + ":" + slavePort + " ===");
        System.out.println("=== [RedisConfig] Session DB: 0, Cache DB: 1 ===");
    }

    /**
     * Redisson Client for Session (DB 0)
     */
    @Bean(destroyMethod = "shutdown")
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();
        ReplicatedServersConfig replicated = config.useReplicatedServers()
                .addNodeAddress("redis://" + masterHost + ":" + masterPort)
                .addNodeAddress("redis://" + slaveHost + ":" + slavePort)
                .addNodeAddress("redis://" + slaveHost2 + ":" + slavePort)
                .setDatabase(0)  // Session용 DB 0
                .setMasterConnectionPoolSize(64)
                .setSlaveConnectionPoolSize(64)
                .setScanInterval(2000);

        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        config.setCodec(new LZ4Codec());

        return Redisson.create(config);
    }

    /**
     * Redisson Client for Cache (DB 1)
     */
    @Bean(name = "cacheRedissonClient", destroyMethod = "shutdown")
    public RedissonClient cacheRedissonClient() {
        Config config = new Config();
        ReplicatedServersConfig replicated = config.useReplicatedServers()
                .addNodeAddress("redis://" + masterHost + ":" + masterPort)
                .addNodeAddress("redis://" + slaveHost + ":" + slavePort)
                .addNodeAddress("redis://" + slaveHost2 + ":" + slavePort)
                .setDatabase(1)  // ✅ Cache용 DB 1
                .setMasterConnectionPoolSize(64)
                .setSlaveConnectionPoolSize(64)
                .setScanInterval(2000);

        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        config.setCodec(new LZ4Codec());

        return Redisson.create(config);
    }

    /**
     * Session용 ConnectionFactory (DB 0)
     */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory(RedissonClient redissonClient) {
        return new RedissonConnectionFactory(redissonClient);
    }

    /**
     * Cache용 ConnectionFactory (DB 1)
     */
    @Bean(name = "cacheRedisConnectionFactory")
    public RedisConnectionFactory cacheRedisConnectionFactory() {
        return new RedissonConnectionFactory(cacheRedissonClient());
    }

    /**
     * Redis 전용 ObjectMapper
     *  - LocalDateTime → ISO-8601 문자열
     *  - defaultTyping 활성화해서 타입 정보(@class) 같이 저장
     *  - 이 ObjectMapper는 Bean으로 등록하지 않으므로 HTTP 메시지 변환에는 영향 없음
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ⚠️ Redis 캐시에서 역직렬화 할 때 타입 복원을 위해 defaultTyping 활성화
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(createRedisObjectMapper());

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * CacheManager - Cache용 ConnectionFactory(DB 1) 사용
     */
    @Bean
    public CacheManager cacheManager() {
        // ✅ Cache 전용 ConnectionFactory 사용 (DB 1)
        RedisConnectionFactory cacheConnectionFactory = cacheRedisConnectionFactory();

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(createRedisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(serializer)
                )
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        // userCache: 사용자 프로필 캐시 TTL
        cacheConfigs.put(
                "userCache",
                defaultConfig.entryTtl(Duration.ofSeconds(userCacheTtlSeconds))
        );

        // roomMessageCountCache: 방별 메시지 수 캐시 TTL
        cacheConfigs.put(
                "roomMessageCountCache",
                defaultConfig.entryTtl(Duration.ofSeconds(roomMessageCountTtlSeconds))
        );

        return RedisCacheManager.RedisCacheManagerBuilder
                .fromConnectionFactory(cacheConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}