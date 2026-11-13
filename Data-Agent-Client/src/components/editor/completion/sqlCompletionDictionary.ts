import type { SqlCompletionItem, SqlCompletionResponse } from '../../../types/sql';

type DictionaryTransport = (resolveEnvironment: boolean, signal: AbortSignal) => Promise<SqlCompletionResponse>;

/** One editor binding owns its dictionary and refreshes expired metadata on demand. */
export class SqlCompletionDictionary {
  readonly ready: Promise<void>;
  private values: SqlCompletionItem[] = [];
  private signature = '[]';
  private readonly initial = new AbortController();
  private environment?: AbortController;
  private pending?: Promise<void>;
  private generation = 0;
  private expires = 0;
  private disposed = false;
  private environmentResolved = false;

  constructor(private readonly transport: DictionaryTransport, private readonly onChange: () => void,
    private readonly ttlMs = 60_000) {
    this.ready = transport(false, this.initial.signal).then(result => {
      if (!this.disposed && !this.environmentResolved) this.apply(result);
    }).catch(() => { /* Completion requests can still resolve an online dictionary. */ })
      .then(() => { void this.refresh(); });
  }

  get items(): SqlCompletionItem[] { return this.values; }

  refresh(): Promise<void> {
    if (this.disposed) return Promise.resolve();
    if (this.pending) return this.pending;
    if (Date.now() < this.expires) return Promise.resolve();
    const controller = new AbortController();
    this.environment = controller;
    const generation = this.generation;
    this.expires = Date.now() + this.ttlMs;
    this.pending = this.transport(true, controller.signal).then(result => {
      if (!this.disposed && !controller.signal.aborted && generation === this.generation) {
        this.environmentResolved = true;
        this.apply(result);
      }
    }).catch(() => { /* Retain the last dictionary and retry after the TTL. */ })
      .finally(() => {
        if (generation === this.generation) {
          this.pending = undefined;
          this.environment = undefined;
        }
      });
    return this.pending;
  }

  invalidate(): void {
    this.generation++;
    this.environment?.abort();
    this.environment = undefined;
    this.pending = undefined;
    this.expires = 0;
  }

  dispose(): void {
    this.disposed = true;
    this.initial.abort();
    this.invalidate();
  }

  private apply(result: SqlCompletionResponse): void {
    const signature = JSON.stringify(result.items);
    if (signature === this.signature) return;
    this.values = result.items;
    this.signature = signature;
    this.onChange();
  }
}
