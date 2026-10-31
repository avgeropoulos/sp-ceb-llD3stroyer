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
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
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


## Where the old 44 x 10 home sits in the big one (y: the old ground was at 5, now HOME_GY).
const O := Vector3(Level.HOME_OX, Level.HOME_GY - 5, Level.HOME_OZ)
const O3 := Vector3i(Level.HOME_OX, Level.HOME_GY - 5, Level.HOME_OZ)


func cell(x: int, y: int, z: int) -> int:
	return g.level.get_cell(O3.x + x, O3.y + y, O3.z + z)


func _decor_index(id: String) -> int:
	for i in Decor.ITEMS.size():
		if Decor.ITEMS[i].id == id:
			return i
	return -1


## Aims straight down at a column of the (old) home area.
func down_at(x: int, z: int) -> void:
	g.home.aim_ray(O + Vector3(x + 0.5, 12, z + 0.5), Vector3.DOWN, g.leader())


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
				check(g.level.sx == Level.HOME_SX and g.level.sz == Level.HOME_SZ, "the home should be the big world")
				# BUILD: aim like Minecraft (a line from the camera); 3 planks in a stack
				g.taps = {"build": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "build", "BUILD should open build mode")
				g.leader().position = O + Vector3(15.5, 5, 6.5)
				coins_before = g.coins
				g.home.sel.build = 0   # planks: free
				for i in 3:
					down_at(16, 6)
					check(g.home.place(), "placing plank %d failed (%s)" % [i, g.home.why])
				check(cell(16, 5, 6) == Level.PLANKS and cell(16, 7, 6) == Level.PLANKS, "planks should stack up")
				check(g.coins == coins_before, "planks are free")
				down_at(16, 6)
				check(g.home.under == O3 + Vector3i(16, 7, 6), "should point at the top plank")
				g.home.remove()
				check(cell(16, 7, 6) == Level.AIR, "REMOVE should take the block you point at")
				# on the side of a block: you're standing there, then step back
				g.home.aim_ray(O + Vector3(12.2, 5.5, 6.5), Vector3.RIGHT, g.leader())
				check(g.home.target == O3 + Vector3i(15, 5, 6), "side of the plank should be the spot next to it (%s)" % g.home.target)
				check(not g.home.can_place, "can't build inside yourself")
				g.leader().position = O + Vector3(12.5, 5, 8.5)
				g.home.aim_ray(O + Vector3(12.2, 5.5, 6.5), Vector3.RIGHT, g.leader())
				check(g.home.place() and cell(15, 5, 6) == Level.PLANKS, "should place on the side of a block (%s)" % g.home.why)
				# shiny blocks cost ores, and give them back
				g.ores.diamond = 0
				g.home.sel.build = HomeBase.BLOCKS.size() - 1
				down_at(14, 8)
				check(not g.home.can_place and g.home.why.contains("diamond"), "diamond blocks need diamonds")
				g.ores.diamond = 1
				down_at(14, 8)
				check(g.home.place() and g.ores.diamond == 0, "a diamond block costs 1 diamond")
				down_at(14, 8)
				g.home.remove()
				check(g.ores.diamond == 1, "removing it gives the diamond back")
				# dig the ground (Minecraft!), but not the bottom layer
				g.home.sel.build = 0
				down_at(18, 7)
				check(g.home.remove() and cell(18, 4, 7) == Level.AIR, "you can dig the grass")
				var stone0: int = g.ores.stone
				for i in 3:   # dirt, dirt, then stone (dug out of the ground: you get the stone)
					down_at(18, 7)
					g.home.remove()
				check(cell(18, 2, 7) == Level.AIR and g.ores.stone == stone0 + 1, "digging down to the stone should give 1 stone (%d)" % (g.ores.stone - stone0))
				g.home.under = Vector3i(O3.x + 18, 0, O3.z + 7)
				g.home.has_under = true
				check(not g.home.remove() and g.level.get_cell(O3.x + 18, 0, O3.z + 7) == Level.BEDROCK, "the bedrock at the bottom stays")
				# too far away
				g.leader().position = O + Vector3(40, 5, 30)
				down_at(16, 6)
				check(not g.home.can_place and g.home.why.contains("far"), "can't build far away")
				# DECOR: a bench, turn it, pick it up
				g.taps = {"decor": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "decor", "DECOR should switch to decor mode")
				g.leader().position = O + Vector3(25.5, 5, 7.5)
				var coins2: int = g.coins
				g.home.sel.decor = 8   # bench, 10 coins
				down_at(25, 5)
				var c: Vector3i = g.home.target
				check(g.home.place(), "placing a bench failed (%s)" % g.home.why)
				check(g.coins == coins2 - 10, "a bench costs 10 coins")
				check(g.home.decor.has(c) and g.home.decor[c].id == "bench", "the bench should be there")
				var r0: int = g.home.decor[c].r
				down_at(25, 5)
				check(g.home.under == c, "should point at the bench")
				g.home.rotate_front()
				check(g.home.decor[c].r == (r0 + 1) % 4, "TURN should turn it")
				down_at(25, 5)
				g.home.place()   # a second thing can't go on top of the bench
				check(g.home.decor.size() == 4, "nothing should stack on a decoration")
				g.home.sel.decor = 17   # the trophy needs Krag Jr. beaten first
				down_at(26, 6)
				check(not g.home.can_place, "the trophy is locked until 1-1 is cleared")
				g.home.sel.decor = 0
				down_at(27, 5)
				g.home.place()
				g.home.mode = "build"
				g.home.under = O3 + Vector3i(27, 4, 5)
				g.home.has_under = true
				check(not g.home.remove() and cell(27, 4, 5) == Level.GRASS, "can't dig out the ground under a decoration")
				g.home.mode = "decor"
				# PAINT: a blue bunk bed, then paint it red; a bush can't be painted
				g.coins += 100
				g.home.sel.decor = _decor_index("bed_bunk")
				g.home.paint = 6   # blue
				down_at(29, 5)
				var bunk: Vector3i = g.home.target
				check(g.home.place(), "placing a bunk bed failed (%s)" % g.home.why)
				check(g.home.decor.has(bunk) and int(g.home.decor[bunk].col) == 6, "the bunk bed should be blue")
				g.home.paint = 1   # red
				down_at(29, 5)
				check(g.home.under == bunk and g.home.paint_front(), "PAINT should repaint the bunk bed")
				check(int(g.home.decor[bunk].col) == 1 and g.home.decor.size() == 6, "the bunk bed should now be red (and still one bed)")
				g.home.sel.decor = 1   # bush: no paint
				down_at(31, 5)
				var bush: Vector3i = g.home.target
				g.home.place()
				check(int(g.home.decor[bush].col) == 0, "a bush keeps its own colours")
				down_at(31, 5)
				check(not g.home.paint_front(), "a bush can't be painted")
				g.home.under = bush
				g.home.remove()
				g.home.paint = 0
				placed = [cell(16, 5, 6), g.home.decor.size()]
				g.taps = {"done": true}
				g._step_home(1.0 / 60)
				check(g.home.mode == "", "DONE should leave build mode")
				var d := Saves.read(1)
				check(d.get("home", {}).get("decor", []).size() == 6, "DONE should save the home (decor count %d)" % d.get("home", {}).get("decor", []).size())
				var saved_col := -1
				for e in d.get("home", {}).get("decor", []):
					if e[0] == "bed_bunk":
						saved_col = int(e[5])
				check(saved_col == 1, "the save should keep the bunk bed's colour")
				check(int(d.get("home", {}).get("v", 0)) == 3, "the home should be saved in the new way")
				# the camera turns, and the stick follows the camera
				g.cam_yaw = PI / 2
				g.move_vec = Vector2(0, -1)
				var p0: Vector3 = g.leader().position
				for i in 30:
					g._update_leader(g.leader(), 1.0 / 60)
				check(g.leader().position.x < p0.x - 1.0, "with the camera turned, UP should walk the way the camera looks")
				g.move_vec = Vector2.ZERO
				g.cam_yaw = 0
				# FLY up and come back down
				g.leader().position = O + Vector3(30.5, 5, 10.5)
				g.set_flying(true)
				g.jump_held = true
				for i in 60:
					g._update_leader(g.leader(), 1.0 / 60)
				check(g.leader().position.y > 9, "FLY + UP should go up (y %.1f)" % g.leader().position.y)
				g.jump_held = false
				g.hud.held[99] = "down"
				for i in 200:
					g._update_leader(g.leader(), 1.0 / 60)
				g.hud.held.erase(99)
				check(not g.flying and g.leader().on_floor, "holding DOWN should land and stop flying")
				# Rocco's double jump: a full second jump with a flip
				check(g.leader().who == "rocco", "Rocco should be out")
				g.jump_pressed = true
				g._update_leader(g.leader(), 1.0 / 60)
				g.jump_pressed = false
				for i in 24:
					g._update_leader(g.leader(), 1.0 / 60)
				var y1: float = g.leader().position.y
				g.jump_pressed = true
				g._update_leader(g.leader(), 1.0 / 60)
				g.jump_pressed = false
				check(g.leader().vel.y > g.leader().jump_v * 0.9 and g.leader().spin_t > 0, "double jump should be strong and flip")
				var top := 0.0
				for i in 50:
					g._update_leader(g.leader(), 1.0 / 60)
					top = maxf(top, g.leader().position.y)
				check(top > y1 + 1.8, "a quick-tap double jump should still go up a lot (%.2f)" % (top - y1))
				# walk onto the rocket's launch pad
				g.leader().position = HomeBase.PAD - Vector3(0.2, 0, 0)
				g.play_t = 5.0
				step = 2
		2:
			if ready_in("map"):
				check(g.sky != null, "the rocket pad should open the sky map")
				check(g.sky.islands.size() == 1 + Worlds.ORDER.size(), "home + every world should be an island")
				# fly over Frosty Peaks: it's locked
				g.sky.rocket.position = Worlds.info("frost").map + Vector3(0, 7, 0)
				g.sky.step(1.0 / 60, Vector2.ZERO, false)
				check(g.sky.near_id == "frost", "should be over Frosty Peaks")
				check(not g.sky.land(), "Frosty Peaks should be locked before Meadow is beaten")
				# fly with the stick for a second: the rocket moves
				var p0: Vector3 = g.sky.rocket.position
				for i in 60:
					g.sky.step(1.0 / 60, Vector2(1, 0), true)
				check(g.sky.rocket.position.x > p0.x + 4, "the stick should fly the rocket")
				g.sky.rocket.position = Worlds.info("meadow").map + Vector3(0, 7, 0)
				g.sky.step(1.0 / 60, Vector2.ZERO, false)
				check(g.sky.land(), "should land on Meadow Mine")
				step = 3
		3:
			if ready_in("play"):
				check(not g.level.is_home and g.slot == 1 and g.level.world == "meadow", "landing should start Meadow Mine with file 1")
				check(g.heroes.mon.line == "fire", "the partner should come along")
				check(g.home_saved.get("decor", []).size() == 6, "the home should be remembered during the level")
				g._pause("play")
				g.pause_choice(1)
				check(Saves.read(1).get("home", {}).get("decor", []).size() == 6, "SAVE in the level lost the home")
				check(Saves.read(1).get("level", "") == "meadow", "a level save should say which world")
				g.state = "play"
				g.boss_done = true
				g.leader().position = g.level.flag_pos + Vector3(0, 0.1, 0)
				step = 4
		4:
			if ready_in("clear"):
				g.clear_t = 2.0
				g.on_tap()
				step = 5
		5:
			if ready_in("map"):
				check(int(g.cleared_w.get("meadow", 0)) == 1, "clearing should count for Meadow")
				check(Worlds.unlocked("frost", g.cleared_w), "Frosty Peaks should unlock")
				check(g.sky.rocket.position.distance_to(Worlds.info("meadow").map) < 9, "the rocket should start over Meadow")
				g.sky.rocket.position = Worlds.HOME_MAP + Vector3(0, 7, 0)
				g.sky.step(1.0 / 60, Vector2.ZERO, false)
				check(g.sky.land(), "should land at home")
				step = 6
		6:
			if ready_in("home"):
				check(cell(16, 5, 6) == Level.PLANKS and cell(18, 4, 7) == Level.AIR and cell(18, 2, 7) == Level.AIR, "the planks (and the hole) should still be there")
				check(g.home.decor.size() == 6 and int(g.home.decor[O3 + Vector3i(29, 5, 5)].col) == 1, "the decorations (and the red bunk bed) should still be there (%d)" % g.home.decor.size())
				check(g.cleared == 1, "clearing should count")
				g.home.mode = "decor"
				g.home.sel.decor = 17
				g.leader().position = O + Vector3(30.5, 5, 7.5)
				down_at(30, 5)
				check(g.home.can_place, "the trophy unlocks after clearing 1-1 (%s)" % g.home.why)
				# an old save (from the small home) moves into the middle of the big one
				var d := Saves.read(1)
				d["home"] = {"blocks": [[16, 5, 6, Level.BRICK], [16, 6, 6, Level.BRICK]], "decor": [["bench", 20, 5, 5, 1]]}
				Saves.write(1, d)
				g.go("home", 1)
				step = 7
		7:
			if ready_in("home"):
				check(cell(16, 5, 6) == Level.BRICK and cell(16, 6, 6) == Level.BRICK, "old blocks should move into the big home")
				check(g.home.decor.has(O3 + Vector3i(20, 5, 5)), "old decorations should move too")
				check(int(g.home.decor[O3 + Vector3i(20, 5, 5)].get("col", -1)) == 0, "an old decoration (no colour saved) keeps its own colours")
				check(g.home.data().blocks.size() == 2 and int(g.home.data().v) == 3, "only the changes are saved (as v3)")
				step = 8
				wait = 3
		8:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if f > 60 * 60:
		print("TEST FAILED: timed out at step %d (state %s)" % [step, Boot.current.state if is_instance_valid(Boot.current) else "?"])
		quit(1)
		return true
	return false
