import { LitElement, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { payFor } from '../domain/calc';
import { monthOf, type EpochDay } from '../domain/dates';
import type { Job } from '../domain/model';
import { EMPTY_VIEW, type DataView } from '../domain/screens';
import type { Strings } from '../i18n/i18n';
import { icon } from '../ui/icons';
import { money, rate, time } from '../ui/format';
import { jobAvatar, segment } from './calendar';

/** A deduction (basis points) as the app's editor shows it: "13,71", "13" (MoneyParser.toInput). */
export function deductionText(bp: number, locale: string): string {
  const separator = new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal')?.value ?? ',';
  const whole = Math.trunc(bp / 100);
  const fraction = bp % 100;
  return fraction === 0 ? String(whole) : `${whole}${separator}${String(fraction).padStart(2, '0')}`;
}

/** What a job pays this month, as the Jobs list says it (JobsScreen.kt jobRatesLabel). */
export function jobRatesLabel(strings: Strings, job: Job, today: EpochDay): string {
  const locale = strings.locale;
  const pay = payFor(job, monthOf(today));
  let label: string;
  if (pay.payType === 'FIXED_MONTHLY') {
    label = strings.t('fixed_salary_per_month', money(pay.monthlyAmount, job.currency, locale));
  } else {
    label = rate(job.hourlyRate, job.currency, locale);
    const second = job.secondRate;
    if (second) label += ` · ${strings.t('second_rate_summary', time(second.from), time(second.to), rate(second.rate, job.currency, locale))}`;
  }
  if (pay.deductionBp > 0) label += ` · ${strings.t('deduction_value', deductionText(pay.deductionBp, locale))}`;
  return label;
}

/** The Jobs list (ui/jobs/JobsScreen.kt), read-only: jobs and their rates are changed in the app. */
@customElement('st-jobs')
export class JobsScreen extends LitElement {
  @property({ attribute: false }) strings!: Strings;
  @property({ attribute: false }) view: DataView = EMPTY_VIEW;
  @property({ type: Number }) today: EpochDay = 0;

  protected override createRenderRoot() {
    return this;
  }

  protected override render() {
    const t = this.strings;
    const jobs = this.view.jobs;
    return html`<div class="jobs">
      <p class="note body-medium on-surface-variant read-only-note">${icon('work')}<span>${t.t('jobs_read_only')}</span></p>
      <ul class="segments">
        ${jobs.map(
          (job, i) => html`<li class="segment job-item ${segment(i, jobs.length)}" data-testid="job" data-job=${job.id}>
            ${jobAvatar(job.name, job.color)}
            <div class="grow">
              <div class="body-large ellipsis" data-testid="job-name">${job.name}</div>
              <div class="body-medium on-surface-variant" data-testid="job-rates">${jobRatesLabel(t, job, this.today)}</div>
            </div>
            <span class="label-medium on-surface-variant" title=${t.t('job_currency', job.currency)}>${job.currency}</span>
          </li>`,
        )}
      </ul>
    </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'st-jobs': JobsScreen;
  }
}
