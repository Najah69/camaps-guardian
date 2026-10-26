package com.najah.camapsguardian.core

import android.content.Context
import com.najah.camapsguardian.R

/**
 * Ce que Shizuku (ou root) permet de voir et de corriger en plus, sans jamais arrêter
 * une application : processus présent, arrêt forcé, et protections contre la mise en veille.
 * Une seule commande par ronde pour toutes les applications surveillées.
 */
object Privileged {

    data class AppState(
        val pkg: String,
        /** Un processus tourne. */
        val running: Boolean,
        /** Arrêtée de force (« stopped ») : ses alarmes et ses services sont annulés. */
        val stopped: Boolean?,
        /** Sur la liste blanche de l'optimisation de batterie. */
        val whitelisted: Boolean?,
        /** RUN_ANY_IN_BACKGROUND n'est pas refusé. */
        val background: Boolean?,
        /** Catégorie de mise en veille (5 exemptée, 10 active … 45 restreinte). */
        val bucket: Int?
    )

    /** État de chaque paquet, ou null sans privilège (ou si la commande échoue). */
    fun probe(pkgs: List<String>): Map<String, AppState>? {
        val list = pkgs.filter { it.matches(Regex("[A-Za-z0-9_.]+")) }.distinct()
        if (list.isEmpty() || !Shell.canElevate()) return null
        val script = buildString {
            append("for p in ").append(list.joinToString(" ")).append("; do ")
            append("echo \"@\$p\"; ")
            append("echo \"pid=\$(pidof \$p)\"; ")
            append("dumpsys package \$p | grep -m1 -o 'stopped=[a-z]*'; ")
            append("echo \"wl=\$(dumpsys deviceidle whitelist | grep -c \",\$p,\")\"; ")
            append("echo \"bg=\$(cmd appops get \$p RUN_ANY_IN_BACKGROUND)\"; ")
            append("echo \"bucket=\$(am get-standby-bucket \$p)\"; ")
            append("done; true")
        }
        val r = Shell.runPrivileged(script)
        if (!r.ok) return null
        val out = HashMap<String, AppState>()
        var cur: String? = null
        val fields = HashMap<String, String>()
        fun flush() {
            val p = cur ?: return
            val bg = fields["bg"]
            out[p] = AppState(
                pkg = p,
                running = fields["pid"]?.isNotBlank() == true,
                stopped = fields["stopped"]?.let { it == "true" },
                whitelisted = fields["wl"]?.trim()?.toIntOrNull()?.let { it > 0 },
                background = bg?.let { !(it.contains("ignore") || it.contains("deny")) },
                bucket = fields["bucket"]?.trim()?.toIntOrNull()
            )
        }
        r.output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("@")) {
                flush()
                cur = line.substring(1)
                fields.clear()
            } else {
                val i = line.indexOf('=')
                if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        flush()
        return out
    }

    /**
     * Remet les protections d'arrière-plan qu'Android ou une surcouche aurait retirées.
     * Renvoie la liste des corrections faites (vide si tout était déjà en place).
     */
    fun protect(ctx: Context, st: AppState): List<String> {
        val done = mutableListOf<String>()
        val p = st.pkg
        if (st.whitelisted == false &&
            Shell.runPrivileged("dumpsys deviceidle whitelist +$p").ok
        ) done += ctx.tr(R.string.fix_whitelist)
        if (st.background == false &&
            Shell.runPrivileged("cmd appops set $p RUN_ANY_IN_BACKGROUND allow").ok
        ) done += ctx.tr(R.string.fix_background)
        // 30 = « fréquente », 40 = « rare », 45 = « restreinte » : les alarmes et les tâches y sont rationnées.
        if ((st.bucket ?: 0) >= 30 &&
            Shell.runPrivileged("am set-standby-bucket $p active").ok
        ) done += ctx.tr(R.string.fix_bucket, st.bucket ?: "?")
        if (done.isNotEmpty()) {
            Logbook.add(ctx.tr(R.string.cat_shell), ctx.tr(R.string.log_protect, p, done.joinToString(", ")))
        }
        return done
    }

    /** L'écouteur de notifications donné est-il réellement lié par Android ? null si inconnu. */
    fun listenerLive(component: String): Boolean? {
        if (!Shell.canElevate()) return null
        val cls = component.substringAfterLast('.').filter { it.isLetterOrDigit() || it == '$' }
        if (cls.isEmpty()) return null
        val r = Shell.runPrivileged(
            "echo L=\$(dumpsys notification | sed -n '/Live notification listeners/,/Snoozed/p' | grep -c '$cls')"
        )
        if (!r.ok) return null
        return r.output.substringAfter("L=", "").trim().toIntOrNull()?.let { it > 0 }
    }

    /** Débranche puis rebranche (ou autorise) un écouteur de notifications, comme adb. */
    fun rebindListener(component: String, revoked: Boolean): Shell.Result =
        Shell.runPrivileged(
            if (revoked) "cmd notification allow_listener $component"
            else "cmd notification disallow_listener $component; sleep 1; cmd notification allow_listener $component"
        )
}
