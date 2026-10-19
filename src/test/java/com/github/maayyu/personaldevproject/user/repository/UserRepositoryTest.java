package com.github.maayyu.personaldevproject.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.maayyu.personaldevproject.PostgresTestConfiguration;
import com.github.maayyu.personaldevproject.user.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfiguration.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void appliesFlywayV1AndValidatesSchemaOnPostgres17() throws Exception {
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
        }
        assertThat(flyway.info().applied()).anySatisfy(migration -> {
            assertThat(migration.getVersion()).isNotNull();
            assertThat(migration.getVersion().getVersion()).isEqualTo("1");
            assertThat(migration.getScript()).isEqualTo("V1__create_initial_tables.sql");
            assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS);
        });
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(entityManager.getEntityManagerFactory().getProperties())
                .containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void savesUserAndReloadsPersistedFields() {
        User saved = userRepository.save(new User("save@example.test", "test-password-hash"));
        entityManager.flush();
        entityManager.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded).isNotSameAs(saved);
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
        assertThat(reloaded.getEmail()).isEqualTo("save@example.test");
        assertThat(reloaded.getPasswordHash()).isEqualTo("test-password-hash");
    }

    @Test
    void findsUserByEmail() {
        userRepository.save(new User("other@example.test", "other-test-hash"));
        User saved = userRepository.save(new User("find@example.test", "find-test-hash"));
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findByEmail("find@example.test").orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getEmail()).isEqualTo("find@example.test");
        assertThat(found.getPasswordHash()).isEqualTo("find-test-hash");
    }

    @Test
    void returnsEmptyWhenEmailDoesNotExist() {
        userRepository.save(new User("existing@example.test", "test-password-hash"));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findByEmail("missing@example.test")).isEmpty();
    }

    @Test
    void allocatesIdsFromUsersSequenceBeforeInsert() {
        User first = userRepository.save(new User("sequence-first@example.test", "first-test-hash"));
        assertThat(first.getId()).isNotNull().isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT currval('users_id_seq')", Long.class))
                .isEqualTo(first.getId());
        assertThat(countUserRows(first.getId())).as("first user before flush").isZero();

        User second = userRepository.save(new User("sequence-second@example.test", "second-test-hash"));
        assertThat(second.getId()).isNotNull().isEqualTo(first.getId() + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT currval('users_id_seq')", Long.class))
                .isEqualTo(second.getId());
        assertThat(countUserRows(first.getId())).as("first user after second save, before flush").isZero();
        assertThat(countUserRows(second.getId())).as("second user before flush").isZero();

        entityManager.flush();
        assertThat(countUserRows(first.getId())).as("first user after flush").isEqualTo(1L);
        assertThat(countUserRows(second.getId())).as("second user after flush").isEqualTo(1L);
        entityManager.clear();
        assertThat(userRepository.findById(first.getId())).isPresent();
        assertThat(userRepository.findById(second.getId())).isPresent();
    }

    @Test
    void retrievesDatabaseGeneratedTimestampsAfterInsertAndReload() {
        User user = new User("timestamps@example.test", "test-password-hash");
        assertThat(user.getCreatedAt()).isNull();
        assertThat(user.getUpdatedAt()).isNull();
        Instant beforeInsert = databaseClock();

        User saved = userRepository.save(user);
        entityManager.flush();
        Instant afterInsert = databaseClock();

        // Check @Generated retrieval before clearing the persistence context as well.
        Instant createdAt = saved.getCreatedAt();
        Instant updatedAt = saved.getUpdatedAt();
        assertThat(createdAt).isNotNull().isBetween(beforeInsert, afterInsert);
        assertThat(updatedAt).isNotNull().isBetween(beforeInsert, afterInsert);
        assertThat(createdAt.getNano() % 1_000).isZero();
        assertThat(updatedAt.getNano() % 1_000).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM users WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(createdAt);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM users WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(updatedAt);

        entityManager.clear();
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded).isNotSameAs(saved);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    private long countUserRows(Long userId) {
        // JdbcTemplate uses the same transaction but bypasses Hibernate, so this SELECT
        // does not flush pending INSERTs as a JPA query could.
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
    }

    private Instant databaseClock() {
        return jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
}
