package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.DbConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceImplTest {

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final DbConnectionService dbConnectionService = mock(DbConnectionService.class);
    private final DatabaseServiceImpl databaseService = new DatabaseServiceImpl(connectionService, dbConnectionService);

    @AfterEach
    void tearDown() throws Exception {
        RequestContext.clear();
        activeConnections().clear();
    }

    @Test
    void getDatabases_returnsConnectionNameAsPseudoCatalogWhenPluginHasNoDatabaseConcept() throws Exception {
        RequestContext.set(RequestContextInfo.builder().userId(7L).build());
        register(22L, 7L, "dm-8");
        DbConnection entity = new DbConnection();
        entity.setId(22L);
        entity.setName("localhost@25236");
        entity.setDatabase("");
        when(dbConnectionService.getById(22L)).thenReturn(entity);

        List<String> databases = databaseService.getDatabases(22L);

        assertEquals(List.of("localhost@25236"), databases);
        verify(connectionService).openConnection(22L);
    }

    @Test
    void getDatabases_prefersConfiguredDatabaseNameAsPseudoCatalog() throws Exception {
        RequestContext.set(RequestContextInfo.builder().userId(7L).build());
        register(23L, 7L, "dm-8");
        DbConnection entity = new DbConnection();
        entity.setId(23L);
        entity.setName("dm-conn");
        entity.setDatabase("DAMENG");
        when(dbConnectionService.getById(23L)).thenReturn(entity);

        List<String> databases = databaseService.getDatabases(23L);

        assertEquals(List.of("DAMENG"), databases);
    }

    private void register(Long connectionId, Long userId, String pluginId) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isClosed()).thenReturn(false);
        when(connection.isValid(1)).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        ActiveConnectionRegistry.registerConnection(
                connectionId,
                new ActiveConnectionRegistry.ActiveConnection(
                        dataSource,
                        userId,
                        userId,
                        connectionId,
                        "dm",
                        pluginId,
                        null,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        WorkspaceTypeEnum.PERSONAL,
                        null
                )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, ActiveConnectionRegistry.ActiveConnection>> activeConnections() throws Exception {
        Field field = ActiveConnectionRegistry.class.getDeclaredField("activeConnections");
        field.setAccessible(true);
        return (Map<Long, Map<String, ActiveConnectionRegistry.ActiveConnection>>) field.get(null);
    }
}
