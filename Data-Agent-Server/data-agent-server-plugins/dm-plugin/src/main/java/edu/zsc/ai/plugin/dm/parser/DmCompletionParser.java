package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.model.sql.*;

import java.util.*;

/** Tolerant DM completion analysis. Every request owns its tokens and query scopes. */
public final class DmCompletionParser {
    public static final DmCompletionParser INSTANCE = new DmCompletionParser();
    private DmCompletionParser() {}

    public CompletionContext analyze(String sql, int cursorOffset) {
        return new Analysis(sql == null ? "" : sql, cursorOffset).analyze();
    }

    private enum Kind { WORD, QUOTED, STRING, COMMENT, SYMBOL, NUMBER }
    private record Lexeme(Kind kind, String value, int start, int end, boolean closed) {
        boolean is(String word) { return (kind == Kind.WORD || kind == Kind.SYMBOL) && value.equalsIgnoreCase(word); }
        CompletionIdentifier identifier() {
            return kind == Kind.QUOTED ? new CompletionIdentifier(value, true)
                    : CompletionIdentifier.unquoted(value);
        }
    }
    private static final Set<String> STRUCTURAL = Set.of(
            "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER",
            "NATURAL", "ON", "USING", "GROUP", "ORDER", "HAVING", "BY", "UNION", "INTERSECT", "MINUS",
            "EXCEPT", "AS", "WITH", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM",
            "FUNCTION", "PROCEDURE", "SCHEMA", "AND", "OR", "NOT", "IN", "IS", "NULL", "EXISTS",
            "CASE", "WHEN", "THEN", "ELSE", "END", "LIMIT", "OFFSET", "FETCH", "FOR", "OF",
            "CONNECT", "START", "RETURNING", "ASC", "DESC", "NULLS", "FIRST", "LAST", "CALL");
    private static boolean identifier(Lexeme t) {
        return t.kind == Kind.QUOTED || t.kind == Kind.WORD
                && !STRUCTURAL.contains(t.value.toUpperCase(Locale.ROOT));
    }
    private static boolean nameChar(int c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static List<Lexeme> tokenize(String text) {
        List<Lexeme> out = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            int start = i;
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (text.startsWith("--", i)) {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                out.add(new Lexeme(Kind.COMMENT, "", start, i, i < text.length()));
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                out.add(new Lexeme(Kind.COMMENT, "", start, i, end >= 0));
            } else if ((c == 'q' || c == 'Q') && i + 2 < text.length() && text.charAt(i + 1) == '\'') {
                char open = text.charAt(i + 2);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int end = text.indexOf("" + close + '\'', i + 3);
                i = end < 0 ? text.length() : end + 2;
                out.add(new Lexeme(Kind.STRING, "", start, i, end >= 0));
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == quote) {
                        if (i < text.length() && text.charAt(i) == quote) { value.append(quote); i++; }
                        else { closed = true; break; }
                    } else value.append(c);
                }
                out.add(new Lexeme(quote == '"' ? Kind.QUOTED : Kind.STRING, value.toString(), start, i, closed));
            } else if (nameChar(text.codePointAt(i))) {
                boolean number = Character.isDigit(text.codePointAt(i));
                while (i < text.length() && nameChar(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                out.add(new Lexeme(number ? Kind.NUMBER : Kind.WORD, text.substring(start, i), start, i, true));
            } else {
                i++;
                out.add(new Lexeme(Kind.SYMBOL, text.substring(start, i), start, i, true));
            }
        }
        return out;
    }

    private static final class Cte {
        final List<CompletionIdentifier> columns;
        Node body;
        Cte(List<CompletionIdentifier> columns) { this.columns = columns; }
    }
    private static final class Relation {
        final CompletionIdentifier qualifier;
        final CompletionTableRef table;
        final Node body;
        final Cte cte;
        final boolean aliased;
        Relation(CompletionIdentifier qualifier, CompletionTableRef table, Node body, Cte cte, boolean aliased) {
            this.qualifier = qualifier; this.table = table; this.body = body; this.cte = cte; this.aliased = aliased;
        }
        CompletionRelation model(ModelState state) {
            Node source = cte != null ? cte.body : body;
            CompletionQuery query = source == null ? null : source.model(state, cte == null ? List.of() : cte.columns);
            if (cte != null && query == null) query = new CompletionQuery(List.of(), List.of(), cte.columns);
            return new CompletionRelation(qualifier, table, query, body != null || cte != null, aliased);
        }
    }
    private static final class ModelState {
        final Set<Node> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Node, CompletionQuery> memo = new IdentityHashMap<>();
    }
    private static final class Node {
        final int start, end, left, right;
        final Node parent;
        final Map<String, Cte> ctes;
        final List<Relation> relations = new ArrayList<>();
        final List<CompletionProjection> projections = new ArrayList<>();
        Node(int start, int end, int left, int right, Node parent, Map<String, Cte> ctes) {
            this.start = start; this.end = end; this.left = left; this.right = right;
            this.parent = parent; this.ctes = new LinkedHashMap<>(ctes);
        }
        CompletionQuery model(ModelState state, List<CompletionIdentifier> explicit) {
            if (state.memo.containsKey(this)) return state.memo.get(this);
            if (state.visiting.size() >= 64 || !state.visiting.add(this)) return new CompletionQuery(List.of(), List.of(), explicit);
            List<CompletionRelation> sources = relations.stream().map(r -> r.model(state)).toList();
            state.visiting.remove(this);
            CompletionQuery query = new CompletionQuery(List.copyOf(projections), sources, explicit);
            state.memo.put(this, query);
            return query;
        }
    }

    private static final class Analysis {
        final String text;
        final int cursor;
        final List<Lexeme> lexical, tokens;
        final int[] closes;
        final List<Node> nodes = new ArrayList<>();
        int replaceStart, replaceEnd;
        String prefix = "";
        boolean quoted;

        Analysis(String text, int cursor) {
            this.text = text; this.cursor = Math.max(0, Math.min(cursor, text.length()));
            lexical = tokenize(text);
            tokens = lexical.stream().filter(t -> t.kind != Kind.COMMENT).toList();
            closes = new int[tokens.size()];
            Arrays.fill(closes, tokens.size());
            Deque<Integer> opens = new ArrayDeque<>();
            for (int i = 0; i < tokens.size(); i++) {
                if (at(i, "(")) opens.push(i);
                else if (at(i, ")") && !opens.isEmpty()) closes[opens.pop()] = i;
            }
        }
        boolean at(int i, String value) { return i >= 0 && i < tokens.size() && tokens.get(i).is(value); }
        int boundary(int index) { return index < tokens.size() ? tokens.get(index).start : text.length(); }
        int close(int index, int end) { return Math.min(closes[index], end); }

        CompletionContext analyze() {
            replaceStart = replaceEnd = cursor;
            for (Lexeme token : lexical) {
                boolean inside = token.start < cursor && (cursor < token.end || cursor == token.end && !token.closed);
                if (inside && (token.kind == Kind.STRING || token.kind == Kind.COMMENT)) return context(CompletionScope.NONE, null, null, List.of(), Map.of(), false, List.of());
                if ((token.kind == Kind.WORD || token.kind == Kind.QUOTED || token.is("*"))
                        && token.start <= cursor && cursor <= token.end) {
                    replaceStart = token.start; replaceEnd = token.end; quoted = token.kind == Kind.QUOTED;
                    int end = cursor;
                    if (quoted && token.closed && end == token.end) end--;
                    prefix = text.substring(token.start + (quoted ? 1 : 0), Math.max(token.start + (quoted ? 1 : 0), end));
                    if (quoted) prefix = prefix.replace("\"\"", "\"");
                    break;
                }
            }
            int start = 0, end = tokens.size(), depth = 0;
            for (int i = 0; i < tokens.size(); i++) {
                if (at(i, "(")) depth++;
                else if (at(i, ")")) depth = Math.max(0, depth - 1);
                else if (at(i, ";") && depth == 0) {
                    if (tokens.get(i).start < cursor) start = i + 1;
                    else { end = i; break; }
                }
            }
            Node root = parseQuery(start, end, null, Map.of(), 0);
            Node active = nodes.stream().filter(n -> n.left <= cursor && cursor <= n.right)
                    .min(Comparator.comparingInt(n -> n.right - n.left)).orElse(root);
            List<CompletionRelation> relations = visible(active);
            Map<String, CompletionQuery> ctes = new LinkedHashMap<>();
            if (active != null) active.ctes.forEach((name, cte) -> ctes.put(name, cte.body == null
                    ? new CompletionQuery(List.of(), List.of(), cte.columns) : cte.body.model(new ModelState(), cte.columns)));
            List<Lexeme> before = tokens.stream().filter(t -> t.end <= replaceStart
                    && (active == null || t.start >= active.left)).toList();
            String clause = currentClause(before);
            int expressionDepth = 0;
            for (Lexeme token : before) {
                if (token.is("(")) expressionDepth++;
                else if (token.is(")")) expressionDepth = Math.max(0, expressionDepth - 1);
            }
            boolean selectList = clause.equals("SELECT") && expressionDepth == 0;
            if (before.isEmpty() || before.get(before.size() - 1).is(";")) return context(CompletionScope.KEYWORD, null, null, relations, ctes, false, List.of());
            Lexeme last = before.get(before.size() - 1);
            if (last.is(".")) {
                List<CompletionIdentifier> chain = qualifier(before);
                CompletionTableRef table = chain.size() >= 2 ? CompletionTableRef.of(chain.get(chain.size()-2).name(), chain.get(chain.size()-1).name()) : null;
                return context(CompletionScope.QUALIFIED, chain.size() == 1 ? chain.get(0).name() : null, table, relations, ctes, selectList, List.of());
            }
            if (last.is("JOIN")) return context(CompletionScope.JOIN, null, null, relations, ctes, false, List.of());
            if (last.is("FROM") || last.is("INTO") || last.is("UPDATE") || last.is("USING")
                    || last.is(",") && clause.equals("FROM") || last.is("TABLE") && before.stream().anyMatch(t -> t.is("ALTER") || t.is("DROP") || t.is("TRUNCATE")))
                return context(CompletionScope.TABLE, null, null, relations, ctes, false, List.of());
            if (last.is("CALL") || last.is("EXECUTE") || last.is("PROCEDURE")) return context(CompletionScope.PROCEDURE, null, null, relations, ctes, false, List.of());
            if (last.is("FUNCTION")) return context(CompletionScope.FUNCTION, null, null, relations, ctes, false, List.of());
            int insertOpen = insertColumnsOpen(active);
            if (insertOpen >= 0) {
                List<String> used = tokens.subList(insertOpen + 1, tokens.size()).stream()
                        .filter(t -> t.end <= replaceStart && identifier(t)).map(t -> t.identifier().name()).toList();
                return context(CompletionScope.INSERT_COLUMN, null, null, relations, ctes, false, used);
            }
            if (clause.equals("SET") && (last.is("SET") || last.is(","))) {
                List<String> used = new ArrayList<>();
                for (int i = 0; i + 1 < before.size(); i++) if (identifier(before.get(i)) && before.get(i+1).is("=")) used.add(before.get(i).identifier().name());
                return context(CompletionScope.UPDATE_COLUMN, null, null, relations, ctes, false, used);
            }
            return context(CompletionScope.COLUMN, null, null, relations, ctes, selectList, List.of());
        }

        private int insertColumnsOpen(Node node) {
            if (node == null || !at(node.start, "INSERT")) return -1;
            for (int i = node.start; i < node.end; i++) {
                if (at(i, "VALUES") || at(i, "SELECT")) break;
                if (at(i, "(") && tokens.get(i).start < cursor && cursor <= boundary(close(i, node.end))) return i;
            }
            return -1;
        }
        private List<CompletionRelation> visible(Node node) {
            Map<String, CompletionRelation> bindings = new LinkedHashMap<>();
            for (Node current = node; current != null; current = current.parent) {
                for (Relation relation : current.relations) bindings.putIfAbsent(relation.qualifier.name(), relation.model(new ModelState()));
            }
            return List.copyOf(bindings.values());
        }
        private CompletionContext context(CompletionScope scope, String qualifier, CompletionTableRef table,
                                           List<CompletionRelation> relations, Map<String, CompletionQuery> ctes,
                                           boolean selectList, List<String> excluded) {
            Map<String, CompletionTableRef> aliases = new LinkedHashMap<>();
            List<CompletionTableRef> tables = new ArrayList<>();
            Map<String, List<String>> derived = new LinkedHashMap<>(), cteColumns = new LinkedHashMap<>();
            ctes.forEach((name, query) -> cteColumns.put(name, names(query)));
            for (CompletionRelation relation : relations) {
                if (relation.logical()) { if (relation.aliased()) derived.put(relation.qualifier().name(), names(relation.query())); }
                else if (relation.aliased()) aliases.put(relation.qualifier().name(), relation.table());
                else tables.add(relation.table());
            }
            return new CompletionContext(scope, qualifier, table, aliases, tables, List.copyOf(ctes.keySet()), cteColumns,
                    derived, replaceStart, replaceEnd, prefix, quoted, relations, ctes, selectList, excluded);
        }
        private List<String> names(CompletionQuery query) {
            if (query == null) return List.of();
            if (!query.explicitColumns().isEmpty()) return query.explicitColumns().stream().map(CompletionIdentifier::name).toList();
            return query.projections().stream().filter(p -> p.output() != null).map(p -> p.output().name()).toList();
        }
        private List<CompletionIdentifier> qualifier(List<Lexeme> before) {
            List<CompletionIdentifier> out = new ArrayList<>();
            for (int i = before.size()-2; i >= 0 && identifier(before.get(i)); i -= 2) {
                out.add(0, before.get(i).identifier());
                if (i == 0 || !before.get(i-1).is(".")) break;
            }
            return out;
        }
        private String currentClause(List<Lexeme> before) {
            String clause = "";
            int depth = 0;
            for (Lexeme token : before) {
                if (token.is("(")) depth++;
                else if (token.is(")")) depth = Math.max(0, depth-1);
                else if (depth == 0 && List.of("SELECT","FROM","WHERE","GROUP","ORDER","HAVING","SET","VALUES","ON").stream().anyMatch(token::is)) clause = token.value.toUpperCase(Locale.ROOT);
            }
            return clause;
        }

        private Node parseQuery(int start, int end, Node parent, Map<String, Cte> inherited, int nesting) {
            Map<String, Cte> ctes = new LinkedHashMap<>(inherited);
            int body = start;
            if (nesting > 64) return new Node(start, end, boundary(start), boundary(end), parent, ctes);
            if (at(body, "WITH")) {
                body++;
                if (at(body, "RECURSIVE")) body++;
                while (body < end && identifier(tokens.get(body))) {
                    String name = tokens.get(body++).identifier().name();
                    List<CompletionIdentifier> explicit = new ArrayList<>();
                    if (at(body, "(")) {
                        int stop = close(body, end);
                        for (int j = body+1; j < stop; j++) if (identifier(tokens.get(j))) explicit.add(tokens.get(j).identifier());
                        body = Math.min(stop+1, end);
                    }
                    if (at(body, "AS")) body++;
                    if (!at(body, "(")) break;
                    Cte cte = new Cte(List.copyOf(explicit));
                    ctes.put(name, cte);
                    int stop = close(body, end);
                    cte.body = parseQuery(body+1, stop, parent, ctes, nesting+1);
                    body = Math.min(stop+1, end);
                    if (!at(body, ",")) break;
                    body++;
                }
            }
            List<Integer> cuts = new ArrayList<>();
            cuts.add(body);
            for (int i = body; i < end; i++) {
                if (at(i, "(")) i = close(i, end);
                else if (at(i, "UNION") || at(i,"INTERSECT") || at(i,"MINUS") || at(i,"EXCEPT")) cuts.add(i);
            }
            Node first = null;
            for (int b = 0; b < cuts.size(); b++) {
                int left = cuts.get(b), right = b+1 < cuts.size() ? cuts.get(b+1) : end;
                if (b > 0) { left++; if (at(left,"ALL") || at(left,"DISTINCT")) left++; }
                Node node = new Node(left, right, boundary(left), boundary(right), parent, ctes);
                nodes.add(node);
                if (first == null) first = node;
                projections(node);
                for (int i = left; i < right; i++) {
                    if (at(i,"FROM") || at(i,"JOIN") || at(i,"UPDATE") || at(i,"INTO")
                            || at(i,"USING") && at(left,"MERGE")) {
                        boolean from = at(i,"FROM");
                        int consumed = relation(node, i+1, right, nesting);
                        if (consumed > i) {
                            i = consumed;
                            while (from && at(i+1,",")) {
                                int next = relation(node, i+2, right, nesting);
                                if (next <= i+1) break;
                                i = next;
                            }
                        }
                    } else if (at(i,"(")) {
                        int stop = close(i,right);
                        childQueries(i+1, stop, node, ctes, nesting+1);
                        i = stop;
                    }
                }
            }
            return first;
        }
        private void childQueries(int start, int end, Node parent, Map<String,Cte> ctes, int nesting) {
            if (nesting > 64) return;
            if (at(start,"SELECT") || at(start,"WITH")) { parseQuery(start,end,parent,ctes,nesting); return; }
            for (int i = start; i < end; i++) if (at(i,"(")) {
                int stop = close(i,end);
                childQueries(i+1,stop,parent,ctes,nesting+1);
                i = stop;
            }
        }
        private int relation(Node node, int start, int end, int nesting) {
            if (start >= end) return start-1;
            if (at(start,"(")) {
                int stop = close(start,end);
                if (!at(start+1,"SELECT") && !at(start+1,"WITH")) return start-1;
                Node query = parseQuery(start+1,stop,null,node.ctes,nesting+1);
                int alias = alias(Math.min(stop+1,end),end);
                if (alias < 0) return Math.min(stop,end-1);
                node.relations.add(new Relation(tokens.get(alias).identifier(),null,query,null,true));
                return alias;
            }
            if (!identifier(tokens.get(start))) return start-1;
            CompletionIdentifier name = tokens.get(start).identifier();
            String schema = null;
            int last = start;
            if (at(last+1,".") && last+2 < end && identifier(tokens.get(last+2))) {
                schema = name.name(); name = tokens.get(last+2).identifier(); last += 2;
            }
            int alias = alias(last+1,end);
            CompletionIdentifier owner = alias >= 0 ? tokens.get(alias).identifier() : name;
            node.relations.add(new Relation(owner,CompletionTableRef.of(schema,name.name()),null,
                    schema == null ? node.ctes.get(name.name()) : null,alias >= 0));
            return alias >= 0 ? alias : last;
        }
        private int alias(int start, int end) {
            if (at(start,"AS")) start++;
            return start < end && identifier(tokens.get(start)) ? start : -1;
        }
        private void projections(Node node) {
            int start = node.start;
            if (!at(start,"SELECT")) return;
            start++;
            if (at(start,"DISTINCT") || at(start,"ALL")) start++;
            if (at(start,"TOP")) {
                start++;
                if (at(start,"(")) start = Math.min(close(start,node.end)+1,node.end);
                else if (start < node.end) start++;
                if (at(start,"PERCENT")) start++;
            }
            int item = start;
            for (int i = start; i <= node.end; i++) {
                if (i < node.end && at(i,"(")) { i = close(i,node.end); continue; }
                boolean stop = i == node.end || at(i,"FROM") || at(i,"INTO");
                if (stop || at(i,",")) {
                    projection(tokens.subList(item,i),node.projections);
                    item = i+1;
                    if (stop) break;
                }
            }
        }
        private void projection(List<Lexeme> item, List<CompletionProjection> out) {
            if (item.isEmpty()) return;
            int depth = 0, as = -1;
            for (int i = 0; i < item.size(); i++) {
                if (item.get(i).is("(")) depth++;
                else if (item.get(i).is(")")) depth--;
                else if (depth == 0 && item.get(i).is("AS")) as = i;
            }
            CompletionIdentifier output = null;
            List<Lexeme> expression = item;
            if (as >= 0 && as+1 < item.size() && identifier(item.get(as+1))) {
                output = item.get(as+1).identifier(); expression = item.subList(0,as);
            } else if (item.size() > 1 && identifier(item.get(item.size()-1))) {
                Lexeme prior = item.get(item.size()-2), last = item.get(item.size()-1);
                if (last.start > prior.end && !List.of(".","+","-","*","/","|","=").contains(prior.value)) {
                    output = last.identifier(); expression = item.subList(0,item.size()-1);
                }
            }
            if (expression.size() == 1 && expression.get(0).is("*")) out.add(new CompletionProjection(null,null,null,true));
            else if (expression.size() == 3 && identifier(expression.get(0)) && expression.get(1).is(".") && expression.get(2).is("*"))
                out.add(new CompletionProjection(null,expression.get(0).identifier(),null,true));
            else {
                CompletionIdentifier qualifier = null, column = null;
                if (expression.size() == 1 && identifier(expression.get(0))) column = expression.get(0).identifier();
                else if (expression.size() == 3 && identifier(expression.get(0)) && expression.get(1).is(".") && identifier(expression.get(2))) {
                    qualifier = expression.get(0).identifier(); column = expression.get(2).identifier();
                }
                if (output == null) output = column;
                if (output != null) out.add(new CompletionProjection(output,qualifier,column,false));
            }
        }
    }
}
