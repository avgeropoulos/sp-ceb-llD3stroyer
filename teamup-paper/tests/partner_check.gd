extends SceneTree
## Partners: pick one (a newer line, the dragon), level up, evolve (Lv 7 now), every move
## and SUPER of all 12 lines at every stage, saved levels.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/partner_check.gd [-- toy]

const PER := 40          # frames for each line + stage
const START := 250

var a
var b
var f := 0
var failed := ""
var line_i := 0
var stage_i := 0
var supers_hit := 0
var supers_tried := 0
var dummy: Foe
var end_f := 0


func _initialize() -> void:
	Look.loaded = true
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Saves.prefix = "user://test_partner"
	_clean()
	a = load("res://main.tscn").instantiate()
	root.add_child(a)
	end_f = START + Mons.ORDER.size() * 3 * PER


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
		check(Mons.ORDER.size() == 12, "there should be 12 partner lines")
		check(a.mon_xp.size() == 12, "every line should have XP")
		a.slot_pick(1)
		check(a.state == "partner", "a new file should open PICK YOUR PARTNER (got %s)" % a.state)
		a.partner_from = ""   # straight into 1-1 (tests/home_check.gd covers the home)
		var di: int = Mons.ORDER.find("dragon")
		a.partner_tap(di)   # look at the dragon
		check(a.state == "partner" and a.partner_sel == di, "one tap should only pick")
		check(a.hud.partner_rect(11).end.y < a.hud.go_rect().position.y and a.hud.partner_rect(5).end.x <= a.hud.size.x, "12 cards should fit above GO")
		a.partner_tap(di)   # tap again = go
		check(a.state == "play" and a.heroes.mon.line == "dragon" and a.heroes.mon.stage == 0, "should start with DRAKLET")
		a.invincible = true
	elif f == 10:
		a.gain_xp(Mons.XP_FOE * 12)
		check(a.mon_level() < 7 and a.state == "play", "evolving should take longer now (Lv %d)" % a.mon_level())
		a.gain_xp(600)
		check(a.mon_level() >= 7, "XP should reach Lv 7 (Lv %d)" % a.mon_level())
		check(a.state == "evolve", "reaching Lv 7 should start the evolution (got %s)" % a.state)
	elif f == 10 + 240:
		check(a.state == "play", "the evolution scene should end")
		check(a.heroes.mon.stage == 1 and a.display_name("mon") == "WYVERNO", "DRAKLET should evolve into WYVERNO (got %s)" % a.display_name("mon"))
		a.control = "mon"
	elif f > START and f < end_f:
		# every line, every stage: ACTION and SUPER (as the hero and as the helper)
		var k := f - START - 1
		if k % PER == 0:
			line_i = (k / PER) / 3
			stage_i = (k / PER) % 3
			var l: String = Mons.ORDER[line_i]
			if stage_i < Mons.LINES[l].names.size():
				a.mon_line = l
				a.heroes.mon.set_form(l, stage_i)
				a.leader().position = Vector3(108, 5.2, 6.5)   # near some bad guys
				a.leader().facing = Vector3.RIGHT
				dummy = Foe.new()   # a fresh target right next to the team
				a.level.add_child(dummy)
				dummy.init(a.level, "grumbler", Vector3(110.2, 5.05, 6.5))
				dummy.hp = 50
				dummy.max_hp = 50
				dummy.set_meta("spawn", -1)
				a.foes.append(dummy)
				var before: int = a.shots.size()
				a.heroes.mon.action_t = 0
				a._action(a.heroes.mon)   # ACTION as the hero you steer
				if l in ["grass", "bird", "bug", "dragon"]:   # close-up moves hit right away
					check(dummy.hp < 50, "%s stage %d: ACTION didn't hit the bad guy in front" % [l, stage_i])
				else:
					check(a.shots.size() > before, "%s stage %d: ACTION made no shot" % [l, stage_i])
				dummy.hp = 50
				dummy.safe_t = 0
				dummy.position = Vector3(110.2, 5.05, 6.5)
				dummy.vel = Vector3.ZERO
				a.super_charge = 1.0
				a._thunderbolt()          # SUPER
				check(a.super_charge == 0.0, "%s: SUPER didn't use the charge" % l)
				supers_tried += 1
				if dummy.hp < 50:
					supers_hit += 1
				else:
					print("SUPER missed: %s %d" % [l, stage_i])
		elif k % PER == 30:
			if is_instance_valid(dummy):
				dummy.remove()
			a.control = "human"
			a.heroes.mon.zap_t = 0   # helper attack next frame
		elif k % PER == 39:
			a.control = "mon"
	elif f == end_f + 4:
		check(supers_hit == supers_tried, "every SUPER should hurt a bad guy next to the team (only %d of %d did)" % [supers_hit, supers_tried])
		print("SUPERs that hit: %d of %d" % [supers_hit, supers_tried])
	elif f == end_f + 5:
		a.set_partner("fairy")
		a.mon_xp.fairy = 2000
		check(a.save_game(), "save failed")
		a.queue_free()
	elif f == end_f + 10:
		b = load("res://main.tscn").instantiate()
		root.add_child(b)
	elif f == end_f + 14:
		b.slot_pick(1)
		check(b.mon_xp.fairy == 2000 and b.mon_xp.dragon >= 600, "partner XP wasn't saved")
		check(b.mon_line == "fairy" and b.heroes.mon.line == "fairy", "the partner wasn't saved")
		check(b.heroes.mon.stage == Mons.stage_of("fairy", Mons.level_of(2000)) and b.heroes.mon.stage == 1, "the partner's evolution wasn't restored")
	elif f == end_f + 20:
		_clean()
		print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
		quit(1 if failed != "" else 0)
		return true
	return false
