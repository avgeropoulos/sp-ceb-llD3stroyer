extends SceneTree
## Plays the level with a robot to make sure it can be beaten.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/smoke.gd

var main
var frame := 0
var phase := "setup"
var phase_frames := 0
var failed := ""
var start_p := Vector3.ZERO
var far := 0.0


func _initialize() -> void:
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Look.toy = a.has("toy")
	print("look: ", "3D toys" if Look.toy else "paper")
	for w in Worlds.ORDER:   # "-- frost" plays Frosty Peaks, and so on
		if a.has(w):
			Boot.place = "level"
			Boot.level = w
	print("world: ", Boot.level)
	main = load("res://main.tscn").instantiate()
	root.add_child(main)


func fail(why: String) -> void:
	if failed == "":
		failed = why


func _process(_dt: float) -> bool:
	frame += 1
	phase_frames += 1
	match phase:
		"setup":
			if frame < 5:
				return false
			_unit_checks()
			main.bot = true
			main.invincible = true
			main.start_game()
			_next("run")
		"run":
			if main.level.open:   # open worlds: roam for a while (tests/open_check.gd plays them to the end)
				if phase_frames == 1:
					start_p = main.leader().position
				far = maxf(far, main.leader().position.distance_to(start_p))
				if phase_frames > 60 * 25:
					var moved: float = far
					print("roamed %.1f blocks, %d coins, %d bad guys, stars %d" % [moved, main.coins, main.beaten, main.open_play.count()])
					if moved < 12:
						fail("the robot didn't get anywhere in the open world")
					else:
						_next("done")
			elif main.boss_on:
				print("reached Krag Jr. after %.1f s at x=%.1f" % [phase_frames / 60.0, main.leader().position.x])
				_next("boss")
			elif phase_frames > 60 * 150:
				fail("the robot got stuck at x=%.1f y=%.1f" % [main.leader().position.x, main.leader().position.y])
		"boss":
			var b = main.boss
			if phase_frames > 90 and is_instance_valid(b) and b.dying < 0:
				b.safe_t = 0
				b.hurt(3, main)
			if main.boss_done:
				print("Krag Jr. beaten")
				_next("flag")
			elif phase_frames > 60 * 30:
				fail("Krag Jr. could not be beaten")
		"flag":
			if main.state == "clear":
				print("LEVEL CLEAR in %.1f s total, %d coins, ores %s, %d bad guys" % [frame / 60.0, main.coins, str(main.ores), main.beaten])
				_next("done")
			elif phase_frames > 60 * 30:
				fail("never reached the flag (x=%.1f)" % main.leader().position.x)
		"done":
			if phase_frames > 30:
				print("TEST PASSED")
				quit(0)
				return true
	if failed != "":
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	return false


func _next(p: String) -> void:
	phase = p
	phase_frames = 0


func _unit_checks() -> void:
	# SWAP cycles Rocco -> Blocky -> partner -> Rocco
	var order := []
	for i in 3:
		main.do_swap()
		order.append(main.leader_name())
	if order != ["blocky", "mon", "rocco"]:
		fail("swap order was %s" % str(order))
	# stone takes 2 hits, iron 3, bedrock never breaks
	var lv: Level = main.level
	lv.set_cell(10, 6, 6, Level.STONE)
	main.hit_block(Vector3i(10, 6, 6), 1, main.leader())
	if lv.get_cell(10, 6, 6) != Level.STONE:
		fail("stone broke after one hit")
	main.hit_block(Vector3i(10, 6, 6), 1, main.leader())
	if lv.get_cell(10, 6, 6) != Level.AIR or main.ores.stone != 1:
		fail("stone didn't break / give stone after two hits")
	main.hit_block(Vector3i(10, 0, 6), 50, main.leader())
	if lv.get_cell(10, 0, 6) != Level.BEDROCK:
		fail("bedrock broke")
	# a ? block turns into a used block and gives something
	var before: int = main.coins + main.hearts
	lv.set_cell(12, 8, 6, Level.MYSTERY)
	main.hit_block(Vector3i(12, 8, 6), 1, main.leader())
	if lv.get_cell(12, 8, 6) != Level.USED:
		fail("? block didn't open")
	if main.coins + main.hearts <= before and main.hearts < main.MAX_HEARTS:
		fail("? block gave nothing")
	lv.set_cell(12, 8, 6, Level.AIR)
	main.ores.stone = 0
	main.coins = 0
	print("unit checks done")
