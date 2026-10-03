package com.naqqa.analytics.config;

import com.naqqa.analytics.collect.KeyValueStore;
import com.naqqa.analytics.collect.MemoryKeyValueStore;
import com.naqqa.analytics.collect.RedisKeyValueStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.ClassUtils;

final class AnalyticsRedisSupport {

    private static final String TEMPLATE = "org.springframework.data.redis.core.StringRedisTemplate";

    private AnalyticsRedisSupport() {
    }

    static boolean present() {
        return ClassUtils.isPresent(TEMPLATE, AnalyticsRedisSupport.class.getClassLoader());
    }

    static KeyValueStore store(ListableBeanFactory beanFactory) {
        MemoryKeyValueStore memory = new MemoryKeyValueStore();
        if (!present()) {
            return memory;
        }
        return Holder.create(beanFactory, memory);
    }

    private static final class Holder {

        static KeyValueStore create(ListableBeanFactory beanFactory, MemoryKeyValueStore memory) {
            var provider = beanFactory.getBeanProvider(StringRedisTemplate.class);
            if (provider.getIfAvailable() == null) {
                return memory;
            }
            return new RedisKeyValueStore(provider::getIfAvailable, memory);
        }
    }
}
