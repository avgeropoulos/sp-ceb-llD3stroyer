extends SceneTree
## GRAB / THROW (grab.gd), KING BOMBO on the summit and the KING KRAG tail swing.
## The game's own loop is paused while checking, and the test steps the hero, the grab,
## the bad guys and the stars itself, so every check is the same every run.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/grab_check.gd [-- toy]

const DT := 1.0 / 60

var g
var step := 0
var f := 0
var failed := ""


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]
		print("  FAIL: " + why)


func L() -> Hero:
	return g.leader()


func frames(n: int) -> void:
	for i in n:
		g._update_leader(L(), DT)
		g.grab.step(DT)
		g._update_foes(DT)
		g._update_shots(DT)
		g.open_play.step(DT)
		g.jump_pressed = false
		g.action_pressed = false


func spawn(kind: String, p: Vector3) -> Foe:
	var fo := Foe.new()
	g.level.add_child(fo)
	fo.init(g.level, kind, p)
	fo.set_meta("spawn", -1)
	fo.set_meta("camp", false)
	g.foes.append(fo)
	return fo


## Flat open ground near the start, away from everyone else.
func clear_spot() -> Vector3:
	var lv: Level = g.level
	var p: Vector3 = lv.start_pos
	for fo in g.foes:   # make room: everybody else goes to sleep far away
		if is_instance_valid(fo) and not fo.is_boss and fo.kind != "kingbombo" and fo.position.distance_to(p) < 14:
			fo.remove()
	return p


func place(p: Vector3, face: Vector3) -> void:
	L().position = p
	L().vel = Vector3.ZERO
	L().facing = face.normalized()
	g.follower().position = p + Vector3(-6, 0.5, -6)
	for i in 2:   # land on the ground (only the hero moves)
		g._update_leader(L(), DT)
	L().facing = face.normalized()


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "play" and g.level.world == "meadow" and g.grab != null:
				g.invincible = true
				g.process_mode = Node.PROCESS_MODE_DISABLED
				check(L().who == "rocco", "Rocco should be out")
				_throw_things()
				_moves_still_work()
				_king_bombo()
				g.process_mode = Node.PROCESS_MODE_INHERIT
				Boot.level = "cloud"
				g.go("level", 0)
				step = 1
				f = 0
		1:
			if g.state == "play" and g.level.world == "cloud" and g.grab != null and f > 3:
				g.invincible = true
				g.process_mode = Node.PROCESS_MODE_DISABLED
				_king_krag()
				step = 2
		2:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if f > 60 * 90:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false


# ------------------------------------------------------------------ grab + throw
func _throw_things() -> void:
	step = 10
	var p := clear_spot()
	place(p, Vector3(0, 0, -1))
	frames(10)
	var a: Foe = spawn("grumbler", L().position + Vector3(0, 0.05, -1.1))
	var b: Foe = spawn("grumbler", L().position + Vector3(0, 0.05, -5.0))
	a.state_t = 0
	L().facing = Vector3(0, 0, -1)
	check(g.grab.label() == "GRAB", "ACTION should say GRAB next to a Grumbler (got %s)" % g.grab.label())
	check(g.action_label() == "GRAB", "the ACTION button should say GRAB (got %s)" % g.action_label())
	g._action(L())
	check(g.grab.holding == a and L().carry, "ACTION should pick the Grumbler up")
	frames(20)
	check(a.position.y > L().position.y + 1.0, "the Grumbler should be over Rocco's head")
	check(g.grab.label() == "THROW", "ACTION should say THROW while carrying")
	b.position = L().position + Vector3(0, 0.05, -4.0)
	b.vel = Vector3.ZERO
	L().facing = Vector3(0, 0, -1)
	g._action(L())   # THROW
	check(g.grab.holding == null and not L().carry, "ACTION should throw it")
	for i in 90:
		frames(1)
	check(not is_instance_valid(b) or b.dying >= 0 or b.hp < b.max_hp, "a thrown Grumbler should knock out the one it hits")
	# a walking bomb: grab it, throw it, it blows up where it lands
	step = 11
	var bomb: Foe = spawn("bomber", L().position + Vector3(0, 0.05, -1.1))
	L().facing = Vector3(0, 0, -1)
	g._action(L())
	check(g.grab.holding == bomb, "ACTION should pick the walking bomb up")
	frames(10)
	L().facing = Vector3(1, 0, 0)
	g._action(L())
	frames(90)
	check(not is_instance_valid(bomb) or bomb.dying >= 0, "a thrown bomb should blow up when it lands")


func _moves_still_work() -> void:
	step = 12
	var p := clear_spot()
	place(p + Vector3(3, 0, 3), Vector3(1, 0, 0))
	frames(20)
	check(g.grab.label() == "", "nothing to grab here (got %s)" % g.grab.label())
	check(g.action_label() == "BACKFLIP", "standing still, ACTION should still be Rocco's BACKFLIP (got %s)" % g.action_label())
	g._action(L())
	check(L().trick == "backflip" and L().vel.y > 10, "Rocco's BACKFLIP should still happen when there's nothing to grab")
	frames(120)


# ------------------------------------------------------------------ KING BOMBO
func _king_bombo() -> void:
	step = 20
	var lv: Level = g.level
	check(lv.summit_spot != Vector3.ZERO, "the world should have a summit")
	check(not g.open_play.objective("bombo").is_empty(), "KING BOMBO should be a star quest")
	var kb: Foe = g.open_play.bombo_foe()
	check(kb != null, "KING BOMBO should be on the summit")
	if kb == null:
		return
	var top: Vector3 = lv.summit_spot
	check(kb.position.distance_to(top) < 3.0, "he should start on the summit")
	# in FRONT of him: he grabs you and throws you
	kb.position = top + Vector3(0, 0.05, 0)
	kb.facing = Vector3(0, 0, 1)
	kb.heading = kb.facing
	place(top + Vector3(0, 0.05, 1.6), Vector3(0, 0, -1))
	check(g.grab._grab_target(L()) == null, "you can't grab him from the FRONT")
	kb.state = "walk"
	kb.state_t = 0
	var grabbed_you := false
	for i in 120:
		frames(1)
		if g.grab.held_by == kb:
			grabbed_you = true
			break
	check(grabbed_you, "KING BOMBO should grab Rocco when Rocco is in front of him (state %s)" % kb.state)
	frames(90)
	check(g.grab.held_by == null and kb.state == "walk", "then he throws you")
	# from BEHIND: 3 throws onto the summit
	for n in 3:
		step = 21 + n
		g.hurt_t = 0
		kb.position = top + Vector3(0, 0.05, 0)
		kb.vel = Vector3.ZERO
		kb.facing = Vector3(0, 0, 1)
		kb.heading = kb.facing
		if kb.state != "dizzy":
			kb.state = "walk"
		place(top + Vector3(0, 0.05, -1.7), Vector3(0, 0, 1))   # right behind him
		check(g.grab.label() == "GRAB", "behind him, ACTION should say GRAB (got %s)" % g.grab.label())
		g._action(L())
		check(g.grab.holding == kb, "ACTION behind him should pick KING BOMBO up")
		frames(15)
		L().position = top + Vector3(0, 0.05, -2.2)
		L().facing = Vector3(0, 0, 1)
		var hp0: int = kb.hp
		g._action(L())   # throw him down onto the summit
		frames(80)
		check(kb.hp == hp0 - 1 or kb.dying >= 0, "a throw onto the summit should hurt him (hp %d -> %d, at %s)" % [hp0, kb.hp, kb.position])
	frames(40)
	check(kb.dying >= 0 or not is_instance_valid(kb), "3 throws should beat KING BOMBO")
	check(g.open_play.stars.has("bombo"), "a star should appear when KING BOMBO is beaten")


# ------------------------------------------------------------------ KING KRAG's tail
func _king_krag() -> void:
	step = 30
	var b: Foe = g.boss
	check(b != null and b.kind == "kingkrag", "Cloud Castle's boss should be KING KRAG")
	if b == null:
		return
	check(g.grab.bombs.size() >= 8, "KING KRAG's arena should have spike bombs round the edge (%d)" % g.grab.bombs.size())
	var ar: Rect2 = g.level.arena
	var y := float(g.level.arena_y) + 0.05
	g.gate_open = true
	g.start_boss(b)
	var hits := 0
	for n in 3:
		step = 31 + n
		# pick a bomb that's ready, stand so it's straight out from where we'll swing him
		var bi := -1
		for i in g.grab.bombs.size():
			if g.grab.bombs[i].cool <= 0:
				bi = i
				break
		check(bi >= 0, "a spike bomb should be ready")
		if bi < 0:
			return
		var bp: Vector3 = g.grab.bombs[bi].pos
		var mid := Vector3(ar.get_center().x + 0.5, y, ar.get_center().y + 0.5)
		var out := Vector3(bp.x - mid.x, 0, bp.z - mid.z).normalized()
		var stand := Vector3(bp.x, y, bp.z) - out * 7.5
		b.grabbed = false
		b.position = stand + out * 1.6 + Vector3(0, 0.05, 0)
		b.vel = Vector3.ZERO
		b.facing = out
		b.state = "walk"
		b.state_t = 0
		place(stand, out)   # right behind him (he's facing away)
		check(g.grab.label() == "GRAB TAIL", "behind KING KRAG, ACTION should say GRAB TAIL (got %s)" % g.grab.label())
		g._action(L())
		check(g.grab.tail == b and b.state == "swung", "ACTION should grab his TAIL")
		for i in 4:   # tap ACTION to spin him faster
			g._action(L())
		check(g.grab.spin_w > 8, "tapping ACTION should spin him faster (%.1f)" % g.grab.spin_w)
		frames(30)
		g.grab.ang = atan2(out.x, out.z)   # let go just as he points at the bomb
		g.grab.spin_w = 7.0
		var hp0: int = b.hp
		g.grab._release_tail(L())
		check(b.state == "flung", "JUMP should throw him")
		for i in 120:
			frames(1)
			if g.grab.flung == null:
				break
		check(b.hp < hp0 or b.dying >= 0, "throwing him into a spike bomb should hurt him (hp %d -> %d)" % [hp0, b.hp])
		if b.hp < hp0 or b.dying >= 0:
			hits += 1
		check(g.grab.bombs[bi].cool > 0, "the spike bomb should be used up")
		frames(10)
		if b.dying >= 0:
			break
	check(hits == 3 and (b.dying >= 0 or not is_instance_valid(b)), "3 spike bombs should beat KING KRAG (hits %d, hp %d)" % [hits, b.hp if is_instance_valid(b) else -1])
	check(g.boss_done, "beating him should count as beating the castle boss")
	frames(120)
	check(g.open_play.flag != null, "the flag should come down")
