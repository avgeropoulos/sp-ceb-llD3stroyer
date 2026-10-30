extends SceneTree
## The home base: new file -> partner -> home; BUILD (costs, refunds, stacking, limits);
## DECOR (place, turn, pick up); the portal to 1-1; finishing 1-1 brings you home with
## everything still there. Run: godot --headless --fixed-fps 60 --path . --script res://tests/home_check.gd [-- toy]

var f := 0
var step := 0
var wait := 0
var failed := ""
var g     # the running game (it changes when the game switches place)
var coins_before := 0
var placed := []


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Saves.prefix = "user://test_home"
	_clean()
	Boot.place = "title"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func ready_in(state: String) -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == state


func face(dir: Vector3) -> void:
	g.leader().facing = dir


func _process(_dt: float) -> bool:
	f += 1
	if wait > 0:
		wait -= 1
		return false
	match step:
		0:
			if ready_in("title"):
				g.slot_pick(1)
				check(g.state == "partner", "a new file should ask for a partner")
				g.choose_partner("fire")
				step = 1
		1:
			if ready_in("home"):
				check(g.home != null and g.level.is_home, "should arrive at the home base")
				check(g.coins == g.START_COINS, "a new file should start with %d coins (got %d)" % [g.START_COINS, g.coins])
				check(g.heroes.mon.line == "fire", "the partner should be EMBER")
				check(g.home.decor.size() == 3, "a new home has a mailbox, flowers and a bush")
				# BUILD: 3 planks in a stack and one more next to it
				g.taps = {"build": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "build", "BUILD should open build mode")
				g.leader().position = Vector3(15.5, 5, 6.5)
				face(Vector3.RIGHT)
				coins_before = g.coins
				g.home.sel.build = 0   # planks, 1 coin
				for i in 3:
					g.home.aim(g.leader())
					check(g.home.place(), "placing plank %d failed (%s)" % [i, g.home.why])
				check(g.level.get_cell(16, 5, 6) == Level.PLANKS and g.level.get_cell(16, 7, 6) == Level.PLANKS, "planks should stack up")
				check(g.coins == coins_before - 3, "3 planks should cost 3 coins")
				g.home.aim(g.leader())
				g.home.remove()
				check(g.level.get_cell(16, 7, 6) == Level.AIR and g.coins == coins_before - 2, "REMOVE should take the top one and refund it")
				# a stone block with no stone: not allowed
				g.ores.stone = 0
				g.home.sel.build = 8
				g.home.aim(g.leader())
				check(not g.home.can_place and g.home.why.contains("stone"), "stone blocks need stone")
				g.ores.stone = 2
				g.home.aim(g.leader())
				check(g.home.place() and g.ores.stone == 1, "a stone block should cost 1 stone")
				# can't build on top of yourself
				g.home.sel.build = 0
				g.leader().position = Vector3(20.5, 5, 6.5)
				face(Vector3.RIGHT)
				g.follower().position = Vector3(21.5, 5, 6.5)
				g.home.aim(g.leader())
				check(not g.home.can_place, "can't build inside a friend")
				g.follower().position = Vector3(18, 5, 8)
				# DECOR: a bench, turn it, pick it up
				g.taps = {"decor": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "decor", "DECOR should switch to decor mode")
				g.leader().position = Vector3(25.5, 5, 5.5)
				face(Vector3.FORWARD)
				var coins2: int = g.coins
				g.home.sel.decor = 8   # bench, 10 coins
				g.home.aim(g.leader())
				var c: Vector3i = g.home.target
				check(g.home.place(), "placing a bench failed (%s)" % g.home.why)
				check(g.coins == coins2 - 10, "a bench costs 10 coins")
				check(g.home.decor.has(c) and g.home.decor[c].id == "bench", "the bench should be there")
				var r0: int = g.home.decor[c].r
				g.home.aim(g.leader())
				g.home.rotate_front()
				check(g.home.decor[c].r == (r0 + 1) % 4, "TURN should turn it")
				g.home.place()   # a second thing can't go on top of the bench
				check(g.home.decor.size() == 4, "nothing should stack on a decoration")
				g.home.sel.decor = 17   # the trophy needs Krag Jr. beaten first
				g.home.aim(g.leader())
				check(not g.home.can_place, "the trophy is locked until 1-1 is cleared")
				g.home.sel.decor = 0
				g.leader().position = Vector3(27.5, 5, 5.5)
				g.home.aim(g.leader())
				g.home.place()
				placed = [g.level.get_cell(16, 5, 6), g.home.decor.size()]
				g.taps = {"done": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "", "DONE should leave build mode")
				var d := Saves.read(1)
				check(d.get("home", {}).get("decor", []).size() == 5, "DONE should save the home (decor count %d)" % d.get("home", {}).get("decor", []).size())
				# walk into the portal
				g.leader().position = Vector3(Level.PORTAL_X - 0.2, 5, 6.5)
				g.play_t = 5.0
				step = 2
		2:
			if ready_in("play"):
				check(not g.level.is_home and g.slot == 1, "the portal should start 1-1 with file 1")
				check(g.heroes.mon.line == "fire", "the partner should come along")
				check(g.home_saved.get("decor", []).size() == 5, "the home should be remembered during 1-1")
				# a mid-level SAVE must keep the home too
				g._pause("play")
				g.pause_choice(1)
				check(Saves.read(1).get("home", {}).get("decor", []).size() == 5, "SAVE in 1-1 lost the home")
				g.state = "play"
				g.boss_done = true
				g.leader().position = Vector3(Level.FLAG_X, 5.1, 6.5)
				step = 3
		3:
			if ready_in("clear"):
				g.clear_t = 2.0
				g.on_tap()
				step = 4
		4:
			if ready_in("home"):
				check(g.level.get_cell(16, 5, 6) == Level.PLANKS, "the planks should still be there")
				check(g.home.decor.size() == 5, "the decorations should still be there (%d)" % g.home.decor.size())
				check(g.cleared == 1, "clearing should count")
				g.home.mode = "decor"
				g.home.sel.decor = 17
				g.leader().position = Vector3(30.5, 5, 5.5)
				face(Vector3.FORWARD)
				g.home.aim(g.leader())
				check(g.home.can_place, "the trophy unlocks after clearing 1-1 (%s)" % g.home.why)
				step = 5
				wait = 3
		5:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if f > 60 * 60:
		print("TEST FAILED: timed out at step %d (state %s)" % [step, Boot.current.state if is_instance_valid(Boot.current) else "?"])
		quit(1)
		return true
	return false
