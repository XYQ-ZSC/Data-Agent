package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.AbstractTableDataImporter;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.CreateTableDdlGenerator;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.InferColumnTypeSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Imports rows from a CSV file. The first CSV line is a header row whose names
 * must match table columns; all inserts run in one transaction.
 */
@Component
public class CsvTableDataImporter extends AbstractTableDataImporter {

    /** Leading bytes inspected when auto-detecting the CSV charset. */
    private static final int ENCODE_DETECT_SAMPLE_SIZE = 8192;

    public CsvTableDataImporter(ConnectionService connectionService,
                                ImportPrepareSupport prepareSupport,
                                ColumnValueConverter valueConverter,
                                ImportBatchSupport batchSupport,
                                InferColumnTypeSupport inferSupport,
                                CreateTableDdlGenerator ddlGenerator) {
        super(connectionService, prepareSupport, valueConverter, batchSupport, inferSupport, ddlGenerator);
    }

    @Override
    public String fileType() {
        return "CSV";
    }

    @Override
    protected PeekResult peekHeadersAndSample(PushbackInputStream pushback, String encoding) throws IOException {
        // Read up to 64 KB — header + 50 data rows almost always fit.
        byte[] chunk = pushback.readNBytes(64 * 1024);
        if (chunk.length == 0) {
            return PeekResult.empty();
        }
        try {
            Charset charset = detectCharset(chunk, encoding);
            String text = charset.decode(java.nio.ByteBuffer.wrap(chunk)).toString();
            // Strip leading BOM if present so CSV header detection sees clean text
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }

            CSVFormat csvFormat = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            try (CSVParser parser = csvFormat.parse(new java.io.StringReader(text))) {
                List<String> headers = parser.getHeaderNames();
                if (headers == null || headers.isEmpty()) {
                    return PeekResult.empty();
                }
                List<String[]> samples = new ArrayList<>();
                int limit = InferColumnTypeSupport.SAMPLE_ROW_LIMIT;
                for (CSVRecord record : parser) {
                    if (samples.size() >= limit) break;
                    String[] row = new String[headers.size()];
                    for (int i = 0; i < headers.size(); i++) {
                        row[i] = record.isSet(i) ? record.get(i) : null;
                    }
                    samples.add(row);
                }
                // CSV streams from the pushed-back buffer, so no content is returned here.
                return new PeekResult(headers, samples, null);
            } catch (RuntimeException ex) {
                // malformed header or bad CSV — surface so that prepare() fails later with a better message
                throw new IOException("Failed to peek CSV header: " + ex.getMessage(), ex);
            }
        } finally {
            // Always push back the consumed bytes so the real parse begins at position 0.
            pushback.unread(chunk);
        }
    }

    /** BOM-aware charset detection purely on a byte sample, without touching the stream. */
    private Charset detectCharset(byte[] sample, String requested) {
        Charset explicit = explicitCharset(requested);
        return explicit != null ? explicit : sniffCharset(sample, sample.length);
    }

    /** Resolves an explicit non-AUTO charset request, or {@code null} to auto-detect. */
    private Charset explicitCharset(String requested) {
        if (requested != null && !requested.isBlank() && !"AUTO".equalsIgnoreCase(requested.trim())) {
            return Charset.forName(requested.trim().toUpperCase(Locale.ROOT));
        }
        return null;
    }

    /**
     * Sniffs the charset from a byte sample: BOM wins (UTF-8/UTF-16LE/UTF-16BE),
     * otherwise strict UTF-8 validation decides between UTF-8 and GBK.
     */
    private Charset sniffCharset(byte[] sample, int length) {
        if (length >= 3 && (sample[0] & 0xFF) == 0xEF && (sample[1] & 0xFF) == 0xBB
                && (sample[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFF && (sample[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFE && (sample[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return looksLikeValidUtf8(sample, length) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
    }

    @Override
    protected ImportSource prepare(InputStream inputStream, String encoding,
                                   ImportPrepareSupport.ResolvedTableColumns resolved,
                                   String tableName) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(inputStream, ENCODE_DETECT_SAMPLE_SIZE);
        Charset charset = resolveCharset(pushback, encoding);
        BufferedReader reader = new BufferedReader(new InputStreamReader(pushback, charset));
        reader.mark(1);
        int firstChar = reader.read();
        if (firstChar != '\uFEFF') {
            reader.reset();
        }

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        CSVParser parser = csvFormat.parse(reader);
        try {
            List<String> csvHeaders = parser.getHeaderNames();
            if (csvHeaders.isEmpty()) {
                throw new ImportFailureException(1, "CSV file is empty or missing a header row");
            }

            List<ColumnMetadata> targetColumns =
                    prepareSupport.matchColumns(csvHeaders, resolved, tableName, "CSV");

            int numColumns = targetColumns.size();
            Iterator<CSVRecord> iterator = parser.iterator();

            return new ImportSource() {
                @Override
                public List<ColumnMetadata> targetColumns() {
                    return targetColumns;
                }

                @Override
                public boolean hasNextRow() {
                    return iterator.hasNext();
                }

                @Override
                public ImportRow nextRow() {
                    CSVRecord record = iterator.next();
                    int fileRowNumber = (int) record.getRecordNumber() + 1;
                    String[] values = new String[numColumns];
                    for (int i = 0; i < numColumns; i++) {
                        values[i] = record.isSet(i) ? record.get(i) : null;
                    }
                    return new ImportRow(values, fileRowNumber);
                }

                @Override
                public void close() throws IOException {
                    parser.close();
                }
            };
        } catch (RuntimeException e) {
            parser.close();
            throw e;
        }
    }

    // --- Charset detection ---

    /**
     * Resolves the charset of the uploaded CSV. An explicit request encoding
     * (UTF-8/GBK) wins; "AUTO" (default) sniffs the byte order mark first and
     * falls back to strict UTF-8 validation on a leading sample: valid UTF-8
     * keeps UTF-8, otherwise the file is decoded as GBK.
     */
    private Charset resolveCharset(PushbackInputStream inputStream, String requested) throws IOException {
        Charset explicit = explicitCharset(requested);
        if (explicit != null) {
            return explicit;
        }
        byte[] sample = new byte[ENCODE_DETECT_SAMPLE_SIZE];
        int sampleLength = inputStream.readNBytes(sample, 0, sample.length);
        if (sampleLength <= 0) {
            return StandardCharsets.UTF_8;
        }
        inputStream.unread(sample, 0, sampleLength);
        return sniffCharset(sample, sampleLength);
    }

    /**
     * Strict UTF-8 structure check on a leading sample; a multi-byte sequence
     * cut off at the end of the sample is tolerated.
     */
    private boolean looksLikeValidUtf8(byte[] data, int length) {
        int i = 0;
        while (i < length) {
            int lead = data[i] & 0xFF;
            int sequenceLength;
            if (lead < 0x80) {
                sequenceLength = 1;
            } else if ((lead & 0xE0) == 0xC0) {
                sequenceLength = 2;
            } else if ((lead & 0xF0) == 0xE0) {
                sequenceLength = 3;
            } else if ((lead & 0xF8) == 0xF0) {
                sequenceLength = 4;
            } else {
                return false;
            }
            if (i + sequenceLength > length) {
                return true;
            }
            for (int j = 1; j < sequenceLength; j++) {
                if ((data[i + j] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += sequenceLength;
        }
        return true;
    }
}
