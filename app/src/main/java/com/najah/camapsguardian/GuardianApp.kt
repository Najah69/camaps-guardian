package com.najah.camapsguardian

import android.app.Application
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.Notifier
import com.najah.camapsguardian.core.Prefs
import com.najah.camapsguardian.core.Sys

class GuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logbook.init(this)
        Notifier.ensureChannels(this)
        Logbook.add("app", "démarrage — ${Sys.deviceLabel()}")
        val p = Prefs.get(this).value
        if (p.enabled) Logbook.add("app", "surveillance déjà active (${p.checkMinutes} min)")
    }
}
