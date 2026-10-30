extends SceneTree
## A file saved in the middle of Frosty Peaks continues in Frosty Peaks (right spot, same blocks, same stars).
var f := 0
var step := 0
var failed := ""
var g


func _initialize() -> void:
	Look.loaded = true
	Look.toy = false
	Saves.prefix = "user://test_world"
	Saves.write(1, {"v": 2, "in_level": true, "level": "frost", "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 7,
		"pos": [50.5, 24.0, 70.5], "checkpoint": [48.5, 6, 86.5], "checkpoint_hit": true, "cells": [[50, 1, 70, 0]],
		"stars": ["tower", "sky"],
		"mon": "water", "mon_xp": {"water": 40}, "hearts": 4})
	Boot.place = "title"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = why


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if step == 0 and is_instance_valid(g) and g.state == "title":
		g.slot_pick(1)
		step = 1
	elif step == 1 and is_instance_valid(g) and g.is_inside_tree() and g.state == "play" and g.level.world == "frost":
		check(g.coins == 7 and g.hearts == 4, "coins / hearts not restored")
		check(Vector2(g.leader().position.x, g.leader().position.z).distance_to(Vector2(50.5, 70.5)) < 0.5, "should continue where we saved (got %s)" % g.leader().position)
		check(g.level.get_cell(50, 1, 70) == Level.AIR, "the mined block should stay mined")
		check(g.open_play.got.has("tower") and g.open_play.got.has("sky") and g.open_play.count() == 2, "the stars found should stay found")
		check(not g.open_play.stars.has("tower") and not g.open_play.beacons["sky"].visible, "found stars shouldn't be out any more")
		check(g.heroes.mon.line == "water", "partner should be SPLASHY")
		check(g.boss.hp == int(Worlds.info("frost").boss_hp), "Krag Jr. should be the Frosty Peaks one")
		step = 2
	elif step == 2 or f > 1200:
		for e in ["", ".bak", ".tmp"]:
			if FileAccess.file_exists(Saves.path(1) + e):
				DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))
		if step != 2:
			failed = "never got into Frosty Peaks (step %d)" % step
		print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
		quit(1 if failed != "" else 0)
		return true
	return false
