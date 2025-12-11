package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.LZ4Codec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    @Value("${REDIS_THREADS:4}")
    private int threads;

    @Value("${REDIS_NETTY_THREADS:4}")
    private int nettyThreads;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://43.201.34.87:6379")
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(4)
                .setDatabase(0);

        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        config.setCodec(new LZ4Codec());

        return Redisson.create(config);
    }
}
