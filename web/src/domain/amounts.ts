/**
 * Money totals kept apart by currency (the app's Amounts): amounts in different currencies are never added
 * together. Values are minor units as bigint; the order is the order the currencies first appeared in, as
 * Kotlin's LinkedHashMap keeps it (the screens show the largest first, ties in this order).
 */
export class Amounts {
  static readonly EMPTY = new Amounts(new Map());

  constructor(readonly byCurrency: ReadonlyMap<string, bigint>) {}

  static of(currency: string, value: bigint): Amounts {
    return new Amounts(new Map([[currency, value]]));
  }

  static sum<T>(items: Iterable<T>, currency: (item: T) => string, value: (item: T) => bigint): Amounts {
    const map = new Map<string, bigint>();
    for (const item of items) {
      const code = currency(item);
      map.set(code, (map.get(code) ?? 0n) + value(item));
    }
    return new Amounts(map);
  }

  get isEmpty(): boolean {
    return this.byCurrency.size === 0;
  }

  get currencies(): string[] {
    return [...this.byCurrency.keys()];
  }

  get(currency: string): bigint {
    return this.byCurrency.get(currency) ?? 0n;
  }

  plus(other: Amounts): Amounts {
    if (other.isEmpty) return this;
    if (this.isEmpty) return other;
    const merged = new Map(this.byCurrency);
    for (const [code, value] of other.byCurrency) merged.set(code, (merged.get(code) ?? 0n) + value);
    return new Amounts(merged);
  }

  /** Per currency; a currency of [other] missing here appears with a negative value. */
  minus(other: Amounts): Amounts {
    if (other.isEmpty) return this;
    const merged = new Map(this.byCurrency);
    for (const [code, value] of other.byCurrency) merged.set(code, (merged.get(code) ?? 0n) - value);
    return new Amounts(merged);
  }

  /** The largest amount first (ties keep their order). */
  sortedByValue(): [string, bigint][] {
    return [...this.byCurrency.entries()].sort((a, b) => (b[1] > a[1] ? 1 : b[1] < a[1] ? -1 : 0));
  }

  filter(keep: (value: bigint) => boolean): Amounts {
    return new Amounts(new Map([...this.byCurrency].filter(([, value]) => keep(value))));
  }
}
