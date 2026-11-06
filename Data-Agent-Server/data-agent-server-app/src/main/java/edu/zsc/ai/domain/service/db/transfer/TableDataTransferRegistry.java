package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of all table-data exporters and importers. Spring injects every
 * {@link TableDataExporter} / {@link TableDataImporter} bean and indexes them
 * by their format keys (fileType + aliases), so callers resolve a strategy by
 * name instead of switching on formats.
 */
@Component
public class TableDataTransferRegistry {

    private final Map<String, TableDataExporter> exporters;
    private final Map<String, TableDataImporter> importers;

    public TableDataTransferRegistry(List<TableDataExporter> exporterList,
                                     List<TableDataImporter> importerList) {
        Map<String, TableDataExporter> exporterMap = new HashMap<>();
        for (TableDataExporter exporter : exporterList) {
            exporterMap.put(normalize(exporter.fileType()), exporter);
            for (String alias : exporter.aliases()) {
                exporterMap.put(normalize(alias), exporter);
            }
        }
        this.exporters = Map.copyOf(exporterMap);

        Map<String, TableDataImporter> importerMap = new HashMap<>();
        for (TableDataImporter importer : importerList) {
            importerMap.put(normalize(importer.fileType()), importer);
            for (String alias : importer.aliases()) {
                importerMap.put(normalize(alias), importer);
            }
        }
        this.importers = Map.copyOf(importerMap);
    }

    public TableDataExporter resolveExporter(String fileType) {
        TableDataExporter exporter = exporters.get(normalize(fileType));
        if (exporter == null) {
            throw new BusinessException("Export format '" + fileType + "' is not supported yet");
        }
        return exporter;
    }

    public TableDataImporter resolveImporter(String fileType) {
        TableDataImporter importer = importers.get(normalize(fileType));
        if (importer == null) {
            throw new BusinessException("Import format '" + fileType + "' is not supported yet");
        }
        return importer;
    }

    private static String normalize(String fileType) {
        return fileType == null ? "" : fileType.trim().toUpperCase(Locale.ROOT);
    }
}
