package com.vkurylo.shifttracker.data.sync

/**
 * The first time this phone's data meets an account's data, records that look the same on both sides are
 * one record with two sync ids, typically the same backup restored on two phones (each import, or each
 * migration to sync ids, gave them their own ids). Merging by sync id alone would show them twice.
 * Linking a local record to its cloud look-alike (giving it the cloud's sync id) makes them one record;
 * from then on the usual rule (last write wins) applies to it.
 *
 * Look-alike, for live records only, one to one, in order:
 * - a job: the same name (ignoring case and surrounding spaces) and currency;
 * - a shift: the same job (after linking jobs), date, start and end;
 * - a bonus: the same job, date, amount and kind (on a day, or for the whole month);
 * - a payout: the same job, month and amount.
 * Records whose sync id the other side already has are the same record already and aren't touched.
 */
object LookAlikes {

    /** Local sync id -> the cloud sync id it becomes. */
    data class Links(
        val jobs: Map<String, String>,
        val shifts: Map<String, String>,
        val bonuses: Map<String, String>,
        val payouts: Map<String, String> = emptyMap(),
    ) {
        val size: Int get() = jobs.size + shifts.size + bonuses.size + payouts.size
    }

    fun find(
        localJobs: List<RemoteJob>,
        localShifts: List<RemoteShift>,
        localBonuses: List<RemoteBonus>,
        cloud: RemoteChanges,
        localPayouts: List<RemotePayout> = emptyList(),
    ): Links {
        val jobs = link(localJobs, cloud.jobs) { "${it.name.trim().lowercase()}|${it.currency}" }
        val jobRef = { syncId: String -> jobs[syncId] ?: syncId }
        val shifts = link(localShifts, cloud.shifts) { "${jobRef(it.jobSyncId)}|${it.dateEpochDay}|${it.startMinute}|${it.endMinute}" }
        val bonuses = link(localBonuses, cloud.bonuses) { "${jobRef(it.jobSyncId)}|${it.dateEpochDay}|${it.amountGrosze}|${it.wholeMonth}" }
        val payouts = link(localPayouts, cloud.payouts) { "${jobRef(it.jobSyncId)}|${it.month}|${it.amountMinor}" }
        return Links(jobs, shifts, bonuses, payouts)
    }

    /**
     * Pairs local records with cloud records of the same [key]. The key of a cloud record is computed by
     * the same function: for shifts and bonuses, [jobRef] maps only local job ids, so cloud keys are unchanged.
     */
    private fun <T : RemoteRecord> link(local: List<T>, cloud: List<T>, key: (T) -> String): Map<String, String> {
        val localIds = local.mapTo(HashSet()) { it.syncId }
        val cloudIds = cloud.mapTo(HashSet()) { it.syncId }
        val candidates = cloud
            .filter { !it.deleted && it.syncId !in localIds }
            .groupByTo(HashMap(), key) { it.syncId }
        val links = LinkedHashMap<String, String>()
        for (record in local) {
            if (record.deleted || record.syncId in cloudIds) continue
            val ids = candidates[key(record)] ?: continue
            if (ids.isNotEmpty()) links[record.syncId] = ids.removeAt(0)
        }
        return links
    }
}
