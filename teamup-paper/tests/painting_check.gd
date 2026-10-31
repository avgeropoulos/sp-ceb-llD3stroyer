extends SceneTree
## GIANT PAINTINGS (Mario 64 style), 3 in every open world, and the FIRE FLOWER:
## * every painting has flat, clear ground in front, and Rocco really jumps into it (real
##   physics, no teleport onto it);
## * each one loads its own BONUS COURSE, and the robot finishes it:
##   SKY CLIMB: every platform jump is really jumped; SECRET SLIDE: Rocco really slides
##   down (real physics) in time; RED COIN MAZE: every red coin can be walked to (the maze
##   is searched) and picked up; BIG BRAWL: the Big Grumbler is beaten;
## * falling 3 times (first world) throws you back out with no star;
## * the star takes you back into the world next to that painting, found and saved;
## * an old save's single "painting" star becomes the first painting's star;
## * a FIRE FLOWER waits near the start, ACTION throws 2 fireballs (never more than 2
##   out), and a fireball beats a normal bad guy in one hit.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/painting_check.gd -- [toy] [world ...]

var g
var f := 0
var step := 0
var failed := ""
var worlds: Array = []
var wi := 0
var pi := 0                   # which painting (0-2) in this world
var hop := 0
var hop_f := 0
var tops: Array = []
var jump_f := 0
var fall_test := true
var slide_f := 0
var report := []


func _initialize() -> void:
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Look.toy = a.has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_paint"
	for w in Worlds.ORDER:
		if a.has(w):
			worlds.append(w)
	if worlds.is_empty():
		worlds = Worlds.ORDER.duplicate()
	print("paintings: ", worlds, " look: ", "toy" if Look.toy else "paper")
	_clean()
	_start_world()


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func _start_world() -> void:
	var d := {"v": 2, "in_level": false, "level": worlds[wi], "coins": 3, "mon": "spark"}
	if wi == 0:   # an old save: one painting star called "painting"
		d["stars_w"] = {worlds[0]: ["painting"]}
	Saves.write(1, d)
	Boot.place = "level"
	Boot.level = worlds[wi]
	Boot.slot = 1
	var m: Node = load("res://main.tscn").instantiate()
	root.add_child(m)
	step = 0
	f = 0
	pi = 0


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "%s painting %d step %d: %s" % [worlds[mini(wi, worlds.size() - 1)], pi + 1, step, why]


func L():
	return g.leader()


func place_at(p: Vector3) -> void:
	L().position = p
	L().vel = Vector3.ZERO
	g.follower().position = p + Vector3(0, 0.5, 0)


func ready_world() -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == "play"


func info() -> Dictionary:
	return g.level.world_paintings[pi]


## One real jump from tops[hop] to tops[hop + 1] (like tests/open_check.gd).
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
		var ok: bool = L().on_floor and L().position.y > to.y - 0.1
		check(ok, "Rocco couldn't jump from platform %d %s to %s (ended at %s)" % [hop, tops[hop], to, L().position])
		hop += 1
		hop_f = 0
		return hop >= tops.size() - 1 or failed != ""
	return false


## Is every red coin in the maze reachable on foot from the start? (search the open floor)
func _maze_reachable() -> bool:
	var lv: Level = g.level
	var startc := Vector2i(floori(lv.start_pos.x), floori(lv.start_pos.z))
	var seen := {startc: true}
	var q := [startc]
	while q.size() > 0:
		var c: Vector2i = q.pop_back()
		for d in [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
			var n: Vector2i = c + d
			if lv.maze_open.has(n) and not seen.has(n):
				seen[n] = true
				q.append(n)
	for r in lv.red_bonus:
		if not seen.has(Vector2i(floori(r.x), floori(r.z))):
			return false
	return true


func _process(_dt: float) -> bool:
	f += 1
	if failed != "":
		return _end()
	match step:
		0:   # in the world: the paintings, the ground in front, the fire flower
			if ready_world() and not g.level.bonus:
				g.invincible = true
				var op: OpenPlay = g.open_play
				var lv: Level = g.level
				if pi == 0:
					check(lv.world_paintings.size() == 3, "every world should have 3 giant paintings (got %d)" % lv.world_paintings.size())
					check(op.paintings.size() == 3, "3 paintings should be up on the walls")
					var kinds := {}
					for p in lv.world_paintings:
						kinds[p.kind] = true
						check(op.ids().has(p.id), "%s should be a star on the star select" % p.id)
						check(p.w >= 5.0 and p.h >= 4.0, "the paintings should be GIANT")
					check(kinds.size() == 3, "the 3 paintings should go to 3 different courses")
					if wi == 0:
						check(op.got.has("painting1") and not op.got.has("painting"), "an old 'painting' star should become painting1's star")
						_fire_checks()
				var inf := info()
				var n: Vector3 = inf.normal
				var stand: Vector3 = Vector3(inf.pos.x, inf.ground + 0.01, inf.pos.z) + n * 1.6
				var base := Vector3i(floori(stand.x), int(inf.ground) - 1, floori(stand.z))
				check(lv.solid(base.x, base.y, base.z), "no ground in front of painting %d" % (pi + 1))
				check(lv.box_hit(stand, 0.35, 1.5) == null, "the spot in front of painting %d is blocked (%s)" % [pi + 1, stand])
				check(lv.box_hit(Vector3(inf.pos.x, inf.ground + 0.01, inf.pos.z) + n * 0.45, 0.3, 1.5) == null, "can't get right up to painting %d" % (pi + 1))
				# walk up and JUMP into it (real physics)
				g.process_mode = Node.PROCESS_MODE_DISABLED
				place_at(stand)
				g._update_leader(L(), 1.0 / 60)
				check(not op.paintings[pi].touching(L()), "standing in front shouldn't count as jumping in")
				jump_f = 0
				step = 1
		1:
			jump_f += 1
			g.cam_yaw = 0
			var n: Vector3 = info().normal
			g.move_vec = Vector2(-n.x, -n.z)
			g.jump_pressed = jump_f == 3
			g.jump_held = jump_f < 30
			g._update_leader(L(), 1.0 / 60)
			g.jump_pressed = false
			if g.open_play.paintings[pi].touching(L()):
				g.move_vec = Vector2.ZERO
				g.process_mode = Node.PROCESS_MODE_INHERIT
				g.open_play.paintings[pi].jump_in()
				step = 2
				f = 0
			elif jump_f > 90:
				check(false, "Rocco never reached painting %d by jumping (at %s)" % [pi + 1, L().position])
		2:   # pulled in: the bonus course loads
			if ready_world() and g.level.bonus:
				g.invincible = true
				check(g.bonus != null, "no bonus course")
				check(g.level.bonus_kind == info_kind(), "painting %d should lead to %s (got %s)" % [pi + 1, info_kind(), g.level.bonus_kind])
				check(L().position.distance_to(g.level.start_pos) < 1.0, "should start at the course's start")
				if fall_test:
					step = 3
				else:
					_start_course()
				f = 0
			elif f > 60 * 30:
				check(false, "the bonus course never loaded")
		3:   # fall off 3 times: back out, no star
			if f == 5 or f == 25 or f == 45:
				L().position = Vector3(5, -30, 5)
			if f == 60:
				check(g.bonus.falls == 3, "3 falls should count (got %d)" % g.bonus.falls)
				step = 30
				f = 0
		30:
			if ready_world() and not g.level.bonus:
				check(not g.open_play.got.has(info().id) or info().id == "painting1" and wi == 0, "falling out shouldn't give the star")
				_check_back_out()
				fall_test = false
				g.invincible = true
				place_at(Vector3(info().pos.x, info().ground + 0.01, info().pos.z) + info().normal * 1.0)
				g.open_play.paintings[pi].jump_in()
				step = 2
				f = 0
			elif f > 60 * 30:
				check(false, "never came back out of the painting")
		4:   # SKY CLIMB: every platform, jumped for real
			for i in 4:
				if _hop_frame():
					report.append("%s sky %d jumps" % [worlds[wi], tops.size() - 1])
					g.process_mode = Node.PROCESS_MODE_INHERIT
					step = 5
					f = 0
					break
		41:  # SECRET SLIDE: real physics all the way down, steering at the next bend
			for i in 4:
				slide_f += 1
				var path: Array = g.level.slide_path
				var seg: int = g.bonus._nearest_seg(L().position)
				var to: Vector3 = path[mini(seg + 1, path.size() - 1)]
				if seg >= path.size() - 2:
					to = g.level.bonus_tops[1]
				var d := Vector3(to.x - L().position.x, 0, to.z - L().position.z)
				g.cam_yaw = 0
				g.move_vec = Vector2(d.x, d.z).normalized() if d.length() > 0.5 else Vector2.ZERO
				g._update_leader(L(), 1.0 / 60)
				g.bonus.step(1.0 / 60)
				if g.bonus.race_t == -100.0:
					report.append("%s slide %.1f s" % [worlds[wi], slide_f / 60.0])
					g.move_vec = Vector2.ZERO
					g.process_mode = Node.PROCESS_MODE_INHERIT
					step = 5
					f = 0
					break
				if L().position.y < g.level.bonus_floor - 9.0 or slide_f > 60 * 60:
					check(false, "Rocco didn't make it down the slide in time (at %s, %.1f s, race %.1f)" % [L().position, slide_f / 60.0, g.bonus.race_t])
					break
		42:  # RED COIN MAZE: all reachable, then pick them up
			if f == 2:
				check(g.level.red_bonus.size() == 8, "the maze should have 8 red coins")
				check(_maze_reachable(), "every red coin should be reachable through the maze")
				for c in g.bonus.reds.duplicate():
					place_at(c.position - Vector3(0, 0.6, 0))
					g.bonus.step(1.0 / 60)
				check(g.bonus.red_got == 8 and g.bonus.star_out, "8 red coins should bring out the star (%d)" % g.bonus.red_got)
				report.append("%s maze OK" % worlds[wi])
				step = 5
				f = 0
		43:  # BIG BRAWL: beat the Big Grumbler
			var b: Foe = g.bonus.brawler()
			if b and f % 20 == 0:
				b.safe_t = 0
				b.hurt(3, g)
			if b == null and f > 30 and g.bonus.star_out:
				report.append("%s brawl OK" % worlds[wi])
				step = 5
				f = 0
			elif f > 60 * 30:
				check(false, "the Big Grumbler was never beaten / no star")
		5:
			if f == 2:
				var s: Vector3 = g.level.bonus_star
				if g.level.bonus_kind == "sky":
					check(Vector2(L().position.x - s.x, L().position.z - s.z).length() < 3.5, "the last jump should land by the star")
				place_at(s - Vector3(0, 0.8, 0))
				g.bonus.step(1.0 / 60)
				check(g.bonus.got, "the painting star should be collected")
				step = 6
				f = 0
		6:
			if ready_world() and not g.level.bonus:
				var id: String = info().id
				check(g.open_play.got.has(id), "%s should be found back in the world" % id)
				var d := Saves.read(1)
				check(d.get("stars_w", {}).get(worlds[wi], []).has(id), "%s should be saved" % id)
				_check_back_out()
				pi += 1
				if pi < 3:
					step = 0
					f = 0
					return false
				wi += 1
				if wi >= worlds.size():
					return _end()
				g.queue_free()
				_start_world()
			elif f > 60 * 30:
				check(false, "never came back from the bonus course")
	if f > 60 * 120:
		check(false, "timed out")
	return false


func info_kind() -> String:
	return Level.paint_kinds(worlds[wi])[pi]


func _start_course() -> void:
	match g.level.bonus_kind:
		"sky":
			tops = g.level.bonus_tops
			hop = 0
			hop_f = 0
			g.process_mode = Node.PROCESS_MODE_DISABLED
			step = 4
		"slide":
			slide_f = 0
			g.process_mode = Node.PROCESS_MODE_DISABLED
			step = 41
		"maze":
			step = 42
		"boss":
			step = 43


## Back in the world: next to the painting you jumped into.
func _check_back_out() -> void:
	var inf := info()
	check(Vector2(L().position.x - inf.pos.x, L().position.z - inf.pos.z).length() < 5.0,
		"should come back out next to painting %d (at %s, painting %s)" % [pi + 1, L().position, inf.pos])


func _fire_checks() -> void:
	var pw: Powers = g.powers
	var near_fire := false
	for it in pw.items:
		if it.kind == "fire" and it.node.position.distance_to(g.level.start_pos) < 8:
			near_fire = true
	check(near_fire, "a FIRE FLOWER should wait near the start")
	place_at(g.level.start_pos)
	pw.give("fire")
	var n0: int = pw.fireballs_out()
	L().action_t = 0
	g._action(L())
	L().action_t = 0
	g._action(L())
	check(pw.fireballs_out() == n0 + 2, "ACTION twice should throw 2 fireballs (got %d)" % (pw.fireballs_out() - n0))
	L().action_t = 0
	g._action(L())
	check(pw.fireballs_out() == 2, "never more than 2 fireballs out")
	var foe := Foe.new()
	g.level.add_child(foe)
	foe.init(g.level, "grumbler", g.level.start_pos + Vector3(3, 0, 0))
	foe.set_meta("spawn", -1)
	check(g._shot_damage({"flower": true, "dmg": 2, "owner": L()}, foe) >= foe.hp, "a fireball should beat a grumbler in one hit")
	foe.remove()
	pw.lose()


func _end() -> bool:
	_clean()
	for r in report:
		print(r)
	print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
	quit(1 if failed != "" else 0)
	return true
