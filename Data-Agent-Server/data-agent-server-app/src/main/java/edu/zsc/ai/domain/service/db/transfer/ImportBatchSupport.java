package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * JDBC batch execution and transaction handling shared by every importer.
 */
@Component
public class ImportBatchSupport {

    private static final Logger log = LoggerFactory.getLogger(ImportBatchSupport.class);

    /** Work executed inside one import transaction; may throw any exception. */
    @FunctionalInterface
    public interface TxWork<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code work} inside one transaction: autoCommit off, work, commit.
     * {@link ImportFailureException} rolls back and is rethrown so the caller can
     * map it to a failure response; any other error rolls back too and is rethrown
     * (checked exceptions are wrapped in a RuntimeException).
     */
    public <T> T runInTransaction(Connection connection, TxWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            T result = work.run();
            connection.commit();
            return result;
        } catch (ImportFailureException failure) {
            safeRollback(connection);
            throw failure;
        } catch (Exception unexpected) {
            // Any error outside the normal ImportFailureException path must roll
            // back too; otherwise restoring autoCommit in finally could commit
            // partial rows on some JDBC drivers.
            safeRollback(connection);
            if (unexpected instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Import failed; transaction rolled back: "
                    + unexpected.getMessage(), unexpected);
        } finally {
            restoreAutoCommit(connection, originalAutoCommit);
        }
    }

    /** Structured failure response carrying the 1-based file row that failed. */
    public static ImportTableDataResponse failureResponse(int failedAtRow, String message, int totalRows) {
        return ImportTableDataResponse.builder()
                .success(false)
                .totalRows(totalRows)
                .insertedRows(0)
                .failedAtRow(failedAtRow)
                .errorMessage(message)
                .build();
    }

    public void executeImportBatch(PreparedStatement statement, int batchFirstRow) {
        try {
            statement.executeBatch();
        } catch (SQLException ex) {
            throw new ImportFailureException(batchFirstRow,
                    "Database rejected the batch: " + ex.getMessage());
        }
    }

    public void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            log.warn("Rollback after import failure failed: {}", rollbackEx.getMessage());
        }
    }

    public void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ex) {
            log.warn("Failed to restore autoCommit: {}", ex.getMessage());
        }
    }
}
