package com.prosketch.app

import android.content.Context
import java.util.Locale

/**
 * UI language. Source strings are Korean; other languages come from assets/i18n/<lang>.tsv
 * ("korean<TAB>translation", {0}… placeholders). Chosen once per process because enum labels
 * are read at class initialisation, so changing it restarts the app.
 */
object L {
    @Volatile var english = false
        private set
    private var map: Map<String, String> = emptyMap()

    /** [mode]: 0 = follow the system, 1 = Korean, 2 = English. */
    fun init(ctx: Context, mode: Int) {
        english = when (mode) { 1 -> false; 2 -> true; else -> Locale.getDefault().language != "ko" }
        map = if (!english) emptyMap() else load(ctx)
    }

    private fun load(ctx: Context): Map<String, String> = runCatching {
        val out = HashMap<String, String>()
        ctx.assets.open("i18n/en.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                val i = line.indexOf('\t')
                if (i > 0) out[unescape(line.substring(0, i))] = unescape(line.substring(i + 1))
            }
        }
        out as Map<String, String>
    }.getOrDefault(emptyMap())

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")

    fun t(ko: String): String = if (english) map[ko] ?: ko else ko

    fun tf(ko: String, vararg args: Any?): String {
        var s = t(ko)
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }
}
