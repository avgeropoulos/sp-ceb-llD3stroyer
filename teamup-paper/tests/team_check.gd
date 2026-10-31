extends SceneTree
## Friends you unlock and the TEAM screen: unlocks by worlds cleared, picking 2 heroes + 1
## partner, SWAP order, each new hero walks / jumps / does its ACTION in an open world,
## save + load keeps the team, an old save gets Rocco + Blocky.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/team_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""
var hero_i := 0
var hero_f := 0
var start_p := Vector3.ZERO
var top_y := 0.0
const NEW := ["lucky", "alix", "petal", "spotty", "brawlo"]


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_team"
	_clean()
	# an old save (no team): file 1, meadow cleared once
	Saves.write(1, {"v": 2, "in_level": false, "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 40, "mon": "fire",
		"mon_xp": {"fire": 0}, "hearts": 5, "home": {}, "scenes_seen": ["intro"]})
	Boot.place = "title"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func ready_in(st: String) -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == st


func _process(_dt: float) -> bool:
	f += 1
	match step:
		0:
			if ready_in("title"):
				g.slot_pick(1)
				step = 1
		1:
			if ready_in("home"):
				# old save: Rocco + Blocky, and LUCKY unlocked (1 world cleared)
				check(g.team == ["rocco", "blocky"], "an old save should have Rocco + Blocky (got %s)" % str(g.team))
				check(g.heroes.rocco.who == "rocco" and g.heroes.blocky.who == "blocky", "the slots should hold Rocco and Blocky")
				check(Team.unlocked(g.cleared_w) == ["rocco", "blocky", "lucky"], "1 world cleared should unlock LUCKY (got %s)" % str(Team.unlocked(g.cleared_w)))
				check("lucky" in g.heroes_seen, "LUCKY should be announced on arriving home")
				# the TEAM button opens the TEAM screen
				g.taps = {"partner": true}
				g._step_home(1.0 / 60)
				check(g.state == "team" and g.team_from == "home", "TEAM should open the TEAM screen")
				g.team_tap("alix")
				check(not "alix" in g.team_sel, "ALIX is still locked")
				# pick LUCKY + ROCCO
				g.team_tap("blocky")
				check(g.team_sel == ["rocco"], "tapping a picked hero should un-pick it (%s)" % str(g.team_sel))
				g.team_tap("lucky")
				check(g.team_sel == ["rocco", "lucky"], "picks should go in order")
				g.team_partner()
				check(g.state == "partner" and g.partner_from == "team", "PARTNER should open from TEAM")
				g.choose_partner("dragon")
				check(g.state == "team" and g.mon_line == "dragon", "choosing a partner should go back to TEAM")
				g.team_sel = ["lucky", "rocco"]
				g.team_go()
				check(g.state == "home" and g.team == ["lucky", "rocco"], "LET'S GO should set the team")
				check(g.leader().who == "lucky" and g.leader_name() == "lucky", "LUCKY should be out first")
				# SWAP: LUCKY -> ROCCO -> partner -> LUCKY
				var order := []
				for i in 3:
					g.do_swap()
					order.append(g.leader_name())
				check(order == ["rocco", "mon", "lucky"], "swap order should be rocco, mon, lucky (got %s)" % str(order))
				check(g.next_swap_name() == "rocco", "the SWAP button should show ROCCO next")
				var d := Saves.read(1)
				check(d.get("team", []) == ["lucky", "rocco"] and d.get("mon", "") == "dragon", "the team should be saved")
				# unlock everyone, then play each new hero in an open world
				g.cleared_w = {"meadow": 1, "frost": 1, "dunes": 1, "swamp": 1, "moon": 1}
				check(Team.unlocked(g.cleared_w).size() == Team.ORDER.size(), "5 worlds should unlock everyone")
				g.save_game(false)
				Boot.level = "meadow"
				g.go("level", 1)
				step = 2
		2:
			if ready_in("play"):
				check(g.team == ["lucky", "rocco"], "the team should come along into a world")
				g.invincible = true
				g.process_mode = Node.PROCESS_MODE_DISABLED   # the test moves the heroes itself
				hero_i = 0
				hero_f = 0
				step = 3
		3:   # each new hero: walk, jump, ACTION
			var id: String = NEW[hero_i]
			var h: Hero = g.heroes.rocco
			if hero_f == 0:
				g.set_team([id, "blocky"])
				g.control = "human"
				g.human = "rocco"
				h.visible = true
				check(h.who == id and g.leader().who == id, "%s should be in slot 1" % id)
				h.position = g.level.start_pos + Vector3(0, 0.05, 0)
				h.vel = Vector3.ZERO
				start_p = h.position
				top_y = h.position.y
			hero_f += 1
			g.move_vec = Vector2(1, 0) if hero_f < 40 else Vector2.ZERO
			g.jump_pressed = hero_f == 45
			g.jump_held = hero_f >= 45 and hero_f < 80
			g.action_pressed = false
			g._update_leader(h, 1.0 / 60)
			g.jump_pressed = false
			top_y = maxf(top_y, h.position.y)
			if hero_f == 39:
				check(h.position.distance_to(start_p) > 2.0, "%s didn't walk" % id)
			if hero_f == 120:
				check(top_y > start_p.y + 1.5, "%s didn't jump (%.2f)" % [id, top_y - start_p.y])
				_try_action(id, h)
			if hero_f >= 160:
				hero_i += 1
				hero_f = 0
				if hero_i >= NEW.size():
					g.process_mode = Node.PROCESS_MODE_INHERIT
					step = 4
		4:
			# PETAL floats: holding JUMP in the air keeps her up much longer
			var h: Hero = g.heroes.rocco
			g.process_mode = Node.PROCESS_MODE_DISABLED
			g.set_team(["petal", "blocky"])
			var air_float := _air_time(h, true)
			var air_plain := _air_time(h, false)
			check(air_float > air_plain + 0.6, "PETAL should float when holding JUMP (%.2f vs %.2f)" % [air_float, air_plain])
			g.process_mode = Node.PROCESS_MODE_INHERIT
			# save in the world and load it back: the team stays
			g.set_team(["spotty", "alix"])
			g.save_game(true)
			g.go("level", 1)
			step = 5
		5:
			if ready_in("play"):
				check(g.team == ["spotty", "alix"], "loading should keep the team (got %s)" % str(g.team))
				check(g.heroes.rocco.who == "spotty" and g.heroes.blocky.who == "alix", "the slots should hold SPOTTY and ALIX")
				step = 6
				f = 0
		6:
			if f > 3:
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


## Each hero's ACTION does its thing next to a bad guy.
func _try_action(id: String, h: Hero) -> void:
	h.position = g.level.start_pos + Vector3(0, 0.05, 0)
	h.vel = Vector3.ZERO
	h.on_floor = true
	h.facing = Vector3.RIGHT
	h.action_t = 0
	var dummy := Foe.new()
	g.level.add_child(dummy)
	dummy.init(g.level, "grumbler", h.position + Vector3(1.3 if id != "alix" else 5.0, 0, 0))
	dummy.hp = 50
	dummy.max_hp = 50
	dummy.set_meta("spawn", -1)
	g.foes.append(dummy)
	var shots0: int = g.shots.size()
	g._action(h)
	match id:
		"lucky":
			check(h.vel.y > h.jump_v, "LUCKY's SPIN JUMP should be a super jump")
		"alix":
			check(g.shots.size() > shots0, "ALIX should shoot an arrow at a bad guy in front")
			check(g.action_label() in ["BOW", "MINE"], "ALIX's button says BOW or MINE")
		"petal":
			check(dummy.hp < 50, "PETAL's TWIRL should bonk a bad guy next to her")
		"spotty", "brawlo":
			check(h.dash_t > 0 and Vector2(h.vel.x, h.vel.z).length() > 10, "%s should dash" % id)
			for i in 20:
				g._update_leader(h, 1.0 / 60)
			check(dummy.hp < 50, "%s's dash should knock the bad guy" % id)
	dummy.remove()
	g.foes.erase(dummy)


## How long the hero stays in the air from a jump (holding JUMP all the way or not).
func _air_time(h: Hero, hold: bool) -> float:
	h.position = g.level.start_pos + Vector3(0, 0.05, 0)
	h.vel = Vector3.ZERO
	h.on_floor = true
	h.float_t = 0
	g.move_vec = Vector2.ZERO
	g._update_leader(h, 1.0 / 60)
	g.jump_pressed = true
	g.jump_held = true
	var t := 0.0
	for i in 400:
		g._update_leader(h, 1.0 / 60)
		g.jump_pressed = false
		g.jump_held = hold
		t += 1.0 / 60
		if i > 5 and h.on_floor:
			break
	return t
