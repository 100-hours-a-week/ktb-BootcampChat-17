// 테스트 코드 생성
// src/test/java/com/ktb/chatapp/service/RedisCacheIntegrationTest.java

package com.ktb.chatapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisCacheIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void testCacheManager() {
        assertNotNull(cacheManager, "CacheManager should not be null");

        Cache cache = cacheManager.getCache("roomMessageCountCache");
        assertNotNull(cache, "roomMessageCountCache should exist");

        // 캐시에 값 저장
        cache.put("test-key", "test-value");

        // 캐시에서 값 조회
        Cache.ValueWrapper wrapper = cache.get("test-key");
        assertNotNull(wrapper);
        assertEquals("test-value", wrapper.get());

        System.out.println("✅ Cache test passed!");
    }

    @Test
    void testRedisConnection() {
        redisTemplate.opsForValue().set("test:cache", "works");
        String value = (String) redisTemplate.opsForValue().get("test:cache");
        assertEquals("works", value);

        System.out.println("✅ Redis connection test passed!");
    }
}