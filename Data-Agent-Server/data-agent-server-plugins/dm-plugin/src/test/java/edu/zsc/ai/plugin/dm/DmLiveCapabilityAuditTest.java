package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live capability audit against a real DM instance (TEST schema).
 * Creates temporary objects named DA_AUDIT_* and cleans them up.
 * Run with: mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Dtest=DmLiveCapabilityAuditTest
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveCapabilityAuditTest {

    private static final String DRIVER_JAR = System.getProperty("dm.driver.jar",
            System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar");
    private static final String SCHEMA = "TEST";

    private ConnectionConfig config() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(System.getProperty("dm.host", "localhost"));
        config.setPort(Integer.getInteger("dm.port", 25236));
        config.setUsername(System.getProperty("dm.user", "SYSDBA"));
        config.setPassword(System.getProperty("dm.password", "Chat2DB_dm2762"));
        config.setDriverJarPath(DRIVER_JAR);
        return config;
    }

    private static void exec(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            System.out.println("[cleanup/setup] " + sql + " -> " + e.getMessage());
        }
    }

    @Test
    void auditWriteAndDdlCapabilities() throws Exception {
        Dm8Plugin plugin = new Dm8Plugin();
        try (Connection conn = plugin.connect(config())) {
            // setup
            exec(conn, "DROP TABLE " + SCHEMA + ".DA_AUDIT_T");
            exec(conn, "CREATE TABLE " + SCHEMA + ".DA_AUDIT_T (ID INT PRIMARY KEY, NAME VARCHAR(50), AMOUNT DECIMAL(10,2), CREATED DATETIME)");
            exec(conn, "DROP VIEW " + SCHEMA + ".DA_AUDIT_V");
            exec(conn, "DROP PROCEDURE " + SCHEMA + ".DA_AUDIT_P");
            exec(conn, "DROP FUNCTION " + SCHEMA + ".DA_AUDIT_F");
            exec(conn, "DROP TRIGGER " + SCHEMA + ".DA_AUDIT_TRG");

            try {
                // 1. row write
                plugin.insertRow(conn, null, SCHEMA, "DA_AUDIT_T", List.of(
                        new TableRowValue("ID", 1), new TableRowValue("NAME", "alpha"), new TableRowValue("AMOUNT", 12.5)));
                plugin.insertRow(conn, null, SCHEMA, "DA_AUDIT_T", List.of(
                        new TableRowValue("ID", 2), new TableRowValue("NAME", "beta")));
                long count = plugin.getTableDataCount(conn, null, SCHEMA, "DA_AUDIT_T");
                System.out.println("[audit] insertRow ok, count=" + count);
                assertEquals(2, count);

                plugin.updateRow(conn, null, SCHEMA, "DA_AUDIT_T",
                        List.of(new TableRowValue("NAME", "alpha2")),
                        List.of(new TableRowValue("ID", 1)), false);
                plugin.deleteRow(conn, null, SCHEMA, "DA_AUDIT_T",
                        List.of(new TableRowValue("ID", 2)), false);
                System.out.println("[audit] updateRow/deleteRow ok, count=" + plugin.getTableDataCount(conn, null, SCHEMA, "DA_AUDIT_T"));

                // 2. filtered query
                long filtered = plugin.getTableDataCount(conn, null, SCHEMA, "DA_AUDIT_T", "ID = 1");
                System.out.println("[audit] filtered count=" + filtered);
                assertEquals(1, filtered);

                // 3. DDL of each object type
                exec(conn, "CREATE VIEW " + SCHEMA + ".DA_AUDIT_V AS SELECT ID, NAME FROM " + SCHEMA + ".DA_AUDIT_T");
                exec(conn, "CREATE OR REPLACE PROCEDURE " + SCHEMA + ".DA_AUDIT_P AS BEGIN NULL; END;");
                exec(conn, "CREATE OR REPLACE FUNCTION " + SCHEMA + ".DA_AUDIT_F RETURN INT AS BEGIN RETURN 1; END;");
                exec(conn, "CREATE TRIGGER " + SCHEMA + ".DA_AUDIT_TRG BEFORE INSERT ON " + SCHEMA + ".DA_AUDIT_T BEGIN NULL; END;");

                String tableDdl = plugin.getTableDdl(conn, null, SCHEMA, "DA_AUDIT_T");
                System.out.println("[audit] table DDL:\n" + tableDdl);
                assertNotNull(tableDdl);

                String viewDdl = plugin.getViewDdl(conn, null, SCHEMA, "DA_AUDIT_V");
                System.out.println("[audit] view DDL=" + (viewDdl == null ? "NULL" : viewDdl.substring(0, Math.min(80, viewDdl.length()))));

                String procDdl = plugin.getProcedureDdl(conn, null, SCHEMA, "DA_AUDIT_P");
                System.out.println("[audit] proc DDL=" + (procDdl == null ? "NULL" : procDdl.substring(0, Math.min(80, procDdl.length()))));

                String funcDdl = plugin.getFunctionDdl(conn, null, SCHEMA, "DA_AUDIT_F");
                System.out.println("[audit] func DDL=" + (funcDdl == null ? "NULL" : funcDdl.substring(0, Math.min(80, funcDdl.length()))));

                String trgDdl = plugin.getTriggerDdl(conn, null, SCHEMA, "DA_AUDIT_TRG");
                System.out.println("[audit] trigger DDL=" + (trgDdl == null ? "NULL" : trgDdl.substring(0, Math.min(80, trgDdl.length()))));

                // 4. trigger list now non-empty
                System.out.println("[audit] triggers=" + plugin.getTriggers(conn, null, SCHEMA, null));
                assertEquals(1, plugin.getTriggers(conn, null, SCHEMA, null).size());

                // 5. rename
                plugin.renameTable(conn, null, SCHEMA, "DA_AUDIT_T", "DA_AUDIT_T2");
                assertTrue(plugin.getTableNames(conn, null, SCHEMA).contains("DA_AUDIT_T2"));
                plugin.renameTable(conn, null, SCHEMA, "DA_AUDIT_T2", "DA_AUDIT_T");
                System.out.println("[audit] renameTable ok");

                // 6. searchTables / countTables
                System.out.println("[audit] searchTables DA_AUDIT% ="
                        + plugin.searchTables(conn, null, SCHEMA, "DA_AUDIT")
                        + " count=" + plugin.countTables(conn, null, SCHEMA, "DA_AUDIT"));

                // 7. sql splitter on PL/SQL-style block
                List<String> stmts = plugin.split("CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;\nSELECT 2;");
                System.out.println("[audit] split result=" + stmts);

                // 8. view data
                var viewData = plugin.getViewData(conn, null, SCHEMA, "DA_AUDIT_V", 0, 10);
                System.out.println("[audit] view data rows ok, cols=" + (viewData.getColumns() == null ? 0 : viewData.getColumns().size()));
            } finally {
                exec(conn, "DROP TRIGGER " + SCHEMA + ".DA_AUDIT_TRG");
                exec(conn, "DROP VIEW " + SCHEMA + ".DA_AUDIT_V");
                exec(conn, "DROP PROCEDURE " + SCHEMA + ".DA_AUDIT_P");
                exec(conn, "DROP FUNCTION " + SCHEMA + ".DA_AUDIT_F");
                exec(conn, "DROP TABLE " + SCHEMA + ".DA_AUDIT_T");
                exec(conn, "DROP TABLE " + SCHEMA + ".DA_AUDIT_T2");
            }
        }
    }
}
