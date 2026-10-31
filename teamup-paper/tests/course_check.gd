extends SceneTree
## The Mario 64-style course of an open world, played with real physics:
## * Rocco WALKS up the spiral mountain path to the summit (no jumping: the path steps up
##   one block at a time), or climbs the pyramid steps in Sunset Dunes;
## * the CANNON: walk in, FIRE, land on the floating island;
## * Frosty Peaks: the ice SLIDE whooshes you down fast; Sunset Dunes: quicksand slows you.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/course_check.gd -- [toy] [world]

var g
var f := 0
var step := 0
var failed := ""
var t := 0.0
var s_aim := 0.0
var best_y := 0.0
var p0 := Vector3.ZERO
var s0 := 0.0
const DT := 1.0 / 60.0


func _initialize() -> void:
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Look.toy = a.has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Boot.place = "level"
	Boot.level = "meadow"
	for w in Worlds.ORDER:
		if a.has(w):
			Boot.level = w
	print("course: ", Boot.level, " look: ", "toy" if Look.toy else "paper")
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func L():
	return g.leader()


func place_at(p: Vector3) -> void:
	L().position = p
	L().vel = Vector3.ZERO
	L().on_floor = false
	g.follower().position = p + Vector3(0, 0.5, 0)


## One frame of real hero physics (the game itself is paused so only the test drives).
func tick(mv: Vector2, jump := false) -> void:
	g.cam_yaw = 0
	g.move_vec = mv
	g.jump_pressed = jump
	g.jump_held = jump
	g._update_leader(L(), DT)
	g.open_play.step(DT)
	g.jump_pressed = false
	g.action_pressed = false


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	var lv: Level = g.level if is_instance_valid(g) else null
	match step:
		0:
			if g.state == "play":
				g.invincible = true
				check(lv.summit_spot != Vector3.ZERO, "the mountain should have a summit")
				var sp: Vector3 = lv.summit_spot
				for dx in range(-4, 5):   # a flat 9x9 top with room to stand
					for dz in range(-4, 5):
						var x := floori(sp.x) + dx
						var z := floori(sp.z) + dz
						check(lv.ground_at(x, z) == int(sp.y), "the summit should be flat (%d,%d is %d, not %d)" % [x, z, lv.ground_at(x, z), int(sp.y)])
				check(not lv.solid(floori(sp.x), floori(sp.y), floori(sp.z)) and not lv.solid(floori(sp.x), floori(sp.y) + 1, floori(sp.z)), "the summit spot is blocked")
				check(sp.y >= 17, "the mountain should be big (summit at %d)" % sp.y)
				if lv.course().get("style", "cone") != "pyramid":
					# the path climbs one block at a time
					var last := -1
					for k in 400:
						var pt: Vector3 = lv.ramp_point(k / 399.0)
						var cell := Vector2i(floori(pt.x), floori(pt.z))
						var gy := lv.ground_at(cell.x, cell.y)
						if lv._c_bridge.has(cell):   # walking on the bridge planks
							gy = int(lv._c_bridge[cell]) + 1
						if last >= 0:
							check(absi(gy - last) <= 1, "the path jumps %d blocks at s=%.2f" % [gy - last, k / 399.0])
						last = gy
				g.process_mode = Node.PROCESS_MODE_DISABLED
				if lv.course().get("style", "cone") == "pyramid":
					place_at(Vector3(lv.mountain.x + 3.5, 6.02, lv.mountain.y + 24.5))
				else:
					var foot: Vector3 = lv.ramp_point(0.0)
					place_at(Vector3(foot.x, lv.ground_at(floori(foot.x), floori(foot.z)) + 0.05, foot.z + 3.0))
				s_aim = 0.0
				best_y = 0
				t = 0
				step = 1
		1:   # WALK up to the summit (no jumping)
			for i in 6:
				t += DT
				var to: Vector3
				if lv.course().get("style", "cone") == "pyramid":
					to = lv.summit_spot
				else:
					# follow the middle of the path a little ahead of where we are
					var best := 0.0
					var bd := 1e9
					for k in 101:
						var sv := clampf(s_aim - 0.05 + k * 0.002, 0.0, 1.0)
						var pt: Vector3 = lv.ramp_point(sv)
						var d := Vector2(pt.x - L().position.x, pt.z - L().position.z).length()
						if d < bd:
							bd = d
							best = sv
					s_aim = maxf(s_aim, best)
					to = lv.ramp_point(minf(s_aim + 0.035, 1.0)) if s_aim < 0.985 else lv.summit_spot
				var d := Vector2(to.x - L().position.x, to.z - L().position.z)
				tick(d.normalized() * 0.7 if d.length() > 0.3 else Vector2.ZERO)
				best_y = maxf(best_y, L().position.y)
				if L().position.y < 2:
					check(false, "fell off the path at %s (s=%.2f)" % [L().position, s_aim])
					break
			var at_top: bool = L().on_floor and absf(L().position.y - lv.summit_spot.y) < 0.2 \
				and Vector2(L().position.x - lv.summit_spot.x, L().position.z - lv.summit_spot.z).length() < 3.5
			if at_top:
				print("walked to the summit in %.1f s" % t)
				step = 2
			elif t > 90:
				check(false, "couldn't walk to the summit (got to %s, best height %.1f of %d, s=%.2f)" % [L().position, best_y, lv.summit_spot.y, s_aim])
				step = 2
		2:   # the CANNON
			if lv.cannon_spot == Vector3.ZERO:
				check(false, "there should be a cannon")
				step = 9
			else:
				place_at(lv.cannon_spot + Vector3(0, 0.05, 0))
				for i in 10:
					tick(Vector2.ZERO)
				check(g.open_play.course.inside, "walking into the cannon should get in")
				check(not L().visible, "you hide inside the cannon")
				tick(Vector2.ZERO, true)   # JUMP fires
				check(not g.open_play.course.inside and L().visible, "JUMP should fire the cannon")
				t = 0
				step = 3
		3:
			for i in 6:
				t += DT
				tick(Vector2.ZERO)
			var isl: Vector3 = lv.island_spot
			if t > 1.0 and L().on_floor:
				var ok: bool = absf(L().position.y - isl.y) < 0.2 and Vector2(L().position.x - isl.x, L().position.z - isl.z).length() < 4.5
				check(ok, "the cannon should land on the floating island %s (landed at %s)" % [isl, L().position])
				if ok:
					print("cannon: landed on the floating island")
				step = 4
			elif t > 6:
				check(false, "the cannon flight never landed")
				step = 4
		4:   # the SLIDE (Frosty Peaks) or QUICKSAND (Sunset Dunes)
			if lv.slide:
				var top: Vector3 = lv.ramp_point(0.85)
				place_at(Vector3(top.x, lv.ground_at(floori(top.x), floori(top.z)) + 0.05, top.z))
				check(lv.get_cell(floori(top.x), lv.ground_at(floori(top.x), floori(top.z)) - 1, floori(top.z)) == Level.ICE, "the slide lane should be ice")
				s0 = 0.85
				p0 = L().position
				best_y = 0.0
				t = 0
				step = 5
			elif lv.course().get("quicksand", false):
				var mud := Vector3.ZERO
				for x in lv.sx:
					for z in lv.sz:
						var gy := lv.ground_at(x, z)
						if gy > 1 and lv.get_cell(x, gy - 1, z) == Level.MUD and mud == Vector3.ZERO:
							mud = Vector3(x + 0.5, gy + 0.05, z + 0.5)
				check(mud != Vector3.ZERO, "Sunset Dunes should have quicksand")
				if mud != Vector3.ZERO:
					place_at(mud)
					for i in 30:
						tick(Vector2(1, 0))
						L().position = Vector3(mud.x, L().position.y, mud.z)   # stay on the sand
					var sp := Vector2(L().vel.x, L().vel.z).length()
					check(sp <= L().speed * 0.55, "quicksand should slow you down (speed %.1f)" % sp)
				step = 9
			else:
				step = 9
		5:   # let go of the stick on the slide: whoosh!
			for i in 6:
				t += DT
				var before: Vector3 = L().position
				tick(Vector2.ZERO)
				best_y += Vector2(L().position.x - before.x, L().position.z - before.z).length()   # distance slid
			if t > 2.0:
				var spd := best_y / t
				check(spd > 8.0, "the slide should be fast (%.1f blocks/s)" % spd)
				check(L().position.y < p0.y - 2, "the slide should take you down (from %.1f to %.1f)" % [p0.y, L().position.y])
				print("slide: %.1f blocks/s, down %.1f blocks" % [spd, p0.y - L().position.y])
				step = 9
		9:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "" and step != 9:
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 200:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
