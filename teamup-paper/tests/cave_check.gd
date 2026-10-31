extends SceneTree
## The big home: mountains are tall, caves are underground and you can walk into them from
## the mine entrance, digging natural ores gives ore, the castle can't be dug, there's a
## painting for every world (a locked one bonks you, an unlocked one takes you there), old
## 96 x 96 homes move into the middle, and the baked home is the same as a freshly made one.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/cave_check.gd [-- toy]

var f := 0
var step := 0
var wait := 0
var failed := ""
var g


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_cave"
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


## Air cells you can reach underground from a spot inside the mine tunnel.
func _reach_from(lv: Level, start: Vector3i) -> Dictionary:
	var seen := {start: true}
	var todo := [start]
	var deep := 0
	while not todo.is_empty() and seen.size() < 60000:
		var c: Vector3i = todo.pop_back()
		if c.y <= 10:
			deep += 1
		for d in [Vector3i(1, 0, 0), Vector3i(-1, 0, 0), Vector3i(0, 1, 0), Vector3i(0, -1, 0), Vector3i(0, 0, 1), Vector3i(0, 0, -1)]:
			var n: Vector3i = c + d
			if seen.has(n) or not lv.inside(n.x, n.y, n.z) or lv.get_cell(n.x, n.y, n.z) != Level.AIR:
				continue
			if n.y >= lv.ground_at(n.x, n.z) - 1:
				continue   # stay underground
			seen[n] = true
			todo.append(n)
	return {"all": seen.size(), "deep": deep}


func _painting(id: String):
	for fr in g.home.frames:
		if fr.id == id:
			return fr
	return null


func _process(_dt: float) -> bool:
	f += 1
	if wait > 0:
		wait -= 1
		return false
	match step:
		0:
			if ready_in("title"):
				g.slot_pick(1)
				g.choose_partner("water")
				step = 1
		1:
			if ready_in("home"):
				var lv: Level = g.level
				check(lv.sx == Level.HOME_SX and lv.sz == Level.HOME_SZ and lv.sx >= 192, "the home should be the bigger world")
				var top := 0
				for h in lv.hmap:
					top = maxi(top, h)
				check(top >= Level.HOME_GY + 15, "the mountains should be tall (highest %d)" % top)
				check(lv.summit.y >= Level.HOME_GY + 12, "the summit should be high up (%s)" % lv.summit)
				# caves: lots of air deep underground, reachable from the mine entrance
				var cave_air := 0
				for z in range(0, lv.sz, 2):
					for x in range(0, lv.sx, 2):
						for y in range(2, Level.HOME_GY - 6):
							if lv.get_cell(x, y, z) == Level.AIR:
								cave_air += 1
				check(cave_air > 1500, "there should be caves underground (%d sampled air cells)" % cave_air)
				var inside := Vector3i(Level.MINE.x - 10, lv.MINE_Y - 4, Level.MINE.z)
				while lv.get_cell(inside.x, inside.y, inside.z) != Level.AIR and inside.y < lv.MINE_Y + 2:
					inside.y += 1
				var r := _reach_from(lv, inside)
				check(r.deep > 1500, "the mine should lead down into the caves (%d deep cells reached, %d in all)" % [r.deep, r.all])
				var lanterns := 0
				var lava := 0
				for c in lv.cells:
					if c == Level.LANTERN:
						lanterns += 1
					elif c == Level.LAVA:
						lava += 1
				check(lanterns > 20 and lava > 0, "caves should have lanterns (%d) and a lava pool (%d)" % [lanterns, lava])
				# digging a natural ore gives the ore; digging a block you placed doesn't
				g.home.mode = "build"
				var ore := Vector3i(-1, -1, -1)
				for z in range(60, 140):
					for x in range(60, 140):
						for y in range(2, 12):
							if ore.x < 0 and lv.get_cell(x, y, z) == Level.IRON:
								ore = Vector3i(x, y, z)
				check(ore.x >= 0, "there should be iron underground")
				g.leader().position = Vector3(ore) + Vector3(0.5, 1.0, 0.5)
				var iron0: int = g.ores.iron
				g.home.under = ore
				g.home.has_under = true
				check(g.home.remove() and g.ores.iron == iron0 + 1, "digging iron ore should give 1 iron")
				var stone0: int = g.ores.stone
				var spot := Vector3i(Level.HOME_OX + 30, Level.HOME_GY, Level.HOME_OZ + 20)
				g.leader().position = Vector3(spot) + Vector3(1.5, 0, 1.5)
				g.home._change(spot, Level.STONE)
				g.home.under = spot
				g.home.has_under = true
				check(g.home.remove() and g.ores.stone == stone0, "a stone block you placed shouldn't give stone")
				# the castle can't be dug
				var wall := Vector3i(Level.CASTLE.position.x, Level.HOME_GY + 2, Level.CASTLE.position.y + 3)
				check(lv.locked.has(wall), "the castle wall should be locked")
				g.leader().position = Vector3(wall) + Vector3(2, 0, 0)
				g.home.under = wall
				g.home.has_under = true
				check(not g.home.remove() and lv.get_cell(wall.x, wall.y, wall.z) == Level.STONE_BRICK, "castle walls can't be dug")
				g.home.mode = ""
				# a painting for every world; a locked one bonks you
				check(g.home.frames.size() == Worlds.ORDER.size(), "a painting for every world (%d of %d)" % [g.home.frames.size(), Worlds.ORDER.size()])
				var lk = _painting(Worlds.ORDER[1])
				check(lk != null and lk.locked, "%s should be locked on a new file" % Worlds.ORDER[1])
				g.leader().position = lk.slot.pos + lk.slot.normal * 0.4 - Vector3(0, 0.75, 0)
				check(g.home.painting_at(g.leader().position) == lk, "standing there should be inside the painting")
				g.home._step_paintings(1.0 / 60)
				check(g.home.entering == "" and g.home.paint_cool > 0, "a locked painting should bonk you back")
				# an unlocked one takes you into that world
				var mp = _painting("meadow")
				check(mp != null and not mp.locked, "Meadow's painting should be open")
				g.home.paint_cool = 0.0
				g.leader().position = mp.slot.pos + mp.slot.normal * 0.4 - Vector3(0, 0.75, 0)
				g.leader().vel = Vector3.ZERO
				step = 2
		2:
			if is_instance_valid(g) and g.home and g.home.entering == "" and g.state == "home" and f % 30 == 0:
				var mp = _painting("meadow")
				g.leader().position = mp.slot.pos + mp.slot.normal * 0.4 - Vector3(0, 0.75, 0)
			if ready_in("play"):
				check(g.level.world == "meadow" and Boot.level == "meadow", "jumping into Meadow's painting should start Meadow (%s)" % g.level.world)
				# an old 96 x 96 home (v2) moves into the middle of the big one
				var d := Saves.read(1)
				d["in_level"] = false
				d["home"] = {"v": 2, "blocks": [[60, 5, 60, Level.BRICK], [60, 4, 61, 0]], "decor": [["bench", 61, 5, 60, 0]]}
				Saves.write(1, d)
				g.go("home", 1)
				step = 3
		3:
			if ready_in("home"):
				var o := Level.HOME_OLD
				check(g.level.get_cell(60 + o.x, 5 + o.y, 60 + o.z) == Level.BRICK, "an old (v2) block should move into the middle")
				check(g.level.get_cell(60 + o.x, 4 + o.y, 61 + o.z) == Level.AIR, "an old (v2) hole should move too")
				check(g.home.decor.has(Vector3i(61, 5, 60) + o), "an old (v2) decoration should move too")
				# the baked home is exactly the freshly made one
				var p := ProjectSettings.globalize_path("user://test_home_cache.bin")
				var keep := Level.cache_path
				Level.cache_path = ""
				var a := Level.new()
				root.add_child(a)
				a.generate_home()
				check(a.save_home_cache(p), "baking the home failed")
				Level.cache_path = p
				var b := Level.new()
				root.add_child(b)
				b.generate_home()
				Level.cache_path = keep
				check(a.cells == b.cells and a.slot_of.size() == b.slot_of.size() and a.paintings.size() == b.paintings.size(), "the baked home should load the same as a fresh one")
				var k: Vector3i = a.slot_of.keys()[a.slot_of.size() / 2]
				check(b.slot_of.has(k) and b.slot_of[k].x == a.slot_of[k].x, "the baked home should draw the same blocks")
				a.queue_free()
				b.queue_free()
				DirAccess.remove_absolute(p)
				step = 4
				wait = 3
		4:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 90:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
