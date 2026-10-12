package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.model.sql.*;

import java.util.*;
import java.util.function.BiFunction;

import static edu.zsc.ai.plugin.dm.parser.DmCompletionParser.identifier;

/** Procedural declarations share the SQL lexer but have their own nested scopes. */
final class DmProceduralScopeAnalyzer {
    record Result(List<CompletionVariable> variables, boolean procedural) {}

    private final String text;
    private final List<DmCompletionParser.Lexeme> tokens;
    private final int[] closes;
    private final int replaceStart;
    private final BiFunction<Integer, Integer, CompletionQuery> queryParser;

    DmProceduralScopeAnalyzer(String text, List<DmCompletionParser.Lexeme> tokens, int[] closes,
                              int replaceStart, BiFunction<Integer, Integer, CompletionQuery> queryParser) {
        this.text = text;
        this.tokens = tokens;
        this.closes = closes;
        this.replaceStart = replaceStart;
        this.queryParser = queryParser;
    }

    private boolean at(int index, String value) {
        return index >= 0 && index < tokens.size() && tokens.get(index).is(value);
    }

    private int close(int index, int end) {
        return Math.min(closes[index], end);
    }

    private static final class Block {
        boolean declaring;
        boolean loop;
        final Map<String, CompletionVariable> bindings = new LinkedHashMap<>();
        final Map<String, List<CompletionVariable>> records = new LinkedHashMap<>();
        final Map<String, CompletionQuery> cursors = new LinkedHashMap<>();
        Block(boolean declaring) {
            this.declaring = declaring;
        }
    }
    /** Declarations are scoped separately from SQL query blocks; literals/comments were already tokenized. */
    Result analyze() {
        Deque<Block> blocks = new ArrayDeque<>();
        int cases = 0;
        Set<Integer> forLoops = new HashSet<>();
        for (int i = 0; i < tokens.size() && tokens.get(i).start() < replaceStart; i++) {
            // Each routine in a script owns a fresh scope; earlier statements may precede it.
            if (at(i, "CREATE")) {
                int routine = i + 1;
                if (at(routine, "OR") && at(routine + 1, "REPLACE")) routine += 2;
                if ((at(routine, "FUNCTION") || at(routine, "PROCEDURE"))
                        && routine + 1 < tokens.size() && identifier(tokens.get(routine + 1))) {
                    Block block = new Block(true);
                    blocks.push(block);
                    int next = routine + 2;
                    if (at(next, ".") && next + 1 < tokens.size()) next += 2;
                    if (at(next, "(")) {
                        int end = close(next, tokens.size());
                        declarations(block, next + 1, end, blocks, true);
                        i = end;
                    } else i = next - 1;
                    continue;
                }
            }
            if (at(i, "CASE") && !at(i - 1, "END")) {
                cases++;
                continue;
            }
            if (at(i, "FOR") && i + 2 < tokens.size() && identifier(tokens.get(i + 1)) && at(i + 2, "IN")) {
                int loop = i + 3;
                while (loop < tokens.size() && !at(loop, "LOOP")) {
                    if (at(loop, "(")) loop = close(loop, tokens.size());
                    loop++;
                }
                if (loop < tokens.size() && tokens.get(loop).start() < replaceStart) {
                    Block block = new Block(false);
                    block.loop = true;
                    CompletionIdentifier name = tokens.get(i + 1).identifier();
                    if (at(i + 3, "(") && (at(i + 4, "SELECT") || at(i + 4, "WITH"))) {
                        CompletionQuery query = queryParser.apply(i + 4, close(i + 3, tokens.size()));
                        block.bindings.put(name.name(), new CompletionVariable(name, "RECORD", null, List.of(), query));
                    } else {
                        CompletionQuery query = null;
                        if (i + 3 < tokens.size()) {
                            String cursorName = tokens.get(i + 3).identifier().name();
                            for (Block outer : blocks) {
                                if (outer.cursors.containsKey(cursorName)) {
                                    query = outer.cursors.get(cursorName);
                                    break;
                                }
                            }
                        }
                        block.bindings.put(name.name(), new CompletionVariable(name,
                                query == null ? "INTEGER" : "RECORD", null, List.of(), query));
                    }
                    blocks.push(block);
                    forLoops.add(loop);
                }
            }
            if (at(i, "LOOP") && !at(i - 1, "END") && !forLoops.contains(i) && !blocks.isEmpty()) {
                Block block = new Block(false);
                block.loop = true;
                blocks.push(block);
                continue;
            }
            if (at(i, "END") && at(i + 1, "LOOP")) {
                if (!blocks.isEmpty() && blocks.peek().loop) blocks.pop();
                i++;
                continue;
            }
            if (at(i, "DECLARE")) {
                blocks.push(new Block(true));
                continue;
            }
            if (at(i, "BEGIN")) {
                if (!blocks.isEmpty() && blocks.peek().declaring) blocks.peek().declaring = false;
                else blocks.push(new Block(false));
                continue;
            }
            if (at(i, "END") && !at(i + 1, "IF") && !at(i + 1, "LOOP") && !at(i + 1, "CASE")) {
                if (cases > 0) {
                    cases--;
                    continue;
                }
                if (!blocks.isEmpty()) blocks.pop();
                continue;
            }
            if (at(i, "END") && at(i + 1, "CASE")) {
                cases = Math.max(0, cases - 1);
                i++;
                continue;
            }
            if (!blocks.isEmpty() && blocks.peek().declaring
                    && (i == 0 || at(i - 1, "DECLARE") || at(i - 1, ";") || at(i - 1, "IS") || at(i - 1, "AS"))) {
                int end = i;
                while (end < tokens.size() && !at(end, ";") && !at(end, "BEGIN")) {
                    if (at(end, "(")) end = close(end, tokens.size());
                    end++;
                }
                if (end < tokens.size() && tokens.get(end).start() <= replaceStart) {
                    declarations(blocks.peek(), i, end, blocks, false);
                    i = end - 1;
                }
            }
        }
        Map<String, CompletionVariable> visible = new LinkedHashMap<>();
        for (Block block : blocks) block.bindings.forEach(visible::putIfAbsent);
        return new Result(List.copyOf(visible.values()), !blocks.isEmpty());
    }
    private void declarations(Block block, int start, int end, Deque<Block> blocks, boolean parameters) {
        if (!parameters && at(start, "CURSOR") && start + 1 < end && identifier(tokens.get(start + 1))) {
            CompletionIdentifier name = tokens.get(start + 1).identifier();
            for (int query = start + 2; query < end; query++) {
                if (at(query, "SELECT") || at(query, "WITH")) {
                    CompletionQuery model = queryParser.apply(query, end);
                    block.cursors.put(name.name(), model);
                    block.bindings.put(name.name(), new CompletionVariable(name, "CURSOR"));
                    break;
                }
            }
            return;
        }
        if (!parameters && at(start, "TYPE") && start + 3 < end
                && at(start + 2, "IS") && at(start + 3, "RECORD") && at(start + 4, "(")) {
            Block fields = new Block(true);
            declarations(fields, start + 5, close(start + 4, end), blocks, true);
            block.records.put(tokens.get(start + 1).identifier().name(), List.copyOf(fields.bindings.values()));
            return;
        }
        int item = start;
        for (int i = start; i <= end; i++) {
            if (i < end && at(i, "(")) {
                i = close(i, end);
                continue;
            }
            if (i == end || parameters && at(i, ",")) {
                if (item + 1 < i && identifier(tokens.get(item))) {
                    CompletionIdentifier name = tokens.get(item).identifier();
                    int type = item + 1;
                    while (type < i && (at(type, "IN") || at(type, "OUT") || at(type, "INOUT")
                            || at(type, "NOCOPY") || at(type, "CONSTANT"))) type++;
                    int finish = type;
                    while (finish < i && !at(finish, ":") && !at(finish, "=") && !at(finish, "DEFAULT")) finish++;
                    if (type < finish) {
                        String dataType = text.substring(tokens.get(type).start(), tokens.get(finish - 1).end());
                        CompletionTableRef row = null;
                        for (int j = type; j + 1 < finish; j++) {
                            if (at(j, "%") && at(j + 1, "ROWTYPE")) {
                                if (j - type == 1) row = CompletionTableRef.of(null, tokens.get(type).identifier().name());
                                else if (j - type == 3 && at(type + 1, "."))
                                    row = CompletionTableRef.of(tokens.get(type).identifier().name(), tokens.get(type + 2).identifier().name());
                            }
                        }
                        List<CompletionVariable> fields = List.of();
                        CompletionQuery query = null;
                        String typeName = tokens.get(type).identifier().name();
                        for (Block outer : blocks) {
                            if (outer.records.containsKey(typeName)) {
                                fields = outer.records.get(typeName);
                                break;
                            }
                        }
                        if (row != null && row.schema() == null) {
                            for (Block outer : blocks) {
                                if (outer.cursors.containsKey(row.name())) {
                                    query = outer.cursors.get(row.name());
                                    row = null;
                                    break;
                                }
                            }
                        }
                        block.bindings.put(name.name(), new CompletionVariable(name, dataType, row, fields, query));
                    }
                }
                item = i + 1;
            }
        }
    }
}
