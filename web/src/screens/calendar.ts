import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { Amounts } from '../domain/amounts';
import { bonusAmount, earnings, isOvernight, paysHourly, shiftEarnings, shiftPaidMinutes } from '../domain/calc';
import { DEFAULT_CURRENCY } from '../domain/currencies';
import { dayOfWeek, firstDay, lengthOfMonth, monthOf, type EpochDay, type YearMonth } from '../domain/dates';
import type { BonusWithJob, EarningsMode, ShiftWithJob } from '../domain/model';
import { calendarState, EMPTY_VIEW, type CalendarState, type DataView } from '../domain/screens';
import type { ReceivedPart } from '../domain/summary';
import type { Strings } from '../i18n/i18n';
import { icon } from '../ui/icons';
import { amounts, cssColor, decimalHours, fullDate, money, monthYear, onColor, time, weekdayNames } from '../ui/format';

/** "Gross" / "Net" next to an earnings figure, only when some job has deductions (EarningsDisplay.kt). */
export function modeTag(strings: Strings, mode: EarningsMode, show: boolean, extraClass = ''): TemplateResult | typeof nothing {
  if (!show) return nothing;
  return html`<span class="tag label-small ${extraClass}" data-testid="mode-tag">${strings.t(mode === 'NET' ? 'earnings_net' : 'earnings_gross')}</span>`;
}

/** "Received" / "Incl. received" next to a figure that counts received pay; nothing when it is calculated. */
export function receivedTag(strings: Strings, part: ReceivedPart, extraClass = ''): TemplateResult | typeof nothing {
  if (part === 'NONE') return nothing;
  return html`<span class="tag received label-small ${extraClass}" data-testid="received-tag"
    >${strings.t(part === 'ALL' ? 'received_tag_all' : 'received_tag_part')}</span
  >`;
}

export const hoursLabel = (strings: Strings, minutes: number) => strings.t('hours_value', decimalHours(minutes, strings.locale));

export function jobAvatar(name: string, color: number): TemplateResult {
  return html`<span class="avatar title-medium" style="background:${cssColor(color)};color:${onColor(color)}" aria-hidden="true"
    >${name.trim().slice(0, 1).toLocaleUpperCase()}</span
  >`;
}

/** A shift as a card (EntryCards.kt ShiftCard), read-only. */
/** [onOpen]: while editing is on, the card opens the shift's editor (otherwise it is plain text). */
export function shiftCard(strings: Strings, item: ShiftWithJob, mode: EarningsMode, position: string, onOpen?: () => void): TemplateResult {
  const { shift, job } = item;
  const details = [`${time(shift.start)}–${time(shift.end)}`];
  if (shift.breakMinutes > 0) details.push(strings.t('break_short', shift.breakMinutes));
  const paid = shiftPaidMinutes(shift);
  return html`<li
    class="entry ${position} ${onOpen ? 'openable' : ''}"
    data-testid="shift-card"
    data-id=${shift.id}
    role=${onOpen ? 'button' : nothing}
    tabindex=${onOpen ? 0 : nothing}
    aria-label=${onOpen ? `${strings.t('edit_shift')}: ${job.name}, ${time(shift.start)}–${time(shift.end)}` : nothing}
    @click=${onOpen ?? nothing}
    @keydown=${onOpen ? (e: KeyboardEvent) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onOpen()) : nothing}
  >
    ${onOpen ? html`<md-ripple></md-ripple><md-focus-ring></md-focus-ring>` : nothing}
    ${jobAvatar(job.name, job.color)}
    <div class="entry-text">
      <div class="title-medium ellipsis">${job.name}</div>
      <div class="body-medium on-surface-variant entry-details">
        <span>${details.join(' · ')}</span>
        ${isOvernight(shift.start, shift.end)
          ? html`<span class="overnight" title=${strings.t('overnight_shift')} aria-label=${strings.t('overnight_shift')}>${icon('nightsStay')}</span>`
          : nothing}
      </div>
      ${shift.note?.trim() ? html`<div class="body-small on-surface-variant ellipsis">${shift.note}</div>` : nothing}
    </div>
    <div class="entry-end">
      <div class="title-medium" data-testid="shift-pay">
        ${paysHourly(job, shift.date) ? money(shiftEarnings(item, mode), job.currency, strings.locale) : strings.t('fixed_salary')}
      </div>
      <div class="body-small on-surface-variant">${hoursLabel(strings, paid)}</div>
    </div>
  </li>`;
}

/** A bonus as a card (EntryCards.kt BonusCard), read-only. */
export function bonusCard(strings: Strings, item: BonusWithJob, mode: EarningsMode, position: string, onOpen?: () => void): TemplateResult {
  const { bonus, job } = item;
  return html`<li
    class="entry bonus ${position} ${onOpen ? 'openable' : ''}"
    data-testid="bonus-card"
    data-id=${bonus.id}
    role=${onOpen ? 'button' : nothing}
    tabindex=${onOpen ? 0 : nothing}
    aria-label=${onOpen ? `${strings.t('edit_bonus')}: ${strings.t('bonus_for_job', job.name)}` : nothing}
    @click=${onOpen ?? nothing}
    @keydown=${onOpen ? (e: KeyboardEvent) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onOpen()) : nothing}
  >
    ${onOpen ? html`<md-ripple></md-ripple><md-focus-ring></md-focus-ring>` : nothing}
    <span class="avatar bonus-avatar" aria-hidden="true">${icon('redeem')}</span>
    <div class="entry-text">
      <div class="title-medium ellipsis">${strings.t('bonus_for_job', job.name)}</div>
      ${bonus.wholeMonth ? html`<div class="label-medium faded">${strings.t('bonus_for_whole_month')}</div>` : nothing}
      ${bonus.note?.trim() ? html`<div class="body-medium faded ellipsis">${bonus.note}</div>` : nothing}
    </div>
    <div class="entry-end title-medium" data-testid="bonus-amount">+${money(bonusAmount(item, mode), job.currency, strings.locale)}</div>
  </li>`;
}

/** Corners of one item in a segmented group: large outside, small between neighbours. */
export function segment(index: number, count: number): string {
  if (count === 1) return 'only';
  if (index === 0) return 'first';
  return index === count - 1 ? 'last' : 'middle';
}

/**
 * The Calendar screen (ui/calendar/CalendarScreen.kt), read-only: the month's tiles (earned, hours, shifts), its
 * whole-month bonuses, the month grid with a dot per job and a bonus mark, and the selected day with its shifts and
 * bonuses. On wide screens the day stands beside the month.
 */
@customElement('st-calendar')
export class CalendarScreen extends LitElement {
  @property({ attribute: false }) strings!: Strings;
  @property({ attribute: false }) view: DataView = EMPTY_VIEW;
  @property() mode: EarningsMode = 'GROSS';
  @property({ type: Boolean }) showModeTag = false;
  @property({ type: Number }) today: EpochDay = 0;
  /** Editing is on for this session: the day panel offers Add and its cards open their editors. */
  @property({ type: Boolean }) editing = false;
  @state() private month: YearMonth | null = null;
  @state() private selected: EpochDay | null = null;
  @state() private bonusesOpen = false;
  private cache: { key: unknown[]; state: CalendarState } | null = null;

  protected override createRenderRoot() {
    return this;
  }

  private get shownMonth(): YearMonth {
    return this.month ?? monthOf(this.today);
  }

  private get selectedDay(): EpochDay {
    return this.selected ?? this.today;
  }

  private get calendar(): CalendarState {
    const key = [this.view, this.mode, this.shownMonth];
    if (!this.cache || this.cache.key.some((k, i) => k !== key[i])) {
      this.cache = { key, state: calendarState(this.view, this.mode, this.shownMonth) };
    }
    return this.cache.state;
  }

  /** Shows [month]; the selection stays on its day (as the app keeps the selected date while paging). */
  goToMonth(month: YearMonth) {
    this.month = month;
    this.bonusesOpen = false;
  }

  private goToToday() {
    this.month = monthOf(this.today);
    this.selected = this.today;
    this.bonusesOpen = false;
  }

  private select(day: EpochDay) {
    this.selected = day;
  }

  private onGridKey(event: KeyboardEvent) {
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };
    const step = moves[event.key];
    if (step === undefined) return;
    event.preventDefault();
    const next = this.selectedDay + step;
    this.selected = next;
    if (monthOf(next) !== this.shownMonth) this.month = monthOf(next);
    void this.updateComplete.then(() => this.querySelector<HTMLButtonElement>('.day[aria-selected="true"]')?.focus());
  }

  /** Asks the app to open an editor ('new-shift' with a date; 'edit-shift' / 'edit-bonus' with a sync id). */
  private request(kind: string, detail: string | EpochDay) {
    this.dispatchEvent(new CustomEvent(kind, { detail, bubbles: true, composed: true }));
  }

  private toolbar() {
    const t = this.strings;
    return html`<div class="screen-toolbar">
      <h2 class="title-large month-title" data-testid="month-title">${monthYear(this.shownMonth, t.locale)}</h2>
      <md-icon-button data-testid="previous-month" aria-label=${t.t('previous_month')} title=${t.t('previous_month')} @click=${() => this.goToMonth(this.shownMonth - 1)}
        >${icon('chevronLeft')}</md-icon-button
      >
      <md-icon-button data-testid="next-month" aria-label=${t.t('next_month')} title=${t.t('next_month')} @click=${() => this.goToMonth(this.shownMonth + 1)}
        >${icon('chevronRight')}</md-icon-button
      >
      <md-icon-button data-testid="today" aria-label=${t.t('today')} title=${t.t('today')} @click=${() => this.goToToday()}>${icon('today')}</md-icon-button>
    </div>`;
  }

  /** The month at a glance: earned (with Received / Gross-Net tags), hours, shifts. */
  private strip() {
    const t = this.strings;
    const summary = this.calendar.monthSummary;
    const tag = this.showModeTag ? ` · ${t.t(this.mode === 'NET' ? 'earnings_net' : 'earnings_gross')}` : '';
    const total = summary.totalEarnings;
    const lines = total.isEmpty ? [money(0n, this.view.jobs[0]?.currency ?? DEFAULT_CURRENCY, t.locale)] : total.sortedByValue().map(([c, v]) => money(v, c, t.locale));
    return html`<div class="month-strip">
      <div class="tile tile-earnings" data-testid="tile-earnings">
        <span class="label-small ellipsis">${t.t('earnings_month')}${tag}</span>
        <div class="tile-amount-row">
          <span class="title-medium emphasized num tile-amount" data-testid="tile-amount">${lines.map((line) => html`<span>${line}</span>`)}</span>
          ${receivedTag(t, summary.receivedPart, 'on-container')}
        </div>
      </div>
      <div class="tile tile-hours" data-testid="tile-hours">
        <span class="label-small">${t.t('hours')}</span>
        <span class="title-medium emphasized num">${decimalHours(summary.totalPaidMinutes, t.locale)}</span>
      </div>
      <div class="tile tile-shifts" data-testid="tile-shifts">
        <span class="label-small">${t.t('shifts')}</span>
        <span class="title-medium emphasized num">${summary.shiftCount}</span>
      </div>
    </div>`;
  }

  /** The month's bonuses for the whole month: a row that opens their list. */
  private monthBonuses() {
    const items = this.calendar.monthBonuses;
    if (items.length === 0) return nothing;
    const t = this.strings;
    const total = Amounts.sum(items, (b) => b.job.currency, (b) => bonusAmount(b, this.mode));
    return html`<div class="month-bonuses">
      <button
        class="month-bonuses-row"
        data-testid="month-bonuses-row"
        aria-expanded=${this.bonusesOpen ? 'true' : 'false'}
        @click=${() => (this.bonusesOpen = !this.bonusesOpen)}
      >
        <md-ripple></md-ripple><md-focus-ring></md-focus-ring>
        ${icon('redeem')}
        <span class="title-small grow ellipsis">${t.t('month_bonuses_row', items.length)}</span>
        <span class="title-small" data-testid="month-bonuses-total">+${amounts(total, t.locale)}</span>
        <span class="chevron ${this.bonusesOpen ? 'open' : ''}">${icon('chevronRight')}</span>
      </button>
      ${this.bonusesOpen
        ? html`<ul class="entries" data-testid="month-bonuses-list" aria-label=${t.t('month_bonuses_sheet_title', monthYear(this.shownMonth, t.locale))}>
            ${items.map((b, i) => bonusCard(t, b, this.mode, segment(i, items.length)))}
          </ul>`
        : nothing}
    </div>`;
  }

  private grid() {
    const t = this.strings;
    const month = this.shownMonth;
    const first = firstDay(month);
    const blanks = dayOfWeek(first) - 1;
    const length = lengthOfMonth(month);
    const { shiftsByDate, bonusesByDate } = this.calendar;
    const names = weekdayNames(t.locale);
    const cells: TemplateResult[] = [];
    // Always six rows, so the height never jumps between months.
    for (let i = 0; i < 42; i++) {
      const n = i - blanks + 1;
      if (n < 1 || n > length) {
        cells.push(html`<div class="day-cell" role="gridcell"></div>`);
        continue;
      }
      const day = first + n - 1;
      const shifts = shiftsByDate.get(day) ?? [];
      const hasBonus = (bonusesByDate.get(day) ?? []).length > 0;
      const selected = day === this.selectedDay;
      const isToday = day === this.today;
      const colors = [...new Map(shifts.map((s) => [s.job.id, s.job.color])).values()].slice(0, 3);
      const label = [fullDate(day, t.locale), shifts.length ? t.plural('shift_count', shifts.length) : '', hasBonus ? t.t('bonus') : '']
        .filter(Boolean)
        .join(', ');
      const classes = ['day', selected ? 'selected' : '', isToday ? 'today' : '', shifts.length || hasBonus ? 'has-entries' : ''].join(' ');
      cells.push(html`<div class="day-cell" role="gridcell">
        <button
          class=${classes}
          data-day=${n}
          aria-label=${label}
          aria-selected=${selected ? 'true' : 'false'}
          tabindex=${selected ? 0 : -1}
          @click=${() => this.select(day)}
        >
          <span class="day-number body-large">${n}</span>
          <span class="day-marks">
            ${colors.map((c) => html`<span class="dot" style="background:${cssColor(c)}"></span>`)}
            ${hasBonus ? html`<span class="bonus-mark">${icon('redeem')}</span>` : nothing}
          </span>
        </button>
      </div>`);
    }
    return html`<div class="weekdays" aria-hidden="true">
        ${names.map((name, i) => html`<span class="label-medium ${i >= 5 ? 'weekend' : ''}">${name}</span>`)}
      </div>
      <div class="month-grid" role="grid" aria-label=${monthYear(month, t.locale)} @keydown=${(e: KeyboardEvent) => this.onGridKey(e)}>
        ${cells}
      </div>`;
  }

  /** The selected day: its total (per currency), and its shifts and bonuses. */
  private dayPanel() {
    const t = this.strings;
    const day = this.selectedDay;
    const { shiftsByDate, bonusesByDate } = this.calendar;
    // The selected day may be outside the month on screen: its entries come from the data, not the grid's months.
    const inState = shiftsByDate.has(day) || bonusesByDate.has(day) || monthOf(day) === this.shownMonth;
    const source = inState ? this.calendar : calendarState(this.view, this.mode, monthOf(day));
    const shifts = source.shiftsByDate.get(day) ?? [];
    const bonuses = source.bonusesByDate.get(day) ?? [];
    const total = earnings(shifts, bonuses, this.mode).filter((v) => v > 0n);
    const count = shifts.length + bonuses.length;
    return html`<section class="day-panel" data-testid="day-panel" aria-live="polite">
      <div class="day-head">
        <div class="grow">
          <h3 class="title-large" data-testid="day-title">${fullDate(day, t.locale)}</h3>
          <p class="body-medium on-surface-variant" data-testid="day-subtitle">
            ${shifts.length === 0
              ? t.t('no_shifts_this_day')
              : `${hoursLabel(t, shifts.reduce((m, s) => m + shiftPaidMinutes(s.shift), 0))} · ${t.plural('shift_count', shifts.length)}`}
          </p>
        </div>
        ${total.isEmpty
          ? nothing
          : html`<div class="day-total">
              <span class="title-large emphasized num primary" data-testid="day-total"
                >${total.sortedByValue().map(([c, v]) => html`<span>${money(v, c, t.locale)}</span>`)}</span
              >
              ${modeTag(t, this.mode, this.showModeTag, 'primary')}
            </div>`}
      </div>
      ${count === 0
        ? html`<p class="day-free body-medium" data-testid="day-free">${icon('eventAvailable')}${t.t('day_free')}</p>`
        : html`<ul class="entries">
            ${shifts.map((s, i) => shiftCard(t, s, this.mode, segment(i, count), this.editing ? () => this.request('edit-shift', s.shift.id) : undefined))}
            ${bonuses.map((b, i) => bonusCard(t, b, this.mode, segment(shifts.length + i, count), this.editing ? () => this.request('edit-bonus', b.bonus.id) : undefined))}
          </ul>`}
      ${this.editing
        ? html`<div class="day-actions">
            <md-filled-button data-testid="add-shift" has-icon @click=${() => this.request('new-shift', day)}>${icon('add', 'icon')}${t.t('add_shift')}</md-filled-button>
          </div>`
        : nothing}
    </section>`;
  }

  protected override render() {
    return html`<div class="calendar">
      <div class="calendar-month">${this.toolbar()} ${this.strip()} ${this.monthBonuses()} ${this.grid()}</div>
      <div class="calendar-day">${this.dayPanel()}</div>
    </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-calendar': CalendarScreen;
  }
}
