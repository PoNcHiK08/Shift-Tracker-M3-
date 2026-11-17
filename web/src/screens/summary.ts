import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { DEFAULT_CURRENCY } from '../domain/currencies';
import { civil, epochDay, lengthOfMonth, monthOf, yearMonth, type EpochDay } from '../domain/dates';
import type { EarningsMode } from '../domain/model';
import type { JobYearReconciliation, PayReconciliation } from '../domain/reconciler';
import {
  breakdownIsEmpty,
  EMPTY_VIEW,
  periodRange,
  summaryState,
  type BreakdownEntry,
  type DataView,
  type PeriodRange,
  type SummaryPeriod,
  type SummaryState,
} from '../domain/screens';
import type { JobSummary } from '../domain/summary';
import type { Strings } from '../i18n/i18n';
import { icon } from '../ui/icons';
import { amounts, cssColor, dateRange, decimalHours, money, monthName, monthYear, shortDate } from '../ui/format';
import { bonusCard, hoursLabel, jobAvatar, modeTag, receivedTag, segment } from './calendar';

const PERIODS: SummaryPeriod[] = ['WEEK', 'MONTH', 'YEAR'];

/** "+12,00 zł" / "-12,00 zł" / "0,00 zł": the sign says which way the difference goes. */
export const signedMoney = (minor: bigint, currency: string, locale: string): string =>
  (minor > 0n ? '+' : '') + money(minor, currency, locale);

/** [day] moved by whole months, the day clamped to the month's length (java.time plusMonths). */
export function plusMonths(day: EpochDay, months: number): EpochDay {
  const c = civil(day);
  const target = yearMonth(c.year, c.month) + months;
  const year = Math.floor(target / 12);
  const month = (target % 12) + 1;
  return epochDay(year, month, Math.min(c.day, lengthOfMonth(target)));
}

/** Summary's ‹ ›: the anchor date moves a week, a month or a year (SummaryViewModel.shift). */
export function stepAnchor(anchor: EpochDay, period: SummaryPeriod, step: number): EpochDay {
  if (period === 'WEEK') return anchor + 7 * step;
  return plusMonths(anchor, period === 'MONTH' ? step : 12 * step);
}

const differenceClass = (difference: bigint) => (difference < 0n ? 'negative' : difference > 0n ? 'positive' : 'zero');

/**
 * The Summary screen (ui/summary/SummaryScreen.kt), read-only: week / month / year, the totals card, by job with
 * each job's share, the month's whole-month bonuses, received pay against the calculated net pay, and the days of
 * the week or months of the year. Gross or net is chosen here (only when some job has deductions).
 */
@customElement('st-summary')
export class SummaryScreen extends LitElement {
  @property({ attribute: false }) strings!: Strings;
  @property({ attribute: false }) view: DataView = EMPTY_VIEW;
  @property() mode: EarningsMode = 'GROSS';
  @property({ type: Boolean }) showModeTag = false;
  @property({ type: Number }) today: EpochDay = 0;
  /** Editing is on: month bonuses and received payments can be added and opened. */
  @property({ type: Boolean }) editing = false;
  @state() private period: SummaryPeriod = 'MONTH';
  /** The day the shown period contains (as the app's saved anchor); today until moved. */
  @state() private anchor: EpochDay | null = null;
  private cache: { key: unknown[]; state: SummaryState } | null = null;

  protected override createRenderRoot() {
    return this;
  }

  private get current(): PeriodRange {
    return periodRange(this.period, this.anchor ?? this.today);
  }

  private get summary(): SummaryState {
    const range = this.current;
    const key = [this.view, this.mode, range.period, range.from];
    if (!this.cache || this.cache.key.some((k, i) => k !== key[i])) this.cache = { key, state: summaryState(this.view, this.mode, range) };
    return this.cache.state;
  }

  private get defaultCurrency(): string {
    return this.view.jobs[0]?.currency ?? DEFAULT_CURRENCY;
  }

  /** Week / month / year around the same anchor date. */
  private setPeriod(period: SummaryPeriod) {
    this.period = period;
  }

  private step(step: number) {
    this.anchor = stepAnchor(this.anchor ?? this.today, this.period, step);
  }

  private request(kind: string, detail: unknown) {
    this.dispatchEvent(new CustomEvent(kind, { detail, bubbles: true, composed: true }));
  }

  private setMode(mode: EarningsMode) {
    this.dispatchEvent(new CustomEvent<EarningsMode>('mode-change', { detail: mode, bubbles: true, composed: true }));
  }

  private label(range: PeriodRange): string {
    const locale = this.strings.locale;
    switch (range.period) {
      case 'WEEK':
        return dateRange(range.from, range.to, locale);
      case 'MONTH':
        return monthYear(monthOf(range.from), locale);
      case 'YEAR':
        return String(new Date(range.from * 86_400_000).getUTCFullYear());
    }
  }

  private choices<T extends string>(testId: string, label: string, options: T[], selected: T, text: (o: T) => string, select: (o: T) => void) {
    return html`<div class="choice-group" role="radiogroup" aria-label=${label} data-testid=${testId}>
      ${options.map(
        (o) => html`<button
          class="choice label-large ${o === selected ? 'selected' : ''}"
          role="radio"
          aria-checked=${o === selected ? 'true' : 'false'}
          data-value=${o}
          @click=${() => select(o)}
        >
          <md-ripple></md-ripple><md-focus-ring></md-focus-ring>${text(o)}
        </button>`,
      )}
    </div>`;
  }

  private header() {
    const t = this.strings;
    const range = this.current;
    const periodText = (p: SummaryPeriod) => t.t(p === 'WEEK' ? 'period_week' : p === 'MONTH' ? 'period_month' : 'period_year');
    const showsToday = this.today >= range.from && this.today <= range.to;
    return html`
      <div class="summary-controls">
        ${this.choices('period', t.t('summary_title'), PERIODS, range.period, periodText, (p) => this.setPeriod(p))}
        ${this.showModeTag
          ? this.choices<EarningsMode>('mode', t.t('earnings_shown'), ['GROSS', 'NET'], this.mode, (m) => t.t(m === 'NET' ? 'earnings_net' : 'earnings_gross'), (m) => this.setMode(m))
          : nothing}
      </div>
      <div class="period-switcher">
        <md-icon-button data-testid="previous-period" aria-label=${t.t('previous_period')} title=${t.t('previous_period')} @click=${() => this.step(-1)}
          >${icon('chevronLeft')}</md-icon-button
        >
        <span class="title-medium period-label" data-testid="period-label">${this.label(range)}</span>
        <md-icon-button data-testid="next-period" aria-label=${t.t('next_period')} title=${t.t('next_period')} @click=${() => this.step(1)}
          >${icon('chevronRight')}</md-icon-button
        >
        ${showsToday
          ? nothing
          : html`<md-icon-button data-testid="current-period" aria-label=${t.t('current_period')} title=${t.t('current_period')}
              @click=${() => (this.anchor = this.today)}
              >${icon('today')}</md-icon-button
            >`}
      </div>
    `;
  }

  private totals() {
    const t = this.strings;
    const locale = t.locale;
    const { summary } = this.summary;
    const period = this.current.period;
    const rated = [...new Set(summary.perJob.filter((j) => j.hourlyPaidMinutes > 0).map((j) => j.currency))];
    const currencies = rated.length ? rated : [summary.currencies[0] ?? this.defaultCurrency];
    const average = currencies.map((c) => money(summary.averageRate(c), c, locale)).join(' · ');
    const title = period === 'WEEK' ? 'earnings_week' : period === 'MONTH' ? 'earnings_month' : 'earnings_year';
    const total = summary.totalEarnings;
    const totalLines = total.isEmpty ? [money(0n, this.defaultCurrency, locale)] : total.sortedByValue().map(([c, v]) => money(v, c, locale));
    const bonuses = summary.countedBonuses.filter((v) => v > 0n);
    return html`<section class="totals-card" data-testid="totals">
      <div class="totals-title">
        <span class="label-large faded">${t.t(title)}</span>
        ${modeTag(t, this.mode, this.showModeTag, 'on-container')} ${receivedTag(t, summary.receivedPart, 'on-container')}
      </div>
      <div class="display-small emphasized num totals-amount" data-testid="totals-amount">${totalLines.map((l) => html`<span>${l}</span>`)}</div>
      ${bonuses.isEmpty ? nothing : html`<div class="body-medium faded" data-testid="totals-bonuses">${t.t('including_bonuses', amounts(bonuses, locale))}</div>`}
      <div class="totals-stats">
        <div class="stat" data-testid="totals-hours"><span class="label-medium faded">${t.t('hours')}</span><span class="title-large num">${decimalHours(summary.totalPaidMinutes, locale)}</span></div>
        <div class="stat" data-testid="totals-shifts"><span class="label-medium faded">${t.t('shifts')}</span><span class="title-large num">${summary.shiftCount}</span></div>
        <div class="stat wide" data-testid="totals-average"><span class="label-medium faded">${t.t('average_rate')}</span><span class="title-large num">${average}</span></div>
      </div>
    </section>`;
  }

  private jobItem(job: JobSummary, total: bigint, position: string) {
    const t = this.strings;
    const locale = t.locale;
    const share = total > 0n ? Number((job.earnings * 10_000n) / total) / 10_000 : 0;
    const details: string[] = [];
    if (job.shiftCount > 0) details.push(hoursLabel(t, job.paidMinutes), t.plural('shift_count', job.shiftCount));
    if (job.countedSalary > 0n) details.push(t.t('salary_value', money(job.countedSalary, job.currency, locale)));
    if (job.countedBonus > 0n) details.push(t.t('bonuses_value', money(job.countedBonus, job.currency, locale)));
    const percent = Math.trunc(share * 100);
    return html`<li class="segment ${position}" data-testid="job-summary" data-job=${job.job.id}>
      <div class="job-row">
        ${jobAvatar(job.job.name, job.job.color)}
        <div class="grow">
          <div class="title-medium ellipsis">${job.job.name}</div>
          <div class="body-medium on-surface-variant" data-testid="job-details">${details.join(' · ')}</div>
        </div>
        <div class="end">
          <span class="title-medium num" data-testid="job-earnings">${money(job.earnings, job.currency, locale)}</span>
          ${receivedTag(t, job.receivedPart)}
        </div>
      </div>
      <div class="bar" role="img" aria-label=${t.t('share_of_earnings', percent)}>
        <span style="width:${Math.min(1, Math.max(0, share)) * 100}%;background:${cssColor(job.job.color)}"></span>
      </div>
    </li>`;
  }

  private amountLine(label: string, value: string, testId: string, valueClass = '') {
    return html`<div class="amount-line" data-testid=${testId}>
      <span class="body-medium on-surface-variant grow">${label}</span><span class="title-small num ${valueClass}">${value}</span>
    </div>`;
  }

  private receivedMonthItem(row: PayReconciliation, position: string) {
    const t = this.strings;
    const locale = t.locale;
    const received =
      row.payouts.length === 0
        ? t.t('received_pay_not_entered')
        : `${money(row.received, row.currency, locale)} · ${t.plural('received_pay_count', row.payouts.length)}`;
    const difference = row.difference;
    return html`<li class="segment ${position}" data-testid="received-pay" data-job=${row.job.id}>
      <div class="job-row">${jobAvatar(row.job.name, row.job.color)}<span class="title-medium ellipsis grow">${row.job.name}</span></div>
      ${this.amountLine(t.t('received_pay_calculated'), money(row.calculatedNet, row.currency, locale), 'calculated')}
      ${this.amountLine(t.t('received_pay_received'), received, 'received')}
      ${difference === null
        ? nothing
        : this.amountLine(t.t('received_pay_difference'), signedMoney(difference, row.currency, locale), 'difference', differenceClass(difference))}
      ${row.payouts.length
        ? html`<ul class="payments">
            ${row.payouts.map((p) => {
              const content = html`<span class="num">${money(p.amount, row.currency, locale)}</span>
                ${p.receivedOn !== null ? html` · ${t.t('received_pay_received_on_value', shortDate(p.receivedOn, locale))}` : nothing}
                ${p.note?.trim() ? html` · ${p.note}` : nothing}`;
              return this.editing
                ? html`<li class="body-small" data-testid="payment" data-id=${p.id}>
                    <button class="payment-button" data-testid="edit-payment" @click=${() => this.request('edit-payout', p.id)}>
                      <md-ripple></md-ripple><md-focus-ring></md-focus-ring>${icon('edit')}<span>${content}</span>
                    </button>
                  </li>`
                : html`<li class="body-small on-surface-variant" data-testid="payment" data-id=${p.id}>${content}</li>`;
            })}
          </ul>`
        : nothing}
      ${this.editing
        ? html`<md-text-button class="add-payment" data-testid="add-payment" has-icon
            @click=${() => this.request('new-payout', { jobId: row.job.id, month: row.month })}
            >${icon('add', 'icon')}${t.t('received_pay_add')}</md-text-button
          >`
        : nothing}
    </li>`;
  }

  private receivedYearItem(line: JobYearReconciliation, position: string) {
    const t = this.strings;
    const locale = t.locale;
    return html`<li class="segment ${position}" data-testid="received-pay-year" data-job=${line.job.id}>
      <div class="job-row">${jobAvatar(line.job.name, line.job.color)}<span class="title-medium ellipsis grow">${line.job.name}</span></div>
      ${this.amountLine(t.t('received_pay_calculated'), money(line.calculatedNet, line.currency, locale), 'calculated')}
      ${this.amountLine(t.t('received_pay_received'), money(line.received, line.currency, locale), 'received')}
      ${this.amountLine(t.t('received_pay_difference'), signedMoney(line.difference, line.currency, locale), 'difference', differenceClass(line.difference))}
    </li>`;
  }

  private breakdownItem(entry: BreakdownEntry, bar: number, max: number, position: string) {
    const t = this.strings;
    const locale = t.locale;
    const period = this.current.period;
    const isCurrent = period === 'WEEK' ? entry.date === this.today : monthOf(entry.date) === monthOf(this.today);
    const label = period === 'WEEK' ? shortDate(entry.date, locale) : monthName(monthOf(entry.date), locale);
    const empty = breakdownIsEmpty(entry);
    return html`<li class="segment breakdown ${position} ${isCurrent ? 'current' : ''}" data-testid="breakdown">
      <div class="breakdown-row">
        <span class="grow ${isCurrent ? 'title-small primary' : 'body-medium'}">${label}</span>
        ${entry.shiftCount > 0 ? html`<span class="body-small on-surface-variant">${hoursLabel(t, entry.paidMinutes)}</span>` : nothing}
        ${receivedTag(t, entry.receivedPart)}
        <span class="title-small num ${empty ? 'on-surface-variant' : ''}" data-testid="breakdown-amount">${empty ? '—' : amounts(entry.earnings, locale)}</span>
      </div>
      ${empty ? nothing : html`<div class="bar thin"><span style="width:${(bar / max) * 100}%"></span></div>`}
    </li>`;
  }

  private sections(): TemplateResult {
    const t = this.strings;
    const s = this.summary;
    const period = this.current.period;
    const parts: TemplateResult[] = [];
    if (period === 'WEEK' && s.summary.fixedPayShiftCount > 0) {
      parts.push(html`<p class="body-medium on-surface-variant note" data-testid="week-fixed-note">${t.t('week_fixed_pay_note')}</p>`);
    }
    if (s.summary.isEmpty && s.receivedPay.length === 0 && s.receivedPayYear.length === 0) {
      parts.push(html`<div class="message" data-testid="summary-empty">
        ${icon('queryStats')}
        <h2 class="headline-small">${t.t('summary_empty_title')}</h2>
        <p class="body-large on-surface-variant">${t.t('summary_empty_message')}</p>
      </div>`);
      return html`${parts}`;
    }
    const jobs = s.summary.perJob;
    if (jobs.length) {
      parts.push(html`<h3 class="section title-small primary">${t.t('by_job')}</h3>
        <ul class="segments">
          ${jobs.map((j, i) => this.jobItem(j, s.summary.totalEarnings.get(j.currency), segment(i, jobs.length)))}
        </ul>`);
    }
    if (period === 'MONTH') {
      parts.push(html`<h3 class="section title-small primary">${t.t('month_bonuses_title')}</h3>
        ${s.monthBonuses.length === 0
          ? html`<p class="body-medium on-surface-variant note" data-testid="month-bonuses-none">${t.t('month_bonuses_none')}</p>`
          : html`<ul class="entries segments" data-testid="summary-month-bonuses">
              ${s.monthBonuses.map((b, i) =>
                bonusCard(t, b, this.mode, segment(i, s.monthBonuses.length), this.editing ? () => this.request('edit-bonus', b.bonus.id) : undefined),
              )}
            </ul>`}
        ${this.editing
          ? html`<md-filled-tonal-button class="add-month-bonus" data-testid="summary-add-month-bonus" has-icon
              @click=${() => this.request('new-bonus', { date: this.current.from, wholeMonth: true })}
              >${icon('add', 'icon')}${t.t('add_month_bonus')}</md-filled-tonal-button
            >`
          : nothing}`);
    }
    if (s.receivedPay.length) {
      parts.push(html`<h3 class="section title-small primary">${t.t('received_pay_title')}</h3>
        <p class="body-small on-surface-variant note">${t.t('received_pay_caption')}</p>
        <ul class="segments">
          ${s.receivedPay.map((r, i) => this.receivedMonthItem(r, segment(i, s.receivedPay.length)))}
        </ul>`);
    }
    if (s.receivedPayYear.length) {
      parts.push(html`<h3 class="section title-small primary">${t.t('received_pay_title')}</h3>
        <p class="body-small on-surface-variant note">${t.t('received_pay_year_caption')}</p>
        <ul class="segments">
          ${s.receivedPayYear.map((r, i) => this.receivedYearItem(r, segment(i, s.receivedPayYear.length)))}
        </ul>`);
    }
    if (s.breakdown.length) {
      // Bars compare earnings; with several currencies in the period they compare hours instead.
      const currencies = new Set(s.breakdown.flatMap((e) => [...e.earnings.byCurrency.keys()]));
      const barOf = (e: BreakdownEntry) =>
        currencies.size <= 1 ? Number([...e.earnings.byCurrency.values()].reduce((a, b) => a + b, 0n)) : e.paidMinutes;
      const max = Math.max(1, ...s.breakdown.map(barOf));
      parts.push(html`<h3 class="section title-small primary">${t.t(period === 'WEEK' ? 'by_day' : 'by_month')}</h3>
        <ul class="segments">
          ${s.breakdown.map((e, i) => this.breakdownItem(e, barOf(e), max, segment(i, s.breakdown.length)))}
        </ul>`);
    }
    return html`${parts}`;
  }

  protected override render() {
    return html`<div class="summary">${this.header()} ${this.totals()} ${this.sections()}</div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-summary': SummaryScreen;
  }
}
