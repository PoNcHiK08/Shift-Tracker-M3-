import { html, nothing, type TemplateResult } from 'lit';
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
import add from '@material-design-icons/svg/filled/add.svg?raw';
import close from '@material-design-icons/svg/filled/close.svg?raw';
import deleteIcon from '@material-design-icons/svg/outlined/delete.svg?raw';
import edit from '@material-design-icons/svg/filled/edit.svg?raw';
import event from '@material-design-icons/svg/filled/event.svg?raw';
import schedule from '@material-design-icons/svg/filled/schedule.svg?raw';
import download from '@material-design-icons/svg/filled/download.svg?raw';
import lock from '@material-design-icons/svg/filled/lock.svg?raw';
import editNote from '@material-design-icons/svg/filled/edit_note.svg?raw';

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
  add,
  close,
  delete: deleteIcon,
  edit,
  event,
  schedule,
  download,
  lock,
  editNote,
};

export type IconName = keyof typeof ICONS;

/** An icon as <md-icon> (sized and coloured by Material Web). Decorative: hidden from screen readers. */
/** A Material icon; [slot] puts it in a component's icon slot (e.g. a button's leading icon). */
export function icon(name: IconName, slot?: string): TemplateResult {
  return html`<md-icon aria-hidden="true" slot=${slot ?? nothing}>${unsafeSVG(ICONS[name])}</md-icon>`;
}
