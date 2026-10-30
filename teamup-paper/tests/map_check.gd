extends SceneTree
## The sky map is easy to land on: tapping an island (or its button) flies the rocket
## there by itself and lands; locked worlds refuse; the rocket drifts onto an island
## you're over; LAND works from a good way off the middle.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/map_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
	Saves.prefix = "user://test_map"
	_clean()
	Saves.write(1, {"v": 2, "cleared_w": {}, "coins": 5, "mon": "fire", "mon_xp": {}})
	Boot.place = "map"
	Boot.slot = 1
	Boot.from_level = "home"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "map":
				var sky: SkyMap = g.sky
				check(sky.order[0] == "home" and sky.order.size() == 1 + Worlds.ORDER.size(), "a button for home and every world")
				# a locked world refuses
				check(not sky.fly_to("frost") and sky.auto_to == "", "Frosty Peaks should be locked")
				# the island buttons on screen
				var n: int = sky.order.size()
				var r: Rect2 = g.hud.island_button_rect(1, n)
				check(g.hud.island_button_at(r.get_center()) == "meadow", "the second button should be Meadow")
				# tapping the island itself on screen
				var pos: Vector2 = g.cam.unproject_position(Worlds.info("meadow").map)
				check(sky.tap_screen(pos, g.cam) and sky.auto_to == "meadow", "tapping the Meadow island should start the autopilot")
				step = 1
				f = 0
		1:
			if g.state == "play" and g.level.world == "meadow":
				print("autopilot landed on Meadow after %.1f s" % (f / 60.0))
				step = 2
				f = 0
				g.go("map", 1)
			elif f > 60 * 15:
				check(false, "the autopilot never landed on Meadow")
				step = 9
		2:
			if g.state == "map" and g.sky != null:
				var sky: SkyMap = g.sky
				# over the edge of the home island: let go of the stick and it drifts to the middle
				sky.rocket.position = Worlds.HOME_MAP + Vector3(5.5, 7, 0)
				for i in 120:
					sky.step(1.0 / 60, Vector2.ZERO, false)
				var off := Vector2(sky.rocket.position.x - Worlds.HOME_MAP.x, sky.rocket.position.z - Worlds.HOME_MAP.z).length()
				check(off < 2.0, "the rocket should drift onto the island (still %.1f away)" % off)
				# LAND from well off the middle still works
				sky.rocket.position = Worlds.HOME_MAP + Vector3(6.0, 7, 0)
				sky.step(1.0 / 60, Vector2.ZERO, false)
				check(sky.near_id == "home", "6 blocks off the middle should still count as over home")
				check(sky.land(), "LAND should work there")
				step = 3
				f = 0
		3:
			if g.state == "home":
				print("landed home")
				step = 9
			elif f > 60 * 10:
				check(false, "LAND never got home")
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
