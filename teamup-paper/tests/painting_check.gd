extends SceneTree
## SECRET PAINTINGS (Mario 64 style) in every open world, and the FIRE FLOWER:
## * every world has a painting on the castle wall with flat ground in front of it,
##   and Rocco really jumps into it (real physics, no teleport onto it);
## * that loads the BONUS COURSE, where every platform jump is really jumped by Rocco;
## * falling 3 times (first world) throws you back out with no star;
## * grabbing the PAINTING STAR takes you back into the world, next to the painting,
##   with the star found and saved;
## * a FIRE FLOWER waits near the start, ACTION throws 2 fireballs (never more than 2
##   out), and a fireball beats a normal bad guy in one hit.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/painting_check.gd -- [toy] [world ...]

var g
var f := 0
var step := 0
var failed := ""
var worlds: Array = []
var wi := 0
var hop := 0
var hop_f := 0
var tops: Array = []
var jump_f := 0
var fall_test := true


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
	Saves.write(1, {"v": 2, "in_level": false, "level": worlds[wi], "coins": 3, "mon": "spark"})
	Boot.place = "level"
	Boot.level = worlds[wi]
	Boot.slot = 1
	var m: Node = load("res://main.tscn").instantiate()
	root.add_child(m)
	step = 0
	f = 0


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "%s step %d: %s" % [worlds[mini(wi, worlds.size() - 1)], step, why]


func L():
	return g.leader()


func place_at(p: Vector3) -> void:
	L().position = p
	L().vel = Vector3.ZERO
	g.follower().position = p + Vector3(0, 0.5, 0)


func ready_world() -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == "play"


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


func _process(_dt: float) -> bool:
	f += 1
	if failed != "":
		return _end()
	match step:
		0:   # in the world: find the painting, check the ground in front, the fire flower
			if ready_world() and not g.level.bonus:
				g.invincible = true
				var op: OpenPlay = g.open_play
				var lv: Level = g.level
				check(op.painting != null, "no painting")
				check(op.ids().has("painting"), "the painting should be a star on the star select")
				var info: Dictionary = lv.painting
				var stand := Vector3(info.x - 1.5, lv.arena_y + 0.01, info.z)
				check(lv.solid(floori(stand.x), lv.arena_y - 1, floori(stand.z)), "no ground in front of the painting")
				check(lv.box_hit(stand, 0.35, 1.5) == null, "the spot in front of the painting is blocked")
				check(lv.box_hit(Vector3(info.x - 0.4, lv.arena_y + 0.01, info.z), 0.3, 1.5) == null, "can't get right up to the painting")
				if wi == 0:
					_fire_checks()
				# walk up and JUMP into it (real physics)
				g.process_mode = Node.PROCESS_MODE_DISABLED
				place_at(stand)
				g._update_leader(L(), 1.0 / 60)
				check(not op.painting.touching(L()), "standing in front shouldn't count as jumping in")
				jump_f = 0
				step = 1
		1:
			jump_f += 1
			g.cam_yaw = 0
			g.move_vec = Vector2(1, 0)
			g.jump_pressed = jump_f == 3
			g.jump_held = jump_f < 30
			g._update_leader(L(), 1.0 / 60)
			g.jump_pressed = false
			if g.open_play.painting.touching(L()):
				print("%s: jumped into the painting after %d frames" % [worlds[wi], jump_f])
				g.move_vec = Vector2.ZERO
				g.process_mode = Node.PROCESS_MODE_INHERIT
				g.open_play.painting.jump_in()
				step = 2
				f = 0
			elif jump_f > 90:
				check(false, "Rocco never reached the painting by jumping (at %s)" % L().position)
		2:   # pulled in: the bonus course loads
			if ready_world() and g.level.bonus:
				g.invincible = true
				check(g.bonus != null and g.level.bonus_tops.size() >= 10, "the bonus course should have platforms")
				check(L().position.distance_to(g.level.start_pos) < 1.0, "should start on the first platform")
				if fall_test:
					step = 3
				else:
					tops = g.level.bonus_tops
					hop = 0
					hop_f = 0
					g.process_mode = Node.PROCESS_MODE_DISABLED
					step = 4
				f = 0
			elif f > 60 * 30:
				check(false, "the bonus course never loaded")
		3:   # fall off 3 times: back out, no star
			if f == 5 or f == 25 or f == 45:
				L().position = Vector3(5, -20, 5)
			if f == 60:
				check(g.bonus.falls == 3, "3 falls should count (got %d)" % g.bonus.falls)
				step = 30
				f = 0
		30:
			if ready_world() and not g.level.bonus:
				check(not g.open_play.got.has("painting"), "falling out shouldn't give the star")
				check(Vector2(L().position.x - g.level.painting.x, L().position.z - g.level.painting.z).length() < 4.0,
					"should come back out next to the painting (at %s)" % L().position)
				fall_test = false
				# and in again (straight in, the jump was tested already)
				g.invincible = true
				place_at(Vector3(g.level.painting.x - 1.0, g.level.arena_y + 0.01, g.level.painting.z))
				g.open_play.painting.jump_in()
				step = 2
				f = 0
			elif f > 60 * 30:
				check(false, "never came back out of the painting")
		4:   # every platform, jumped for real
			for i in 4:
				if _hop_frame():
					print("%s: bonus course %d jumps OK" % [worlds[wi], tops.size() - 1])
					g.process_mode = Node.PROCESS_MODE_INHERIT
					step = 5
					f = 0
					break
		5:
			if f == 2:
				var s: Vector3 = g.level.bonus_star
				check(Vector2(L().position.x - s.x, L().position.z - s.z).length() < 3.5, "the last jump should land by the star")
				place_at(s - Vector3(0, 0.8, 0))
				g.bonus.step(1.0 / 60)
				check(g.bonus.got, "the PAINTING STAR should be collected")
				step = 6
				f = 0
		6:
			if ready_world() and not g.level.bonus:
				check(g.open_play.got.has("painting"), "the painting star should be found back in the world")
				var d := Saves.read(1)
				check(d.get("stars_w", {}).get(worlds[wi], []).has("painting"), "the painting star should be saved")
				check(Vector2(L().position.x - g.level.painting.x, L().position.z - g.level.painting.z).length() < 4.0,
					"should come back out next to the painting (at %s)" % L().position)
				wi += 1
				if wi >= worlds.size():
					return _end()
				g.queue_free()
				_start_world()
			elif f > 60 * 30:
				check(false, "never came back from the bonus course")
	if f > 60 * 90:
		check(false, "timed out")
	return false


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
	print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
	quit(1 if failed != "" else 0)
	return true
