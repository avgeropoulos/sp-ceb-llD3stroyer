extends SceneTree
## Every world's castle boss: it's the right one (bosses.gd), its name is on the health bar,
## it attacks (shots, dives, dashes, stomps), takes damage, DRY KRAG falls apart and gets
## back up once, and when it's beaten the flag comes down.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/boss_check.gd [-- toy] [-- <world> to start there]

var g
var f := 0
var wi := 0
var step := 0
var failed := ""
var attacked := false
var fell_apart := false
var came_back := false
var report := []


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	_start(maxi(0, Worlds.ORDER.find(OS.get_cmdline_user_args()[-1])) if OS.get_cmdline_user_args().size() > 0 else 0)


func _start(i: int) -> void:
	wi = i
	step = 0
	f = 0
	attacked = false
	fell_apart = false
	came_back = false
	Boot.place = "level"
	Boot.level = Worlds.ORDER[i]
	Boot.slot = 0
	if is_instance_valid(g):
		g.go("level", 0)
	else:
		root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "%s: %s" % [Worlds.ORDER[wi], why]


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if failed != "" or f > 60 * 60:
		if failed == "":
			failed = "%s: timed out at step %d" % [Worlds.ORDER[wi], step]
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if not is_instance_valid(g) or not g.is_inside_tree() or g.level.world != Worlds.ORDER[wi]:
		return false
	var b: Foe = g.boss
	match step:
		0:
			if g.state == "play":
				g.invincible = true
				var want := Bosses.for_world(Worlds.ORDER[wi])
				check(is_instance_valid(b) and b.kind == want, "the castle boss should be %s" % want)
				check(b.hp == int(g.world_info().boss_hp), "boss HP should come from the world")
				g.open_play.open_gate()
				var ar: Rect2 = g.level.arena
				g.leader().position = Vector3(ar.get_center().x + 0.5, g.level.arena_y + 0.05, ar.end.y - 3.0)
				step = 1
				f = 0
		1:
			if g.boss_on:
				check(b.boss_name() == Bosses.name_of(b.kind), "the health bar name")
				step = 2
				f = 0
			elif f > 60 * 5:
				check(false, "the boss never woke up")
		2:   # let it fight for a while: it must attack
			for sh in g.shots:
				if sh.owner == b:
					attacked = true
			if b.state in ["dive", "spin", "stomp", "wand", "ring", "balls", "fire", "bluefire", "bones", "teleport"]:
				attacked = true
			if attacked and f > 60:
				step = 3
				f = 0
			elif f > 60 * 14:
				check(false, "the boss never attacked (state %s)" % b.state)
		3:   # beat it
			if is_instance_valid(b) and b.dying < 0:
				if b.state == "heap":
					fell_apart = true
				elif fell_apart and not came_back and b.hp > 0:
					came_back = true
				b.safe_t = 0
				var hp0: int = b.hp
				b.hurt(3, g)
				if b.state != "heap" and hp0 > 0:
					check(b.hp < hp0 or b.dying >= 0 or b.state == "heap", "the boss didn't take damage")
			if g.boss_done:
				if b.kind == "drykrag":
					check(fell_apart and came_back, "DRY KRAG should fall apart and come back once")
				step = 4
				f = 0
		4:
			if f > 100:
				check(g.open_play.flag != null, "the flag should come down")
				report.append("%s: %s" % [Worlds.ORDER[wi], b.boss_name() if is_instance_valid(b) else Bosses.name_of(Bosses.for_world(Worlds.ORDER[wi]))])
				if wi + 1 < Worlds.ORDER.size():
					_start(wi + 1)
				else:
					print("bosses: ", ", ".join(report))
					print("TEST PASSED")
					quit(0)
					return true
	return false
