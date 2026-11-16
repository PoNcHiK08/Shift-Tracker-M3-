import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { browserStrings, type Strings } from '../i18n/i18n';
import { isDemo } from '../demo/demo';
import { routeOf, type Route } from './app-shell';
import { icon } from '../ui/icons';
import { createStore } from '../data/source';
import type { DataStore, LoadError, Snapshot } from '../data/store';
import './app-shell';

/** The whole app: which screen is open, demo or signed-in, and the data the screens show. */
@customElement('st-app')
export class App extends LitElement {
  @property({ attribute: false }) strings: Strings = browserStrings();
  @property({ type: Boolean }) demo = isDemo();
  /** Set by tests; otherwise made for this page on connect. */
  @property({ attribute: false }) store: DataStore | null = null;
  /** After signing out the page is loaded again, so nothing of the account stays in memory. */
  @property({ attribute: false }) reload: () => void = () => location.reload();
  @state() private route: Route = routeOf(typeof location === 'undefined' ? '' : location.hash);
  @state() protected snapshot: Snapshot | null = null;
  private unsubscribe: (() => void) | null = null;

  private readonly onHashChange = () => {
    this.route = routeOf(location.hash);
  };

  private readonly onVisible = () => {
    if (document.visibilityState === 'visible') this.store?.onFocus();
  };

  protected override createRenderRoot() {
    return this;
  }

  override connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this.onHashChange);
    window.addEventListener('focus', this.onVisible);
    document.addEventListener('visibilitychange', this.onVisible);
    document.documentElement.lang = this.strings.lang;
    document.title = this.strings.t('app_name');
    if (this.store) this.attach(this.store);
    else void createStore(this.demo).then((store) => this.isConnected && !this.store && this.attach(store));
  }

  override disconnectedCallback() {
    window.removeEventListener('hashchange', this.onHashChange);
    window.removeEventListener('focus', this.onVisible);
    document.removeEventListener('visibilitychange', this.onVisible);
    this.unsubscribe?.();
    this.unsubscribe = null;
    super.disconnectedCallback();
  }

  private attach(store: DataStore) {
    this.store = store;
    this.unsubscribe = store.subscribe((snapshot) => {
      this.snapshot = snapshot;
    });
    store.start();
  }

  private async signOut() {
    await this.store?.signOut();
    this.reload();
  }

  private banner() {
    if (!this.demo) return nothing;
    return html`<div class="banner body-medium" role="note" data-testid="demo-banner">
      ${icon('queryStats')}<span>${this.strings.t('demo_banner')}</span>
      <a href=${location.pathname}>${this.strings.t('demo_exit')}</a>
    </div>`;
  }

  private actions() {
    const s = this.snapshot;
    if (this.demo || !s?.user) return nothing;
    const t = this.strings;
    return html`
      ${s.refreshing ? html`<md-circular-progress indeterminate class="refresh-progress" aria-label=${t.t('refreshing')}></md-circular-progress>` : nothing}
      <md-icon-button
        data-testid="refresh"
        aria-label=${t.t('refresh')}
        title=${t.t('refresh')}
        ?disabled=${s.refreshing || s.phase.kind === 'loading'}
        @click=${() => void this.store?.refresh()}
        >${icon('refresh')}</md-icon-button
      >
      <md-icon-button
        data-testid="sign-out"
        aria-label=${t.t('sign_out')}
        title=${s.user.email ? `${t.t('sign_out')} (${s.user.email})` : t.t('sign_out')}
        @click=${() => void this.signOut()}
        >${icon('logout')}</md-icon-button
      >
    `;
  }

  private errorText(error: LoadError): string {
    switch (error.kind) {
      case 'access-denied':
        return this.strings.t('error_access_denied');
      case 'offline':
        return this.strings.t('error_offline');
      case 'other':
        return this.strings.t('error_other', error.detail);
    }
  }

  private message(content: TemplateResult, testId: string) {
    return html`<div class="message" data-testid=${testId}>${content}</div>`;
  }

  private loading() {
    return this.message(
      html`<md-circular-progress indeterminate></md-circular-progress>
        <p class="body-large on-surface-variant">${this.strings.t('loading')}</p>`,
      'loading',
    );
  }

  private signIn(busy: boolean, error: string | null) {
    const t = this.strings;
    return this.message(
      html`${icon('calendar')}
        <h2 class="headline-small">${t.t('sign_in_title')}</h2>
        <p class="body-large on-surface-variant">${t.t('sign_in_message')}</p>
        ${error ? html`<p class="body-medium error-text" role="alert" data-testid="sign-in-error">${t.t('sign_in_failed', error)}</p>` : nothing}
        <md-filled-button data-testid="sign-in" has-icon ?disabled=${busy} @click=${() => void this.store?.signIn()}>
          ${icon('login', 'icon')}${busy ? t.t('signing_in') : t.t('sign_in_button')}
        </md-filled-button>
        <md-text-button href="?demo">${t.t('sign_in_demo')}</md-text-button>`,
      'signed-out',
    );
  }

  private content() {
    const s = this.snapshot;
    const t = this.strings;
    if (!s) return this.loading();
    switch (s.phase.kind) {
      case 'not-configured':
        return this.message(
          html`<p class="body-large">${t.t('error_not_configured')}</p>
            <md-filled-tonal-button href="?demo">${t.t('sign_in_demo')}</md-filled-tonal-button>`,
          'not-configured',
        );
      case 'starting':
      case 'loading':
        return this.loading();
      case 'signed-out':
        return this.signIn(false, s.phase.signInError);
      case 'signing-in':
        return this.signIn(true, null);
      case 'failed':
        return this.message(
          html`<p class="body-large" role="alert">${this.errorText(s.phase.error)}</p>
            <md-filled-tonal-button data-testid="try-again" @click=${() => void this.store?.refresh()}>${t.t('try_again')}</md-filled-tonal-button>`,
          'failed',
        );
      case 'ready':
        return this.ready(s);
    }
  }

  private ready(s: Snapshot) {
    const t = this.strings;
    const notes = html`
      ${s.refreshError
        ? html`<div class="notice body-medium" role="alert" data-testid="refresh-error">${this.errorText(s.refreshError)}</div>`
        : nothing}
      ${s.skipped > 0 ? html`<div class="notice body-medium" data-testid="skipped">${t.t('skipped_records', s.skipped)}</div>` : nothing}
    `;
    if (s.view.jobs.length === 0) {
      return html`${notes}${this.message(
        html`<h2 class="headline-small">${t.t('empty_title')}</h2>
          <p class="body-large on-surface-variant">${t.t('empty_message')}</p>`,
        'empty',
      )}`;
    }
    return html`${notes}${this.screen(s)}`;
  }

  /** The open screen with the loaded data (the screens themselves come with their own commits). */
  protected screen(_s: Snapshot): TemplateResult {
    return html`<div class="screen-body" data-testid="screen-${this.route}"></div>`;
  }

  protected override render() {
    return html`<st-shell
      .strings=${this.strings}
      .route=${this.route}
      .banner=${this.banner()}
      .actions=${this.actions()}
      .content=${this.content()}
    ></st-shell>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-app': App;
  }
}
