extends SceneTree
## Regression checks for bugs found by fuzzing (see README "Known fixes"). Deterministic (fixed seed).
## 1. Pause in Rainbow Road: RESUME keeps racing, QUIT RACE goes home (it used to quit at once, no prize).
## 2. Random buttons / stick / menus in a world for a while: never stuck, node count stays bounded.
## 3. SAVE & QUIT inside a painting keeps the WORLD save (stars + where you were), not the painting.
## 4. A damaged save file falls back to the save before (.prev) instead of looking empty.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/fuzz_check.gd -- [paper|toy]
## (CI also checks the log has no SCRIPT ERROR.)

const FUZZ_FRAMES := 60 * 60
var g
var f := 0
var step := 0
var wait := 0
var failed := ""
var rng := RandomNumberGenerator.new()
var stick := Vector2.ZERO
var held := {}
var fuzz_f := 0
var nodes0 := 0
var max_nodes := 0
var state_t := 0
var last_state := ""
var enter_pos := Vector3.ZERO
var stars_before: Array = []


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	rng.seed = 6060
	Saves.prefix = "user://fuzz_check"
	_clear(1)
	_clear(2)
	Saves.write(1, {"v": 2, "in_level": false, "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 50, "mon": "spark",
		"ores": {"stone": 20, "iron": 5, "gold": 5, "diamond": 5}, "stars_w": {"meadow": ["tower", "sky"]}, "scenes_seen": ["intro"]})
	Boot.slot = 1
	Boot.place = "home"
	print("FUZZ CHECK look ", "toy" if Look.toy else "paper")
	root.add_child(load("res://main.tscn").instantiate())


func _clear(n: int) -> void:
	for e in ["", ".bak", ".tmp", ".prev"]:
		if FileAccess.file_exists(Saves.path(n) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(n) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = why
		print("FAILED at step %d: %s" % [step, why])


func finish() -> bool:
	for k in held.keys():
		Input.action_release(k)
	print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
	quit()
	return true


func _press(act: String) -> void:
	Input.action_press(act)
	held[act] = 3


func _process(_dt: float) -> bool:
	f += 1
	for k in held.keys():
		held[k] -= 1
		if held[k] <= 0:
			Input.action_release(k)
			held.erase(k)
	if wait > 0:
		wait -= 1
		return false
	g = Boot.current
	if failed != "":
		return finish()
	if f > 60 * 200:
		check(false, "timed out (step %d, state %s)" % [step, g.state if is_instance_valid(g) else "?"])
		return finish()
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	g.invincible = true
	match step:
		0:   # --- 1. Rainbow Road pause
			if g.state == "home" and f > 30:
				g.start_kart("home")
				check(g.state == "kart" and g.kart != null, "Rainbow Road didn't start")
				step = 1
				wait = 90
		1:
			g._pause("kart")
			check(g.state == "paused" and g.pause_count() == 2, "pause in the race should show RESUME / QUIT RACE")
			check(g.pause_label(1) == "QUIT RACE", "second pause button should be QUIT RACE")
			g.pause_choice(0)
			check(g.state == "kart" and g.kart != null, "RESUME should keep racing")
			step = 2
			wait = 30
		2:
			var c0: int = g.coins
			g._pause("kart")
			g.pause_choice(1)
			check(g.kart == null and g.state == "home", "QUIT RACE should go home (state %s)" % g.state)
			check(g.coins == c0, "quitting a race shouldn't give a prize")
			g.land_on("meadow")
			step = 3
		3:   # --- 2. random play in a world
			if g.state == "play" and g.level and g.level.open and not g.level.bonus:
				nodes0 = int(Performance.get_monitor(Performance.OBJECT_NODE_COUNT))
				step = 4
		4:
			_fuzz()
			fuzz_f += 1
			if fuzz_f >= FUZZ_FRAMES:
				for k in held.keys():
					Input.action_release(k)
				held.clear()
				g.hud.stick = Vector2.ZERO
				var n := int(Performance.get_monitor(Performance.OBJECT_NODE_COUNT))
				print("FUZZ nodes start %d max %d end %d" % [nodes0, max_nodes, n])
				check(max_nodes < nodes0 * 2 + 800, "node count grew too much (%d -> %d)" % [nodes0, max_nodes])
				step = 5
		5:   # get back to plain play in the world (and the world, not a painting)
			if g.state == "paused":
				g.pause_choice(0)
			elif g.state == "starselect" and g.open_play:
				g.open_play.choose(0)
			elif g.state in ["play", "home", "map"] and not (g.level and g.level.open and not g.level.bonus and not g.level.is_home and g.state == "play"):
				if g.state == "home" or g.state == "map" or g.level.bonus:
					g.land_on("meadow") if g.state == "home" else g.go("level", g.slot)
					Boot.level = "meadow"
					wait = 60
			elif g.state == "play":
				step = 6
		6:   # --- 3. save & quit inside a painting
			enter_pos = g.leader().position
			g.safe_pos = enter_pos
			stars_before = g.open_play.got.keys()
			g.enter_painting(1)
			step = 7
		7:
			if g.state == "play" and g.level.bonus:
				g._pause("play")
				g.pause_choice(2)   # SAVE & QUIT
				step = 8
		8:
			if g.state == "title":
				var d := Saves.read(1)
				check(d.get("level", "") == "meadow", "save inside a painting should keep the world (got %s)" % d.get("level"))
				g.slot_pick(1)
				step = 9
		9:
			if g.state == "play" and g.open_play and not g.level.bonus:
				var p: Vector3 = g.leader().position
				check(Vector2(p.x, p.z).distance_to(Vector2(enter_pos.x, enter_pos.z)) < 3.0,
					"continued at %s, not where the painting was entered (%s)" % [p, enter_pos])
				for s in stars_before:
					check(g.open_play.got.has(s), "star %s lost after saving inside a painting" % s)
				step = 10
		10:  # --- 4. damaged save -> previous copy
			Saves.write(2, {"coins": 10})
			Saves.write(2, {"coins": 20})
			var fa := FileAccess.open(Saves.path(2), FileAccess.WRITE)
			fa.store_string("{\"coins\": 30, \"oo")
			fa.close()
			check(int(Saves.read(2).get("coins", -1)) == 10, "a damaged save should fall back to the save before")
			Saves.write(2, {"coins": 40})
			check(int(Saves.read(2).get("coins", -1)) == 40, "saving over a damaged file should work")
			_clear(2)
			return finish()
	return false


## Random stick, buttons, HUD taps and menu picks (never QUIT/SAVE & QUIT: the run must stay in the world).
func _fuzz() -> void:
	max_nodes = maxi(max_nodes, int(Performance.get_monitor(Performance.OBJECT_NODE_COUNT)))
	if g.state != last_state:
		last_state = g.state
		state_t = 0
	state_t += 1
	check(state_t < 60 * 20 or g.state in ["play", "home", "map", "paused"], "stuck in state %s" % g.state)
	if fuzz_f % 30 == 0:
		stick = Vector2(rng.randf_range(-1, 1), rng.randf_range(-1, 1)) if rng.randf() < 0.85 else Vector2.ZERO
	g.hud.stick = stick
	if rng.randf() < 0.02:
		g.hud.look_delta += Vector2(rng.randf_range(-80, 80), rng.randf_range(-20, 20))
	if rng.randf() < 0.08:
		_press(["jump", "jump", "jump", "action", "action", "swap", "super", "sprint", "fly", "mega"][rng.randi() % 10])
	if rng.randf() < 0.004:
		_press("pause")
	if rng.randf() < 0.04 and g.hud.buttons.size() > 0:
		var keys: Array = g.hud.buttons.keys()
		var k: String = keys[rng.randi() % keys.size()]
		if k != "pause":
			g.hud.pressed_now[k] = true
	if rng.randf() < 0.01:
		g.hud.world_taps.append(Vector2(rng.randf_range(0, 1280), rng.randf_range(0, 720)))
	match g.state:
		"paused":
			if rng.randf() < 0.1:
				g.pause_choice(0 if rng.randf() < 0.8 else 1)   # RESUME or SAVE
		"starselect":
			if g.open_play and rng.randf() < 0.05:
				g.open_play.choose(0)
