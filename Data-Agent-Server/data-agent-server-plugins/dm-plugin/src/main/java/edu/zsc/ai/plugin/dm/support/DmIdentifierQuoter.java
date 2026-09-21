package edu.zsc.ai.plugin.dm.support;

import org.apache.commons.lang3.StringUtils;

/**
 * Package-private double-quote identifier helper for DM (DaMeng).
 *
 * <p>DM uses double quotes as the identifier delimiter (SQL standard /
 * Oracle-compatible). Identifiers are always quoted here (rather than only when
 * they "need" quoting) so exact case is preserved and reserved words are safe.
 * Embedded double quotes are escaped by doubling them.
 *
 * <p>Kept inside the support package to avoid clashing with identifier utility
 * classes provided elsewhere in the plugin.
 *
 * @author hhz
 */
final class DmIdentifierQuoter {

    private static final char DOUBLE_QUOTE = '"';

    private DmIdentifierQuoter() {
    }

    /**
     * Quote an identifier with double quotes, always.
     *
     * @param identifier the raw identifier, must not be blank
     * @return the double-quoted identifier
     */
    static String quote(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            throw new IllegalArgumentException("Identifier must not be null or empty");
        }
        String escaped = StringUtils.replace(identifier, "\"", "\"\"");
        return DOUBLE_QUOTE + escaped + DOUBLE_QUOTE;
    }

    /**
     * Build a fully qualified, fully double-quoted identifier: "schema"."object".
     *
     * @param qualifier  the schema/qualifier name, may be blank
     * @param objectName the object name, must not be blank
     * @return the fully qualified quoted identifier
     */
    static String buildFullIdentifier(String qualifier, String objectName) {
        if (StringUtils.isBlank(objectName)) {
            throw new IllegalArgumentException("Object name must not be null or empty");
        }
        if (StringUtils.isNotBlank(qualifier)) {
            return quote(qualifier.trim()) + "." + quote(objectName.trim());
        }
        return quote(objectName.trim());
    }
}
