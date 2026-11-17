import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { property, query, state } from 'lit/decorators.js';
import type { DataStore } from '../data/store';
import { classify } from '../data/store';
import type { EarningsMode, Job } from '../domain/model';
import { isDirty } from '../edit/forms';
import { valuesOf, type EditableRecord } from '../edit/documents';
import type { Plan, Refusal, WriteOp } from '../edit/plan';
import type { Strings } from '../i18n/i18n';
import { icon } from '../ui/icons';

/** What an editor tells the app when it is done: a message for the snackbar, and how to undo a delete. */
export interface EditorDone {
  message: string;
  undo?: WriteOp;
}

type Problem = { kind: 'refused'; refusal: Refusal } | { kind: 'denied' } | { kind: 'error'; detail: string };

/**
 * What every editor (shift, bonus, payment) shares: a modal dialog (full screen on a phone), the unsaved-changes
 * guard (closing with changes asks first), Save and Delete through the store's single write path, and what a refused
 * write says ("changed on another device", with Reload). Nothing is written without the user pressing Save or
 * Delete; a delete can be undone from the snackbar.
 */
export abstract class EditorBase<F extends object> extends LitElement {
  @property({ attribute: false }) strings!: Strings;
  @property({ attribute: false }) store: DataStore | null = null;
  @property() mode: EarningsMode = 'GROSS';
  @property({ type: Boolean }) showModeTag = false;
  @state() protected form: F | null = null;
  @state() protected initial: F | null = null;
  /** The stored record being edited (with the version it was opened from); null for a new one. */
  @state() protected record: EditableRecord | null = null;
  @state() protected saving = false;
  @state() protected showErrors = false;
  @state() protected problem: Problem | null = null;
  @state() protected confirmDiscard = false;
  @state() protected confirmDelete = false;
  @query('dialog.editor') protected dialog!: HTMLDialogElement;

  protected override createRenderRoot() {
    return this;
  }

  protected get jobs(): Job[] {
    return this.store?.state.view.jobs ?? [];
  }

  get isOpen(): boolean {
    return this.form !== null;
  }

  protected async openWith(form: F, record: EditableRecord | null) {
    this.form = form;
    this.initial = form;
    this.record = record;
    this.saving = false;
    this.showErrors = false;
    this.problem = null;
    this.confirmDiscard = false;
    this.confirmDelete = false;
    await this.updateComplete;
    if (!this.dialog.open) this.dialog.showModal();
  }

  get dirty(): boolean {
    return this.form !== null && this.initial !== null && isDirty(this.initial, this.form);
  }

  /** Close, unless there are unsaved changes: then ask first. */
  requestClose() {
    if (this.saving) return;
    if (this.dirty) {
      this.confirmDiscard = true;
      return;
    }
    this.close();
  }

  protected close(done?: EditorDone) {
    this.dialog?.close();
    this.form = null;
    this.initial = null;
    this.record = null;
    this.confirmDiscard = false;
    this.confirmDelete = false;
    this.dispatchEvent(new CustomEvent<EditorDone | undefined>('editor-closed', { detail: done, bubbles: true, composed: true }));
  }

  /** Writes [op]; on success closes with [message] (and the undo of a delete). */
  protected async commit(op: WriteOp, message: string, undoable = false) {
    if (!this.store || this.saving) return;
    this.saving = true;
    this.problem = null;
    let result: Plan;
    try {
      result = await this.store.write(op);
    } catch (error) {
      this.saving = false;
      const code = (error as { code?: string }).code;
      this.problem = code === 'permission-denied' ? { kind: 'denied' } : { kind: 'error', detail: this.errorDetail(error) };
      return;
    }
    this.saving = false;
    if (!result.ok) {
      this.problem = { kind: 'refused', refusal: result.refusal };
      return;
    }
    const record = this.record;
    const undo: WriteOp | undefined =
      undoable && record ? { kind: 'restore', collection: op.collection, id: op.id, values: valuesOf(record), base: result.updatedAt } : undefined;
    this.close({ message, undo });
  }

  private errorDetail(error: unknown): string {
    const kind = classify(error);
    if (kind.kind === 'offline') return this.strings.t('error_offline');
    return kind.kind === 'other' ? kind.detail : String(error);
  }

  /** Reload after a refusal: read the latest data and close (the user opens the record again). */
  private async reload() {
    this.close();
    await this.store?.refresh();
  }

  protected problemBanner(): TemplateResult | typeof nothing {
    const p = this.problem;
    if (!p) return nothing;
    const t = this.strings;
    let text: string;
    let reload = false;
    switch (p.kind) {
      case 'refused':
        reload = p.refusal !== 'unreadable';
        text = p.refusal === 'no-job' ? t.t('refused_no_job') : p.refusal === 'unreadable' ? t.t('refused_unreadable') : t.t('conflict_changed');
        break;
      case 'denied':
        text = t.t('write_denied');
        break;
      case 'error':
        text = t.t('write_failed', p.detail);
        break;
    }
    return html`<div class="editor-problem body-medium" role="alert" data-testid="editor-problem">
      <span>${text}</span>
      ${reload ? html`<md-text-button data-testid="editor-reload" @click=${() => void this.reload()}>${t.t('conflict_reload')}</md-text-button>` : nothing}
    </div>`;
  }

  /** The dialog frame: title, body, actions, and the discard / delete confirmations on top. */
  protected frame(title: string, body: TemplateResult, options: { canSave: boolean; onSave: () => void; onDelete?: () => void; deleteTitle?: string; deleteMessage?: string }) {
    const t = this.strings;
    return html`<dialog
      class="editor"
      data-testid="editor"
      aria-label=${title}
      @cancel=${(e: Event) => {
        e.preventDefault();
        if (this.confirmDiscard) this.confirmDiscard = false;
        else if (this.confirmDelete) this.confirmDelete = false;
        else this.requestClose();
      }}
    >
      ${this.form
        ? html`<div class="editor-surface">
            <header class="editor-head">
              <md-icon-button data-testid="editor-close" aria-label=${t.t('close')} @click=${() => this.requestClose()}>${icon('close')}</md-icon-button>
              <h2 class="title-large">${title}</h2>
            </header>
            <div class="editor-body">${this.problemBanner()} ${body}</div>
            <footer class="editor-actions">
              ${options.onDelete
                ? html`<md-text-button class="danger" data-testid="editor-delete" ?disabled=${this.saving} @click=${() => (this.confirmDelete = true)}
                    >${icon('delete', 'icon')}${t.t('delete')}</md-text-button
                  >`
                : nothing}
              <span class="grow"></span>
              <md-text-button data-testid="editor-cancel" ?disabled=${this.saving} @click=${() => this.requestClose()}>${t.t('cancel')}</md-text-button>
              <md-filled-button
                data-testid="editor-save"
                ?disabled=${this.saving}
                @click=${() => {
                  this.showErrors = true;
                  if (options.canSave) options.onSave();
                }}
                >${this.saving ? t.t('saving') : t.t('save')}</md-filled-button
              >
            </footer>
            ${this.confirmDiscard
              ? this.confirm('discard', t.t('discard_title'), t.t('discard_message'), t.t('discard_confirm'), t.t('keep_editing'), () => this.close(), () => (this.confirmDiscard = false))
              : nothing}
            ${this.confirmDelete && options.onDelete
              ? this.confirm('delete', options.deleteTitle ?? t.t('delete'), options.deleteMessage ?? '', t.t('delete'), t.t('cancel'), () => {
                  this.confirmDelete = false;
                  options.onDelete!();
                }, () => (this.confirmDelete = false))
              : nothing}
          </div>`
        : nothing}
    </dialog>`;
  }

  private confirm(id: string, title: string, message: string, yes: string, no: string, onYes: () => void, onNo: () => void) {
    return html`<div class="confirm-scrim" data-testid="confirm-${id}">
      <div class="confirm" role="alertdialog" aria-label=${title}>
        <h3 class="headline-small">${title}</h3>
        ${message ? html`<p class="body-medium on-surface-variant">${message}</p>` : nothing}
        <div class="confirm-actions">
          <md-text-button data-testid="confirm-${id}-no" @click=${onNo}>${no}</md-text-button>
          <md-text-button data-testid="confirm-${id}-yes" class=${id === 'delete' ? 'danger' : ''} @click=${onYes}>${yes}</md-text-button>
        </div>
      </div>
    </div>`;
  }

  /** A text field's counter near the limit, and its error over it (TextLengthSupport.kt). */
  protected lengthSupport(length: number) {
    const t = this.strings;
    if (length < 18_000) return nothing;
    const over = length > 20_000;
    return html`<span class="field-support ${over ? 'error-text' : ''}">${t.t(over ? 'text_too_long' : 'text_length', String(length), '20000')}</span>`;
  }

  protected setForm(next: F) {
    this.form = next;
  }

  protected abstract renderForm(): TemplateResult;

  protected override render() {
    return this.renderForm();
  }
}
