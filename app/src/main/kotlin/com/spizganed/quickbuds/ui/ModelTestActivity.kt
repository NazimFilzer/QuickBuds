package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.ModelCatalog

/**
 * Settings › Test my earbuds, and the launch prompt's action ([ModelTest]). Start clears the packet log and reconnects,
 * so the log starts with a clean handshake. The automatic check notes which reads the buds answered. The person then
 * tries each feature in the app, comes back, and marks it. Send posts one "Test" report, always with the log.
 * The marks survive leaving the screen.
 */
class ModelTestActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var status: TextView
    private lateinit var start: TextView
    private lateinit var results: LinearLayout
    private lateinit var checkCard: LinearLayout
    private lateinit var featureCard: LinearLayout
    private lateinit var sendNote: TextView
    private lateinit var send: TextView
    private var features = emptyList<ModelTest.Feature>()
    private var state: ModelTest.State? = null
    private var sending = false

    /** Set while Start runs: the reads the buds answered since the reconnect. */
    private var running = false
    private var answered = mutableSetOf<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@ModelTestActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        p = ThemeRes.palette(this)
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.test_title))
        root.addView(note(R.string.test_intro))

        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label)
            textSize = 16f
            typeface = ThemeRes.medium(context)
            gravity = Gravity.CENTER
            setTextColor(if (primary) p.onAccent else p.accent)
            background = ThemeRes.ripple(this@ModelTestActivity,
                if (primary) ThemeRes.pill(this@ModelTestActivity, p.accent, 26f) else ThemeRes.card(this@ModelTestActivity, 26f))
            layoutParams = LinearLayout.LayoutParams(0, dp(52f), 1f)
            setOnClickListener { onClick() }
        }
        start = button(R.string.test_start, false) { begin() }
        root.addView(LinearLayout(this).apply { setPadding(0, dp(12f), 0, 0); addView(start) })
        status = note(0).apply { visibility = View.GONE }
        root.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(SettingRowFactory.sectionLabel(this, R.string.test_section_check))
        checkCard = SettingRowFactory.card(this)
        results.addView(checkCard)
        results.addView(SettingRowFactory.sectionLabel(this, R.string.test_section_features))
        featureCard = SettingRowFactory.card(this)
        results.addView(featureCard)
        send = button(R.string.problem_send, true) { submit() }
        results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20f), 0, 0)
            addView(button(R.string.problem_preview, false) { preview() })
            addView(send.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(12f) })
        })
        sendNote = note(R.string.test_mark_all)
        results.addView(sendNote)
        results.addView(View(this), LinearLayout.LayoutParams(1, dp(24f)))
        root.addView(results)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
        bound = bindService(Intent(this, BudsService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        // The features shown can change while the person tries them (a model picked by hand, a new read).
        if (!running) render()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        manager?.removeListener(this)
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun note(text: Int) = TextView(this).apply {
        if (text != 0) setText(text)
        setTextColor(p.textSecondary)
        textSize = 13f
        setPadding(ThemeRes.dp(context, 4f), ThemeRes.dp(context, 8f), 0, 0)
    }

    /** Clears the log, drops the link and connects again; [finishCheck] runs once the buds have answered. */
    private fun begin() {
        val m = manager ?: return
        val device = BudsDevice.find(this) ?: return showStatus(R.string.no_buds_paired)
        running = true
        answered.clear()
        start.isEnabled = false
        start.alpha = 0.4f
        results.visibility = View.GONE
        showStatus(R.string.test_reconnecting)
        PacketLogger.clear()
        PacketLogger.log("MODEL TEST: start, ${ModelCatalog.current(this)?.name ?: "unknown model"}")
        m.disconnect()
        main.postDelayed({ m.connect(device) }, RECONNECT_GAP_MS)
        main.postDelayed({ if (running && "connected" !in answered) fail() }, CONNECT_TIMEOUT_MS)
    }

    override fun onConnected(connected: Boolean) {
        if (!running || !connected || "connected" in answered) return
        answered += "connected"
        main.postDelayed({ finishCheck() }, HANDSHAKE_MS)
    }

    override fun onBattery(left: Int?, case: Int?, right: Int?, chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean) {
        if (running && (left != null || right != null || case != null)) answered += "battery"
    }

    override fun onCapabilities() {
        if (running) answered += "features"
    }

    override fun onFeatureStates(states: Map<Int, Int>) {
        if (running && states.isNotEmpty()) answered += "states"
    }

    private fun fail() {
        running = false
        start.isEnabled = true
        start.alpha = 1f
        showStatus(R.string.test_failed)
    }

    private fun finishCheck() {
        if (!running) return
        running = false
        val model = ModelCatalog.current(this) ?: return fail()
        val checks = ModelTest.CHECKS.associate { it.first to (it.first in answered) }
        PacketLogger.log("MODEL TEST: check ${checks.entries.joinToString { "${it.key}=${it.value}" }}")
        ModelTest.save(this, ModelTest.State(model.id, checks, mutableMapOf(), mutableMapOf()))
        start.isEnabled = true
        start.alpha = 1f
        status.visibility = View.GONE
        render()
    }

    private fun showStatus(text: Int) {
        status.setText(text)
        status.visibility = View.VISIBLE
    }

    /** The check and one row per feature, from the saved state; hidden before Start. */
    private fun render() {
        state = ModelTest.load(this)
        val s = state
        start.setText(if (s == null) R.string.test_start else R.string.test_restart)
        results.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        features = ModelTest.shown(this)

        checkCard.removeAllViews()
        for ((key, label, _) in ModelTest.CHECKS) {
            val row = SettingRowFactory.build(this, 0, label, 0, null)
            SettingRowFactory.subtitle(this, row).setText(if (s.checks[key] == true) R.string.test_answered else R.string.test_no_answer)
            SettingRowFactory.addRow(checkCard, row)
        }

        featureCard.removeAllViews()
        for (f in features) {
            val row = SettingRowFactory.build(this, 0, f.label, 0, SettingRowFactory.buildChevron(this)) { mark(f) }
            SettingRowFactory.subtitle(this, row).text = when (s.marks[f.key]) {
                ModelTest.WORKS -> getString(R.string.test_works)
                ModelTest.BROKEN -> getString(R.string.test_broken) + ": " + s.notes[f.key].orEmpty()
                ModelTest.SKIP -> getString(R.string.test_skipped)
                else -> getString(R.string.test_not_marked)
            }
            SettingRowFactory.addRow(featureCard, row)
        }
        paintSend()
    }

    /** Works / Does not work / Skip; Does not work asks what happened. */
    private fun mark(f: ModelTest.Feature) {
        val s = state ?: return
        val current = s.marks[f.key]
        fun set(mark: String) {
            s.marks[f.key] = mark
            ModelTest.save(this, s)
            render()
        }
        BottomSheetDialog(this)
            .title(getString(f.label))
            .items(listOf(
                BottomSheetDialog.Item(getString(R.string.test_works), current == ModelTest.WORKS) { set(ModelTest.WORKS) },
                BottomSheetDialog.Item(getString(R.string.test_broken), current == ModelTest.BROKEN) { askNote(f) },
                BottomSheetDialog.Item(getString(R.string.test_skip), current == ModelTest.SKIP) { set(ModelTest.SKIP) },
            ))
            .show()
    }

    private fun askNote(f: ModelTest.Feature) {
        val s = state ?: return
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(f.label))
            .message(getString(R.string.test_broken_hint))
            .input(s.notes[f.key].orEmpty(), 300)
            .confirm(getString(R.string.test_broken)) {
                val text = sheet.inputValue()
                if (text.isEmpty()) return@confirm
                sheet.close()
                s.marks[f.key] = ModelTest.BROKEN
                s.notes[f.key] = text
                ModelTest.save(this, s)
                render()
            }
            .cancel(getString(R.string.dialog_cancel)) { sheet.close() }
        // The sheet opens from another sheet's row: let that one close first.
        main.post { sheet.show() }
    }

    /** Send needs every feature marked and a log with frames from the buds. */
    private fun ready(): Boolean {
        val s = state ?: return false
        return !sending && ModelTest.complete(s, features) &&
            PacketLogger.getFileContent().lines().any { it.contains("  RX") }
    }

    private fun paintSend() {
        send.isEnabled = ready()
        send.alpha = if (send.isEnabled) 1f else 0.4f
        sendNote.visibility = if (send.isEnabled || sending) View.GONE else View.VISIBLE
    }

    private fun report(): ProblemReport.Report? {
        val s = state ?: return null
        val model = ModelCatalog.current(this) ?: return null
        return ProblemReport.Report(model.name, listOf(ModelTest.CATEGORY),
            ModelTest.description(s, features, "Android"), ProblemReport.log(this), false)
    }

    private fun preview() {
        val r = report() ?: return
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.problem_preview)).message(ProblemReport.preview(r))
        if (ready()) sheet.confirm(getString(R.string.problem_send)) { sheet.close(); submit() }
        sheet.cancel(getString(R.string.dialog_close)) { sheet.close() }
        sheet.show()
    }

    private fun submit() {
        if (!ready()) return
        val r = report() ?: return
        sending = true
        send.setText(R.string.problem_sending)
        paintSend()
        Thread {
            val (ok, refused) = ProblemReport.send(this, r)
            main.post {
                sending = false
                send.setText(R.string.problem_send)
                paintSend()
                val text = if (ok) getString(R.string.problem_sent) else refused ?: getString(R.string.problem_failed)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                if (ok) {
                    ModelTest.clear(this)
                    finish()
                }
            }
        }.start()
    }

    companion object {
        /** The buds need a moment between the drop and the new connect. */
        private const val RECONNECT_GAP_MS = 1500L
        /** No connection by then: the test says so and Start works again. */
        private const val CONNECT_TIMEOUT_MS = 25_000L
        /** After the connect, the handshake's reads (battery, `0x8100`, `0x810D`) arrive within this time. */
        private const val HANDSHAKE_MS = 6000L
    }
}
