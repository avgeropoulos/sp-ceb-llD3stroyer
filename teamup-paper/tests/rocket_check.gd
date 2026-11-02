extends SceneTree
## Leaving a world by rocket: the BLAST OFF! button on the launch pad near the start, and "ROCKET: LEAVE" in the
## pause menu (the rocket swoops down to you), both go back to the sky map over that world, and the
## stars found are kept. The sky map / home rockets are big.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/rocket_check.gd -- [toy]

var g
var f := 0
var step := 0
var wait := 0
var failed := ""


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_rocket"
	_clean()
	Saves.write(1, {"v": 2, "in_level": false, "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 3,
		"mon": "spark", "stars_w": {"meadow": ["tower"]}})
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


func _process(_dt: float) -> bool:
	f += 1
	if wait > 0:
		wait -= 1
		return false
	match step:
		0:
			if ready_in("play") and g.slot == 1:
				var wr: WorldRocket = g.world_rocket
				check(wr != null, "every open world should have a rocket")
				if wr == null:
					step = 9
					return false
				var s: Vector3 = g.level.start_pos
				var d := Vector2(wr.pad.x - s.x, wr.pad.z - s.z).length()
				check(d > 3.0 and d < 9.0, "the launch pad should be near the start (%.1f blocks)" % d)
				check(not g.level.solid(floori(wr.pad.x), floori(wr.pad.y + 1.5), floori(wr.pad.z)), "the pad should be clear")
				check(g.pause_count() == 6 and g.pause_label(5) == "ROCKET: LEAVE", "the pause menu should have ROCKET: LEAVE")
				check(g.open_play.got.has("tower"), "the saved star should be found")
				# step onto the pad
				g.play_t = 5.0
				var l = g.leader()
				l.position = wr.pad + Vector3(-2.2, 0.05, 0)   # just off the pad, then walk on
				g.cam_yaw = 0
				step = 1
		1:
			if ready_in("play"):
				var wr: WorldRocket = g.world_rocket
				if not wr.busy():
					g.leader().position.x += 0.06   # walking east onto the pad (small steps)
					if wr.near:   # the BLAST OFF! button shows on the pad: tap it
						check(g.hud.buttons.has("blastoff"), "the HUD should show BLAST OFF! on the pad")
						g.hud.pressed_now["blastoff"] = true
				if f > 900:
					check(false, "walking onto the pad never took off (at %s, pad %s)" % [g.leader().position, wr.pad])
					step = 9
			elif ready_in("map"):
				check(Boot.from_level == "meadow", "the sky map should start over the world we left")
				check(g.sky.rocket.scale.y > 0.0, "a rocket on the sky map")
				var d := Saves.read(1)
				check("tower" in d.get("stars_w", {}).get("meadow", []), "stars should be kept after leaving")
				# land back in the world, then leave from the pause menu
				g.land_on("meadow")
				step = 2
		2:
			if ready_in("play") and g.level.world == "meadow" and g.world_rocket:
				g.leader().position = g.level.summit_spot + Vector3(2, 0.1, 2) if g.level.summit_spot != Vector3.ZERO else g.level.start_pos + Vector3(10, 0, 10)
				g._pause("play")
				g.pause_choice(5)
				check(g.state == "play" and g.world_rocket.busy(), "ROCKET: LEAVE should call the rocket down")
				step = 3
		3:
			if ready_in("map"):
				check(Boot.from_level == "meadow", "the pause-menu rocket should also go to the sky map")
				step = 9
				wait = 3
			elif f > 3000:
				check(false, "the pause-menu rocket never left")
				step = 9
		9:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	return false
