package com.najah.camapsguardian

import android.app.Application
import com.najah.camapsguardian.R
import com.najah.camapsguardian.core.App
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.Notifier
import com.najah.camapsguardian.core.Prefs
import com.najah.camapsguardian.core.Sys
import com.najah.camapsguardian.core.tr

class GuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        App.install(this)
        Logbook.init(this)
        Notifier.ensureChannels(this)
        Logbook.add(tr(R.string.cat_app), tr(R.string.log_app_started, Sys.deviceLabel()))
        val p = Prefs.get(this).value
        if (p.enabled) {
            Logbook.add(
                tr(R.string.cat_app),
                tr(R.string.log_already_active, p.checkMinutes)
            )
        }
    }
}
