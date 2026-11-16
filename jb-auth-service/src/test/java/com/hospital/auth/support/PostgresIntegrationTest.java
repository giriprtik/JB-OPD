package com.hospital.auth.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole service against a throwaway PostgreSQL 15 (same major version as docker-compose).
 * Flyway runs every migration on startup, so a broken or empty migration fails these tests.
 * Skipped automatically when Docker is not available (disabledWithoutDocker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

    /**
     * One container for the whole test run (singleton pattern), NOT one per test class.
     * Spring caches the application context across test classes; with a per-class @Container the second
     * class would reuse a cached context still pointing at the first class's (already stopped) container.
     * Testcontainers' Ryuk sidecar removes the container when the JVM exits.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void appProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> TestJwt.SECRET);
        registry.add("app.internal-secret", () -> TestJwt.INTERNAL_SECRET);
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }
}
