package com.example.autoskaner_ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Who creates the schema, asserted rather than assumed.
 *
 * <p>Flyway creating the tables and Hibernate creating the tables look identical from a passing
 * repository test: both leave a database the entities fit. They differ on the environment nobody
 * tests — a fresh Postgres, where only the migration runs. So this pins the arrangement itself:
 * V1 applied, and {@code ddl-auto} is {@code validate}, which fails startup on drift instead of
 * quietly patching the live schema and leaving the migration wrong.
 *
 * <p>Deliberately does not name a column: {@code SavedAnalysisRepositoryTest} covers those, and a
 * second list of column names here would be a copy of the migration that has to be edited twice.
 */
@SpringBootTest
@ActiveProfiles("mock")
class SchemaIsOwnedByFlywayTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void theInitialMigrationApplied() {
        // Every identifier here is quoted lower-case, which is the one spelling both engines read the
        // same way. Flyway creates its history table with quoted lower-case names; H2 folds an
        // UNQUOTED name to upper case even in PostgreSQL compatibility mode, so plain
        // `FROM flyway_schema_history` fails with `Table "FLYWAY_SCHEMA_HISTORY" not found (candidates
        // are: "flyway_schema_history")`. PostgreSQL folds unquoted names to lower case, so the
        // quoted form is correct there too — the unquoted form is what is only accidentally portable.
        Boolean success =
                jdbc.queryForObject(
                        "SELECT \"success\" FROM \"flyway_schema_history\" WHERE \"version\" = '1'",
                        Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    void bothTablesTheMigrationDeclaresAreQueryable() {
        // Queried rather than looked up in information_schema, whose identifier casing differs
        // between H2 and PostgreSQL.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyses", Long.class)).isNotNull();
    }

    @Test
    void hibernateOnlyValidatesTheSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
}
