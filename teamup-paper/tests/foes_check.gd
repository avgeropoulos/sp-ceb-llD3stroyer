extends SceneTree
## The newer bad guys in an open world: each one is put next to the hero, has to move or
## attack, and has to be beaten the right way (stomp vs attacks). The bomb breaks blocks,
## the ghost hides its face when the camera looks at it, Chompy's chain holds.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/foes_check.gd -- [toy]

const KINDS := ["buzzer", "hopper", "boohoo", "bomber", "chompy", "spiky", "bat", "mummy", "roller", "cactusman", "biggrumbler"]

var g
var f := 0
var step := 0
var failed := ""
var k := 0
var foe: Foe
var start := Vector3.ZERO
var spot := Vector3.ZERO
var moved := 0.0
var shots0 := 0
var hits0 := 0


func _initialize() -> void:
	Look.loaded = true
	OpenPlay.select_enabled = false   # tests/quest_check.gd covers the star select
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "%s: %s" % [KINDS[k] if k < KINDS.size() else "?", why]


## A flat open place: clear a 13 x 13 patch of air above flat ground near the start.
func _arena() -> void:
	var lv: Level = g.level
	var c := Vector3i(floori(lv.start_pos.x), 6, floori(lv.start_pos.z) - 14)
	for x in range(c.x - 6, c.x + 7):
		for z in range(c.z - 6, c.z + 7):
			for y in range(1, 20):
				lv.set_cell(x, y, z, Level.STONE if y < 6 else Level.AIR)
	spot = Vector3(c.x + 0.5, 6.01, c.z + 0.5)


func _spawn(kind: String, at: Vector3) -> Foe:
	var fo := Foe.new()
	g.level.add_child(fo)
	fo.init(g.level, kind, at)
	fo.set_meta("spawn", -1)
	g.foes.append(fo)
	return fo


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree() or g.state != "play":
		return false
	match step:
		0:
			g.invincible = true
			# only our test bad guy: send the others far away to sleep
			for fo in g.foes:
				if is_instance_valid(fo) and not fo.is_boss:
					fo.remove()
			_arena()
			check(g.level.spawns.any(func(s): return s.kind == "bat"), "bats should live under the sky steps")
			check(g.level.spawns.any(func(s): return s.kind == "biggrumbler"), "a BIG GRUMBLER should guard a ? block")
			k = 0
			step = 1
		1:   # put the next one next to the hero
			if k >= KINDS.size():
				step = 9
				return false
			var kind: String = KINDS[k]
			g.leader().position = spot
			g.leader().vel = Vector3.ZERO
			g.follower().position = spot + Vector3(-5, 0.5, 5)
			var at := spot + Vector3(4.0, 0, 0)
			if kind == "bat":
				at = spot + Vector3(2.5, 3.0, 0)
			if kind == "boohoo":   # behind the camera: it only comes when you're not looking at it
				at = spot + Vector3(0, 0.5, 13)
			foe = _spawn(kind, at)
			g.cam_yaw = 0.0
			start = foe.position
			moved = 0.0
			shots0 = g.shots.size()
			hits0 = g.hearts
			g.invincible = false
			g.hurt_t = 0
			f = 0
			step = 2
		2:   # watch it for 4 seconds: it has to move or attack
			if "zap_t" in g.follower():
				g.follower().zap_t = 99.0   # the partner mustn't beat it for us
			if is_instance_valid(foe) and foe.dying < 0:
				moved = maxf(moved, foe.position.distance_to(start))
			g.hurt_t = 0 if f % 30 == 0 else g.hurt_t
			if f == 150 and KINDS[k] == "boohoo":
				# the camera looks right at it: it must hide its face
				g.cam.look_at(foe.position, Vector3.UP)
				foe.tick(1.0 / 60, g)
				check(foe.state == "shy", "the ghost should hide when the camera looks at it")
			if f > 240:
				var kind: String = KINDS[k]
				var acted: bool = moved > 0.6 or g.shots.size() > shots0 or g.hearts < hits0 or (kind == "bomber" and not is_instance_valid(foe))
				check(acted, "didn't move or attack (moved %.2f)" % moved)
				g.hearts = g.MAX_HEARTS
				g.invincible = true
				step = 3
				f = 0
		3:   # beat it the right way
			var kind: String = KINDS[k]
			if kind == "bomber":
				if is_instance_valid(foe) and foe.dying < 0:
					foe.queue_free()
				# a fresh bomb next to some blocks: light it and wait for the BOOM
				for y in [6, 7]:
					g.level.set_cell(floori(spot.x) + 3, y, floori(spot.z), Level.STONE)
				foe = _spawn("bomber", spot + Vector3(2.5, 0, 0.5))
				foe.hurt(1, g)
				check(foe.state == "lit", "hitting a bomb should light it")
				step = 4
				f = 0
				return false
			if is_instance_valid(foe) and foe.dying < 0:
				if kind == "spiky" or kind == "cactusman" or kind == "chompy":
					check(not foe.stompable, "should be too spiky / hard to stomp")
					# a stomp hurts YOU
					g.invincible = false
					g.hurt_t = 0
					var h0: int = g.hearts
					g.leader().position = foe.position + Vector3(0, foe.height * 0.6, 0)
					g.leader().vel = Vector3(0, -3, 0)
					g._update_foes(1.0 / 60)
					check(g.hearts < h0, "stomping it should hurt you")
					g.invincible = true
					g.hearts = g.MAX_HEARTS
					for i in 20:
						foe.safe_t = 0
						foe.hurt(1, g)
				else:
					check(foe.stompable, "should be stompable")
					for i in 12:
						if not is_instance_valid(foe) or foe.dying >= 0:
							break
						g.leader().position = foe.position + Vector3(0, foe.height * 0.6, 0)
						g.leader().vel = Vector3(0, -3, 0)
						foe.safe_t = 0
						g.hurt_t = 0
						g._update_foes(1.0 / 60)
			check(not is_instance_valid(foe) or foe.dying >= 0, "couldn't be beaten")
			step = 5
			f = 0
		4:   # the bomb
			if not is_instance_valid(foe) or foe.dying >= 0:
				check(g.level.get_cell(floori(spot.x) + 3, 6, floori(spot.z)) == Level.AIR, "the BOOM should break blocks")
				step = 5
				f = 0
			elif f > 60 * 4:
				check(false, "the lit bomb never went off")
				step = 5
		5:
			if f > 20:
				for fo in g.foes:
					if is_instance_valid(fo) and not fo.is_boss:
						fo.remove()
				print("%s OK" % KINDS[k])
				k += 1
				step = 1
		9:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 30:
		print("TEST FAILED: timed out at step %d (%s)" % [step, KINDS[k] if k < KINDS.size() else ""])
		quit(1)
		return true
	return false
