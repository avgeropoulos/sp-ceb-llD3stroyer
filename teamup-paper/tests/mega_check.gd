extends SceneTree
## MEGA EVOLUTION: only a partner at its final form with a full SUPER bar can do it; the MEGA
## button shows; the scene plays and ends; the MEGA form is bigger and hits harder; the SUPER
## is stronger; the timer turns it back. Every line has MEGA pictures and a MEGA model.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/mega_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""
var scene_f := 0
var normal_dmg := 0


func _initialize() -> void:
	Look.loaded = true
	OpenPlay.select_enabled = false
	Cutscene.enabled = false
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]
		print("FAIL: " + why)


func M() -> Hero:
	return g.heroes.mon


## A sturdy grumbler right in front of the partner; returns how much one attack took off it.
func hit_test() -> int:
	var d := Foe.new()
	g.level.add_child(d)
	d.init(g.level, "grumbler", M().position + Vector3(0, 0, 1.2))
	d.set_meta("spawn", -1)
	d.hp = 99
	d.max_hp = 99
	g.foes.append(d)
	M().facing = Vector3(0, 0, 1)
	g._mon_attack(M(), d)
	var shots_before: int = g.shots.size()
	for i in 90:   # let any shot fly into it
		g._update_shots(1.0 / 60)
	var took: int = 99 - d.hp
	d.remove()
	return took


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state != "play":
				return false
			g.invincible = true
			# every line has MEGA pictures and a MEGA model
			for l in Mons.ORDER:
				check(ResourceLoader.exists("res://art/mon_%sM_idle.png" % l), "%s has no MEGA picture" % l)
				var kind: String = "sparky:2" if l == "spark" else "mon:%s:%d:mega" % [l, Mega.final_stage(l)]
				var toy := Toys.build(kind)
				check(toy.get_meta("p").has("orbit") and toy.get_meta("p").orbit.size() >= 1, "%s MEGA should have an aura" % l)
				toy.free()
			# not yet: first form, or the SUPER bar not full
			g.mon_line = "water"
			g.mon_xp["water"] = 0
			g.set_partner("water")
			g.super_charge = 1.0
			check(not g.mega.ready(), "a first-form partner can't MEGA")
			# final form + full SUPER: ready, and the button shows
			g.mon_xp["water"] = 99999
			g.set_partner("water")
			check(M().stage == 2, "HYDRO should be the final form")
			g.super_charge = 0.5
			check(not g.mega.ready(), "needs a full SUPER bar")
			normal_dmg = hit_test()
			g.super_charge = 1.0
			g.hud._layout()
			check(g.mega.ready() and g.hud.buttons.has("mega"), "the MEGA button should show")
			var size_before: float = M().sprite_h
			check(g.mega.start(), "MEGA should start")
			check(g.state == "mega" and g.super_charge == 0.0, "the MEGA scene should play and use the SUPER bar")
			M().set_meta("size_before", size_before)
			scene_f = 0
			step = 1
		1:
			scene_f += 1
			if g.state == "play":
				check(scene_f > 100 and scene_f < 220, "the scene should take about 2.6 s (%d frames)" % scene_f)
				check(M().mega and g.mega.active(), "the MEGA form should be out")
				check(M().sprite_h > float(M().get_meta("size_before")), "MEGA should be bigger")
				if Look.toy:
					check(M().model != null, "the MEGA model should be built")
				var mega_dmg := hit_test()
				check(mega_dmg > normal_dmg, "MEGA attacks should hit harder (%d vs %d)" % [mega_dmg, normal_dmg])
				check(not g.mega.ready(), "can't MEGA again while MEGA")
				# the timer runs out: back to normal
				g.mega.left = 0.05
				g.mega.step(0.1)
				check(not M().mega and not g.mega.active(), "MEGA should end when the time is up")
				check(absf(M().sprite_h - float(M().get_meta("size_before"))) < 0.01, "back to the normal size")
				# Sparky's line (2 forms) can MEGA too
				g.mon_line = "spark"
				g.mon_xp["spark"] = 99999
				g.set_partner("spark")
				g.super_charge = 1.0
				check(g.mega.start(), "ZAPPER should MEGA too")
				step = 2
			elif scene_f > 400:
				check(false, "the MEGA scene never ended")
				step = 3
		2:
			if g.state == "play":
				check(M().mega and M().line == "spark", "MEGA ZAPPER should be out")
				g.mega.end()
				check(not M().mega, "end() turns it back")
				step = 3
		3:
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if f > 60 * 60:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
