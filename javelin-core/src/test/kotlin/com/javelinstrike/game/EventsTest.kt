package com.javelinstrike.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventsTest {
    private val dt = 1f / 60f
    private var nanos = 0L
    private var time = 0f

    private fun newGame(touch: Boolean = false): Pair<Game, Input> {
        val input = Input()
        val store = Storage.Memory().apply {
            save(Game.KEY_SEEN_HOWTO, 1)
            if (touch) save(Game.KEY_CONTROLS, ControlMode.TOUCH.ordinal)
        }
        return Game(input, object : SoundSink { override fun play(s: Sound) {} }, store, seed = 3).apply { viewH = 780f } to input
    }

    /** Runs frames; [sense] is called before each one to feed sensors (time in seconds). */
    private fun run(g: Game, seconds: Float, sense: (Float) -> Unit = {}) {
        repeat((seconds * 60).toInt()) {
            sense(time)
            g.update(dt)
            time += dt
        }
    }

    /** Spins the phone at [rate] rad/s for one frame's worth of 200 Hz gyro samples. */
    private fun spin(g: Game, rate: Float) {
        repeat(3) {
            g.onGyro(nanos, 0f, 0f, rate)
            nanos += 5_555_555L
        }
    }

    /** Arm pumping: a hard shake every 1/[hz] seconds. */
    private fun pump(g: Game, hz: Float): (Float) -> Unit = { t ->
        val cycle = (t * hz) % 1f
        g.onAccel(if (cycle < 0.3f) 14f else 0.5f, 0f, 0f)
    }

    private fun tap(g: Game, input: Input, x: Float = 180f, y: Float = 400f) {
        input.push(Input.DOWN, x, y, time)
        input.push(Input.UP, x, y, time)
    }

    private fun begin(g: Game, input: Input, kind: OlympicEventKind): OlympicEvent {
        g.startEvent(kind)
        assertEquals(Game.Screen.EVENT, g.screen)
        run(g, 0.6f)
        tap(g, input)
        run(g, dt)
        return g.event!!.also { assertEquals(OlympicEvent.Stage.ACTIVE, it.stage) }
    }

    private fun shot(g: Game, name: String) {
        val img = BufferedImage(720, 1560, BufferedImage.TYPE_INT_ARGB)
        g.render(AwtGfx(img, 2f))
        ImageIO.write(img, "png", File(File("build/screens").apply { mkdirs() }, "$name.png"))
    }

    @Test fun eventsMenuOpensEachEvent() {
        val (g, input) = newGame()
        input.push(Input.DOWN, 180f, g.titleButton(0)[1] + 10f, 0f)
        run(g, 0.4f)
        assertEquals(Game.Screen.EVENTS, g.screen)
        shot(g, "e0_events")
        val b = g.eventButton(1)
        input.push(Input.DOWN, b[0] + 10f, b[1] + 10f, 0f)
        run(g, dt)
        assertIs<DiscusEvent>(g.event)
        shot(g, "e1_discus_intro")
        // The X button leaves the event.
        val q = floatArrayOf(g.viewW - 30f, 20f)
        input.push(Input.DOWN, q[0], q[1], 0f)
        run(g, dt)
        assertEquals(Game.Screen.EVENTS, g.screen)
    }

    @Test fun discusSpinAndReleaseFacingTheField() {
        val (g, input) = newGame()
        val ev = begin(g, input, OlympicEventKind.DISCUS)
        input.push(Input.DOWN, 180f, 400f, time)
        // One and a bit turns at 10 rad/s, letting go just past facing forward.
        val turns = (2f * PI.toFloat() + 0.15f) / 10f
        run(g, turns) { spin(g, 10f) }
        shot(g, "e2_discus_spin")
        input.push(Input.UP, 180f, 400f, time)
        run(g, 1f)
        shot(g, "e3_discus_flight")
        run(g, 4f)
        assertTrue(ev.result in 4000..6600, "throw ${ev.result} cm")
    }

    @Test fun discusReleasedFacingBackwardsIsAFoul() {
        val (g, input) = newGame()
        val ev = begin(g, input, OlympicEventKind.DISCUS)
        input.push(Input.DOWN, 180f, 400f, time)
        run(g, (3f * PI.toFloat()) / 10f) { spin(g, 10f) }
        input.push(Input.UP, 180f, 400f, time)
        run(g, 6f)
        assertEquals(0, ev.result)
    }

    @Test fun sprintRaceAndFalseStart() {
        val (g, input) = newGame()
        val ev = begin(g, input, OlympicEventKind.SPRINT) as SprintEvent
        // Moving after "SET" is a false start: the countdown begins again.
        run(g, 2f)
        run(g, 0.3f, pump(g, 4.5f))
        run(g, 0.5f)
        assertEquals(0f, ev.player.z)
        run(g, 3.3f)
        shot(g, "e4_sprint_start")
        run(g, 6f, pump(g, 4.5f))
        shot(g, "e5_sprint_race")
        run(g, 22f, pump(g, 4.5f))
        assertTrue(ev.player.finished)
        assertTrue(ev.result in 19000..24000, "time ${ev.result} ms")
        assertEquals(1, ev.place())
        run(g, 3f)
        assertEquals(OlympicEvent.Stage.RESULTS, ev.stage)
        shot(g, "e6_sprint_results")
        assertEquals(ev.result, g.store.load(OlympicEventKind.SPRINT.bestKey))
    }

    @Test fun sprintWithTouchTaps() {
        val (g, input) = newGame(touch = true)
        val ev = begin(g, input, OlympicEventKind.SPRINT) as SprintEvent
        run(g, 3.4f)
        var left = true
        repeat(60 * 25) { i ->
            if (i % 14 == 0) { tap(g, input, if (left) 60f else 300f, 500f); left = !left }
            g.update(dt); time += dt
        }
        assertTrue(ev.player.finished, "should have run 192 m by tapping, got ${ev.player.z}")
    }

    @Test fun longJumpRunAndJump() {
        val (g, input) = newGame()
        val ev = begin(g, input, OlympicEventKind.LONG_JUMP) as LongJumpEvent
        val pumping = pump(g, 4.5f)
        var frames = 0
        while (ev.z < -0.6f && frames < 60 * 15) { pumping(time); g.update(dt); time += dt; frames++ }
        shot(g, "e7_longjump_run")
        tap(g, input)
        run(g, 0.4f)
        shot(g, "e8_longjump_air")
        run(g, 2f)
        assertTrue(ev.result in 600..900, "jump ${ev.result} cm")
        // Never jumping runs over the board: a foul, so the best stays the first jump.
        val first = ev.result
        run(g, 1f)
        frames = 0
        while (ev.z < 1f && frames < 60 * 15) { pumping(time); g.update(dt); time += dt; frames++ }
        run(g, 2.5f)
        assertEquals(first, ev.result)
    }
}
