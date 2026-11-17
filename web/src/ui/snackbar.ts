import { LitElement, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';

export interface SnackbarAction {
  label: string;
  run: () => void;
}

/**
 * An M3 snackbar: one short message at the bottom (above the navigation bar), with at most one action (Undo). It goes
 * away after [duration] ms, when the action is pressed, or when the next message replaces it.
 */
@customElement('st-snackbar')
export class Snackbar extends LitElement {
  @state() private message: string | null = null;
  @state() private action: SnackbarAction | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  protected override createRenderRoot() {
    return this;
  }

  show(message: string, action: SnackbarAction | null = null, duration = action ? 10_000 : 4_000) {
    this.message = message;
    this.action = action;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.dismiss(), duration);
  }

  dismiss() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.message = null;
    this.action = null;
  }

  get text(): string | null {
    return this.message;
  }

  protected override render() {
    if (!this.message) return nothing;
    const action = this.action;
    return html`<div class="snackbar body-medium" role="status" data-testid="snackbar">
      <span class="grow">${this.message}</span>
      ${action
        ? html`<button
            class="snackbar-action label-large"
            data-testid="snackbar-action"
            @click=${() => {
              this.dismiss();
              action.run();
            }}
          >
            ${action.label}
          </button>`
        : nothing}
    </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-snackbar': Snackbar;
  }
}
