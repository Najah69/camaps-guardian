package com.najah.camapsguardian.core

import android.app.job.JobParameters
import android.app.job.JobService
import com.najah.camapsguardian.R

/**
 * Dernier filet : une tâche périodique persistante (15 min) qui relance le service
 * même si les alarmes ont été perdues et que le téléphone a redémarré.
 */
class WakeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val p = Prefs.get(this).value
        Logbook.add(
            tr(R.string.cat_job),
            tr(
                R.string.log_job_tick,
                tr(if (p.enabled) R.string.ui_monitoring_on else R.string.ui_monitoring_off)
            )
        )
        if (p.enabled) WatchdogService.start(this, tr(R.string.log_trigger_job))
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
