/*
 * Currencies a job can be paid in, as the app checks and formats them (domain/model/Currencies.kt): the ISO 4217
 * codes Java's Currency knows with 0 to 3 decimals. This list is the app's own (exported with the golden fixtures;
 * test/unit/parity.test.ts checks it). The browser's Intl data is not used for it: it accepts unknown codes and
 * disagrees with Java on a few decimals (IDR).
 */
const BY_DIGITS: Record<number, string> = {
  0: (
    'ADP BEF BIF BYB BYR CLP DJF ESP GNF GRD ISK ITL JPY KMF KRW LUF MGF PTE PYG ROL ' +
    'RWF TPE TRL UGX UYI VND VUV XAF XOF XPF '
  ).trim(),
  2: (
    'AED AFA AFN ALL AMD ANG AOA ARS ATS AUD AWG AYM AZM AZN BAM BBD BDT BGL BGN BMD ' +
    'BND BOB BOV BRL BSD BTN BWP BYN BZD CAD CDF CHE CHF CHW CNY COP COU CRC CSD CUC ' +
    'CUP CVE CYP CZK DEM DKK DOP DZD EEK EGP ERN ETB EUR FIM FJD FKP FRF GBP GEL GHC ' +
    'GHS GIP GMD GTQ GWP GYD HKD HNL HRK HTG HUF IDR IEP ILS INR IRR JMD KES KGS KHR ' +
    'KPW KYD KZT LAK LBP LKR LRD LSL LTL LVL MAD MDL MGA MKD MMK MNT MOP MRO MRU MTL ' +
    'MUR MVR MWK MXN MXV MYR MZM MZN NAD NGN NIO NLG NOK NPR NZD PAB PEN PGK PHP PKR ' +
    'PLN QAR RON RSD RUB RUR SAR SBD SCR SDD SDG SEK SGD SHP SIT SKK SLE SLL SOS SRD ' +
    'SRG SSP STD STN SVC SYP SZL THB TJS TMM TMT TOP TRY TTD TWD TZS UAH USD USN USS ' +
    'UYU UZS VEB VED VEF VES WST XAD XCD XCG YER YUM ZAR ZMK ZMW ZWD ZWG ZWL ZWN ZWR '
  ).trim(),
  3: (
    'BHD IQD JOD KWD LYD OMR TND '
  ).trim(),
};

const DIGITS = new Map<string, number>();
for (const [digits, codes] of Object.entries(BY_DIGITS)) for (const code of codes.split(' ')) DIGITS.set(code, Number(digits));

export const DEFAULT_CURRENCY = 'PLN';

/** A valid ISO 4217 code with 0 to 3 decimals (the app refuses others). */
export function isValidCurrency(code: string): boolean {
  return /^[A-Z]{3}$/.test(code) && DIGITS.has(code);
}

/** Decimals of [code]: 2 for PLN/EUR, 0 for JPY, 3 for KWD; 2 for anything unknown. */
export function fractionDigits(code: string): number {
  return DIGITS.get(code) ?? 2;
}

export function knownCurrencies(): [string, number][] {
  return [...DIGITS.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1));
}

/** Display symbols (Currencies.kt); anything missing is shown by its code. */
const SYMBOLS: Record<string, string> = {
  PLN: 'zł', UAH: '₴', EUR: '€', USD: '$', GBP: '£', CZK: 'Kč', JPY: '¥', CNY: '¥', CHF: 'CHF', SEK: 'kr', NOK: 'kr',
  DKK: 'kr', ISK: 'kr', HUF: 'Ft', RON: 'lei', BGN: 'лв', INR: '₹', KRW: '₩', ILS: '₪', TRY: '₺', GEL: '₾', KZT: '₸',
  BRL: 'R$', CAD: 'CA$', AUD: 'A$', NZD: 'NZ$', HKD: 'HK$', SGD: 'S$', MXN: 'MX$', THB: '฿', PHP: '₱', VND: '₫', ZAR: 'R',
};

export function currencySymbol(code: string): string {
  return SYMBOLS[code] ?? code;
}

/** Largest amount with [wholeDigits] whole digits and [fraction] decimals (MoneyParser.maxMinor). */
export function maxMinor(fraction: number, wholeDigits: number = RATE_WHOLE_DIGITS): bigint {
  return 10n ** BigInt(wholeDigits + fraction) - 1n;
}

export const RATE_WHOLE_DIGITS = 7;
export const AMOUNT_WHOLE_DIGITS = 10;
