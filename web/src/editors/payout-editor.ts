import { html, nothing } from 'lit';
import { customElement } from 'lit/decorators.js';
import type { PayoutRecord } from '../data/documents';
import { currencySymbol } from '../domain/currencies';
import { dateString, parseDate, type YearMonth } from '../domain/dates';
import { newSyncId } from '../edit/documents';
import { checkPayout, editPayoutForm, newPayoutForm, payoutValues, setPayoutMonth, type PayoutForm } from '../edit/forms';
import { limitInput } from '../edit/money-input';
import { monthYear } from '../ui/format';
import { icon } from '../ui/icons';
import { jobAvatar } from '../screens/calendar';
import { decimalSeparator } from './bonus-editor';
import { EditorBase } from './editor-base';

/**
 * Add or edit one received payment (ui/summary/ReceivedPay.kt): for one job (the row it was opened from), the month
 * it was earned for, the amount received, an optional date it arrived and an optional note. Delete writes a
 * tombstone; the snackbar offers Undo.
 */
@customElement('st-payout-editor')
export class PayoutEditor extends EditorBase<PayoutForm> {
  openNew(jobId: string, month: YearMonth): boolean {
    const job = this.jobs.find((j) => j.id === jobId);
    if (!job) return false;
    void this.openWith(newPayoutForm(job, month), null);
    return true;
  }

  openExisting(id: string): boolean {
    const record = this.store?.record('payouts', id);
    if (!record || record.kind !== 'payout' || record.deleted) return false;
    const job = this.jobs.find((j) => j.id === record.jobId) ?? null;
    if (!job) return false;
    void this.openWith(editPayoutForm(record, job, decimalSeparator(this.strings.locale)), record);
    return true;
  }

  private save() {
    const values = payoutValues(this.form!, this.jobs);
    if (!values) return;
    const record = this.record as PayoutRecord | null;
    void this.commit(
      record
        ? { kind: 'update', collection: 'payouts', id: record.id, values, base: record.updatedAt }
        : { kind: 'create', collection: 'payouts', id: newSyncId(), values },
      this.strings.t('received_pay_saved'),
    );
  }

  private deletePayout() {
    const record = this.record as PayoutRecord;
    void this.commit({ kind: 'delete', collection: 'payouts', id: record.id, base: record.updatedAt }, this.strings.t('received_pay_deleted'), true);
  }

  protected override renderForm() {
    const form = this.form;
    const t = this.strings;
    if (!form) return this.frame('', html``, { canSave: false, onSave: () => {} });
    const check = checkPayout(form, this.jobs);
    const job = check.job;
    const update = (next: PayoutForm) => this.setForm(next);
    const body = html`
      ${job
        ? html`<div class="job-row payout-job" data-testid="payout-job">
            ${jobAvatar(job.name, job.color)}
            <div class="grow">
              <div class="title-medium">${t.t('received_pay_sheet_title', job.name)}</div>
            </div>
          </div>`
        : nothing}

      <span class="field-label label-large">${t.t('received_pay_month')}</span>
      <div class="month-stepper" data-testid="payout-month">
        <md-icon-button aria-label=${t.t('previous_month')} data-testid="payout-month-previous" @click=${() => update(setPayoutMonth(form, form.month - 1))}
          >${icon('chevronLeft')}</md-icon-button
        >
        <span class="title-medium grow" data-testid="payout-month-label">${monthYear(form.month, t.locale)}</span>
        <md-icon-button aria-label=${t.t('next_month')} data-testid="payout-month-next" @click=${() => update(setPayoutMonth(form, form.month + 1))}
          >${icon('chevronRight')}</md-icon-button
        >
      </div>
      <span class="field-support">${t.t('received_pay_month_hint')}</span>

      <label class="field">
        <span class="field-label label-large">${t.t('received_pay_amount')}</span>
        <span class="input-row ${this.showErrors && check.amount === null ? 'invalid' : ''}"
          ><input type="text" inputmode="decimal" data-testid="payout-amount" .value=${form.amountText}
            @input=${(e: Event) => update({ ...form, amountText: (e.target as HTMLInputElement).value })} /><span class="suffix">${currencySymbol(job?.currency ?? 'PLN')}</span></span
        >
        ${this.showErrors && check.amount === null ? html`<span class="field-support error-text" data-testid="amount-invalid">${t.t('error_amount_invalid')}</span>` : nothing}
      </label>

      <label class="field">
        <span class="field-label label-large">${t.t('received_pay_received_on')}</span>
        <span class="input-row">${icon('event')}<input type="date" data-testid="payout-received-on" .value=${form.receivedOn === null ? '' : dateString(form.receivedOn)}
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value;
            update({ ...form, receivedOn: value ? parseDate(value) : null });
          }} />
          ${form.receivedOn !== null
            ? html`<md-icon-button data-testid="payout-received-on-clear" aria-label=${t.t('received_pay_received_on_clear')} @click=${(e: Event) => {
                e.preventDefault();
                update({ ...form, receivedOn: null });
              }}>${icon('close')}</md-icon-button>`
            : nothing}</span
        >
      </label>

      <label class="field">
        <span class="field-label label-large">${t.t('received_pay_note')}</span>
        <textarea rows="2" data-testid="payout-note" class=${check.noteTooLong ? 'invalid' : ''} .value=${form.note}
          @input=${(e: Event) => {
            const area = e.target as HTMLTextAreaElement;
            const next = limitInput(form.note, area.value);
            if (next !== area.value) area.value = next;
            update({ ...form, note: next });
          }}></textarea>
        ${this.lengthSupport(form.note.length)}
      </label>
    `;
    const record = this.record;
    return this.frame(record ? t.t('received_pay_edit') : t.t('received_pay_new'), body, {
      canSave: check.canSave,
      onSave: () => this.save(),
      onDelete: record ? () => this.deletePayout() : undefined,
      deleteTitle: t.t('delete'),
    });
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-payout-editor': PayoutEditor;
  }
}
