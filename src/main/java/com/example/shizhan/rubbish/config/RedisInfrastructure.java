package com.example.shizhan.rubbish.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisInfrastructure {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.redis.host:127.0.0.1}") String host,
            @Value("${spring.redis.port:6379}") int port, @Value("${spring.redis.password:}") String password,
            @Value("${spring.redis.database:0}") int database, @Value("${spring.redis.ssl:false}") boolean ssl) {
        Config config = new Config();
        config.useSingleServer().setAddress((ssl ? "rediss://" : "redis://") + host + ":" + port)
                .setDatabase(database).setPassword(password.isEmpty() ? null : password)
                .setConnectionPoolSize(16).setConnectionMinimumIdleSize(2).setTimeout(3000);
        return Redisson.create(config);
    }
    @Bean public RedisMessageListenerContainer redisMessages(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
