/*
 * Amounts and texts as the app's editors take them (domain/calc/MoneyParser.kt, domain/model/TextLimits.kt).
 */

export const RATE_WHOLE_DIGITS = 7;
export const AMOUNT_WHOLE_DIGITS = 10;

/** The app's limit for names and notes; counted in UTF-16 units, as Kotlin's String.length and the rules do. */
export const MAX_TEXT = 20_000;

/**
 * "27,70", "27.7" or "30" in minor units of a currency with [fractionDigits] decimals; null for empty, negative or
 * malformed input, more decimals than the currency has, or more than [wholeDigits] whole digits
 * (MoneyParser.parseMinor).
 */
export function parseMinor(input: string, fractionDigits: number, wholeDigits: number = RATE_WHOLE_DIGITS): bigint | null {
  const normalized = input.replace(/[\s  ]/g, '').replace(/,/g, '.');
  if (normalized === '') return null;
  const decimals = fractionDigits > 0 ? `(\\.[0-9]{0,${fractionDigits}})?` : '\\.?';
  if (!new RegExp(`^[0-9]{1,${wholeDigits}}${decimals}$`).test(normalized)) return null;
  const [whole = '0', fraction = ''] = normalized.split('.');
  return BigInt(whole) * 10n ** BigInt(fractionDigits) + BigInt((fraction + '0'.repeat(fractionDigits)).slice(0, fractionDigits) || '0');
}

/** "2770" -> "27,70"; "3000" -> "30" (MoneyParser.toInput): an amount as an edit field shows it. */
export function toInput(minor: bigint, fractionDigits: number, separator: string = ','): string {
  if (fractionDigits === 0) return minor.toString();
  const unit = 10n ** BigInt(fractionDigits);
  const whole = minor / unit;
  const fraction = minor % unit;
  return fraction === 0n ? whole.toString() : `${whole}${separator}${fraction.toString().padStart(fractionDigits, '0')}`;
}

/**
 * What a field holding [old] keeps when changed to [next] (TextLimits.limitInput): anything up to the limit; a text
 * already over it may only get shorter; a paste that goes over is cut to fit, never between an emoji's two halves.
 */
export function limitInput(old: string, next: string, max: number = MAX_TEXT): string {
  if (next.length <= max) return next;
  if (next.length <= old.length) return next;
  if (old.length >= max) return old;
  return cut(next, max);
}

export function cut(text: string, max: number = MAX_TEXT): string {
  if (text.length <= max) return text;
  const code = text.charCodeAt(max - 1);
  const end = max > 0 && code >= 0xd800 && code <= 0xdbff ? max - 1 : max;
  return text.slice(0, end);
}

/** A note as the app saves it: trimmed, and null when nothing is left. */
export const noteValue = (text: string): string | null => {
  const trimmed = text.trim();
  return trimmed === '' ? null : trimmed;
};
