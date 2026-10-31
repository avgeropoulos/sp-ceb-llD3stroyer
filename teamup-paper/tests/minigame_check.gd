extends SceneTree
## The ARCADE and its mini games, and the POSEIDON FLOOD:
## home -> walk into the arcade -> menu; its walls can't be broken; KRAG BOWL (a strike
## straight down the middle at full power, scores add up, the Grumbler bowls too, the game
## ends, the prize is paid, back home); COIN SCRAMBLE and PENGUIN RACE play to the end
## with a result; POSEIDON FLOOD needs a second tap, floods, washes bad guys away, drains,
## and only works once per visit (and never at home).
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/minigame_check.gd [-- toy]

var f := 0
var step := 0
var wait := 0
var failed := ""
var g
var mg
var coins0 := 0
var foes0 := 0
var beaten0 := 0
var max_water := 0.0


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false   # tests/scene_check.gd covers the cutscenes
	OpenPlay.select_enabled = false
	Saves.prefix = "user://test_minigame"
	_clean()
	Boot.place = "title"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func ready_in(state: String) -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == state


func _process(_dt: float) -> bool:
	f += 1
	if wait > 0:
		wait -= 1
		return false
	match step:
		0:
			if ready_in("title"):
				g.slot_pick(1)
				g.choose_partner("water")
				step = 1
		1:
			if ready_in("home"):
				var h = g.home
				check(h.arcade_at != Vector3.ZERO, "the arcade should be built at home")
				check(g.pause_count() == 3, "no POSEIDON FLOOD at home")
				# the arcade's walls can't be broken
				h.mode = "build"
				h.under = Vector3i(floori(h.arcade_at.x) + 3, int(h.arcade_at.y) + 1, floori(h.arcade_at.z))
				h.has_under = true
				g.leader().position = h.arcade_door()
				check(not h.remove(), "arcade walls must not break")
				check(g.level.get_cell(h.under.x, h.under.y, h.under.z) != Level.AIR, "the wall should still be there")
				h.mode = ""
				# walk in through the door
				g.play_t = 5.0
				g.leader().position = h.arcade_door()
				g._step_home(1.0 / 60)
				check(g.state == "home", "standing outside shouldn't open the arcade")
				g.leader().position = h.arcade_at + Vector3(0, 0.02, 0.2)
				g._step_home(1.0 / 60)
				check(g.state == "arcade", "walking in should open the ARCADE menu (state %s)" % g.state)
				coins0 = g.coins
				g.start_minigame("bowling")
				mg = g.minigames
				check(g.state == "minigame" and mg != null and mg.id == "bowling", "KRAG BOWL should start")
				check(not g.follower().visible, "the friend waits outside")
				step = 2
		2:   # KRAG BOWL: always straight down the middle at full power
			if mg.phase == "play" and mg.turn == "you" and mg.bphase == "aim":
				mg.bowl(0.0, 1.0)
			if mg.phase == "result":
				var you: Array = mg.scores["you"]
				check(you.size() == MiniGames.FRAMES, "3 frames should be bowled (%d)" % you.size())
				check(you.size() > 0 and int(you[0][0]) == 10, "straight at full power should be a STRIKE (got %s)" % str(you))
				check(mg.total("you") == MiniGames.FRAMES * 20, "3 strikes = 60 (got %d)" % mg.total("you"))
				check(mg.scores["cpu"].size() == MiniGames.FRAMES, "the Grumbler should bowl every frame")
				var cpu_pins := 0
				for fr in mg.scores["cpu"]:
					for k in fr:
						cpu_pins += int(k)
				check(cpu_pins > 0, "the Grumbler should knock some pins down")
				check(mg.won and mg.prize == MiniGames.PRIZE_WIN, "60 should win the prize")
				print("bowling: you %d, grumbler %d (%s)" % [mg.total("you"), mg.total("cpu"), str(mg.scores["cpu"])])
				g.minigame_done(mg.prize)
				check(g.state == "home" and g.minigames == null, "should be back home")
				check(g.coins == coins0 + MiniGames.PRIZE_WIN, "the prize should be paid (%d -> %d)" % [coins0, g.coins])
				check(g.follower().visible, "the friend comes back")
				check(g.leader().position.distance_to(g.home.arcade_door()) < 0.5, "you come out of the arcade door")
				g.start_minigame("coins")
				mg = g.minigames
				mg.autopilot = true
				step = 3
			elif f > 60 * 200:
				check(false, "bowling never ended (frame %d, %s, %s)" % [mg.frame, mg.turn, mg.bphase])
		3:   # COIN SCRAMBLE with the robot grabbing coins
			if mg.phase == "play" and mg.time_left > 6:
				mg.time_left = 6.0
			if mg.phase == "result":
				var rivals_got: int = mg.counts[0] + mg.counts[1] + mg.counts[2]
				print("coin scramble: you %d, grumblers %d %d %d -> %s" % [mg.counts["you"], mg.counts[0], mg.counts[1], mg.counts[2], mg.result_text])
				check(mg.counts["you"] > 0, "the robot should grab coins")
				check(rivals_got > 0, "the Grumblers should grab coins too")
				check(mg.result_text != "" and mg.prize > 0, "there should be a result and a prize")
				g.minigame_done(mg.prize)
				g.start_minigame("race")
				mg = g.minigames
				mg.autopilot = true
				step = 4
		4:   # PENGUIN RACE
			if mg.phase == "result":
				print("penguin race: %s in %.1f s, order %s" % [mg.result_text, mg.race_t, str(mg.finish_order)])
				check("you" in mg.finish_order, "the robot should finish the race")
				check(mg.result_text != "", "there should be a result")
				var penguins_done := 0
				for k in mg.finish_order:
					if not (k is String):
						penguins_done += 1
				check(penguins_done >= 1 or mg.won, "the penguins race too")
				g.minigame_done(mg.prize)
				check(g.state == "home", "back home after the race")
				g.save_game(false)
				Boot.level = "meadow"
				g.go("level", 1)
				step = 5
		5:   # POSEIDON FLOOD in Meadow Mine
			if ready_in("play"):
				g.invincible = true
				g._pause("play")
				check(g.pause_count() == 5, "POSEIDON FLOOD should be in the pause menu (%d)" % g.pause_count())
				check(g.pause_label(4) == "POSEIDON FLOOD", "label should be POSEIDON FLOOD")
				g.pause_choice(4)
				check(g.flood == null and g.state == "paused", "one tap must NOT flood (it asks again)")
				check(g.pause_label(4).begins_with("TAP AGAIN"), "the button should say TAP AGAIN")
				foes0 = 0
				for fo in g.foes:
					if is_instance_valid(fo) and fo.alive and fo.dying < 0 and not fo.is_boss and fo.position.y < Flood.TOP - 0.5:
						foes0 += 1
				beaten0 = g.beaten
				g.pause_choice(4)
				check(g.flood != null and g.state == "play", "the second tap should flood")
				step = 6
				f = 0
		6:
			if g.flood:
				max_water = maxf(max_water, g.flood.water_y)
				if g.flood.t > Flood.RISE + 1.0 and g.flood.t < Flood.RISE + 1.1:
					check(g.leader().position.y > Flood.TOP - 2.0, "the team should float on the water (y %.1f)" % g.leader().position.y)
			if g.flood == null and f > 30:
				print("flood: water up to %.1f, washed away %d of %d bad guys" % [max_water, g.beaten - beaten0, foes0])
				check(max_water > Flood.TOP - 0.5, "the water should rise")
				check(g.beaten - beaten0 >= maxi(1, foes0 / 2), "the flood should wash the bad guys away (%d of %d)" % [g.beaten - beaten0, foes0])
				check(g.flood_used, "the flood is used up")
				g._pause("play")
				g.pause_choice(4)
				g.pause_choice(4)
				check(g.flood == null and g.pause_label(4) == "FLOOD USED", "only one flood per visit")
				g.state = "play"
				step = 7
				wait = 3
			elif f > 60 * 20:
				check(false, "the flood never drained")
				step = 7
		7:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 300:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
