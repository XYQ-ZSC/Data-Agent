package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.exception.BusinessException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which columns of a query result should be written into an export file.
 * Matching is case-insensitive and the export keeps the table's original column
 * order regardless of the order in which columns were requested.
 */
public final class ExportColumnSelector {

    private ExportColumnSelector() {
    }

    /**
     * @param headers          column names of the underlying query result
     * @param requestedColumns columns requested by the caller; {@code null} means all columns
     * @return ascending indexes into each result row for the columns to export
     */
    public static List<Integer> resolve(List<String> headers, List<String> requestedColumns) {
        if (requestedColumns == null) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < headers.size(); i++) {
                all.add(i);
            }
            return all;
        }

        Set<String> wanted = new LinkedHashSet<>();
        for (String name : requestedColumns) {
            if (name != null && !name.isBlank()) {
                wanted.add(name.trim());
            }
        }
        if (wanted.isEmpty()) {
            throw new BusinessException("At least one column must be selected for export");
        }

        Map<String, Integer> indexByName = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header != null) {
                indexByName.putIfAbsent(header.toLowerCase(Locale.ROOT), i);
            }
        }

        List<Integer> indexes = new ArrayList<>();
        for (String name : wanted) {
            Integer index = indexByName.get(name.toLowerCase(Locale.ROOT));
            if (index == null) {
                throw new BusinessException("Unknown column for export: " + name);
            }
            indexes.add(index);
        }
        indexes.sort(Comparator.naturalOrder());
        return indexes;
    }
}
