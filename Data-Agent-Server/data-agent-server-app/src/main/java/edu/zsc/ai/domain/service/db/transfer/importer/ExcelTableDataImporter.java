package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.AbstractTableDataImporter;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports rows from an Excel file (.xlsx or .xls, auto-detected). The first
 * sheet's first row is a header row whose names must match table columns; all
 * inserts run in one transaction.
 */
@Component
public class ExcelTableDataImporter extends AbstractTableDataImporter {

    public ExcelTableDataImporter(ConnectionService connectionService,
                                  ImportPrepareSupport prepareSupport,
                                  ColumnValueConverter valueConverter,
                                  ImportBatchSupport batchSupport) {
        super(connectionService, prepareSupport, valueConverter, batchSupport);
    }

    @Override
    public String fileType() {
        return "XLSX";
    }

    @Override
    public List<String> aliases() {
        return List.of("XLS");
    }

    @Override
    protected ImportSource prepare(InputStream inputStream, String encoding,
                                   ImportPrepareSupport.ResolvedTableColumns resolved,
                                   String tableName) throws IOException {
        Workbook workbook = WorkbookFactory.create(inputStream);
        try {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new ImportFailureException(1, "Excel file contains no sheets");
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new ImportFailureException(1, "Excel file is empty or missing a header row");
            }

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                String header = cell == null ? "" : readExcelCellAsString(cell);
                headers.add(header == null ? "" : header.trim());
            }
            List<ColumnMetadata> targetColumns =
                    prepareSupport.matchColumns(headers, resolved, tableName, "Excel");
            if (targetColumns.isEmpty()) {
                throw new ImportFailureException(1, "Excel file's header row contains no columns");
            }

            int numColumns = targetColumns.size();
            int lastRowNum = sheet.getLastRowNum();

            return new ImportSource() {
                private int currentRow = 1; // start after header row 0

                @Override
                public List<ColumnMetadata> targetColumns() {
                    return targetColumns;
                }

                @Override
                public boolean hasNextRow() {
                    while (currentRow <= lastRowNum) {
                        if (!isEmptyExcelRow(sheet.getRow(currentRow))) {
                            return true;
                        }
                        currentRow++;
                    }
                    return false;
                }

                @Override
                public ImportRow nextRow() {
                    Row row = sheet.getRow(currentRow);
                    int fileRowNumber = currentRow + 1; // 1-based, header included
                    currentRow++;
                    String[] values = new String[numColumns];
                    for (int i = 0; i < numColumns; i++) {
                        values[i] = readExcelCellAsString(row.getCell(i));
                    }
                    return new ImportRow(values, fileRowNumber);
                }

                @Override
                public void close() throws IOException {
                    workbook.close();
                }
            };
        } catch (RuntimeException e) {
            try { workbook.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    // --- Excel cell reading ---

    private boolean isEmptyExcelRow(Row row) {
        if (row == null) {
            return true;
        }
        short lastCellNum = row.getLastCellNum(); // 1-based count; -1 when the row has no cells.
        for (int c = 0; c < lastCellNum; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static final DateTimeFormatter EXCEL_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXCEL_DATE_TIME_MILLIS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Converts an Excel cell to a plain string for the shared import parameter
     * conversion (null = SQL NULL). Whole numbers lose the ".0" tail, decimals avoid
     * scientific notation, date-formatted cells become "yyyy-MM-dd HH:mm:ss[.SSS]",
     * formulas use their cached result.
     */
    private String readExcelCellAsString(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> {
                String value = cell.getStringCellValue();
                yield value.isEmpty() ? null : value;
            }
            case NUMERIC -> readExcelNumericCellAsString(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null; // BLANK or unsupported cached type
        };
    }

    private String readExcelNumericCellAsString(Cell cell) {
        double value = cell.getNumericCellValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            return dateTime.getNano() == 0
                    ? dateTime.format(EXCEL_DATE_TIME_FORMAT)
                    : dateTime.format(EXCEL_DATE_TIME_MILLIS_FORMAT);
        }
        // Whole numbers without a ".0" tail; other decimals via BigDecimal to avoid
        // scientific notation.
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return new BigDecimal(String.valueOf(value)).toPlainString();
    }
}
