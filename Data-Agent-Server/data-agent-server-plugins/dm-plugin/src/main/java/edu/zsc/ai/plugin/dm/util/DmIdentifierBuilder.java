package edu.zsc.ai.plugin.dm.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Builds fully qualified DM identifiers. DM has schemas but no catalogs,
 * so the first part is the schema name: "SCHEMA"."OBJECT".
 */
public final class DmIdentifierBuilder {

    private DmIdentifierBuilder() {
    }

    public static String buildFullIdentifier(String schema, String objectName) {
        if (StringUtils.isBlank(objectName)) {
            throw new IllegalArgumentException("Object name must not be null or empty");
        }

        if (StringUtils.isNotBlank(schema)) {
            String quotedSchema = DmIdentifierEscaper.getInstance().quoteIdentifier(schema);
            String quotedObject = DmIdentifierEscaper.getInstance().quoteIdentifier(objectName);
            return String.format("%s.%s", quotedSchema, quotedObject);
        } else {
            return DmIdentifierEscaper.getInstance().quoteIdentifier(objectName);
        }
    }
}
