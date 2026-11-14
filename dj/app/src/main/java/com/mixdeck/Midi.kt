package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.mixdeck.midi.MidiAction
import com.mixdeck.midi.MidiParser
import com.mixdeck.midi.MidiRouter

/**
 * Listens to every MIDI controller plugged into the phone (USB, with an OTG adapter if needed) and
 * hands what they send to the [router]. Nothing to choose: plug it in and it works. [plugged] is
 * told a new controller's name, on the main thread.
 */
class PhoneMidi(context: Context, private val router: MidiRouter, private val plugged: (String) -> Unit) {
    private val manager = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, Pair<String, MidiDevice>>()
    private var stopped = false

    /** The controllers connected now (main thread). */
    val names: List<String> get() = open.values.map { it.first }.sorted()

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = add(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            open.remove(device.id)?.let { runCatching { it.second.close() }; router.releaseAll() }
        }
    }

    fun start() {
        val m = manager ?: return
        runCatching {
            m.registerDeviceCallback(callback, main)
            @Suppress("DEPRECATION")
            for (info in m.devices) add(info)
        }
    }

    fun stop() {
        stopped = true
        runCatching { manager?.unregisterDeviceCallback(callback) }
        for ((_, d) in open.values) runCatching { d.close() }
        open.clear()
        router.releaseAll()
    }

    private fun add(info: MidiDeviceInfo) {
        // A device's "output" ports are where its knobs and buttons come out.
        val ports = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        if (ports.isEmpty() || info.id in open) return
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI controller"
        runCatching {
            manager?.openDevice(info, { device ->
                if (device == null) return@openDevice
                if (stopped || info.id in open) { runCatching { device.close() }; return@openDevice }
                for (p in ports) {
                    val parser = MidiParser(router::handle)
                    runCatching {
                        device.openOutputPort(p.portNumber)?.connect(object : MidiReceiver() {
                            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                runCatching { parser.feed(msg, offset, count) }
                            }
                        })
                    }
                }
                open[info.id] = name to device
                plugged(name)
            }, main)
        }
    }
}

/** ☰ → MIDI controller…: link the controller's knobs, faders and buttons to the console. */
object MidiSetup {
    private const val PHONE_REMOVE = " Hold a row to remove its link."

    fun show(a: Activity, router: MidiRouter, midi: PhoneMidi, saved: () -> Unit) {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val main = Handler(Looper.getMainLooper())
        val rows = ArrayList<String>()
        val adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, rows)
        fun fill() {
            rows.clear()
            for (act in MidiAction.ALL) rows += MidiRouter.row(act, router.map.controlFor(act)?.control)
            adapter.notifyDataSetChanged()
        }
        val devices = TextView(a).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val how = TextView(a).apply { text = MidiRouter.HOW + PHONE_REMOVE; textSize = 13f; setTextColor(0xFF9A9AA8.toInt()) }
        val status = TextView(a).apply { textSize = 15f; setTextColor(0xFF1DE9B6.toInt()); setPadding(0, dp(6), 0, dp(6)) }
        val list = ListView(a).apply { this.adapter = adapter }
        fill()

        list.setOnItemClickListener { _, _, i, _ ->
            val act = MidiAction.ALL[i]
            router.learning = act
            status.text = MidiRouter.waiting(act)
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            router.map.unbind(MidiAction.ALL[i]); saved(); fill(); true
        }
        router.onLearned = { b ->
            main.post {
                saved(); fill()
                // On to the next row, so a whole controller is quick to do.
                val next = MidiAction.ALL.getOrNull(MidiAction.ALL.indexOf(b.action) + 1)
                router.learning = next
                status.text = MidiRouter.learned(b) + (next?.let { "\n" + MidiRouter.waiting(it) } ?: "")
                if (next != null) list.smoothScrollToPosition(MidiAction.ALL.indexOf(next))
            }
        }

        val view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(devices); addView(how); addView(status)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = AlertDialog.Builder(a).setTitle(MidiRouter.TITLE).setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton(MidiRouter.CLEAR_ALL, null)
            .create()
        val tick = object : Runnable {
            override fun run() {
                val names = midi.names
                devices.text = if (names.isEmpty()) MidiRouter.NONE else MidiRouter.connected(names)
                main.postDelayed(this, 700)
            }
        }
        dialog.setOnDismissListener { main.removeCallbacks(tick); router.learning = null; router.onLearned = null }
        dialog.show()
        // Asks first, and keeps the setup open.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(a).setMessage(MidiRouter.CLEAR_ASK)
                .setPositiveButton(MidiRouter.CLEAR_ALL) { _, _ -> router.map.clear(); saved(); fill() }
                .setNegativeButton("Cancel", null).show()
        }
        tick.run()
    }
}
