package com.najah.camapsguardian.core

import android.content.Context

/**
 * Contexte applicatif, posé une fois par [com.najah.camapsguardian.GuardianApp].
 * Il permet de traduire depuis des endroits qui n'ont pas de `Context` sous la main
 * (objets `Shell`, tâches de fond), sans faire passer un contexte partout.
 */
object App {

    @Volatile
    lateinit var ctx: Context
        private set

    fun install(context: Context) {
        ctx = context.applicationContext
    }

    fun isReady(): Boolean = this::ctx.isInitialized
}

/**
 * Traduction. L'application est bilingue français/anglais : le texte vient de
 * `res/values/strings.xml` (anglais, langue par défaut) et `res/values-fr/strings.xml`
 * (français). Android choisit selon la langue du téléphone.
 *
 * Règle d'écriture des ressources : les emplacements sont toujours `%1$s`, `%2$s`, etc.
 * (jamais `%d`), et on passe des chaînes ou des nombres — les deux sont acceptés par `%s`,
 * ce qui évite toute exception de format au mauvais moment.
 */
fun Context.tr(id: Int, vararg args: Any): String =
    if (args.isEmpty()) getString(id) else getString(id, *args)

/** Traduction quand on n'a pas de `Context` : utilise le contexte applicatif. */
fun t(id: Int, vararg args: Any): String =
    if (App.isReady()) App.ctx.tr(id, *args) else ""
