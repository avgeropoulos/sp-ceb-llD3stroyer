extends SceneTree
## Mario 64 style quests: the STAR SELECT screen when you land (stars from an earlier
## visit stay gold), picking a quest, the light beam + arrow showing the way, the
## "YOU GOT A STAR!" moment then the next quest picked by itself, STARS in the pause
## menu, and saving / continuing with the same quest.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/quest_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = true
	OpenPlay.select_enabled = true
	Saves.prefix = "user://test_quest"
	_clean()
	# a file that already found the camp star in Meadow Mine on an earlier visit
	Saves.write(1, {"v": 2, "in_level": false, "level": "meadow", "coins": 5, "mon": "spark",
		"stars_w": {"meadow": ["camp"]}, "scenes_seen": ["intro", "gate", "krag", "victory"]})
	Boot.place = "level"
	Boot.level = "meadow"
	Boot.slot = 1
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	var op = g.open_play
	match step:
		0:
			if g.state == "starselect":
				g.invincible = true
				check(op.got.has("camp") and op.count() == 1, "the camp star from the last visit should still be there")
				check(op.ids().size() >= 7, "7+ stars per world")
				check(op.ids()[op.sel] == "tower", "the first star not found yet should be highlighted (got %s)" % op.ids()[op.sel])
				var sky: int = op.ids().find("sky")
				op.select_tap(sky)
				check(g.state == "starselect" and op.sel == sky, "the first tap only picks the card")
				op.select_tap(sky)
				check(g.state == "play" and op.current == "sky", "the second tap should start SKY STEPS")
				check(g.hud.message.contains("SKY STEPS"), "a big banner should say which star (%s)" % g.hud.message)
				step = 1
				f = 0
			elif f > 60 * 30:
				check(false, "the star select screen never showed (state %s)" % g.state)
				step = 9
		1:
			if f == 20:
				check(op.beam != null and op.beam.visible, "the light beam should show the way")
				var tp: Vector3 = op.target_pos()
				check(Vector2(op.beam.position.x - tp.x, op.beam.position.z - tp.z).length() < 0.1, "the beam should be on the quest")
				# look the other way: the arrow at the screen edge points the way
				var l: Hero = g.leader()
				var away := Vector3(l.position.x - tp.x, 0, l.position.z - tp.z).normalized()
				g.cam_yaw = atan2(-away.x, -away.z)   # the camera sits between the hero and the star
			if f == 60:
				check(g.hud.arrow_at.x >= 0, "an arrow should point the way when the quest is off the screen")
				check(g.hud.arrow_at.y > g.hud.size.y * 0.5, "with the quest behind the camera, the arrow should be at the bottom (%s)" % g.hud.arrow_at)
				check(op.progress() != "", "the goal should show at the top")
				# grab the sky star: "YOU GOT A STAR!" plays
				var star: Node3D = op.stars["sky"]
				g.leader().position = star.position - Vector3(0, 0.8, 0)
			if f == 64:
				check(g.state == "stargot", "the star moment should play (state %s)" % g.state)
				check(op.got.has("sky"), "the star should count")
				check(op.current != "sky" and op.current != "" and not op.got.has(op.current), "the next quest should be picked by itself (%s)" % op.current)
			if f == 64 + 60 * 3:
				check(g.state == "play", "the star moment should end by itself")
				# STARS in the pause menu
				g._pause("play")
				check(g.pause_count() >= 4, "the pause menu should have STARS in an open world")
				g.pause_choice(3)
				check(g.state == "starselect" and op.select_from == "pause", "STARS should open the star select")
				var cave: int = op.ids().find("cave")
				op.choose(cave)
				check(g.state == "play" and op.current == "cave", "choosing should switch the quest")
				g.save_game(true)
				var d := Saves.read(1)
				check(String(d.get("quest", "")) == "cave", "the save should keep the quest")
				var sw: Array = d.get("stars_w", {}).get("meadow", [])
				check(sw.has("camp") and sw.has("sky"), "the save should keep the stars found (%s)" % str(sw))
				g.go("level", 1)
				step = 2
				f = 0
		2:
			if f > 5 and g.state == "play" and g.open_play:
				check(op.current == "cave", "continuing should keep the quest (%s)" % op.current)
				check(op.got.has("sky") and op.got.has("camp"), "continuing should keep the stars")
				step = 9
			elif f > 60 * 30:
				check(false, "continuing never got back into play (state %s)" % g.state)
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
