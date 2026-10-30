extends SceneTree
## A file saved in the middle of Frosty Peaks continues in Frosty Peaks (right spot, same blocks).
var f := 0
var step := 0
var failed := ""
var g


func _initialize() -> void:
	Look.loaded = true
	Look.toy = false
	Saves.prefix = "user://test_world"
	Saves.write(1, {"v": 2, "in_level": true, "level": "frost", "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 7,
		"pos": [120.5, 6.0, 6.5], "checkpoint": [91.5, 5, 6.5], "checkpoint_hit": true, "cells": [[100, 4, 6, 0]],
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
		check(g.leader().position.x > 119 and g.leader().position.x < 122, "should continue at x=120 (got %.1f)" % g.leader().position.x)
		check(g.level.get_cell(100, 4, 6) == Level.AIR, "the mined block should stay mined")
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
