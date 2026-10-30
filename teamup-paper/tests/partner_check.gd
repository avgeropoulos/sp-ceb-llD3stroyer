extends SceneTree
## Partners: pick one, level up, evolve, every move and SUPER at every stage, saved levels.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/partner_check.gd [-- toy]

var a
var b
var f := 0
var failed := ""
var line_i := 0
var stage_i := 0
var supers_hit := 0


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Saves.prefix = "user://test_partner"
	_clean()
	a = load("res://main.tscn").instantiate()
	root.add_child(a)


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = why


func _process(_dt: float) -> bool:
	f += 1
	if f == 5:
		a.slot_pick(1)
		check(a.state == "partner", "a new file should open PICK YOUR PARTNER (got %s)" % a.state)
		a.partner_from = ""   # straight into 1-1 (tests/home_check.gd covers the home)
		a.partner_tap(0)   # look at the water one
		check(a.state == "partner" and a.partner_sel == 0, "one tap should only pick")
		a.partner_tap(0)   # tap again = go
		check(a.state == "play" and a.heroes.mon.line == "water" and a.heroes.mon.stage == 0, "should start with SPLASHY")
		a.invincible = true
	elif f == 10:
		a.gain_xp(Mons.XP_FOE * 12)   # plenty for Lv 4
		check(a.mon_level() >= 4, "XP should reach Lv 4 (Lv %d)" % a.mon_level())
		check(a.state == "evolve", "reaching Lv 4 should start the evolution (got %s)" % a.state)
	elif f == 10 + 240:
		check(a.state == "play", "the evolution scene should end")
		check(a.heroes.mon.stage == 1 and a.display_name("mon") == "TIDAL", "SPLASHY should evolve into TIDAL (got %s)" % a.display_name("mon"))
		a.control = "mon"
	elif f > 250 and f < 250 + 4 * 3 * 40:
		# every line, every stage: ACTION and SUPER (as the hero and as the helper)
		var k := f - 251
		if k % 40 == 0:
			line_i = (k / 40) / 3
			stage_i = (k / 40) % 3
			var l: String = Mons.ORDER[line_i]
			if stage_i < Mons.LINES[l].names.size():
				a.mon_line = l
				a.heroes.mon.set_form(l, stage_i)
				a.leader().position = Vector3(108, 5.2, 6.5)   # near some bad guys
				var before: int = a.shots.size()
				a.heroes.mon.action_t = 0
				a._action(a.heroes.mon)   # ACTION as the hero you steer
				if l != "grass":
					check(a.shots.size() > before, "%s stage %d: ACTION made no shot" % [l, stage_i])
				var dummy := Foe.new()   # a fresh target right next to the team
				a.level.add_child(dummy)
				dummy.init(a.level, "grumbler", Vector3(110.5, 5.05, 6.5))
				dummy.hp = 50
				dummy.max_hp = 50
				dummy.set_meta("spawn", -1)
				a.foes.append(dummy)
				a.super_charge = 1.0
				a._thunderbolt()          # SUPER
				check(a.super_charge == 0.0, "%s: SUPER didn't use the charge" % l)
				supers_hit += 1 if dummy.hp < 50 else 0
				dummy.remove()
		elif k % 40 == 20:
			a.control = "human"
			a.heroes.mon.zap_t = 0   # helper attack next frame
		elif k % 40 == 39:
			a.control = "mon"
	elif f == 250 + 4 * 3 * 40 + 4:
		check(supers_hit == 11, "every SUPER should hurt a bad guy next to the team (only %d of 11 did)" % supers_hit)
		print("SUPERs that hit: %d of 11" % supers_hit)
	elif f == 250 + 4 * 3 * 40 + 5:
		a.set_partner("grass")
		a.mon_xp.grass = 200
		check(a.save_game(), "save failed")
		a.queue_free()
	elif f == 250 + 4 * 3 * 40 + 10:
		b = load("res://main.tscn").instantiate()
		root.add_child(b)
	elif f == 250 + 4 * 3 * 40 + 14:
		b.slot_pick(1)
		check(b.mon_xp.grass == 200 and b.mon_xp.water >= Mons.XP_FOE * 12, "partner XP wasn't saved")
		check(b.mon_line == "grass" and b.heroes.mon.line == "grass", "the partner wasn't saved")
		check(b.heroes.mon.stage == Mons.stage_of("grass", Mons.level_of(200)), "the partner's evolution wasn't restored")
	elif f == 250 + 4 * 3 * 40 + 20:
		_clean()
		print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
		quit(1 if failed != "" else 0)
		return true
	return false
