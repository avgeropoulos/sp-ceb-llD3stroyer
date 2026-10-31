extends SceneTree
## Plays an open world start to finish: every jump on the SKY STEPS and the TALL TOWER
## is really jumped by Rocco (real physics), Blocky digs into the SECRET CAVE, the camp
## is beaten, 40 coins are collected, all 5 stars open the gold gate, Krag Jr. is beaten
## and the flag ends the world.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/open_check.gd -- [toy] [meadow|frost|dunes]

var g
var f := 0
var step := 0
var failed := ""
var hop := 0
var hop_f := 0
var tops: Array = []


func _initialize() -> void:
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
	Look.toy = a.has("toy")
	Boot.place = "level"
	Boot.level = "meadow"
	for w in Worlds.ORDER:
		if a.has(w):
			Boot.level = w
	print("open world: ", Boot.level, " look: ", "toy" if Look.toy else "paper")
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func L():
	return g.leader()


func place_at(p: Vector3) -> void:
	L().position = p
	L().vel = Vector3.ZERO
	g.follower().position = p + Vector3(0, 0.5, 0)


## One real jump from tops[hop] to tops[hop + 1]: steer at it, hold JUMP, see where we land.
func _hop_frame() -> bool:
	var to: Vector3 = tops[hop + 1]
	var d := Vector3(to.x - L().position.x, 0, to.z - L().position.z)
	if hop_f == 0:
		place_at(tops[hop] + Vector3(0, 0.02, 0))
		g._update_leader(L(), 1.0 / 60)
	hop_f += 1
	g.cam_yaw = 0
	g.move_vec = Vector2(d.x, d.z).normalized() if d.length() > 0.25 else Vector2.ZERO
	g.jump_pressed = hop_f == 2
	g.jump_held = hop_f < 30
	g._update_leader(L(), 1.0 / 60)
	g.jump_pressed = false
	if hop_f > 100:
		# landed on that step (or hopped even further up the stairs)
		var ok: bool = L().on_floor and L().position.y > to.y - 0.1
		check(ok, "Rocco couldn't jump from %s to %s (ended at %s)" % [tops[hop], to, L().position])
		hop += 1
		hop_f = 0
		return hop >= tops.size() - 1
	return false


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "play":
				g.invincible = true
				var lv: Level = g.level
				check(lv.open and lv.sx == Level.OPEN_S, "the world should be open")
				check(lv.objectives.size() >= 7, "there should be 7+ star challenges, Mario 64 style (got %d)" % lv.objectives.size())
				check(lv.red_spots.size() == OpenPlay.RED_COINS, "there should be 8 red coins (got %d)" % lv.red_spots.size())
				for rp in lv.red_spots:
					check(not lv.solid(floori(rp.x), floori(rp.y), floori(rp.z)), "a red coin is inside a block at %s" % rp)
				check(lv.coin_spots.size() >= Level.STAR_COINS + 5, "not enough coins (%d)" % lv.coin_spots.size())
				check(not lv.solid(floori(lv.start_pos.x), floori(lv.start_pos.y + 0.3), floori(lv.start_pos.z)), "the start is inside a block")
				for o in lv.objectives:
					var p: Vector3 = o.pos
					check(not lv.solid(floori(p.x), floori(p.y), floori(p.z)), "%s star is inside a block" % o.id)
				# the castle can't be dug into
				var wall: Vector3i = lv.gate[0] + Vector3i(-3, 0, 0)
				g.hit_block(wall, 50, L())
				check(lv.get_cell(wall.x, wall.y, wall.z) == Level.STONE_BRICK, "castle walls must not break")
				g.hit_block(lv.gate[0], 50, L())
				check(lv.get_cell(lv.gate[0].x, lv.gate[0].y, lv.gate[0].z) == Level.GOLD_BLOCK, "the gate must not break")
				tops = lv.step_tops
				g.process_mode = Node.PROCESS_MODE_DISABLED   # only the test moves Rocco while jumping
				hop = 0
				hop_f = 0
				step = 1
		1:   # SKY STEPS, jump by jump
			for i in 4:
				if _hop_frame():
					print("sky steps: %d jumps OK" % (tops.size() - 1))
					tops = g.level.tower_tops
					hop = 0
					hop_f = 0
					step = 2
					break
		2:   # TALL TOWER stairs
			for i in 4:
				if _hop_frame():
					print("tower: %d steps OK" % (tops.size() - 1))
					g.process_mode = Node.PROCESS_MODE_INHERIT
					step = 3
					break
		3:   # collect the stars that are out from the start
			for id in ["sky", "tower"]:
				place_at(g.open_play.objective(id).pos - Vector3(0, 0.8, 0))
				g.open_play.step(1.0 / 60)
				check(g.open_play.got.has(id), "%s star wasn't collected" % id)
			# SECRET CAVE: Blocky digs through the rock plug
			g.do_swap()
			check(g.leader_name() == "blocky", "should be Blocky")
			var o: Dictionary = g.open_play.objective("cave")
			var cz := floori(o.pos.z) + 2        # the plug is at cz + 2 and cz + 3
			var cx := floori(o.pos.x) - 1
			for layer in [cz + 3, cz + 2]:
				place_at(Vector3(cx + 0.5, 6.01, layer + 1.5))
				L().facing = Vector3(0, 0, -1)
				for i in 12:
					if g.level.get_cell(cx, 6, layer) == Level.AIR and g.level.get_cell(cx, 7, layer) == Level.AIR:
						break
					g._mine(L())
			check(g.level.get_cell(cx, 6, cz + 2) == Level.AIR and g.level.get_cell(cx, 7, cz + 3) == Level.AIR, "Blocky should dig through the rock")
			place_at(o.pos - Vector3(0, 0.8, 0))
			g.open_play.step(1.0 / 60)
			check(g.open_play.got.has("cave"), "cave star wasn't collected")
			g.do_swap()
			g.do_swap()
			# BAD GUY CAMP
			for fo in g.foes:
				if is_instance_valid(fo) and fo.get_meta("camp", false):
					fo.hurt(99, g)
			step = 4
			f = 0
		4:
			if f == 60:
				check(g.open_play.stars.has("camp"), "the camp star should appear when the camp is beaten")
				place_at(g.open_play.objective("camp").pos - Vector3(0, 0.8, 0))
				g.open_play.step(1.0 / 60)
				check(g.open_play.got.has("camp"), "camp star wasn't collected")
				check(not g.gate_open, "4 stars shouldn't open the gate")
				# COIN HUNT
				for c in g.coin_nodes.duplicate():
					if g.coins_taken.size() >= Level.STAR_COINS:
						break
					place_at(c.position - Vector3(0, 0.6, 0))
					g._update_coins(1.0 / 60)
				g.open_play.step(1.0 / 60)
				check(g.open_play.stars.has("coins"), "the coin star should appear after %d coins" % Level.STAR_COINS)
				place_at(g.open_play.objective("coins").pos - Vector3(0, 0.8, 0))
				g.open_play.step(1.0 / 60)
				check(g.open_play.count() == 5 and g.gate_open, "5 stars should open the gate")
				var gc: Vector3i = g.level.gate[0]
				check(g.level.get_cell(gc.x, gc.y, gc.z) == Level.AIR, "the gate should be gone")
				# the red coins on the little pillars must be reachable: really jump up onto one
				tops = []
				for rp in g.level.red_spots:
					var top := Vector3(rp.x, rp.y - 0.6, rp.z)
					var gy: int = g.level.ground_at(floori(rp.x), floori(rp.z))
					if top.y > gy + 1.5 and top.y < gy + 2.5:
						for dd in [Vector2i(-2, 0), Vector2i(2, 0), Vector2i(0, -2), Vector2i(0, 2)]:
							var bx: int = floori(rp.x) + dd.x
							var bz: int = floori(rp.z) + dd.y
							var by: int = g.level.ground_at(bx, bz)
							if by >= gy and by <= gy + 1 and not g.level.solid(bx, by, bz) and not g.level.solid(bx, by + 1, bz) \
									and not g.level.solid(bx, by + 2, bz):
								tops = [Vector3(bx + 0.5, by, bz + 0.5), top]
								break
					if tops.size() == 2:
						break
				check(tops.size() == 2, "couldn't find a red coin pillar to jump onto")
				hop = 0
				hop_f = 0
				g.process_mode = Node.PROCESS_MODE_DISABLED
				step = 8
				f = 0
		8:   # jump onto a red coin pillar for real
			if tops.size() < 2:
				step = 9
				f = 0
			else:
				for i in 4:
					if _hop_frame():
						print("red coin pillar: jumped on OK")
						g.process_mode = Node.PROCESS_MODE_INHERIT
						step = 9
						f = 0
						break
		9:   # RED COINS, BIG GRUMBLER, LOST DOG
			if f == 2:
				for c in g.open_play.reds.duplicate():
					place_at(c.position - Vector3(0, 0.4, 0))
					g.open_play.step(1.0 / 60)
				check(g.open_play.red_count == OpenPlay.RED_COINS, "all 8 red coins should be picked up (%d)" % g.open_play.red_count)
				check(g.open_play.stars.has("reds"), "the red coin star should appear")
				var bf: Foe = g.open_play.boss_foe()
				if not g.open_play.objective("boss").is_empty():
					check(bf != null, "the BIG GRUMBLER should be there")
					if bf:
						bf.hurt(99, g)
				# LOST DOG: find Dennis
				place_at(g.level.dog_spot + Vector3(1.5, 0.01, 0))
				g.open_play.step(1.0 / 60)
				check(g.open_play.dog_following, "Dennis should follow you when you find him")
			if f > 2 and f < 700 and not (g.open_play.stars.has("dog") or g.open_play.got.has("dog")):   # walk him home (the hero jumps ahead, Dennis catches up)
				var home: Vector3 = g.level.dog_home
				var d: Vector3 = g.open_play.dennis.position
				var to := Vector3(home.x - d.x, 0, home.z - d.z)
				if to.length() > 2:
					place_at(Vector3(d.x, 0, d.z) + to.normalized() * minf(to.length(), 6.0) + Vector3(0, g.level.ground_below(d.x + to.normalized().x * minf(to.length(), 6.0), d.z + to.normalized().z * minf(to.length(), 6.0), 30) + 0.01, 0))
			if f > 2 and (f == 700 or g.open_play.stars.has("dog")):
				f = 1000
				check(g.open_play.stars.has("dog") or g.open_play.got.has("dog"), "bringing Dennis home should make a star (Dennis at %s, home %s)" % [g.open_play.dennis.position, g.level.dog_home])
				for id in ["reds", "boss", "dog"]:
					if g.open_play.stars.has(id):
						place_at(g.open_play.stars[id].position - Vector3(0, 0.8, 0))
						g.open_play.step(1.0 / 60)
				if g.open_play.ids().has("painting"):   # the PAINTING STAR (tests/painting_check.gd jumps in for it)
					g.open_play.got["painting"] = true
					if g.open_play.current == "painting":
						g.open_play.pick(g.open_play.next_open("painting"), false)
				check(g.open_play.count() == g.level.objectives.size(), "every star should be found (%d / %d)" % [g.open_play.count(), g.level.objectives.size()])
				check(g.open_play.current == "", "no quest left: the castle is next")
				# into the castle
				var ar: Rect2 = g.level.arena
				place_at(Vector3(ar.get_center().x, g.level.arena_y + 0.01, ar.end.y - 3))
				step = 5
				f = 0
		5:
			if g.boss_on and is_instance_valid(g.boss) and g.boss.dying < 0:
				g.boss.safe_t = 0
				g.boss.hurt(3, g)
			if g.boss_done:
				step = 6
				f = 0
			elif f > 60 * 20:
				check(false, "Krag Jr. never started / was never beaten (boss_on %s)" % g.boss_on)
				step = 7
		6:
			if f == 90:
				check(g.open_play.flag != null, "the flag should come down")
				place_at(g.level.flag_pos)
			if g.state == "clear":
				print("WORLD CLEAR: %d coins, %d bad guys" % [g.coins, g.beaten])
				step = 7
			elif f > 400:
				check(false, "touching the flag didn't finish the world")
				step = 7
		7:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 120 and step < 7:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
