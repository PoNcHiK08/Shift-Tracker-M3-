import { html, nothing } from 'lit';
import { customElement } from 'lit/decorators.js';
import type { BonusRecord } from '../data/documents';
import { currencySymbol } from '../domain/currencies';
import { dateString, monthOf, parseDate, type EpochDay } from '../domain/dates';
import { newSyncId } from '../edit/documents';
import { bonusValues, checkBonus, editBonusForm, newBonusForm, setBonusMonth, type BonusForm } from '../edit/forms';
import { limitInput } from '../edit/money-input';
import { cssColor, monthYear } from '../ui/format';
import { icon } from '../ui/icons';
import { EditorBase } from './editor-base';

/** The decimal separator the reader's locale types ("," in Ukrainian, "." in English). */
export function decimalSeparator(locale: string): string {
  return new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal')?.value ?? ',';
}

/**
 * Add or edit a bonus (ui/bonus/EditBonusScreen.kt): job, "On a specific day" or "For the whole month" (with the
 * month; such a bonus is stored on the 1st and is on no day), amount in the job's currency, note. Delete writes a
 * tombstone; the snackbar offers Undo.
 */
@customElement('st-bonus-editor')
export class BonusEditor extends EditorBase<BonusForm> {
  openNew(date: EpochDay, wholeMonth = false) {
    void this.openWith(newBonusForm(this.jobs, date, wholeMonth), null);
  }

  openExisting(id: string): boolean {
    const record = this.store?.record('bonuses', id);
    if (!record || record.kind !== 'bonus' || record.deleted) return false;
    const job = this.jobs.find((j) => j.id === record.jobId) ?? null;
    void this.openWith(editBonusForm(record, job, decimalSeparator(this.strings.locale)), record);
    return true;
  }

  private save() {
    const values = bonusValues(this.form!, this.jobs);
    if (!values) return;
    const record = this.record as BonusRecord | null;
    void this.commit(
      record
        ? { kind: 'update', collection: 'bonuses', id: record.id, values, base: record.updatedAt }
        : { kind: 'create', collection: 'bonuses', id: newSyncId(), values },
      this.strings.t('bonus_saved'),
    );
  }

  private deleteBonus() {
    const record = this.record as BonusRecord;
    void this.commit({ kind: 'delete', collection: 'bonuses', id: record.id, base: record.updatedAt }, this.strings.t('bonus_deleted'), true);
  }

  protected override renderForm() {
    const form = this.form;
    const t = this.strings;
    if (!form) return this.frame('', html``, { canSave: false, onSave: () => {} });
    const check = checkBonus(form, this.jobs);
    const update = (next: BonusForm) => this.setForm(next);
    const month = monthOf(form.date);
    const currency = check.job?.currency ?? 'PLN';
    const body = html`
      <span class="field-label label-large">${t.t('job')}</span>
      <div class="pills" role="radiogroup" aria-label=${t.t('job')} data-testid="job-pills">
        ${this.jobs.map(
          (job) => html`<button
            class="pill label-large ${job.id === form.jobId ? 'selected' : ''}"
            role="radio"
            aria-checked=${job.id === form.jobId ? 'true' : 'false'}
            data-job=${job.id}
            @click=${() => update({ ...form, jobId: job.id })}
          >
            <span class="dot" style="background:${cssColor(job.color)}"></span>${job.name}
          </button>`,
        )}
      </div>
      ${this.showErrors && check.jobMissing ? html`<span class="field-support error-text">${t.t('error_job_required')}</span>` : nothing}

      <div class="choice-group bonus-kind" role="radiogroup" aria-label=${t.t('bonus')} data-testid="bonus-kind">
        ${[false, true].map(
          (whole) => html`<button
            class="choice label-large ${form.wholeMonth === whole ? 'selected' : ''}"
            role="radio"
            aria-checked=${form.wholeMonth === whole ? 'true' : 'false'}
            data-whole-month=${String(whole)}
            @click=${() => update({ ...form, wholeMonth: whole })}
          >
            <md-ripple></md-ripple>${t.t(whole ? 'bonus_kind_month' : 'bonus_kind_day')}
          </button>`,
        )}
      </div>

      ${form.wholeMonth
        ? html`<span class="field-label label-large">${t.t('bonus_month')}</span>
            <div class="month-stepper" data-testid="bonus-month">
              <md-icon-button aria-label=${t.t('previous_month')} data-testid="bonus-month-previous" @click=${() => update(setBonusMonth(form, month - 1))}
                >${icon('chevronLeft')}</md-icon-button
              >
              <span class="title-medium grow" data-testid="bonus-month-label">${monthYear(month, t.locale)}</span>
              <md-icon-button aria-label=${t.t('next_month')} data-testid="bonus-month-next" @click=${() => update(setBonusMonth(form, month + 1))}
                >${icon('chevronRight')}</md-icon-button
              >
            </div>`
        : html`<label class="field">
            <span class="field-label label-large">${t.t('date')}</span>
            <span class="input-row">${icon('event')}<input type="date" data-testid="bonus-date" required .value=${dateString(form.date)}
              @change=${(e: Event) => {
                const value = (e.target as HTMLInputElement).value;
                if (value) update({ ...form, date: parseDate(value) });
              }} /></span>
          </label>`}

      <label class="field">
        <span class="field-label label-large">${t.t('bonus_amount')}</span>
        <span class="input-row ${this.showErrors && check.amount === null ? 'invalid' : ''}"
          ><input type="text" inputmode="decimal" data-testid="bonus-amount-input" .value=${form.amountText}
            @input=${(e: Event) => update({ ...form, amountText: (e.target as HTMLInputElement).value })} /><span class="suffix">${currencySymbol(currency)}</span></span
        >
        ${this.showErrors && check.amount === null ? html`<span class="field-support error-text" data-testid="amount-invalid">${t.t('error_amount_invalid')}</span>` : nothing}
      </label>

      <label class="field">
        <span class="field-label label-large">${t.t('bonus_note')}</span>
        <textarea rows="2" data-testid="bonus-note" class=${check.noteTooLong ? 'invalid' : ''} .value=${form.note}
          @input=${(e: Event) => {
            const area = e.target as HTMLTextAreaElement;
            const next = limitInput(form.note, area.value);
            if (next !== area.value) area.value = next;
            update({ ...form, note: next });
          }}></textarea>
        ${this.lengthSupport(form.note.length)}
      </label>
      <p class="body-small on-surface-variant" data-testid="bonus-hint">${t.t(form.wholeMonth ? 'bonus_hint_month' : 'bonus_hint')}</p>
    `;
    const record = this.record;
    return this.frame(record ? t.t('edit_bonus') : t.t('new_bonus'), body, {
      canSave: check.canSave,
      onSave: () => this.save(),
      onDelete: record ? () => this.deleteBonus() : undefined,
      deleteTitle: t.t('delete_bonus_title'),
    });
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-bonus-editor': BonusEditor;
  }
}
