package ru.quipy.payments.config


import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.*
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class RateLimiterConfig {
    @Bean
    fun slidingWindowRateLimiter(): RateLimiter {
        // Настройка: окно 1 секунда, максимум 5 запросов за окно
        return FixedWindowRateLimiter(10, 1, TimeUnit.SECONDS)
        /*return TokenBucketRateLimiter( rate = 10,
            bucketMaxCapacity = 10,
            window = 1,
            timeUnit = TimeUnit.SECONDS);*/
    }
}