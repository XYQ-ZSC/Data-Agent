import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SqlCompletionItem, SqlCompletionParams, SqlCompletionResponse } from '../../../types/sql';
import { completionSortText, completionWord, SqlCompletionSession, staticCompletions, type CancellationToken } from './sqlCompletion';
const item = (label: string, overrides: Partial<SqlCompletionItem> = {}): SqlCompletionItem => ({
  label, kind: 'COLUMN', insertText: label, filterText: label, replaceStart: 9, replaceEnd: 9, rank: 0, snippet: false, ...overrides,
});
const params = (word = ''): SqlCompletionParams => ({ connectionId: 1, schemaName: 'APP', sql: `SELECT e.${word} FROM EMPLOYEE e`, cursorOffset: 9 + word.length });
const token = () => {
  let cancel = () => {};
  const result: CancellationToken & { cancel(): void } = {
    isCancellationRequested: false,
    onCancellationRequested(listener) { cancel = listener; return { dispose: vi.fn() }; },
    cancel() { result.isCancellationRequested = true; cancel(); },
  };
  return result;
};
const response = (items = [item('ID'), item('NAME')], incomplete = false): SqlCompletionResponse => ({ items, incomplete });
beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());
async function request(session: SqlCompletionSession, requestParams = params(), identity = '') {
  const promise = session.complete(requestParams, token(), () => true, identity);
  await vi.runAllTimersAsync(); return promise;
}

describe('replacement and static dictionary', () => {
  it('covers the complete quoted identifier while keeping only the prefix before the cursor', () => {
    const sql = 'SELECT e."First Old" FROM EMPLOYEE e';
    const word = completionWord(sql, sql.indexOf('Old'));
    expect(word.prefix).toBe('First ');
    expect(sql.slice(word.start, word.end)).toBe('"First Old"');
    expect(sql.slice(0, word.start) + '"First Name"' + sql.slice(word.end)).toBe('SELECT e."First Name" FROM EMPLOYEE e');
  });
  it('decodes doubled quotes and supports special identifier initials', () => {
    const sql = 'SELECT "a""b"';
    expect(completionWord(sql, sql.length - 1).prefix).toBe('a"b');
    for (const word of ['_EMP', '$EMP', '#EMP', '中文表']) expect(completionWord(`SELECT ${word}`, 7 + word.length).prefix).toBe(word);
  });
  it('suppresses comments, strings and alternative quoted literals', () => {
    for (const marked of ["SELECT 'x|y'", "SELECT q'[x|y]'", '-- x|', 'SELECT /* x|y */ ID']) {
      const cursor = marked.indexOf('|'); const sql = marked.replace('|', '');
      expect(completionWord(sql, cursor).suppressed).toBe(true);
      expect(staticCompletions([item('SELECT', { kind: 'KEYWORD' })], sql, cursor).items).toEqual([]);
    }
  });
  it('inserts a template at statement start and excludes it inside expressions', () => {
    const dictionary = [item('sel', { kind: 'SNIPPET', snippet: true }), item('SELECT', { kind: 'KEYWORD' })];
    expect(staticCompletions(dictionary, 'se', 2).items).toHaveLength(2);
    expect(staticCompletions(dictionary, 'SELECT se', 9).items).toEqual([]);
    expect(staticCompletions(dictionary, 'SELECT * FROM ', 14).items).toEqual([]);
    expect(staticCompletions(dictionary, 'SELECT * FROM EMPLOYEE, ', 24).items).toEqual([]);
    expect(staticCompletions(dictionary, 'SELECT e.', 9).items).toEqual([]);
  });
  it('leaves clause keywords to the backend and restricts local functions to expressions', () => {
    const dictionary = [item('CREATE',{kind:'KEYWORD'}),item('WHERE',{kind:'KEYWORD'}),item('UPPER',{kind:'FUNCTION'}),item('SELECT',{kind:'KEYWORD'})];
    expect(staticCompletions(dictionary,'',0).items.map(i => i.label)).toEqual(['CREATE','SELECT']);
    expect(staticCompletions(dictionary,'SELECT UP',9).items.map(i => i.label)).toEqual(['UPPER']);
    const order='SELECT * FROM EMPLOYEE ORDER BY ID ';
    expect(staticCompletions(dictionary,order,order.length).items).toEqual([]);
    const from='SELECT * FROM EMPLOYEE ';
    expect(staticCompletions(dictionary,from,from.length).items).toEqual([]);
  });
  it('does not add a second function argument list', () => {
    const sql = 'SELECT NVL()';
    const result = staticCompletions([item('NVL', { kind: 'FUNCTION', insertText: 'NVL(${1:value}, ${2:fallback})', snippet: true })], sql, 9).items[0];
    expect(result.insertText).toBe('NVL'); expect(result.snippet).toBe(false);
    expect(sql.slice(0, result.replaceStart) + result.insertText + sql.slice(result.replaceEnd)).toBe(sql);
  });
  it('keeps an escaped quote in a partially typed quoted prefix', () => {
    const sql = 'SELECT e."a""b"';
    expect(completionWord(sql, sql.indexOf('b')).prefix).toBe('a"');
  });
  it('covers an existing star for all-column replacement', () => {
    const sql = 'SELECT e.* FROM EMPLOYEE e';
    const word = completionWord(sql, 10);
    expect(word.prefix).toBe('*'); expect(sql.slice(word.start, word.end)).toBe('*');
  });
  it('preserves numeric ranks and array order after index 99', () => {
    const values = Array.from({ length: 130 }, (_, i) => completionSortText(item('KEYWORD', { rank: 100 }), i));
    expect([...values].sort()).toEqual(values);
    expect(completionSortText(item('Z', { rank: 0 }), 0) < completionSortText(item('A', { rank: 100 }), 0)).toBe(true);
  });
});

describe('remote session', () => {
  it('publishes static items before metadata arrives and reuses the exact refreshed snapshot', async () => {
    let resolve!: (value: SqlCompletionResponse) => void;
    const transport = vi.fn().mockImplementation(() => new Promise(done => { resolve = done; }));
    const session = new SqlCompletionSession(transport);
    const ready = vi.fn(); const cancellation = token();
    const local = response([item('SELECT', { kind: 'KEYWORD', rank: 100 })]);
    const first = await session.progressive(params(), cancellation, () => true, 'editor', local, ready);
    expect(first.items[0].label).toBe('SELECT'); expect(first.incomplete).toBe(true);
    expect(transport).not.toHaveBeenCalled();
    await vi.runAllTimersAsync(); resolve(response([item('ID')], true)); await vi.runAllTimersAsync();
    expect(ready).toHaveBeenCalledOnce();
    const refreshed = await session.progressive(params(), cancellation, () => true, 'editor', local, ready);
    expect(refreshed.items[0].label).toBe('ID'); expect(refreshed.incomplete).toBe(true);
    expect(transport).toHaveBeenCalledOnce();
  });
  it('does not refresh a dismissed or superseded suggestion session', async () => {
    let resolve!: (value: SqlCompletionResponse) => void;
    const session = new SqlCompletionSession(() => new Promise(done => { resolve = done; }));
    const ready = vi.fn(); const cancellation = token();
    await session.progressive(params(), cancellation, () => true, 'editor', response([item('SELECT')]), ready);
    await vi.runAllTimersAsync(); cancellation.cancel(); resolve(response()); await vi.runAllTimersAsync();
    expect(ready).not.toHaveBeenCalled();
  });
  it('withdraws an offline function when the current server catalog returns no matching candidate', async () => {
    const session = new SqlCompletionSession(vi.fn().mockResolvedValue(response([])));
    const ready = vi.fn(); const cancellation = token();
    const local = response([item('UNSUPPORTED_FN', { kind: 'FUNCTION' })]);
    await session.progressive(params(), cancellation, () => true, 'editor', local, ready);
    await vi.runAllTimersAsync();
    expect(ready).toHaveBeenCalledOnce();
    expect((await session.progressive(params(), cancellation, () => true, 'editor', local, ready)).items).toEqual([]);
  });
  it('keeps offline candidates when the completion request fails', async () => {
    const session = new SqlCompletionSession(vi.fn().mockRejectedValue(new Error('offline')));
    const ready = vi.fn(); const cancellation = token(); const local = response([item('UPPER', { kind: 'FUNCTION' })]);
    await session.progressive(params(), cancellation, () => true, 'editor', local, ready);
    await vi.runAllTimersAsync();
    expect(ready).not.toHaveBeenCalled();
    expect((await session.progressive(params(), cancellation, () => true, 'editor', local, ready)).items[0].label).toBe('UPPER');
  });
  it('waits for the debounce interval', async () => {
    const transport = vi.fn().mockResolvedValue(response()); const session = new SqlCompletionSession(transport);
    const promise = session.complete(params(), token(), () => true);
    await vi.advanceTimersByTimeAsync(119); expect(transport).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); await promise; expect(transport).toHaveBeenCalledOnce();
  });
  it('cancels before dispatch without waiting for the debounce', async () => {
    const transport = vi.fn().mockResolvedValue(response()); const session = new SqlCompletionSession(transport);
    const cancellation = token(); const promise = session.complete(params(), cancellation, () => true);
    cancellation.cancel(); expect((await promise).items).toEqual([]); expect(transport).not.toHaveBeenCalled();
  });
  it('aborts the HTTP signal and rejects late responses even if the transport ignores abort', async () => {
    let firstResolve!: (value: SqlCompletionResponse) => void;
    const transport = vi.fn().mockImplementationOnce(() => new Promise(resolve => { firstResolve = resolve; })).mockResolvedValue(response([item('NAME')]));
    const session = new SqlCompletionSession(transport);
    const first = session.complete(params(), token(), () => true); await vi.runAllTimersAsync();
    const second = session.complete(params('N'), token(), () => true); await vi.runAllTimersAsync();
    expect(transport.mock.calls[0][1].aborted).toBe(true);
    firstResolve(response([item('OLD')])); expect((await first).items).toEqual([]);
    expect((await second).items[0].label).toBe('NAME');
  });
  it('checks the model version/binding before accepting results', async () => {
    let resolve!: (value: SqlCompletionResponse) => void; let current = true;
    const session = new SqlCompletionSession(() => new Promise(done => { resolve = done; }));
    const promise = session.complete(params(), token(), () => current); await vi.runAllTimersAsync();
    current = false; resolve(response()); expect((await promise).items).toEqual([]);
  });
  it('reuses complete results only for a longer prefix and recomputes ranges', async () => {
    const transport = vi.fn().mockResolvedValue(response()); const session = new SqlCompletionSession(transport);
    await request(session); const result = await request(session, params('NA'));
    expect(transport).toHaveBeenCalledOnce(); expect(result.items.map(i => i.label)).toEqual(['NAME']);
    expect(result.items[0].replaceEnd).toBe(11);
  });
  it('decodes quoted filterText when reusing candidates', async () => {
    const transport = vi.fn().mockResolvedValue(response([item('First Name', { filterText: '"First Name"', insertText: '"First Name"', replaceEnd: 12 })]));
    const session = new SqlCompletionSession(transport);
    const requestParams = (prefix: string) => ({ ...params(), sql: `SELECT e."${prefix}" FROM EMPLOYEE e`, cursorOffset: 10 + prefix.length });
    await request(session, requestParams('F'));
    expect((await request(session, requestParams('First '))).items[0].filterText).toBe('"First Name"');
    expect(transport).toHaveBeenCalledOnce();
  });
  it('refetches a truncated response and a shortened prefix', async () => {
    const transport = vi.fn().mockResolvedValue(response(undefined, true)); const session = new SqlCompletionSession(transport);
    await request(session); await request(session, params('N')); expect(transport).toHaveBeenCalledTimes(2);
    transport.mockResolvedValue(response()); await request(session, params('NA')); await request(session, params('N'));
    expect(transport).toHaveBeenCalledTimes(4);
  });
  it('separates connection/schema/workspace identities and invalidates on reset', async () => {
    const transport = vi.fn().mockResolvedValue(response()); const session = new SqlCompletionSession(transport);
    await request(session); await request(session, { ...params(), connectionId: 2 });
    await request(session, { ...params(), schemaName: 'OTHER' }); await request(session, params(), 'org:2');
    expect(transport).toHaveBeenCalledTimes(4); session.reset(); await request(session);
    expect(transport).toHaveBeenCalledTimes(5);
  });
  it('does not reuse after another SQL context changes or the cache expires', async () => {
    const transport = vi.fn().mockResolvedValue(response()); const session = new SqlCompletionSession(transport);
    await request(session); await request(session, { ...params(), sql: params().sql.replace('EMPLOYEE', 'DEPARTMENT') });
    await vi.advanceTimersByTimeAsync(10_001); await request(session); expect(transport).toHaveBeenCalledTimes(3);
  });
  it('does not shift a dialect replacement range it cannot identify locally', async () => {
    const transport = vi.fn().mockResolvedValue(response([item('ID', { replaceStart: 0, replaceEnd: 9 })]));
    const session = new SqlCompletionSession(transport);
    await request(session); await request(session, params('I'));
    expect(transport).toHaveBeenCalledTimes(2);
  });
});
