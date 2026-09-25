package com.telco.billshock.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16 + pgvector, the same image as docker-compose.yml. Flyway applies the schema
 * and the seed (profile {@code test}). The context, and so the container, is shared by all
 * integration tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            // Docker's default 64 MB /dev/shm broke parallel VACUUM in Phase 2 (PROGRESS.md).
            .withSharedMemorySize(256L * 1024 * 1024);
    }
}
