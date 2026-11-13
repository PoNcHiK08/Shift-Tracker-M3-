import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { icon, type IconName } from '../ui/icons';
import type { Strings } from '../i18n/i18n';

export type Route = 'calendar' | 'summary' | 'jobs';

export const ROUTES: readonly Route[] = ['calendar', 'summary', 'jobs'];

/** The screen in the address (#/summary), Calendar when there is none. */
export function routeOf(hash: string): Route {
  const name = hash.replace(/^#\/?/, '').split(/[/?]/)[0];
  return (ROUTES as readonly string[]).includes(name ?? '') ? (name as Route) : 'calendar';
}

interface Destination {
  route: Route;
  label: string;
  selectedIcon: IconName;
  icon: IconName;
}

/**
 * The frame around the screens: a top app bar with the screen's title and actions, and the app's destinations
 * (Calendar, Summary, Jobs) as a bottom navigation bar on phone widths or a navigation rail on wider screens (CSS
 * shows one of them). Rendered in the light DOM, so the app's stylesheet and tests reach everything.
 */
@customElement('st-shell')
export class Shell extends LitElement {
  @property({ attribute: false }) strings!: Strings;
  @property() route: Route = 'calendar';
  @property({ attribute: false }) actions: TemplateResult | typeof nothing = nothing;
  @property({ attribute: false }) banner: TemplateResult | typeof nothing = nothing;
  @property({ attribute: false }) content: TemplateResult | typeof nothing = nothing;

  protected override createRenderRoot() {
    return this;
  }

  private destinations(): Destination[] {
    const s = this.strings;
    return [
      { route: 'calendar', label: s.t('nav_calendar'), selectedIcon: 'calendar', icon: 'calendarOutlined' },
      { route: 'summary', label: s.t('nav_summary'), selectedIcon: 'insights', icon: 'insightsOutlined' },
      { route: 'jobs', label: s.t('nav_jobs'), selectedIcon: 'work', icon: 'workOutlined' },
    ];
  }

  private screenTitle(): string {
    switch (this.route) {
      case 'calendar':
        return this.strings.t('calendar_title');
      case 'summary':
        return this.strings.t('summary_title');
      case 'jobs':
        return this.strings.t('jobs_title');
    }
  }

  private navItems(kind: 'bar' | 'rail') {
    return this.destinations().map((d) => {
      const selected = d.route === this.route;
      return html`<a
        class="nav-item"
        href="#/${d.route}"
        aria-current=${selected ? 'page' : 'false'}
        data-route=${d.route}
        data-kind=${kind}
      >
        <span class="nav-indicator">
          <md-ripple></md-ripple>
          ${icon(selected ? d.selectedIcon : d.icon)}
        </span>
        <span class="nav-label label-medium">${d.label}</span>
        <md-focus-ring></md-focus-ring>
      </a>`;
    });
  }

  protected override render() {
    return html`
      <nav class="nav-rail" aria-label=${this.strings.t('app_name')}>${this.navItems('rail')}</nav>
      <div class="frame">
        ${this.banner}
        <header class="top-bar">
          <h1 class="title-large top-bar-title">${this.screenTitle()}</h1>
          <div class="top-bar-actions">${this.actions}</div>
        </header>
        <main class="screen" id="main">${this.content}</main>
      </div>
      <nav class="nav-bar" aria-label=${this.strings.t('app_name')}>${this.navItems('bar')}</nav>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-shell': Shell;
  }
}
