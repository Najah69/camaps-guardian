package com.najah.camapsguardian.core

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Dernier filet : une tâche périodique persistante (15 min) qui relance le service
 * même si les alarmes ont été perdues et que le téléphone a redémarré.
 */
class WakeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val p = Prefs.get(this).value
        Logbook.add(
            "job",
            "tâche périodique — surveillance ${if (p.enabled) "active" else "inactive"}"
        )
        if (p.enabled) WatchdogService.start(this, "tâche périodique")
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
