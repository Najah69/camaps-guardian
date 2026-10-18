package com.najah.camapsguardian.core

import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

/**
 * Exécution de commandes privilégiées : root (su) puis Shizuku, sans dépendance externe
 * (Shizuku est appelé par réflexion : l'application fonctionne même s'il n'est pas installé).
 */
object Shell {

    data class Result(val ok: Boolean, val output: String, val how: String)

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootOk = false

    @Volatile
    private var shizukuChecked = false

    @Volatile
    private var shizukuOk = false

    // ---------------------------------------------------------------- exec

    private fun exec(cmd: List<String>, timeoutMs: Long): Result {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                Result(false, "délai dépassé", cmd.firstOrNull() ?: "?")
            } else {
                val out = try {
                    p.inputStream.bufferedReader().use { it.readText() }
                } catch (t: Throwable) {
                    ""
                }
                Result(p.exitValue() == 0, out.trim(), cmd.firstOrNull() ?: "?")
            }
        } catch (t: Throwable) {
            Result(false, t.message ?: t.javaClass.simpleName, cmd.firstOrNull() ?: "?")
        }
    }

    // ---------------------------------------------------------------- root

    fun hasRoot(): Boolean {
        if (rootChecked) return rootOk
        rootChecked = true
        val r = exec(listOf("su", "-c", "id"), 5000)
        rootOk = r.ok && r.output.contains("uid=0")
        Logbook.add("shell", if (rootOk) "root disponible" else "pas de root")
        return rootOk
    }

    // ---------------------------------------------------------------- Shizuku

    private fun shizukuClass(): Class<*>? = try {
        Class.forName("rikka.shizuku.Shizuku")
    } catch (t: Throwable) {
        null
    }

    fun hasShizuku(): Boolean {
        if (shizukuChecked) return shizukuOk
        shizukuChecked = true
        val c = shizukuClass()
        shizukuOk = if (c == null) {
            false
        } else {
            try {
                val ping = c.getMethod("pingBinder").invoke(null) as? Boolean ?: false
                val granted = try {
                    (c.getMethod("checkSelfPermission").invoke(null) as? Int) ==
                        PackageManager.PERMISSION_GRANTED
                } catch (t: Throwable) {
                    false
                }
                ping && granted
            } catch (t: Throwable) {
                false
            }
        }
        Logbook.add("shell", if (shizukuOk) "Shizuku disponible" else "Shizuku absent ou non autorisé")
        return shizukuOk
    }

    /** À appeler depuis l'interface (thread principal) : affiche la demande d'autorisation Shizuku. */
    fun requestShizukuPermission(): Boolean {
        val c = shizukuClass() ?: return false
        return try {
            c.getMethod("requestPermission", Integer.TYPE).invoke(null, 0)
            Logbook.add("shell", "autorisation Shizuku demandée")
            true
        } catch (t: Throwable) {
            Logbook.add("shell", "demande Shizuku impossible : ${t.message}")
            false
        }
    }

    private fun shizukuExec(command: String): Result? {
        val c = shizukuClass() ?: return null
        return try {
            val m = c.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return Result(false, "processus Shizuku nul", "shizuku")
            val finished = p.waitFor(20_000L, TimeUnit.MILLISECONDS)
            val out = try {
                p.inputStream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                ""
            }
            val err = try {
                p.errorStream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                ""
            }
            if (!finished) {
                p.destroy()
                Result(false, "délai dépassé", "shizuku")
            } else {
                Result(p.exitValue() == 0, (out + err).trim(), "shizuku")
            }
        } catch (t: Throwable) {
            Result(false, t.message ?: "erreur Shizuku", "shizuku")
        }
    }

    // ---------------------------------------------------------------- façade

    /** Exécute une commande shell avec le meilleur privilège disponible. */
    fun runPrivileged(command: String): Result {
        if (hasRoot()) {
            val r = exec(listOf("su", "-c", command), 20_000L)
            if (r.ok) return Result(true, r.output, "root")
        }
        if (hasShizuku()) {
            shizukuExec(command)?.let { return it }
        }
        return Result(false, "aucun privilège (ni root ni Shizuku)", "aucun")
    }

    fun canElevate(): Boolean = hasRoot() || hasShizuku()

    fun resetCache() {
        rootChecked = false
        shizukuChecked = false
    }
}
