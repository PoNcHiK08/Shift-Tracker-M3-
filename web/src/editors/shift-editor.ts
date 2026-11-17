import { html, nothing } from 'lit';
import { customElement } from 'lit/decorators.js';
import { dateString, parseDate, type EpochDay } from '../domain/dates';
import type { ShiftRecord } from '../data/documents';
import { newSyncId } from '../edit/documents';
import {
  BREAK_PRESETS,
  breakValue,
  checkShift,
  editShiftForm,
  newShiftForm,
  selectShiftJob,
  setBreakText,
  shiftPreviewEarnings,
  shiftValues,
  type ShiftForm,
} from '../edit/forms';
import { limitInput } from '../edit/money-input';
import { cssColor, money, rate, time } from '../ui/format';
import { icon } from '../ui/icons';
import { modeTag } from '../screens/calendar';
import { jobRatesLabel } from '../screens/jobs';
import { EditorBase } from './editor-base';

/** "7:30" (hours:minutes, Formatters.hoursMinutes). */
const hoursMinutes = (minutes: number) => `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
const minuteOf = (value: string): number | null => {
  const m = /^(\d{2}):(\d{2})/.exec(value);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};

/**
 * Add or edit a shift (ui/shift/EditShiftScreen.kt): job, date, start and end ("ends the next day" when the end is
 * not after the start), unpaid break (presets and minutes), note, and the live earnings card with the app's own
 * calculation. An existing shift keeps the rates it was saved with; when its job's current rate is different the card
 * says so (a hint only: nothing here changes rates). Delete writes a tombstone; the snackbar offers Undo.
 */
@customElement('st-shift-editor')
export class ShiftEditor extends EditorBase<ShiftForm> {
  /** A new shift on [date]. */
  openNew(date: EpochDay) {
    void this.openWith(newShiftForm(this.jobs, date), null);
  }

  /** The shift with sync id [id], as held now (the version a save must still find on the server). */
  openExisting(id: string): boolean {
    const record = this.store?.record('shifts', id);
    if (!record || record.kind !== 'shift' || record.deleted) return false;
    void this.openWith(editShiftForm(record), record);
    return true;
  }

  private save() {
    const form = this.form!;
    const values = shiftValues(form, this.jobs);
    if (!values) return;
    const t = this.strings;
    const record = this.record as ShiftRecord | null;
    void this.commit(
      record
        ? { kind: 'update', collection: 'shifts', id: record.id, values, base: record.updatedAt }
        : { kind: 'create', collection: 'shifts', id: newSyncId(), values },
      t.t('shift_saved'),
    );
  }

  private deleteShift() {
    const record = this.record as ShiftRecord;
    void this.commit({ kind: 'delete', collection: 'shifts', id: record.id, base: record.updatedAt }, this.strings.t('shift_deleted'), true);
  }

  private earningsCard(form: ShiftForm) {
    const t = this.strings;
    const check = checkShift(form, this.jobs);
    const currency = check.job?.currency ?? 'PLN';
    const second = form.secondRate;
    const base = check.duration - check.pay.secondRateMinutes;
    const breakMinutes = breakValue(form) ?? 0;
    return html`<section class="earnings-card" data-testid="earnings-card" aria-live="polite">
      <div class="earnings-top">
        <div class="grow">
          ${check.fixedPay
            ? html`<span class="label-medium faded">${t.t('pay_type')}</span>
                <div class="headline-small emphasized">${t.t('fixed_salary')}</div>`
            : html`<span class="label-medium faded">${t.t('earnings')} ${modeTag(t, this.mode, this.showModeTag, 'on-container')}</span>
                <div class="headline-small emphasized num" data-testid="earnings-amount">
                  ${money(shiftPreviewEarnings(form, check, this.mode), currency, t.locale)}
                </div>`}
        </div>
        <div class="end">
          <span class="label-medium faded">${t.t('paid_hours')}</span>
          <div class="title-large num" data-testid="paid-hours">${hoursMinutes(check.pay.paidMinutes)}</div>
        </div>
      </div>
      ${check.fixedPay
        ? html`<p class="body-medium">${t.t('fixed_salary_shift_hint')}</p>`
        : second === null
          ? html`<p class="body-medium" data-testid="rate-used">${t.t('rate_used', rate(form.rate, currency, t.locale))}</p>`
          : html`<p class="body-medium">${t.t('rate_line_base', rate(form.rate, currency, t.locale), hoursMinutes(base))}</p>
              <p class="body-medium">${t.t('rate_line_second', time(second.from), time(second.to), rate(second.rate, currency, t.locale), hoursMinutes(check.pay.secondRateMinutes))}</p>
              ${breakMinutes > 0 && check.pay.secondRateMinutes > 0 && base > 0 ? html`<p class="body-small faded">${t.t('break_split_hint')}</p>` : nothing}`}
      ${check.rateOutdated && check.job
        ? html`<p class="body-small" data-testid="rate-outdated">${t.t('rate_changed_hint', jobRatesLabel(t, check.job, form.date))}</p>`
        : nothing}
    </section>`;
  }

  protected override renderForm() {
    const form = this.form;
    const t = this.strings;
    if (!form) return this.frame('', html``, { canSave: false, onSave: () => {} });
    const check = checkShift(form, this.jobs);
    const update = (next: ShiftForm) => this.setForm(next);
    const breakMinutes = breakValue(form);
    const body = html`
      <span class="field-label label-large">${t.t('job')}</span>
      <div class="pills" role="radiogroup" aria-label=${t.t('job')} data-testid="job-pills">
        ${this.jobs.map(
          (job) => html`<button
            class="pill label-large ${job.id === form.jobId ? 'selected' : ''}"
            role="radio"
            aria-checked=${job.id === form.jobId ? 'true' : 'false'}
            data-job=${job.id}
            @click=${() => update(selectShiftJob(form, job))}
          >
            <span class="dot" style="background:${cssColor(job.color)}"></span>${job.name}
          </button>`,
        )}
      </div>
      ${this.showErrors && check.jobMissing ? html`<span class="field-support error-text">${t.t('error_job_required')}</span>` : nothing}

      <label class="field">
        <span class="field-label label-large">${t.t('date')}</span>
        <span class="input-row">${icon('event')}<input type="date" data-testid="shift-date" required .value=${dateString(form.date)}
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value;
            if (value) update({ ...form, date: parseDate(value) });
          }} /></span>
      </label>
      <div class="field-row">
        <label class="field">
          <span class="field-label label-large">${t.t('start_time')}</span>
          <span class="input-row">${icon('schedule')}<input type="time" data-testid="shift-start" required .value=${time(form.start)}
            @change=${(e: Event) => {
              const m = minuteOf((e.target as HTMLInputElement).value);
              if (m !== null) update({ ...form, start: m });
            }} /></span>
        </label>
        <label class="field">
          <span class="field-label label-large">${t.t('end_time')}</span>
          <span class="input-row">${icon(check.overnight ? 'nightsStay' : 'schedule')}<input type="time" data-testid="shift-end" required .value=${time(form.end)}
            @change=${(e: Event) => {
              const m = minuteOf((e.target as HTMLInputElement).value);
              if (m !== null) update({ ...form, end: m });
            }} /></span>
          ${check.overnight ? html`<span class="field-support" data-testid="ends-next-day">${t.t('ends_next_day')}</span>` : nothing}
        </label>
      </div>

      <span class="field-label label-large">${t.t('unpaid_break')}</span>
      <div class="choice-group break-presets" role="radiogroup" aria-label=${t.t('unpaid_break')}>
        ${BREAK_PRESETS.map(
          (minutes) => html`<button
            class="choice label-large ${breakMinutes === minutes ? 'selected' : ''} ${minutes === 0 ? 'wide' : ''}"
            role="radio"
            aria-checked=${breakMinutes === minutes ? 'true' : 'false'}
            data-break=${minutes}
            @click=${() => update(setBreakText(form, String(minutes)))}
          >
            <md-ripple></md-ripple>${minutes === 0 ? t.t('no_break') : t.t('minutes_short', minutes)}
          </button>`,
        )}
      </div>
      <label class="field">
        <span class="field-label label-large">${t.t('break_minutes')}</span>
        <span class="input-row ${breakMinutes === null || check.breakTooLong ? 'invalid' : ''}"
          ><input type="text" inputmode="numeric" data-testid="shift-break" .value=${form.breakText}
            @input=${(e: Event) => update(setBreakText(form, (e.target as HTMLInputElement).value))} /><span class="suffix">${t.t('minutes_suffix')}</span></span
        >
        ${check.breakTooLong ? html`<span class="field-support error-text" data-testid="break-too-long">${t.t('error_break_too_long')}</span>` : nothing}
      </label>

      <label class="field">
        <span class="field-label label-large">${t.t('note_optional')}</span>
        <textarea rows="2" data-testid="shift-note" class=${check.noteTooLong ? 'invalid' : ''} .value=${form.note}
          @input=${(e: Event) => {
            const area = e.target as HTMLTextAreaElement;
            const next = limitInput(form.note, area.value);
            if (next !== area.value) area.value = next;
            update({ ...form, note: next });
          }}></textarea>
        ${this.lengthSupport(form.note.length)}
      </label>

      ${this.earningsCard(form)}
    `;
    const record = this.record;
    return this.frame(record ? t.t('edit_shift') : t.t('new_shift'), body, {
      canSave: check.canSave,
      onSave: () => this.save(),
      onDelete: record ? () => this.deleteShift() : undefined,
      deleteTitle: t.t('delete_shift_title'),
      deleteMessage: t.t('delete_shift_message'),
    });
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-shift-editor': ShiftEditor;
  }
}
