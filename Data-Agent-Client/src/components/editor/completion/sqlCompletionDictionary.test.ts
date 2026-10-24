import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SqlCompletionItem, SqlCompletionResponse } from '../../../types/sql';
import { SqlCompletionDictionary } from './sqlCompletionDictionary';
import { SqlCompletionSession } from './sqlCompletion';

const item = (label: string): SqlCompletionItem => ({
  label, kind: 'FUNCTION', insertText: `${label}()`, filterText: label,
  replaceStart: 0, replaceEnd: 0, rank: 0, snippet: false,
});
const response = (label = 'NVL'): SqlCompletionResponse => ({ items: [item(label)], incomplete: false });
const deferred = () => {
  let resolve!: (value: SqlCompletionResponse) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<SqlCompletionResponse>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(0); });
afterEach(() => vi.useRealTimers());

describe('completion dictionary lifecycle', () => {
  it('makes offline candidates ready without waiting for the environment', async () => {
    const environment = deferred();
    const transport = vi.fn().mockResolvedValueOnce(response()).mockReturnValueOnce(environment.promise);
    const changed = vi.fn();
    const dictionary = new SqlCompletionDictionary(transport, changed);
    await dictionary.ready;
    expect(dictionary.items[0].label).toBe('NVL');
    expect(transport).toHaveBeenCalledTimes(2);
    environment.resolve(response('UPPER'));
    await dictionary.refresh();
    expect(dictionary.items[0].label).toBe('UPPER');
    expect(changed).toHaveBeenCalledTimes(2);
  });

  it('does not poll idle editors and refreshes expired metadata only on demand', async () => {
    const transport = vi.fn().mockResolvedValue(response());
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    await dictionary.refresh();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(transport).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
    await dictionary.refresh();
    expect(transport).toHaveBeenCalledTimes(3);
  });

  it('does not reset completion when only environment metadata changes', async () => {
    const transport = vi.fn().mockResolvedValue(response());
    const changed = vi.fn();
    const dictionary = new SqlCompletionDictionary(transport, changed);
    await dictionary.ready;
    await dictionary.refresh();
    expect(changed).toHaveBeenCalledTimes(1);
    transport.mockResolvedValue({ ...response(), environment: { databaseVersion: 'DM8', settings: { COUNT_64BIT: '1' } } });
    await vi.advanceTimersByTimeAsync(60_000);
    await dictionary.refresh();
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('keeps an in-flight completion alive across an unchanged dictionary refresh', async () => {
    const completion = deferred();
    const completionTransport = vi.fn().mockReturnValue(completion.promise);
    const session = new SqlCompletionSession(completionTransport);
    const dictionary = new SqlCompletionDictionary(vi.fn().mockResolvedValue(response()), () => session.reset());
    await dictionary.ready;
    await dictionary.refresh();
    const pending = session.complete({ connectionId: 1, sql: 'SELECT ', cursorOffset: 7 }, {
      isCancellationRequested: false,
      onCancellationRequested: () => ({ dispose() {} }),
    }, () => true);
    await vi.advanceTimersByTimeAsync(60_000);
    const signal = completionTransport.mock.calls[0][1] as AbortSignal;
    await dictionary.refresh();
    expect(signal.aborted).toBe(false);
    completion.resolve(response());
    expect((await pending).items[0].label).toBe('NVL');
    dictionary.dispose();
    session.reset();
  });

  it('deduplicates concurrent refreshes and observes the TTL', async () => {
    const environment = deferred();
    const transport = vi.fn().mockResolvedValueOnce(response()).mockReturnValue(environment.promise);
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    const first = dictionary.refresh();
    expect(dictionary.refresh()).toBe(first);
    expect(transport).toHaveBeenCalledTimes(2);
    environment.resolve(response());
    await first;
    await dictionary.refresh();
    expect(transport).toHaveBeenCalledTimes(2);
  });

  it('preserves the last dictionary on failure and backs off retries', async () => {
    const transport = vi.fn().mockResolvedValueOnce(response()).mockRejectedValue(new Error('offline'));
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    await dictionary.refresh();
    expect(dictionary.items[0].label).toBe('NVL');
    await dictionary.refresh();
    expect(transport).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(60_000);
    transport.mockResolvedValue(response('UPPER'));
    await dictionary.refresh();
    expect(dictionary.items[0].label).toBe('UPPER');
  });

  it('accepts an authoritative empty dictionary', async () => {
    const transport = vi.fn().mockResolvedValueOnce(response()).mockResolvedValue({ items: [], incomplete: false });
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    await dictionary.refresh();
    expect(dictionary.items).toEqual([]);
  });

  it('aborts invalidated refreshes and ignores their late responses', async () => {
    const old = deferred();
    const fresh = deferred();
    const transport = vi.fn().mockResolvedValueOnce(response()).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    const oldRefresh = dictionary.refresh();
    const signal = transport.mock.calls[1][1] as AbortSignal;
    dictionary.invalidate();
    expect(signal.aborted).toBe(true);
    const freshRefresh = dictionary.refresh();
    old.resolve(response('OLD'));
    await oldRefresh;
    expect(dictionary.items[0].label).toBe('NVL');
    expect(dictionary.refresh()).toBe(freshRefresh);
    fresh.resolve(response('NEW'));
    await freshRefresh;
    expect(dictionary.items[0].label).toBe('NEW');
  });

  it('does not let delayed offline results overwrite a resolved environment', async () => {
    const offline = deferred();
    const transport = vi.fn().mockReturnValueOnce(offline.promise).mockResolvedValue(response('ONLINE'));
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    dictionary.invalidate();
    await dictionary.refresh();
    offline.resolve(response('OFFLINE'));
    await dictionary.ready;
    expect(dictionary.items[0].label).toBe('ONLINE');
  });

  it('disposes the binding without accepting late results or starting new requests', async () => {
    const offline = deferred();
    const changed = vi.fn();
    const transport = vi.fn().mockReturnValue(offline.promise);
    const dictionary = new SqlCompletionDictionary(transport, changed);
    dictionary.dispose();
    expect((transport.mock.calls[0][1] as AbortSignal).aborted).toBe(true);
    offline.resolve(response());
    await dictionary.ready;
    await dictionary.refresh();
    expect(changed).not.toHaveBeenCalled();
    expect(transport).toHaveBeenCalledTimes(1);
  });

  it('can still load online candidates if the initial offline request fails', async () => {
    const transport = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(response('ONLINE'));
    const dictionary = new SqlCompletionDictionary(transport, vi.fn());
    await dictionary.ready;
    await dictionary.refresh();
    expect(dictionary.items[0].label).toBe('ONLINE');
  });
});
