package com.security.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseMigrationBootstrapper Tests")
class DatabaseMigrationBootstrapperTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData databaseMetaData;

    private DatabaseMigrationBootstrapper bootstrapper;

    @BeforeEach
    void setUp() throws Exception {
        bootstrapper = new DatabaseMigrationBootstrapper(jdbcTemplate);
        
        // Mock getDataSource and connection for DB name check
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
    }

    @Test
    @DisplayName("Should run Sentinel Value migrations and GIN Index creation on PostgreSQL")
    void run_PostgreSQL_MigratesSentinelsAndCreatesGINIndex() throws Exception {
        // Arrange
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        
        java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
        when(databaseMetaData.getTables(isNull(), any(), eq("vector_store"), isNull())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        
        when(jdbcTemplate.update(anyString())).thenReturn(1);

        // Act
        bootstrapper.run();

        // Assert
        // Verify update calls for documents, wiki_pages, wiki_page_drafts, embeddings (7 database updates, plus 4 vector_store updates)
        verify(jdbcTemplate, atLeast(10)).update(anyString());
        // Verify execute for CREATE INDEX or ALTER TABLE
        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
    }

    @Test
    @DisplayName("Should run only Sentinel Value migrations on non-PostgreSQL databases")
    void run_NonPostgreSQL_MigratesSentinelsOnly() throws Exception {
        // Arrange
        when(databaseMetaData.getDatabaseProductName()).thenReturn("H2");
        when(jdbcTemplate.update(anyString())).thenReturn(1);

        // Act
        bootstrapper.run();

        // Assert
        // Verify sentinel updates are run (7 updates)
        verify(jdbcTemplate, times(7)).update(anyString());
        // Verify no vector_store updates or index creation is executed
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
