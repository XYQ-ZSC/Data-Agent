package edu.zsc.ai.plugin.sql;

import edu.zsc.ai.plugin.capability.SqlSplitter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;


public class DefaultSqlSplitter implements SqlSplitter {

    public static final DefaultSqlSplitter INSTANCE = new DefaultSqlSplitter();

    @Override
    public List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        if (StringUtils.isBlank(sql)) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean proceduralBlock = beginsWithProceduralBlock(sql);
        int len = sql.length();

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                current.append(c);
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    current.append(c).append(next);
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (inSingleQuote) {
                current.append(c);
                if (c == '\'' && next == '\'') {
                    // Escaped single quote inside string literal
                    current.append(next);
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                current.append(c);
                if (c == '"' && next == '"') {
                    current.append(next);
                    i++;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                current.append(c);
                continue;
            }

            if (c == '/' && next == '*') {
                inBlockComment = true;
                current.append(c);
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                current.append(c);
                continue;
            }

            if (c == '"') {
                inDoubleQuote = true;
                current.append(c);
                continue;
            }

            if (c == ';' && !proceduralBlock) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
                continue;
            }

            current.append(c);
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }

        return statements;
    }

    private boolean beginsWithProceduralBlock(String sql) {
        String remaining = sql.stripLeading();
        while (!remaining.isEmpty()) {
            if (remaining.startsWith("--")) {
                int newline = remaining.indexOf('\n');
                remaining = newline < 0 ? "" : remaining.substring(newline + 1).stripLeading();
            } else if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                remaining = end < 0 ? "" : remaining.substring(end + 2).stripLeading();
            } else {
                break;
            }
        }
        return remaining.matches("(?is)^(BEGIN|DECLARE)(?:\\s|$).*?");
    }
}
