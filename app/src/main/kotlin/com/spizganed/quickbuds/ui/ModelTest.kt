package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import org.json.JSONObject

/**
 * Test my earbuds ([ModelTestActivity]): the owner marks each feature the app shows, and one report with the log goes
 * to the Worker as category "Test". A model's `confirmed` list in `models.json` names the features an owner
 * confirmed; a model whose list covers every feature shown gets no prompt. The desktop app does the same
 * (desktop/src/modeltest.rs), with the same keys and the same report text.
 */
object ModelTest {

    /** One feature: its key (also in `confirmed`), its label, its name in the report, and whether the buds have it. */
    class Feature(val key: String, val label: Int, val english: String, val shown: (Context) -> Boolean)

    /** The home rows, noise control, and the three Earbud settings rows most models have. */
    val FEATURES = listOf(
        Feature("battery", R.string.problem_cat_battery, "Battery") { true },
        Feature("anc", R.string.anc_section, "Noise control") {
            Capabilities.supports(it, OpoProtocol.CMD_SET_ANC) && !AncModes.of(it).isEmpty
        },
        Feature("wind", R.string.row_wind_noise_title, "Wind noise reduction") { MainActivity.rowSupported(it, "wind") },
        Feature("game", R.string.row_game_title, "Low latency") { MainActivity.rowSupported(it, "game") },
        Feature("hires", R.string.row_hires_title, "High-quality audio") { MainActivity.rowSupported(it, "hires") },
        Feature("spatial", R.string.row_spatial_title, "3D audio") { MainActivity.rowSupported(it, "spatial") },
        Feature("golden", R.string.row_golden_title, "Hearing profile") { MainActivity.rowSupported(it, "golden") },
        Feature("eq", R.string.row_eq_title, "Equalizer") { MainActivity.rowSupported(it, "eq") },
        Feature("dual", R.string.row_dual_title, "Dual connection") { MainActivity.rowSupported(it, "dual") },
        Feature("gestures", R.string.row_gesture_title, "Gestures") {
            Capabilities.supports(it, OpoProtocol.CMD_SET_KEY_FUNCTION) && !GestureModel.of(it).isEmpty
        },
        Feature("wear", R.string.row_wear_title, "Wear detection") {
            Capabilities.hasFeature(it, OpoProtocol.FEATURE_AUTO_PLAY_PAUSE)
        },
        Feature("find", R.string.row_find_title, "Find my earbuds") { Capabilities.supports(it, OpoProtocol.CMD_FIND_BUDS) },
    )

    /** The report's category; the Worker requires the log with it (report-worker/src/index.js). */
    const val CATEGORY = "Test"

    /** A feature's mark. */
    const val WORKS = "works"
    const val BROKEN = "broken"
    const val SKIP = "skip"

    /** The automatic check: (key, label, name in the report). */
    val CHECKS = listOf(
        Triple("battery", R.string.test_check_battery, "battery level"),
        Triple("features", R.string.test_check_features, "feature list"),
        Triple("states", R.string.test_check_states, "feature states"),
    )

    /** The test in progress, as JSON: model id, checks, marks and notes. Kept while the person tries features. */
    private const val KEY_STATE = "modelTestState"
    /** Model ids the launch prompt has asked about, comma-separated. It asks once per model. */
    private const val KEY_ASKED = "modelTestAsked"

    fun shown(c: Context): List<Feature> = FEATURES.filter { it.shown(c) }

    /** The features an owner confirmed on [model] (`models.json` `confirmed`). */
    fun confirmed(model: ModelCatalog.Model): Set<String> {
        val list = model.json.optJSONArray("confirmed") ?: return emptySet()
        return (0 until list.length()).map { list.getString(it) }.toSet()
    }

    /** The launch prompt asks: a known model, not asked before, with a feature shown that nobody confirmed. */
    fun shouldAsk(c: Context): Boolean {
        val model = ModelCatalog.current(c) ?: return false
        if (model.id in asked(c)) return false
        val done = confirmed(model)
        return shown(c).any { it.key !in done }
    }

    fun markAsked(c: Context) {
        val model = ModelCatalog.current(c) ?: return
        prefs(c).edit().putString(KEY_ASKED, (asked(c) + model.id).joinToString(",")).apply()
    }

    private fun asked(c: Context) = prefs(c).getString(KEY_ASKED, null)?.split(',').orEmpty().toSet()

    /** The test in progress for the current model; null before Start, or when the model changed. */
    class State(val model: String, val checks: Map<String, Boolean>, val marks: MutableMap<String, String>,
                val notes: MutableMap<String, String>)

    fun load(c: Context): State? {
        val model = ModelCatalog.current(c) ?: return null
        val j = runCatching { JSONObject(prefs(c).getString(KEY_STATE, null) ?: return null) }.getOrNull() ?: return null
        if (j.optString("model") != model.id) return null
        fun map(name: String) = j.optJSONObject(name)?.let { o -> o.keys().asSequence().associateWith { o.get(it) } }.orEmpty()
        return State(model.id, map("checks").mapValues { it.value == true },
            map("marks").mapValues { it.value.toString() }.toMutableMap(),
            map("notes").mapValues { it.value.toString() }.toMutableMap())
    }

    fun save(c: Context, s: State) {
        val j = JSONObject().put("model", s.model).put("checks", JSONObject(s.checks))
            .put("marks", JSONObject(s.marks.toMap())).put("notes", JSONObject(s.notes.toMap()))
        prefs(c).edit().putString(KEY_STATE, j.toString()).apply()
    }

    fun clear(c: Context) = prefs(c).edit().remove(KEY_STATE).apply()

    /** Every feature shown has a mark, and every "does not work" has a note. */
    fun complete(s: State, features: List<Feature>) = features.all { f ->
        val mark = s.marks[f.key] ?: return@all false
        mark != BROKEN || !s.notes[f.key].isNullOrBlank()
    }

    /** The report's description, in English for the developer: the check, then one line per feature. */
    fun description(s: State, features: List<Feature>, platform: String): String {
        val check = CHECKS.joinToString(", ") { (key, _, name) ->
            "$name ${if (s.checks[key] == true) "answered" else "no answer"}"
        }
        val lines = features.map { f ->
            when (s.marks[f.key]) {
                WORKS -> "${f.english}: works"
                BROKEN -> "${f.english}: does not work: ${s.notes[f.key].orEmpty().trim()}"
                else -> "${f.english}: skipped"
            }
        }
        return (listOf("Model test ($platform)", "Automatic check: $check") + lines).joinToString("\n")
    }

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
