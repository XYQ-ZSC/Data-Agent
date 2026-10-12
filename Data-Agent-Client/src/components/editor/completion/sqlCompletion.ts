import type { SqlCompletionItem, SqlCompletionParams, SqlCompletionResponse } from '../../../types/sql';

export interface CancellationToken {
  isCancellationRequested: boolean;
  onCancellationRequested(listener: () => void): { dispose(): void };
}
export type CompletionTransport = (params: SqlCompletionParams, signal: AbortSignal) => Promise<SqlCompletionResponse>;
const empty = (): SqlCompletionResponse => ({ items: [], incomplete: false });

/** UTF-16 offsets match Monaco and the Java API. Quoted identifiers may contain doubled quotes/spaces. */
export function completionWord(sql: string, cursor: number) {
  let start = cursor, end = cursor, prefixEnd = cursor, quoted = false, suppressed = false;
  const before: string[] = [];
  const name = /[\p{L}\p{N}_$#]/u;
  for (let i = 0; i < sql.length;) {
    const begin = i;
    let closed = true;
    let type = 'symbol';
    if (/\s/u.test(sql[i])) { i++; continue; }
    if (sql.startsWith('--', i)) {
      type = 'literal'; i += 2;
      while (i < sql.length && !/[\r\n]/.test(sql[i])) i++;
      closed = i < sql.length;
    } else if (sql.startsWith('/*', i)) {
      type = 'literal'; const close = sql.indexOf('*/', i + 2);
      closed = close >= 0; i = closed ? close + 2 : sql.length;
    } else if (/[qQ]/.test(sql[i]) && sql[i + 1] === "'" && i + 2 < sql.length) {
      type = 'literal'; const delimiter = sql[i + 2];
      // Find the delimiter followed by a quote, allowing delimiters inside the literal.
      const finish = sql.indexOf(`${({ '[': ']', '(': ')', '{': '}', '<': '>' } as Record<string, string>)[delimiter] ?? delimiter}'`, i + 3);
      closed = finish >= 0; i = closed ? finish + 2 : sql.length;
    } else if (sql[i] === '"' || sql[i] === "'") {
      const quote = sql[i++]; type = quote === '"' ? 'quoted' : 'literal'; closed = false;
      while (i < sql.length) {
        if (sql[i++] === quote) {
          if (sql[i] === quote) i++;
          else { closed = true; break; }
        }
      }
    } else if (name.test(String.fromCodePoint(sql.codePointAt(i)!))) {
      type = 'word';
      while (i < sql.length && name.test(String.fromCodePoint(sql.codePointAt(i)!))) i += sql.codePointAt(i)! > 0xffff ? 2 : 1;
    } else i++;
    if (type === 'literal' && begin < cursor && (cursor < i || cursor === i && !closed)) suppressed = true;
    if ((type === 'word' || type === 'quoted' || sql.slice(begin, i) === '*') && begin <= cursor && cursor <= i) {
      start = begin; end = i; quoted = type === 'quoted';
      prefixEnd = quoted && closed && cursor === i ? cursor - 1 : cursor;
    } else if (i <= cursor && type !== 'literal') before.push(sql.slice(begin, i).toUpperCase());
  }
  let prefix = sql.slice(start + (quoted ? 1 : 0), prefixEnd);
  if (quoted) prefix = prefix.replace(/""/g, '"');
  return { start, end, prefix, quoted, suppressed, before };
}

export function staticCompletions(dictionary: SqlCompletionItem[], sql: string, cursor: number): SqlCompletionResponse {
  const word = completionWord(sql, cursor);
  const tokens = word.before.slice(word.before.lastIndexOf(';') + 1);
  const last = tokens[tokens.length - 1];
  if (word.suppressed || word.quoted || last === '.' || ['FROM', 'JOIN', 'INTO', 'UPDATE', 'USING', 'TABLE', 'CALL', 'EXECUTE', 'PROCEDURE'].includes(last ?? '')) return empty();
  const statement = tokens.length === 0;
  const statementKeywords = new Set(['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'WITH', 'CREATE', 'ALTER', 'DROP',
    'TRUNCATE', 'CALL', 'EXECUTE', 'COMMIT', 'ROLLBACK', 'SAVEPOINT', 'GRANT', 'REVOKE', 'EXPLAIN', 'DECLARE', 'BEGIN']);
  let clause = '';
  const clauses: string[] = [];
  for (const value of tokens) {
    if (value === '(') clauses.push(clause);
    else if (value === ')') clause = clauses.pop() ?? clause;
    else if (['SELECT', 'FROM', 'WHERE', 'ON', 'HAVING', 'SET', 'VALUES', 'GROUP', 'ORDER'].includes(value)) clause = value;
  }
  const functionOnly = last === 'FUNCTION';
  if (!statement && !functionOnly && !['SELECT', 'WHERE', 'ON', 'HAVING', 'SET', 'VALUES', 'GROUP', 'ORDER'].includes(clause)) return empty();
  const expressionStart = ['SELECT', 'DISTINCT', 'ALL', 'WHERE', 'ON', 'HAVING', 'BY', 'AND', 'OR', 'NOT', 'WHEN', 'THEN',
    'ELSE', 'SET', 'VALUES', 'BETWEEN', 'LIKE', 'IN', 'IS', 'RETURN', 'IF', 'ELSIF', 'WHILE', '(', ',', '=', '>', '<', '+', '-', '*', '/', ':'].includes(last ?? '');
  return { items: dictionary.filter(item => (statement || item.kind !== 'SNIPPET') && (!functionOnly || item.kind === 'FUNCTION')
    && (item.kind === 'KEYWORD' ? statement && statementKeywords.has(item.label.toUpperCase())
      : item.kind === 'FUNCTION' ? !statement && (functionOnly || expressionStart) : true)
    && item.filterText.toLowerCase().startsWith(word.prefix.toLowerCase()))
    .map(item => ({ ...item, replaceStart: word.start, replaceEnd: word.end,
      insertText: item.kind === 'FUNCTION' && /^\s*\(/.test(sql.slice(word.end)) ? item.label : item.insertText,
      snippet: item.kind === 'FUNCTION' && /^\s*\(/.test(sql.slice(word.end)) ? false : item.snippet })), incomplete: false };
}

/** Per-editor session: cancellation, late-response fencing and conservative complete-prefix reuse. */
export class SqlCompletionSession {
  private active?: AbortController;
  private sequence = 0;
  private failedSequence = -1;
  private snapshot?: { key: string; result: SqlCompletionResponse; expires: number };
  private cache = new Map<string, { prefix: string; result: SqlCompletionResponse; expires: number }>();
  constructor(private transport: CompletionTransport, private delayMs = 120) {}
  reset() { this.sequence++; this.active?.abort(); this.cache.clear(); this.snapshot = undefined; }

  /** Monaco awaits all providers in a language group. Publish local items first, then
   * refresh the same suggestion session once metadata arrives; separate providers alone
   * would still make static items wait for JDBC. */
  progressive(params: SqlCompletionParams, token: CancellationToken, isCurrent: () => boolean,
    identity: string, local: SqlCompletionResponse, onReady: () => void): Promise<SqlCompletionResponse> {
    const key = JSON.stringify([identity, params]);
    if (this.snapshot?.key === key && this.snapshot.expires > Date.now()) return Promise.resolve(this.snapshot.result);
    const pending = this.complete(params, token, isCurrent, identity);
    if (!local.items.length) return pending;
    const sequence = this.sequence;
    void pending.then(result => {
      if (sequence !== this.sequence || token.isCancellationRequested || !isCurrent()) return;
      const failed = this.failedSequence === sequence;
      this.snapshot = { key, result: failed ? local : result, expires: Date.now() + 10_000 };
      if (!failed) onReady();
    });
    return Promise.resolve({ ...local, incomplete: true });
  }

  async complete(params: SqlCompletionParams, token: CancellationToken, isCurrent: () => boolean, identity = ''): Promise<SqlCompletionResponse> {
    this.active?.abort();
    const controller = new AbortController(); this.active = controller;
    const sequence = ++this.sequence;
    const subscription = token.onCancellationRequested(() => controller.abort());
    const current = () => !controller.signal.aborted && !token.isCancellationRequested && sequence === this.sequence && isCurrent();
    const word = completionWord(params.sql, params.cursorOffset);
    const key = JSON.stringify([identity, params.connectionId, params.databaseName, params.schemaName, word.quoted,
      params.sql.slice(0, word.start), params.sql.slice(word.end)]);
    try {
      if (!current()) return empty();
      const cached = this.cache.get(key);
      if (cached && cached.expires > Date.now() && !cached.result.incomplete && word.prefix.startsWith(cached.prefix)) {
        return { items: cached.result.items.filter(item => word.quoted ? item.filterText.slice(1, -1).replace(/""/g, '"').startsWith(word.prefix)
          : item.filterText.toLowerCase().startsWith(word.prefix.toLowerCase()))
          .map(item => ({ ...item, replaceStart: word.start, replaceEnd: word.end })), incomplete: false };
      }
      await new Promise<void>(resolve => {
        const finish = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', finish); resolve(); };
        const timer = setTimeout(finish, this.delayMs);
        controller.signal.addEventListener('abort', finish, { once: true });
      });
      if (!current()) return empty();
      const result = await this.transport(params, controller.signal);
      if (!current()) return empty();
      // Reuse only ranges understood by the local lexical scanner. A dialect-specific
      // replacement outside this token must be requested again rather than shifted.
      if (result.items.every(item => item.replaceStart === word.start && item.replaceEnd === word.end)) {
        this.cache.set(key, { prefix: word.prefix, result, expires: Date.now() + 10_000 });
      }
      if (this.cache.size > 40) this.cache.delete(this.cache.keys().next().value!);
      return result;
    } catch { if (sequence === this.sequence) this.failedSequence = sequence; return empty(); }
    finally { subscription.dispose(); if (this.active === controller) this.active = undefined; }
  }
}

export function completionSortText(item: SqlCompletionItem, index: number) {
  return `${String(item.rank).padStart(4, '0')}:${String(index).padStart(6, '0')}:${item.label.toLowerCase()}`;
}
