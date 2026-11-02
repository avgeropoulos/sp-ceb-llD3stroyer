extends SceneTree
## RAINBOW ROAD: lands on the rainbow island from the sky map, the race counts down,
## items work (mushroom, banana, shell), falling off the road brings FLUFFY, the AI karts
## lap the track, the robot drives you over the line, the podium gives the prize (and the
## RAINBOW STAR for 1st), and it's saved; then you're back on the sky map.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/kart_check.gd -- [toy]

var g
var k: KartRace
var f := 0
var step := 0
var failed := ""
var t0 := 0


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	Saves.prefix = "user://test_kart"
	_clean()
	Saves.write(1, {"v": 2, "coins": 10, "cleared_w": {"meadow": 1}, "cleared": 1, "mon": "spark"})
	Boot.place = "map"
	Boot.slot = 1
	Boot.from_level = "home"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "map":
				check(g.sky.order.has("rainbow") and g.sky.can_land("rainbow"), "the RAINBOW island should be on the map and open")
				check(g.sky.fly_to("rainbow"), "should fly to the rainbow island")
				step = 1
				f = 0
		1:
			if g.state == "kart":
				k = g.kart
				check(k.phase == "count", "the race should start with a countdown")
				check(k.racers.size() == 6, "6 karts (got %d)" % k.racers.size())
				check(k.L > 300, "the track should be long (%.0f)" % k.L)
				step = 2
				f = 0
			elif f > 60 * 20:
				check(false, "the rocket never landed on the rainbow island (state %s)" % g.state)
				step = 9
		2:
			if f == 30:
				check(k.phase == "count" and k.you.speed == 0.0, "nobody moves before GO")
			if k.phase == "race":
				check(k.count_t >= 3.0, "GO comes after 3-2-1")
				# --- items, tested on the side while the race runs ---
				var y: Dictionary = k.you
				k.give_item(y, "mushroom")
				k._use_item(y)
				check(y.boost > 1.0, "MUSHROOM should boost")
				var r1: Dictionary = k.racers[0]
				for rc in k.racers:   # clear the lane in front of you
					if rc != y and rc != r1:
						rc.lat = -y.lat
				# a shell straight at a kart just ahead
				k.give_item(y, "shell")
				r1.s = fposmod(y.s + 6.0, k.L)
				r1.lat = y.lat
				r1.y = 0.0
				k._use_item(y)
				for i in 20:
					k._update_items(1.0 / 60)
				check(r1.spin > 0, "a GREEN SHELL should spin out the kart in front")
				# a banana dropped in front of another kart
				var r2: Dictionary = k.racers[1]
				k.give_item(r2, "banana")
				k._use_item(r2)
				check(k.bananas.size() == 1, "BANANA should be dropped")
				var bn: Dictionary = k.bananas[0]
				var r3: Dictionary = k.racers[2]
				r3.s = fposmod(bn.s - 0.4, k.L)
				r3.lat = bn.lat
				r3.spin = 0.0
				r3.y = 0.0
				r3.air = false
				r3.fall = -1.0
				r3.rescue = -1.0
				k._drive(r3, 1.0 / 60)
				check(r3.spin > 0, "driving over a BANANA should spin out")
				# off the edge: FLUFFY brings you back
				var r4: Dictionary = k.racers[3]
				r4.lat = KartRace.W
				r4.air = false
				r4.y = 0.0
				r4.fall = -1.0
				k._drive(r4, 1.0 / 60)
				check(r4.fall >= 0, "leaving the road should make you fall")
				for i in 160:
					k._drive(r4, 1.0 / 60)
				check(r4.fall < 0 and r4.rescue < 0 and absf(r4.lat) < 0.5 and r4.y == 0.0, "FLUFFY should put you back on the road (lat %.1f y %.1f)" % [r4.lat, r4.y])
				k.autopilot = true   # the robot drives you round
				t0 = f
				step = 3
		3:
			if k.you.done:
				var ai_laps := 0
				for rc in k.racers:
					if not rc.you:
						ai_laps = maxi(ai_laps, rc.lap)
				check(ai_laps >= 2, "the other karts should be lapping too (best %d)" % ai_laps)
				print("race finished in %.1f s, place %d" % [k.race_t, k.place])
				step = 4
				f = 0
			elif f - t0 > 60 * 150:
				check(false, "the robot never finished 3 laps (lap %d, s %.0f)" % [k.you.lap, k.you.s])
				step = 9
		4:
			if k.phase == "podium" and k.can_leave():
				check(k.place >= 1 and k.place <= 6, "a place 1-6")
				check(k.prize == KartRace.PRIZES[k.place - 1], "prize matches the place")
				check(k.star_won == (k.place == 1), "the star is only for 1st")
				var before: int = g.coins
				var place: int = k.place
				var star: bool = k.star_won
				var prize: int = k.prize
				g.kart_done()
				check(g.state == "map" and g.kart == null, "should be back on the sky map")
				check(g.coins == before + prize, "the prize should be paid")
				var d := Saves.read(1)
				check(int(d.get("kart_best", 0)) == place, "best place should be saved")
				check(bool(d.get("kart_star", false)) == star, "the rainbow star should be saved")
				# winning always gives the star (force a win to check that path too)
				g.start_kart("map")
				g.kart.place = 1
				g.kart.you.done = true
				g.kart._start_podium()
				check(g.kart.star_won and g.kart.prize == KartRace.PRIZES[0], "1st = RAINBOW STAR + big prize")
				g.kart.podium_t = 3.0
				g.kart_done()
				check(g.kart_star and bool(Saves.read(1).get("kart_star", false)), "the RAINBOW STAR should be saved")
				check(Decor.locked(Decor.find("rainbow_cup"), g) == false, "the RAINBOW CUP decoration should unlock")
				step = 9
			elif f > 60 * 20:
				check(false, "no podium")
				step = 9
		9:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "" and step != 9:
		step = 9
	return false
