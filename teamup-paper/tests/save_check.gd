extends SceneTree
## SAVE -> quit -> load gives back the same run; ERASE / UNDO ERASE; clearing the level.
## Run: godot --headless --path . --script res://tests/save_check.gd

var a
var b
var f := 0
var failed := ""
var coin_count := 0
var expect_pos := Vector3(60.5, 5, 6.5)


func _initialize() -> void:
	Look.loaded = true
	Look.toy = false
	Saves.prefix = "user://test_save"
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))
	a = load("res://main.tscn").instantiate()
	root.add_child(a)


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = why


func _process(_dt: float) -> bool:
	f += 1
	match f:
		5:
			check(not Saves.exists(1), "file 1 should start empty")
			a.slot_pick(1)
			check(a.state == "play" and a.slot == 1, "picking an empty file should start a new game")
			# change things
			a.level.set_cell(20, 4, 6, Level.AIR)
			var c = a.coin_nodes[0]
			a.coins_taken.append(int(c.get_meta("i")))
			a.coins += 1
			c.queue_free()
			a.coin_nodes.erase(c)
			coin_count = a.coin_nodes.size()
			a.foes[0].hurt(99, a)
			a.ores.iron = 3
			a.hearts = 3
			a.do_swap()   # Blocky out
			a.leader().position = expect_pos
			a.safe_pos = expect_pos
			a.checkpoint_hit = true
		8:
			a.state = "paused"
			a.pause_choice(1)   # SAVE
			check(Saves.exists(1), "SAVE didn't write file 1")
			check(a.save_note.begins_with("SAVED"), "SAVE didn't say SAVED")
			a.queue_free()
		12:
			b = load("res://main.tscn").instantiate()
			root.add_child(b)
		16:
			b.slot_pick(1)
		20:
			check(b.level.get_cell(20, 4, 6) == Level.AIR, "the mined block came back")
			check(b.coin_nodes.size() == coin_count, "collected coin came back (%d vs %d)" % [b.coin_nodes.size(), coin_count])
			check(b.coins == 3, "coins should be 3 (1 picked up + 2 for the bad guy), got %d" % b.coins)
			check(b.ores.iron == 3, "iron should be 3")
			check(b.hearts == 3, "hearts should be 3")
			check(b.human == "blocky" and b.leader().who == "blocky", "Blocky should be out")
			check(b.leader().position.distance_to(expect_pos) < 0.5, "should be back at x=60, got %s" % b.leader().position)
			check(b.checkpoint_hit, "checkpoint should be reached")
			var gone := true
			for foe in b.foes:
				if is_instance_valid(foe) and foe.alive and int(foe.get_meta("spawn")) == 0:
					gone = false
			check(gone, "the beaten bad guy came back")
			# ERASE needs two taps, UNDO brings it back
			b.slot_erase(1)
			check(Saves.exists(1), "one ERASE tap shouldn't erase")
			b.slot_erase(1)
			check(not Saves.exists(1) and Saves.can_undo(1), "two ERASE taps should erase (with undo)")
			b.slot_undo(1)
			check(Saves.exists(1), "UNDO ERASE didn't bring the file back")
			# finishing the level saves a fresh level but keeps coins and ores
			b.boss_done = true
			b.leader().position = Vector3(Level.FLAG_X, 5.1, 6.5)
		24:
			check(b.state == "clear", "touching the flag should clear the level")
			var d := Saves.read(1)
			check(int(d.get("cleared", 0)) == 1 and not d.get("in_level", true), "clear should save cleared=1 with a fresh level")
			check(int(d.get("coins", 0)) == b.coins, "clear should keep the coins")
		26:
			for ext in ["", ".bak", ".tmp"]:
				if FileAccess.file_exists(Saves.path(1) + ext):
					DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	return false
