extends SceneTree
## The Mario 64-style extras (extras.gd): the 100 COIN STAR (and that it's saved), a friend's
## star quest (meadow: Rocco really RACES ZOOMY up the mountain path with real physics and
## sprinting; frost: MAMA CLUCK's 5 lost chicks are found and walked home), mushroom folk hints,
## a 1-UP, a ! cap block, riding DASH THE DINO (faster, tongue eats a bad guy, SWAP gets off,
## getting hit makes him run off) and the critters.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/extras_check.gd -- [toy] [meadow|frost]

var g
var f := 0
var step := 0
var failed := ""
var ex: Extras
var op
var s_goal := 0.0
var run_f := 0


func _initialize() -> void:
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Cutscene.enabled = false
	Look.toy = a.has("toy")
	Boot.place = "level"
	Boot.level = "frost" if a.has("frost") else "meadow"
	print("extras: ", Boot.level, " look: ", "toy" if Look.toy else "paper")
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


func tick(n := 1) -> void:
	for i in n:
		g._update_leader(L(), 1.0 / 60)
		op.step(1.0 / 60)


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "play" and g.open_play and f > 5:
				g.invincible = true
				g.process_mode = Node.PROCESS_MODE_DISABLED   # only the test moves things
				op = g.open_play
				ex = op.extras
				check(ex != null, "the extras should be made in every open world")
				check(op.ids().has("coins100") and op.ids().has("npc"), "100 COINS and the friend's quest should be star missions")
				check(ex.folk.size() >= 2, "there should be mushroom folk with hints (%d)" % ex.folk.size())
				check(ex.oneups.size() >= 2, "there should be hidden 1-UPs (%d)" % ex.oneups.size())
				tick()
				# ---------------- mushroom folk talk
				var fk: Dictionary = ex.folk[0]
				place_at(fk.node.position + Vector3(1.5, 0.02, 0))
				tick()
				check((fk.bubble as Label3D).visible and fk.text != "", "a mushroom friend should talk when you're near")
				place_at(fk.node.position + Vector3(12, 0.02, 0))
				tick()
				check(not (fk.bubble as Label3D).visible, "the bubble should hide when you walk off")
				# ---------------- the 100 COIN STAR
				g.coins = ex.coins0 + 99
				place_at(g.level.start_pos + Vector3(0, 0.02, 0))
				tick()
				check(not op.stars.has("coins100"), "99 coins isn't enough")
				check(op.progress("coins100").contains("99 / 100"), "the quest panel should count coins (%s)" % op.progress("coins100"))
				g.coins += 1
				tick()
				check(op.stars.has("coins100"), "100 coins in one visit should make a star pop out")
				for i in 300:   # it floats down to you
					tick()
					if op.got.has("coins100"):
						break
				check(op.got.has("coins100"), "the 100-coin star should float down to you and be collected")
				check(g.save_data().get("stars", []).has("coins100"), "the 100-coin star should be saved")
				check(int(g.save_data().get("visit_coins", -1)) >= 100, "this visit's coins should be saved for the 100 COIN STAR")
				# ---------------- 1-UP
				g.hearts = 1
				var up: Node3D = ex.oneups[0]
				place_at(up.position + Vector3(0, 0.02, 0))
				tick()
				check(g.hearts == g.MAX_HEARTS, "a 1-UP should give all hearts back (%d)" % g.hearts)
				# ---------------- ! cap block
				if ex.caps.size() > 0:
					var c: Dictionary = ex.caps[0]
					var n: Node3D = c.node
					var items_before: int = g.powers.items.size()
					place_at(Vector3(n.position.x, n.position.y - L().height - 0.05, n.position.z))
					L().vel.y = 6.0
					ex._caps_step(L())
					check(c.used and g.powers.items.size() == items_before + 1, "bumping a ! block from below should give a %s" % c.kind)
				else:
					print("note: no room for ! blocks here")
				step = 1 if ex.quest == "race" else 3
				f = 0
		1:   # ---------------- ZOOMY's race: stand by him, 3-2-1, then really run up the path
			var r: Node3D = ex.racer
			place_at(r.position + Vector3(1.5, 0.02, 0.5))
			for i in 30:
				tick()
			check(ex.racer_bubble.visible, "ZOOMY should talk when you're near")
			for i in 400:
				tick()
				if ex.race == "run":
					break
			check(ex.race == "run", "standing next to ZOOMY should start the race after a count (%s)" % ex.race)
			place_at(g.level.ramp_point(0.0) + Vector3(0, 0.05, 0))
			s_goal = 0.02
			run_f = 0
			step = 2
		2:
			for k in 4:
				run_f += 1
				var to: Vector3 = g.level.ramp_point(s_goal) if s_goal < 1.0 else g.level.summit_spot
				var d := Vector3(to.x - L().position.x, 0, to.z - L().position.z)
				if d.length() < 1.2 and s_goal < 1.0:
					s_goal = minf(1.0, s_goal + 0.02)
				g.cam_yaw = 0
				g.move_vec = Vector2(d.x, d.z).normalized()
				g.sprinting = true
				g.jump_pressed = L().on_floor and L().walls_hit
				g.jump_held = true
				tick()
				g.jump_pressed = false
				if ex.race != "run" or run_f > 60 * 90:
					break
			if ex.race != "run" or run_f > 60 * 90:
				g.move_vec = Vector2.ZERO
				g.sprinting = false
				print("race: %s after %.1f s (ZOOMY at %.0f%%)" % [ex.race, run_f / 60.0, ex.race_s * 100])
				check(ex.race == "won", "Rocco should win the race by running up the path (got %s, Rocco at %s, s=%.2f)" % [ex.race, L().position, s_goal])
				check(op.stars.has("npc"), "beating ZOOMY should make a star")
				if op.stars.has("npc"):
					place_at(op.stars["npc"].position - Vector3(0, 0.8, 0))
					tick()
				check(op.got.has("npc"), "the race star should be collected")
				step = 5
		3:   # ---------------- MAMA CLUCK's chicks
			check(ex.chicks.size() == Extras.CHICKS, "there should be %d lost chicks (%d)" % [Extras.CHICKS, ex.chicks.size()])
			place_at(ex.mama.position + Vector3(1.5, 0.02, 0))
			tick()
			check(ex.mama_bubble.visible, "MAMA CLUCK should talk when you're near")
			for c in ex.chicks:
				var n: Node3D = c.node
				place_at(Vector3(n.position.x, c.pos.y + 0.02, n.position.z) + Vector3(0.6, 0, 0))
				tick(2)
				check(c.state == "follow", "touching a chick should make it follow you")
			check(ex.following() == ex.chicks.size(), "all the chicks should follow (%d)" % ex.following())
			place_at(ex.mama.position + Vector3(2.0, 0.02, 0))
			tick(90)
			check(ex.chicks_home == ex.chicks.size(), "walking them to MAMA should bring them home (%d)" % ex.chicks_home)
			check(op.stars.has("npc") or op.got.has("npc"), "all the chicks home should make a star")
			if op.stars.has("npc"):
				place_at(op.stars["npc"].position - Vector3(0, 0.8, 0))
				tick()
			check(op.got.has("npc"), "the chicks star should be collected")
			step = 5
		5:   # ---------------- DASH THE DINO
			if ex.dash == null:
				print("note: no DASH in this world")
				step = 7
				return false
			place_at(ex.dash.position + Vector3(0, 1.6, 0))
			L().vel.y = -3.0
			ex._dash_step(1.0 / 60, L())
			check(ex.riding, "jumping onto DASH should ride him")
			check(g._power_speed() > 1.3, "riding DASH should be faster (%.2f)" % g._power_speed())
			check(g.action_label() == "TONGUE", "ACTION should say TONGUE while riding")
			place_at(L().position + Vector3(0, 0.02, 0))
			L().facing = Vector3(1, 0, 0)
			var foe := Foe.new()
			g.level.add_child(foe)
			foe.init(g.level, "grumbler", L().position + Vector3(2.0, 0, 0))
			foe.set_meta("spawn", -1)
			g.foes.append(foe)
			g._action(L())
			check(foe.dying >= 0 or not foe.alive, "DASH's tongue should gobble the bad guy in front")
			# SWAP gets off
			g.swap_pressed = true
			check(ex.on_swap() and not ex.riding, "SWAP should get off DASH")
			g.swap_pressed = false
			# ride again, then get hit: DASH runs off and no heart is lost
			place_at(ex.dash.position + Vector3(0, 1.6, 0))
			L().vel.y = -3.0
			g.hurt_t = 0
			ex._dash_step(1.0 / 60, L())
			check(ex.riding, "you can get back on")
			g.invincible = false
			var h0: int = g.hearts
			g.hurt_hero(L().position + Vector3(1, 0, 0))
			g.invincible = true
			check(not ex.riding and ex.dash_away_t > 0 and g.hearts == h0, "getting hit on DASH should make him run off (no heart lost)")
			step = 6
		6:   # ---------------- critters
			if g.level.world == "meadow":
				check(ex.butterflies.size() >= 5, "butterflies should flutter about in the meadow")
			if ex.birds.size() > 0:
				var b: Dictionary = ex.birds[0]
				place_at(b.node.position + Vector3(2, 0.02, 0))
				tick(2)
				check(b.state == "fly", "a bird should fly off when you run near")
			step = 7
		7:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 200:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
