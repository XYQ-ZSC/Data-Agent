package edu.zsc.ai.plugin.dm.util;

import org.apache.commons.lang3.StringUtils;

import edu.zsc.ai.plugin.capability.SqlIdentifierEscaper;

/**
 * DM (达梦) identifier escaper. DM uses double quotes to quote identifiers;
 * a double quote inside an identifier is escaped by doubling it.
 * Unquoted identifiers are stored uppercase by DM.
 */
public class DmIdentifierEscaper implements SqlIdentifierEscaper {

    private static final DmIdentifierEscaper INSTANCE = new DmIdentifierEscaper();

    private static final String DOUBLE_QUOTE = "\"";
    private static final String ESCAPED_DOUBLE_QUOTE = "\"\"";

    public static DmIdentifierEscaper getInstance() {
        return INSTANCE;
    }

    private DmIdentifierEscaper() {
    }

    @Override
    public String escapeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (StringUtils.contains(identifier, DOUBLE_QUOTE)) {
            return StringUtils.replace(identifier, DOUBLE_QUOTE, ESCAPED_DOUBLE_QUOTE);
        }
        return identifier;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (StringUtils.isEmpty(identifier)) {
            return identifier;
        }
        // Metadata names are already resolved by DM. Always quote each part so
        // a mixed-case or reserved-word name keeps its exact database spelling.
        return DOUBLE_QUOTE + escapeIdentifier(identifier) + DOUBLE_QUOTE;
    }

    /**
     * DM string literals only escape the single quote by doubling it;
     * backslash has no special meaning (Oracle compatible).
     */
    @Override
    public String escapeStringLiteral(String value) {
        if (value == null) {
            return null;
        }
        if (!StringUtils.contains(value, SINGLE_QUOTE)) {
            return value;
        }
        return StringUtils.replace(value, SINGLE_QUOTE, DOUBLE_SINGLE_QUOTE);
    }
}
