import { html, type TemplateResult } from 'lit';
import { unsafeSVG } from 'lit/directives/unsafe-svg.js';
// Material icons (Apache 2.0), the same set the app uses (Icons.Filled / Icons.Outlined), as inline SVG: no icon font.
import calendarFilled from '@material-design-icons/svg/filled/calendar_month.svg?raw';
import calendarOutlined from '@material-design-icons/svg/outlined/calendar_month.svg?raw';
import insightsFilled from '@material-design-icons/svg/filled/insights.svg?raw';
import insightsOutlined from '@material-design-icons/svg/outlined/insights.svg?raw';
import workFilled from '@material-design-icons/svg/filled/work.svg?raw';
import workOutlined from '@material-design-icons/svg/outlined/work.svg?raw';
import refresh from '@material-design-icons/svg/filled/refresh.svg?raw';
import logout from '@material-design-icons/svg/filled/logout.svg?raw';
import chevronLeft from '@material-design-icons/svg/filled/chevron_left.svg?raw';
import chevronRight from '@material-design-icons/svg/filled/chevron_right.svg?raw';
import today from '@material-design-icons/svg/filled/today.svg?raw';
import redeem from '@material-design-icons/svg/filled/redeem.svg?raw';
import nightsStay from '@material-design-icons/svg/filled/nights_stay.svg?raw';
import eventAvailable from '@material-design-icons/svg/filled/event_available.svg?raw';
import queryStats from '@material-design-icons/svg/filled/query_stats.svg?raw';
import login from '@material-design-icons/svg/filled/login.svg?raw';
import payments from '@material-design-icons/svg/filled/payments.svg?raw';

const ICONS = {
  calendar: calendarFilled,
  calendarOutlined,
  insights: insightsFilled,
  insightsOutlined,
  work: workFilled,
  workOutlined,
  refresh,
  logout,
  chevronLeft,
  chevronRight,
  today,
  redeem,
  nightsStay,
  eventAvailable,
  queryStats,
  login,
  payments,
};

export type IconName = keyof typeof ICONS;

/** An icon as <md-icon> (sized and coloured by Material Web). Decorative: hidden from screen readers. */
export function icon(name: IconName): TemplateResult {
  return html`<md-icon aria-hidden="true">${unsafeSVG(ICONS[name])}</md-icon>`;
}
