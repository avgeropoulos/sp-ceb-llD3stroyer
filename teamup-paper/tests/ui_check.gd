extends SceneTree
## The touch buttons never overlap each other (or the island buttons / the thumb stick) and always fit on the
## screen, in every state (play, mega ready, rocket pad, map, home walk / night / TV / flying, build, decor),
## on a 1280x720 / 1920x1080 phone, a 2560x1600 tablet and a 2340x1080 long phone. Also: a button tap still
## makes you jump with the press animation running, and a hit-stop never eats a jump.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/ui_check.gd -- [toy]

var g
var f := 0
var step := 0
var wait := 0
var failed := ""
var checked := 0
# window size -> the HUD's size (stretch mode canvas_items, aspect expand, base 1280x720)
const SCREENS := [Vector2(1280, 720), Vector2(1920, 1080), Vector2(2560, 1600), Vector2(2340, 1080)]


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_ui"
	_clean()
	Saves.write(1, {"v": 2, "in_level": false, "cleared_w": {"meadow": 1, "frost": 1}, "cleared": 2, "coins": 10,
		"mon": "fire", "mon_xp": {"fire": 2000}, "scenes_seen": ["intro"]})
	Boot.place = "level"
	Boot.level = "meadow"
	Boot.slot = 1
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


func hud_size(win: Vector2) -> Vector2:
	var k := minf(win.x / 1280.0, win.y / 720.0)
	return win / k


## Lays the buttons out for every screen size and checks them.
func check_layout(what: String) -> void:
	var hud: Hud = g.hud
	var keep := hud.size
	for win in SCREENS:
		hud.size = hud_size(win)
		hud._layout()
		var s := hud.size
		var names: Array = hud.buttons.keys()
		for i in names.size():
			var a: Array = hud.buttons[names[i]]
			var c: Vector2 = a[0]
			var r: float = a[1]
			check(c.x - r >= 0 and c.y - r >= 0 and c.x + r <= s.x and c.y + r <= s.y,
				"%s %s: %s sticks out of the screen" % [what, win, names[i]])
			for j in range(i + 1, names.size()):
				var b: Array = hud.buttons[names[j]]
				check(c.distance_to(b[0]) >= r + b[1] + 4.0,
					"%s %s: %s and %s overlap" % [what, win, names[i], names[j]])
			# the thumb stick's resting spot (bottom left)
			var sr := 70.0 * clampf(s.y / 720.0, 0.75, 1.6)
			var sc := Vector2(sr * 2.1, s.y - sr * 2.1)
			check(c.distance_to(sc) >= r + sr * 1.22, "%s %s: %s covers the thumb stick" % [what, win, names[i]])
			if g.state == "map" and g.sky:
				var n: int = g.sky.order.size()
				for k in n:
					var ir: Rect2 = hud.island_button_rect(k, n)
					var near := Vector2(clampf(c.x, ir.position.x, ir.end.x), clampf(c.y, ir.position.y, ir.end.y))
					check(c.distance_to(near) >= r, "%s %s: %s covers an island button" % [what, win, names[i]])
					check(ir.position.x >= 0 and ir.end.x <= s.x and ir.end.y <= s.y, "%s %s: island button off screen" % [what, win])
		checked += 1
	hud.size = keep
	hud._layout()


func _process(_dt: float) -> bool:
	f += 1
	if wait > 0:
		wait -= 1
		return false
	match step:
		0:
			if ready_in("play") and g.play_t > 0.3:
				g.invincible = true
				check_layout("play")
				# MEGA ready + standing on the rocket pad: every play button at once
				var m = g.heroes.mon
				m.set_form(m.line, g.mega.final_stage(m.line))
				g.super_charge = 1.0
				g.mega.left = 0
				g.mega.t = -1.0
				if g.world_rocket:
					g.world_rocket.near = true
				check(g.mega.ready(), "MEGA should be ready for the layout check")
				g.identity.meter = 1.0   # TEAM UP! ready too
				check_layout("play+mega+rocket+teamup")
				check(g.hud.buttons.has("mega") and g.hud.buttons.has("blastoff") and g.hud.buttons.has("teamup"), "mega, blastoff and TEAM UP! buttons should show")
				g.identity.meter = 0.0
				if g.world_rocket:
					g.world_rocket.near = false
				g.super_charge = 0.0
				step = 1
		1:
			# a tap on JUMP makes the hero jump even while the press animation plays
			var l = g.leader()
			if l.on_floor:
				g.hud.pressed_now["jump"] = true
				g.hud.press_t["jump"] = 0.0
				step = 2
				wait = 0
		2:
			var l = g.leader()
			check(l.vel.y > 1.0 or not l.on_floor, "tapping JUMP should jump (press animation on)")
			step = 3
			wait = 60
		3:
			# a hit-stop is a few frames at most, and a jump during it still counts
			var l = g.leader()
			if l.on_floor:
				g.hit_stop(0.07)
				g.hud.pressed_now["jump"] = true
				step = 4
		4:
			check(g.hitstop > -1.0, "hitstop")
			step = 5
			wait = 8
		5:
			var l = g.leader()
			check(g.hitstop <= 0.0, "the hit-stop should be over after a few frames (%.3f)" % g.hitstop)
			check(l.vel.y > 0.5 or not l.on_floor, "a jump pressed during a hit-stop should still happen")
			g.save_game(false)
			g.go("map", 1)
			step = 6
		6:
			if ready_in("map"):
				wait = 5
				step = 7
		7:
			check_layout("map")
			g.go("home", 1)
			step = 8
		8:
			if ready_in("home") and g.play_t > 0.3:
				check_layout("home")
				g.identity.near = "board"   # by the QUEST BOARD (TEAM PLAZA)
				check_layout("home+plaza")
				check(g.hud.buttons.has("plaza"), "the TEAM PLAZA button should show")
				g.identity.near = ""
				g.flying = true
				if g.daynight:
					g.daynight.night = 1.0
				g.leader().position = Vector3(g.MOVIE_SPOT.x, g.leader().position.y, g.MOVIE_SPOT.z)
				check_layout("home+night+tv+fly")
				g.home.mode = "build"
				check_layout("build+fly")
				g.home.mode = "decor"
				check_layout("decor+fly")
				g.home.mode = ""
				g.flying = false
				step = 9
		9:
			check(checked >= 7 * SCREENS.size(), "only %d layouts checked" % checked)
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED (%d layouts)" % checked)
			quit(1 if failed != "" else 0)
			return true
	if f > 5000:
		check(false, "timed out")
		step = 9
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	return false
