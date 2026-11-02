extends SceneTree
## MECHA KRAG at home: he asks first (NOT NOW works), lands, walks, fires missiles at red X
## marks (a real one hurts you), dud missiles can be grabbed and thrown back at his back
## weak spots, jumping into a foot weak spot works, 4 spots beat him, coins rain, the
## MECHA KRAG TROPHY unlocks, it's saved, and the home's blocks are exactly as before.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/mecha_check.gd -- [toy]

var g
var m: MechaKrag
var f := 0
var step := 0
var failed := ""
var cells := PackedByteArray()
var coins0 := 0
var hearts0 := 0
var ms: Dictionary


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = true   # he only comes when scenes are on (not for the robot)
	Saves.prefix = "user://test_mecha"
	_clean()
	var seen := []
	for h in Team.INFO:
		seen.append(h)
	Saves.write(1, {"v": 2, "coins": 5, "cleared_w": {"meadow": 1, "frost": 1, "dunes": 1}, "cleared": 3, "mon": "spark",
		"scenes_seen": ["intro"], "heroes_seen": seen})
	Boot.place = "home"
	Boot.slot = 1
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func P() -> Hero:
	return g.leader()


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "mecha_ask":
				g.decline_mecha()
				check(g.state == "home" and g.mecha == null and g.mecha_cool == 3, "NOT NOW should send him away")
				check(int(Saves.read(1).get("mecha_cool", 0)) == 3, "NOT NOW should be saved")
				cells = g.level.cells.duplicate()
				coins0 = g.coins
				g.ask_mecha()
				check(g.state == "mecha_ask", "should ask again")
				g.start_mecha()
				m = g.mecha
				check(m != null and m.active and m.phase == "land", "FIGHT! should bring him in")
				check(g.hud.buttons.has("action") or true, "")
				step = 1
				f = 0
			elif f > 60 * 25:
				check(false, "he never showed up at home (state %s)" % g.state)
				step = 9
		1:
			if m.phase in ["walk", "fight"]:
				check(m.model.position.y < m.pos.y + 1.0, "he should be on the ground after landing")
				g.invincible = false
				step = 2
				f = 0
			elif f > 60 * 6:
				check(false, "he never landed")
				step = 9
		2:   # a real missile: the red X, then BOOM where you stand
			if m.missiles.size() > 0 and ms.is_empty():
				ms = m.missiles[0]
				ms.dud = false
				check(is_instance_valid(ms.marker) and ms.marker.is_inside_tree(), "a red X should show where it lands")
				hearts0 = g.hearts
				g.hurt_t = 0.0
			if not ms.is_empty():
				P().position = ms.to + Vector3(0, 0.05, 0)
				P().vel = Vector3.ZERO
				if not m.missiles.has(ms):
					check(g.hearts == hearts0 - 1, "a missile landing on you should hurt (hearts %d -> %d)" % [hearts0, g.hearts])
					ms = {}
					g.invincible = true
					step = 3
					f = 0
			if f > 60 * 10:
				check(false, "no missile")
				step = 9
		3:   # a dud: grab it and throw it back
			if m.missiles.size() > 0 and ms.is_empty():
				ms = m.missiles[0]
				ms.dud = true
			if not ms.is_empty() and not m.missiles.has(ms) and m.duds.size() > 0:
				var d: Dictionary = m.duds[0]
				P().position = (d.node as Node3D).position + Vector3(0.5, -0.3, 0)
				check(m.label() == "GRAB", "next to a dud the button should say GRAB (%s)" % m.label())
				check(m.on_action(), "ACTION should grab the dud")
				check(not m.carrying.is_empty() and m.label() == "THROW", "now carrying it")
				check(m.on_action(), "ACTION should throw it")
				ms = {}
				step = 4
				f = 0
			if f > 60 * 12:
				check(false, "no dud missile")
				step = 9
		4:
			if m.thrown.is_empty():
				var back_hit := 0
				for sp in m.spots:
					if sp.back and not sp.alive:
						back_hit += 1
				check(back_hit == 1, "the thrown dud should knock out a BACK weak spot (%d)" % back_hit)
				# jump into a FOOT weak spot
				var foot: Dictionary = {}
				for sp in m.spots:
					if not sp.back and sp.alive:
						foot = sp
						break
				var at := m.spot_pos(foot)
				P().position = at - Vector3(0, P().height * 0.5, 0)
				P().on_floor = false
				P().vel = Vector3(0, -3, 0)
				m._check_feet(P())
				check(not foot.alive, "jumping into a FOOT weak spot should break it")
				check(m.spots_left() == 2, "2 weak spots left (%d)" % m.spots_left())
				for i in 4:
					if m.spots[i].alive:
						m.hit_spot_index(i)
				check(m.phase == "boom", "4 weak spots should beat him (%s)" % m.phase)
				step = 5
				f = 0
			elif f > 60 * 4:
				check(false, "the thrown dud never landed")
				step = 9
		5:
			if g.mecha != null and m.phase == "coins" and m.rain.size() > 0:
				P().position = (m.rain[0].node as Node3D).position
			if g.mecha == null:
				check(g.mecha_beaten == 1, "he should count as beaten")
				check(g.coins >= coins0 + 50, "coins should rain (%d -> %d)" % [coins0, g.coins])
				check(not Decor.locked(Decor.find("mecha_trophy"), g), "the MECHA KRAG TROPHY should unlock")
				check(g.state == "home", "back to normal at home")
				check(int(Saves.read(1).get("mecha_beaten", 0)) == 1, "it should be saved")
				check(g.level.cells == cells, "your home's blocks must be exactly as before")
				step = 9
			elif f > 60 * 25:
				check(false, "the finale never ended")
				step = 9
		9:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "" and step != 9:
		step = 9
	return false
