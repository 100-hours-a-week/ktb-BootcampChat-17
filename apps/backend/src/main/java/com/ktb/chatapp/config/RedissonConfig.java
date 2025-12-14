package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.LZ4Codec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class RedissonConfig {

    @Value("${REDIS_MASTER_HOST}")
    private String masterHost;

    @Value("${REDIS_MASTER_PORT}")
    private int masterPort;

    @Value("${REDIS_SLAVE_HOSTS}")
    private String slaveHosts;

    @Value("${REDIS_SLAVE_PORT:6379}")
    private int slavePort;

    @Value("${REDIS_THREADS:4}")
    private int threads;

    @Value("${REDIS_NETTY_THREADS:4}")
    private int nettyThreads;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        // slave host 문자열 → redis://host:port 형태로 변환
        List<String> slaveAddresses = Arrays.stream(slaveHosts.split(","))
                .map(String::trim)
                .map(host -> "redis://" + host + ":" + slavePort)
                .toList();

        config.useMasterSlaveServers()
                .setMasterAddress("redis://" + masterHost + ":" + masterPort)
                .addSlaveAddress(slaveAddresses.toArray(new String[0]))
                .setDatabase(0)
                .setMasterConnectionPoolSize(32)
                .setSlaveConnectionPoolSize(32)
                .setSubscriptionConnectionPoolSize(16);

        config.setThreads(threads);
        config.setNettyThreads(nettyThreads);
        config.setCodec(new LZ4Codec());

//        ReplicatedServersConfig replicated = config.useReplicatedServers()
//                .addNodeAddress("redis://" + masterHost + ":" + masterPort)
//                .addNodeAddress("redis://" + slaveHost + ":" + slavePort)
//                .addNodeAddress("redis://" + slaveHost2 + ":" + slavePort)
//                .setDatabase(0)
//                .setMasterConnectionPoolSize(64)
//                .setSlaveConnectionPoolSize(64)
//                .setScanInterval(2000);
//
//        config.setThreads(threads);
//        config.setNettyThreads(nettyThreads);
//
//        config.setCodec(new LZ4Codec());

        return Redisson.create(config);
    }
}
