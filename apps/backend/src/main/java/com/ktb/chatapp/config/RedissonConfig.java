package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.LZ4Codec;
import org.redisson.config.Config;
import org.redisson.config.ReplicatedServersConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${REDIS_MASTER_HOST:localhost}")  // ✅ 기본값 추가
    private String masterHost;

    @Value("${REDIS_MASTER_PORT:6379}")       // ✅ 기본값 추가
    private int masterPort;

    @Value("${REDIS_SLAVE_HOST:localhost}")   // ✅ 기본값 추가
    private String slaveHost;

    @Value("${REDIS_SLAVE_HOST2:localhost}")  // ✅ 기본값 추가
    private String slaveHost2;

    @Value("${REDIS_SLAVE_PORT:6379}")        // ✅ 기본값 추가
    private int slavePort;

    @Value("${REDIS_THREADS:4}")
    private int threads;

    @Value("${REDIS_NETTY_THREADS:4}")
    private int nettyThreads;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        ReplicatedServersConfig replicated = config.useReplicatedServers()
                .addNodeAddress("redis://" + masterHost + ":" + masterPort)
                .addNodeAddress("redis://" + slaveHost + ":" + slavePort)
                .addNodeAddress("redis://" + slaveHost2 + ":" + slavePort)
                .setDatabase(0)
                .setMasterConnectionPoolSize(64)
                .setSlaveConnectionPoolSize(64)
                .setScanInterval(2000);

        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        config.setCodec(new LZ4Codec());

        return Redisson.create(config);
    }
}