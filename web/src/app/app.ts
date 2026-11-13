import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { browserStrings, type Strings } from '../i18n/i18n';
import { isDemo } from '../demo/demo';
import { routeOf, type Route } from './app-shell';
import { icon } from '../ui/icons';
import './app-shell';

/** The whole app: which screen is open, demo or signed-in, and the data the screens show. */
@customElement('st-app')
export class App extends LitElement {
  @property({ attribute: false }) strings: Strings = browserStrings();
  @property({ type: Boolean }) demo = isDemo();
  @state() private route: Route = routeOf(typeof location === 'undefined' ? '' : location.hash);

  private readonly onHashChange = () => {
    this.route = routeOf(location.hash);
  };

  protected override createRenderRoot() {
    return this;
  }

  override connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this.onHashChange);
    document.documentElement.lang = this.strings.lang;
    document.title = this.strings.t('app_name');
  }

  override disconnectedCallback() {
    window.removeEventListener('hashchange', this.onHashChange);
    super.disconnectedCallback();
  }

  private banner() {
    if (!this.demo) return nothing;
    return html`<div class="banner body-medium" role="note" data-testid="demo-banner">
      ${icon('queryStats')}<span>${this.strings.t('demo_banner')}</span>
      <a href=${location.pathname}>${this.strings.t('demo_exit')}</a>
    </div>`;
  }

  protected override render() {
    return html`<st-shell
      .strings=${this.strings}
      .route=${this.route}
      .banner=${this.banner()}
      .content=${html`<div class="message"><p class="body-large on-surface-variant">${this.strings.t('loading')}</p></div>`}
    ></st-shell>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-app': App;
  }
}
