package com.najah.camapsguardian.core

import android.content.pm.PackageManager
import com.najah.camapsguardian.R
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Exécution de commandes privilégiées : root (su) puis Shizuku.
 *
 * Avec Shizuku, la commande tourne dans le serveur Shizuku, avec les droits du shell (comme
 * une commande adb), par `Shizuku.newProcess`. Le « UserService » recommandé par Shizuku
 * plante sur Android 15 au démarrage de son processus (NullPointerException dans
 * LoadedApk.makeApplicationInner, constaté le 26/10/2026 sur le Hammer) : on ne l'utilise pas.
 * Sans root, Shizuku s'arrête à chaque redémarrage du téléphone : son état est relu à chaque
 * appel, jamais mis en cache.
 */
object Shell {

    data class Result(val ok: Boolean, val output: String, val how: String)

    private const val SHIZUKU_REQUEST = 4301

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootOk = false

    /** Dernier état Shizuku journalisé (on ne note que les changements). */
    @Volatile
    private var shizukuLogged: Boolean? = null

    private val listeners = AtomicBoolean(false)

    // ---------------------------------------------------------------- exec

    private fun exec(cmd: List<String>, timeoutMs: Long): Result {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                Result(false, t(R.string.log_timeout), cmd.firstOrNull() ?: "?")
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
        Logbook.add(
            t(R.string.cat_shell),
            if (rootOk) t(R.string.log_root_ok) else t(R.string.log_no_root)
        )
        return rootOk
    }

    // ---------------------------------------------------------------- Shizuku

    private fun installListeners() {
        if (!listeners.compareAndSet(false, true)) return
        runCatching {
            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == SHIZUKU_REQUEST) {
                    Logbook.add(
                        t(R.string.cat_shell),
                        t(
                            if (result == PackageManager.PERMISSION_GRANTED) R.string.log_shizuku_granted
                            else R.string.log_shizuku_denied
                        )
                    )
                }
            }
        }
    }

    /** Shizuku démarré, assez récent (API 11+) et autorisé pour Guardian. */
    fun hasShizuku(): Boolean {
        installListeners()
        val ok = try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        if (shizukuLogged != ok) {
            shizukuLogged = ok
            Logbook.add(
                t(R.string.cat_shell),
                if (ok) t(R.string.log_shizuku_ok) else t(R.string.log_shizuku_absent)
            )
        }
        return ok
    }

    /** Shizuku tourne-t-il (autorisé ou non) ? */
    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** À appeler depuis l'interface : affiche la demande d'autorisation de Shizuku. */
    fun requestShizukuPermission(): Boolean {
        installListeners()
        return try {
            if (!Shizuku.pingBinder()) {
                Logbook.add(t(R.string.cat_shell), t(R.string.log_shizuku_not_running))
                return false
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            Logbook.add(t(R.string.cat_shell), t(R.string.log_shizuku_requested))
            true
        } catch (ex: Throwable) {
            Logbook.add(
                t(R.string.cat_shell),
                t(R.string.log_shizuku_request_failed, ex.message ?: "")
            )
            false
        }
    }

    /** `newProcess` est privée depuis l'API 13 de Shizuku, mais toujours là : appel par réflexion. */
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    private fun shizukuExec(command: String): Result {
        return try {
            val p = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
            val out = StringBuilder()
            // Lecture en parallèle : une sortie volumineuse (dumpsys) bloquerait sinon le processus.
            val reader = thread(isDaemon = true) {
                runCatching { out.append(p.inputStream.bufferedReader().readText()) }
            }
            val err = thread(isDaemon = true) {
                runCatching { out.append(p.errorStream.bufferedReader().readText()) }
            }
            if (!p.waitForTimeout(20, TimeUnit.SECONDS)) {
                p.destroy()
                Result(false, t(R.string.log_timeout), "shizuku")
            } else {
                reader.join(3000L)
                err.join(1000L)
                Result(p.exitValue() == 0, out.toString().trim(), "shizuku")
            }
        } catch (ex: Throwable) {
            val cause = (ex as? java.lang.reflect.InvocationTargetException)?.targetException ?: ex
            Result(false, cause.message ?: t(R.string.log_shizuku_error), "shizuku")
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
            return shizukuExec(command)
        }
        return Result(false, t(R.string.log_no_privilege), "aucun")
    }

    fun canElevate(): Boolean = hasRoot() || hasShizuku()

    fun resetCache() {
        rootChecked = false
        shizukuLogged = null
    }
}
