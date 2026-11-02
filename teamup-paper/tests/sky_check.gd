extends SceneTree
## Day and night, weather and fireworks:
## time moves on; at night lanterns glow brighter, stars show and SLEEP brings the morning;
## every world gets its weather (rain, snow, sand, embers, sparkles, star dust) and it changes;
## a rainbow comes after rain; fireworks go up for a star, the gate, a boss and the home
## launcher, and every node they make is freed again afterwards.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/sky_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""
var nodes0 := 0
var wi := 0
const WORLDS := {"meadow": "rain", "frost": "snow", "dunes": "sand", "lava": "embers", "candy": "sparkles", "moon": "stardust"}


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_sky"
	_clean()
	Boot.place = "home"
	Boot.slot = 0
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func ready_in(st: String) -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == st


func _count(n: Node) -> int:
	var c := 1
	for ch in n.get_children():
		c += _count(ch)
	return c


func _process(_dt: float) -> bool:
	f += 1
	match step:
		0:
			if ready_in("home"):
				var dn: DayNight = g.daynight
				check(dn != null and g.weather != null and g.fireworks != null, "home should have day/night, weather and fireworks")
				# time moves on
				var t0: float = dn.tod
				dn.step(6.0)
				check(dn.tod > t0, "time of day should advance")
				# go to midnight: night, stars, glowing lanterns and windows
				dn.tod = 0.0
				dn.step(0.2)
				check(dn.is_night() and dn.night > 0.8, "midnight should be night (%.2f)" % dn.night)
				check(dn.stars.visible and dn.moon.visible, "stars and moon at night")
				var lm: StandardMaterial3D = g.level._mats[Level.LANTERN]
				var wm: StandardMaterial3D = g.level._mats[Level.WINDOW]
				check(lm.emission_energy_multiplier > 3.0, "lanterns should glow brighter at night (%.2f)" % lm.emission_energy_multiplier)
				check(wm.emission_enabled and wm.emission_energy_multiplier > 1.0, "windows should light up at night")
				var top: Color = dn.sky_mat.sky_top_color
				check(top.v < 0.25, "the night sky should be dark (%.2f)" % top.v)
				check(not g.daynight.sun.shadow_enabled, "no sun shadows at night")
				# the SLEEP button shows at night and brings the morning
				g.hud._layout()
				check(g.hud.buttons.has("sleep"), "SLEEP button at night")
				g.taps = {"sleep": true}
				g._step_home(1.0 / 60)
				g.taps = {}
				dn.step(0.2)
				check(not dn.is_night() and absf(dn.tod - DayNight.MORNING) < 0.01, "SLEEP should make it morning (tod %.2f)" % dn.tod)
				check(wm.emission_energy_multiplier < 0.3, "windows go dark in the day")
				# sunset is orange
				dn.tod = 0.74
				dn.step(0.2)
				check(dn.dusk > 0.5 and dn.sky_mat.sky_horizon_color.r > dn.sky_mat.sky_horizon_color.b, "sunset should be orange")
				# shooting stars at night
				dn.tod = 0.0
				for i in 20:
					dn.step(0.5)
				check(dn.shooting.size() > 0 or dn.shoot_t > 0, "shooting stars")
				dn.tod = 0.4
				dn.step(0.2)
				# rain -> rainbow
				var w: Weather = g.weather
				w.set_weather("rain")
				check(w.fall != null and w.splash != null and w.particle_count() > 0, "rain should fall with splashes")
				for i in 30:
					w.step(0.5)
				check(dn.cloudy > 0.5, "rain makes the sky grey")
				w.thunder_t = 0
				w.step(0.1)
				check(w.flash > 0.5, "thunder flash during rain")
				w.set_weather("clear")
				check(w.fall == null and dn.rainbow_t > 0, "a rainbow after the rain")
				dn.step(5.0)
				check(dn.rainbow.visible, "the rainbow should show")
				# away (kart race / arcade): no rain or moon showing, full daylight
				w.set_weather("rain")
				g.state = "kart"
				g._step_sky(0.1)
				check(not w.fall.visible and not dn.moon.visible and g.daynight.sun.light_energy >= 1.0, "the kart race should hide the weather and sky bits")
				g.state = "home"
				g._step_sky(0.1)
				check(w.fall.visible, "weather comes back after the race")
				w.set_weather("clear")
				dn.rainbow_t = 0.0
				# the home fireworks launcher
				g.leader().position = g.LAUNCHER_SPOT + Vector3(1, 0.05, 0)
				g.hud._layout()
				check(g.hud.buttons.has("fireworks"), "BOOM! button by the launcher")
				for i in 30:   # let warm-up fireworks finish first
					g.fireworks.step(0.2)
				nodes0 = _count(g)
				g.taps = {"fireworks": true}
				g._step_home(1.0 / 60)
				g.taps = {}
				check(g.fireworks.busy(), "the launcher should start a show")
				var most := 0
				for i in 120:
					g.fireworks.step(1.0 / 20)
					most = maxi(most, g.fireworks.live_nodes())
				check(most >= 6, "the show should have several rockets and bursts (%d)" % most)
				for i in 80:
					g.fireworks.step(0.1)
				step = 1
				wi = 0
				f = 0
		1:
			if f == 3:
				check(not g.fireworks.busy(), "the show should end")
				check(_count(g) <= nodes0 + 2, "fireworks should clean up after themselves (%d -> %d)" % [nodes0, _count(g)])
				step = 2
				_next_world()
		2:
			if ready_in("play") and g.level.world == WORLDS.keys()[wi] and g.daynight:
				var w: Weather = g.weather
				var want: String = WORLDS[g.level.world]
				check(w.where == g.level.world, "weather should know the world")
				if want in Weather.AMBIENT:
					check(w.now == want, "%s should start with %s (got %s)" % [g.level.world, want, w.now])
				else:
					w.set_weather(want)
					check(w.now == want and w.fall != null, "%s should have %s" % [g.level.world, want])
				# weather changes now and then
				var seen := {w.now: true}
				for i in 60:
					w.timer = 0
					w.step(0.1)
					seen[w.now] = true
				check(seen.size() >= 2, "%s weather should change (saw %s)" % [g.level.world, str(seen.keys())])
				# worlds only get a gentle night
				g.daynight.tod = 0.0
				g.daynight.step(0.2)
				check(g.daynight.night < 0.6, "worlds should never get too dark (%.2f)" % g.daynight.night)
				if wi == 0:
					# a star = a little fireworks show
					var o: Dictionary = g.open_play.objective("tower")
					g.open_play._make_star("tower")
					g.open_play._collect("tower")
					check(g.fireworks.busy(), "a star should start fireworks")
				wi += 1
				if wi >= WORLDS.size():
					step = 3
				else:
					_next_world()
		3:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 90:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false


func _next_world() -> void:
	Boot.level = WORLDS.keys()[wi]
	g.go("level", 0)
