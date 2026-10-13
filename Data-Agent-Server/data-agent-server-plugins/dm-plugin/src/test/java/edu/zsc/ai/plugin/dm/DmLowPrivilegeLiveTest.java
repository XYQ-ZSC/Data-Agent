package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Read-only acceptance probe for a non-admin DM account. */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLowPrivilegeLiveTest {
    @Test
    void scopedConnectionCanQueryAndBrowseItsSchema() throws Exception {
        String username = DmLiveTestFixture.setting("low_user");
        String password = DmLiveTestFixture.setting("low_password");
        String schema = DmLiveTestFixture.setting("low_schema");
        assumeTrue(username != null && password != null && schema != null,
                "Set DM_LOW_USER, DM_LOW_PASSWORD and DM_LOW_SCHEMA to run the low-privilege probe");

        ConnectionConfig config = new ConnectionConfig();
        config.setHost(DmLiveTestFixture.setting("host") == null ? "localhost" : DmLiveTestFixture.setting("host"));
        config.setPort(DmLiveTestFixture.setting("port") == null ? 25236 : Integer.parseInt(DmLiveTestFixture.setting("port")));
        config.setDriverJarPath(DmLiveTestFixture.DRIVER_JAR);
        config.setUsername(username);
        config.setPassword(password);
        config.setSchema(schema);
        Dm8Plugin plugin = new Dm8Plugin();
        try (Connection connection = plugin.connect(config);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM DUAL")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            assertTrue(plugin.getSchemas(connection, null).contains(schema));
            List<String> tables = plugin.getTableNames(connection, null, schema);
            plugin.getViews(connection, null, schema);
            plugin.getSequences(connection, null, schema);
            if (!tables.isEmpty()) {
                plugin.getConstraints(connection, null, schema, tables.get(0));
            }
        }
    }
}
