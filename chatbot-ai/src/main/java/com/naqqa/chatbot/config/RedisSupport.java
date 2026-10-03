package com.naqqa.chatbot.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.ClassUtils;

import java.util.function.Supplier;

final class RedisSupport {

    private static final String TEMPLATE = "org.springframework.data.redis.core.StringRedisTemplate";

    private RedisSupport() {
    }

    static boolean present() {
        return ClassUtils.isPresent(TEMPLATE, RedisSupport.class.getClassLoader());
    }

    static Supplier<StringRedisTemplate> supplier(ListableBeanFactory beanFactory) {
        if (!present()) {
            return () -> null;
        }
        ObjectProvider<StringRedisTemplate> provider = beanFactory.getBeanProvider(StringRedisTemplate.class);
        return provider::getIfAvailable;
    }

    static ObjectProvider<StringRedisTemplate> provider(ListableBeanFactory beanFactory) {
        return beanFactory.getBeanProvider(StringRedisTemplate.class);
    }
}
