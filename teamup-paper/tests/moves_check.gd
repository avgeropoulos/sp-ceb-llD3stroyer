extends SceneTree
## Rocco's Mario-64 moves on flat ground in Meadow Mine, with real physics:
## long jump distance, triple jump heights, backflip, side flip, wall kick, dive + belly
## slide, ground pound. Run: godot --headless --fixed-fps 60 --path . --script res://tests/moves_check.gd [-- toy]

var g
var f := 0
var failed := ""
var done := false
var base := Vector3.ZERO


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = why
	print(("  ok   " if ok else "  FAIL ") + why)


func R() -> Hero:
	return g.heroes.rocco


## One physics step of the hero with these buttons (the game itself is paused).
func tick(stick: Vector2, jump := false, held := false, action := false, sprint := false) -> void:
	g.move_vec = stick
	g.jump_pressed = jump
	g.jump_held = held or jump
	g.action_pressed = action
	g.sprinting = sprint
	g._update_leader(R(), 1.0 / 60)
	g.jump_pressed = false
	g.action_pressed = false


func ticks(n: int, stick: Vector2, held := false, sprint := false) -> void:
	for i in n:
		tick(stick, false, held, false, sprint)


## Flat, empty ground: clear a big box of blocks above y = 6 and fill the floor.
func flatten(c: Vector3i, r: int) -> void:
	var lv: Level = g.level
	for x in range(c.x - r, c.x + r):
		for z in range(c.z - r, c.z + r):
			for y in range(1, 6):
				lv.cells[lv.idx(x, y, z)] = Level.GRASS if y == 5 else Level.DIRT
			for y in range(6, 26):
				lv.cells[lv.idx(x, y, z)] = Level.AIR
	lv.rebuild()


func reset(p: Vector3) -> void:
	var h := R()
	h.position = p
	h.vel = Vector3.ZERO
	h.trick = ""
	h.slam = ""
	h.chain = 0
	h.spin_t = 0
	h.facing = Vector3.RIGHT
	ticks(4, Vector2.ZERO)


func land(max_frames := 200) -> float:
	var top := R().position.y
	for i in max_frames:
		tick(Vector2.ZERO)
		top = maxf(top, R().position.y)
		if R().on_floor:
			break
	return top


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if done or not is_instance_valid(g) or not g.is_inside_tree() or g.state != "play":
		return false
	done = true
	g.process_mode = Node.PROCESS_MODE_DISABLED   # only this test moves Rocco
	g.invincible = true
	g.cam_yaw = 0.0
	check(g.leader_name() == "rocco", "Rocco should be out")
	base = Vector3(48.5, 6.0, 48.5)
	flatten(Vector3i(48, 0, 48), 22)
	for fo in g.foes:
		if is_instance_valid(fo):
			fo.position += Vector3(0, -50, 0)   # out of the way
	var h := R()

	# ACTION label: standing still = BACKFLIP
	reset(base)
	check(g.action_label() == "BACKFLIP", "standing still, ACTION should say BACKFLIP (got %s)" % g.action_label())

	# normal jump height (for comparing)
	reset(base)
	tick(Vector2.ZERO, true, true)
	var normal_top := land() - base.y
	print("  normal jump height %.2f" % normal_top)

	# LONG JUMP from a sprint
	reset(base + Vector3(-14, 0, 0))
	ticks(40, Vector2(1, 0), false, true)
	check(g.action_label() == "LONG JUMP", "running, ACTION should say LONG JUMP")
	var x0 := h.position.x
	tick(Vector2(1, 0), false, false, true, true)
	check(h.trick == "long", "ACTION while running should LONG JUMP")
	var lt := 0
	while lt < 200 and not (h.on_floor and lt > 2):
		tick(Vector2(1, 0), false, false, false, true)
		lt += 1
	var dist := h.position.x - x0
	print("  long jump: %.1f blocks in %.2f s" % [dist, lt / 60.0])
	check(dist >= 8.0, "a sprint LONG JUMP should go at least 8 blocks (went %.1f)" % dist)

	# TRIPLE JUMP: each jump right as you land, running
	reset(base + Vector3(-18, 0, 0))
	ticks(30, Vector2(1, 0))
	var tops := []
	for k in 3:
		var y0 := h.position.y
		tick(Vector2(1, 0), true, true)
		var top := y0
		for i in 120:
			tick(Vector2(1, 0), false, true)
			top = maxf(top, h.position.y)
			if h.on_floor:
				break
		tops.append(top - y0)
		if k == 1:
			check(h.trick == "" , "landing should end the move")
	print("  triple jump heights %s" % str(tops))
	check(tops[1] > tops[0] + 0.3 and tops[2] > tops[1] + 0.3, "each jump of the TRIPLE JUMP should go higher %s" % str(tops))

	# BACKFLIP
	reset(base)
	tick(Vector2.ZERO, false, false, true)
	check(h.trick == "backflip" and h.spin_t > 0 and h.flip == "back", "ACTION standing still should BACKFLIP")
	var bf := land() - base.y
	print("  backflip height %.2f" % bf)
	check(bf > normal_top + 1.5, "BACKFLIP should go much higher than a jump (%.2f vs %.2f)" % [bf, normal_top])

	# SIDE FLIP: run right, pull the stick left, jump
	reset(base + Vector3(-6, 0, 0))
	ticks(30, Vector2(1, 0))
	ticks(3, Vector2(-1, 0))
	tick(Vector2(-1, 0), true, true)
	check(h.trick == "sideflip" and h.flip == "side", "jumping while turning back should SIDE FLIP (trick %s)" % h.trick)
	check(h.facing.x < -0.5 and h.vel.x < 0, "the SIDE FLIP should turn Rocco around")
	var sf := land() - base.y
	check(sf > normal_top + 0.8, "SIDE FLIP should be high (%.2f)" % sf)

	# WALL KICK between two walls
	var lv: Level = g.level
	var wx := int(base.x) + 3
	for y in range(6, 20):
		for z in range(int(base.z) - 3, int(base.z) + 4):
			lv.set_cell(wx, y, z, Level.STONE)
			lv.set_cell(wx - 5, y, z, Level.STONE)
	reset(Vector3(wx - 1.5, 6.0, base.z))
	tick(Vector2(1, 0), true, true)
	var hit_wall := false
	for i in 60:
		tick(Vector2(1, 0), false, true)
		if h.wall_t > 0:
			hit_wall = true
			break
	check(hit_wall, "jumping into a wall should let you WALL KICK")
	var y_kick := h.position.y
	tick(Vector2(1, 0), true, true)
	check(h.trick == "wallkick" and h.vel.x < 0, "JUMP against the wall should kick off it")
	var top_k := y_kick
	var second := false
	for i in 90:
		tick(Vector2(-1, 0), false, true)
		top_k = maxf(top_k, h.position.y)
		if h.wall_t > 0 and h.vel.y < 4:
			tick(Vector2(-1, 0), true, true)   # and off the other wall
			second = h.trick == "wallkick" and h.vel.x > 0
			break
	check(second, "should WALL KICK again off the other wall")
	for i in 40:
		tick(Vector2(1, 0), false, true)
		top_k = maxf(top_k, h.position.y)
	print("  wall kicks reached %.1f (from %.1f)" % [top_k - base.y, y_kick - base.y])
	check(top_k > y_kick + 3.5, "two WALL KICKS should climb up (%.1f)" % (top_k - y_kick))
	for y in range(6, 20):
		for z in range(int(base.z) - 3, int(base.z) + 4):
			lv.set_cell(wx, y, z, Level.AIR)
			lv.set_cell(wx - 5, y, z, Level.AIR)

	# DIVE then belly slide, roll out
	reset(base + Vector3(-12, 0, 0))
	ticks(30, Vector2(1, 0), false, true)
	tick(Vector2(1, 0), true, true, false, true)
	ticks(6, Vector2(1, 0), true, true)
	check(g.action_label() == "DIVE", "in the air pushing forward, ACTION should say DIVE (got %s)" % g.action_label())
	tick(Vector2(1, 0), false, true, true, true)
	check(h.trick == "dive", "ACTION in the air while running should DIVE")
	var slid := false
	for i in 120:
		tick(Vector2(1, 0), false, false, false, true)
		if h.trick == "slide" and h.on_floor:
			slid = true
			break
	check(slid, "a DIVE should land in a belly slide")
	tick(Vector2(1, 0), true, true)
	check(h.trick == "" and h.vel.y > 5, "JUMP during the slide should roll out")
	land()

	# GROUND POUND still smashes a block
	reset(base)
	var under := Vector3i(int(base.x), 5, int(base.z))
	lv.set_cell(under.x, under.y, under.z, Level.BRICK)
	tick(Vector2.ZERO, true, true)
	ticks(10, Vector2.ZERO, true)
	check(g.action_label() == "POUND", "in the air, stick still: ACTION should say POUND")
	tick(Vector2.ZERO, false, true, true)
	check(h.slam == "wind", "ACTION in the air (stick still) should GROUND POUND")
	land(120)
	check(lv.get_cell(under.x, under.y, under.z) == Level.AIR, "GROUND POUND should smash the brick")

	# Blocky didn't change
	g.do_swap()
	check(g.action_label() == "MINE", "Blocky's ACTION is still MINE")

	print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
	quit(1 if failed != "" else 0)
	return true
