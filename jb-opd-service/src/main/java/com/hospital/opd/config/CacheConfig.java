package com.hospital.opd.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DOCTORS = "doctors";
    static final long LAB_REPORT_CACHE_BYTES = 64L * 1024 * 1024;

    /**
     * Doctor profiles change a few times a month, but are looked up on every registration.
     * Five minutes keeps the desk working through a short auth outage.
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(DOCTORS);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());
        return manager;
    }

    /**
     * Rendered lab-report PDFs keyed by lab order id. Results are immutable once COMPLETED, so a report never
     * goes stale. In memory only (PHI never hits disk); bounded by size; access checks happen before the cache.
     * If result amendments are ever added, invalidate the order's entry when an amendment is released.
     */
    @Bean
    public Cache<Long, byte[]> labReportCache(MeterRegistry registry) {
        Cache<Long, byte[]> cache = Caffeine.newBuilder()
                .maximumWeight(LAB_REPORT_CACHE_BYTES)
                .weigher((Long orderId, byte[] pdf) -> pdf.length)
                .expireAfterAccess(Duration.ofHours(2))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "labReports");   // /actuator/metrics/cache.gets?tag=cache:labReports
        return cache;
    }
}
