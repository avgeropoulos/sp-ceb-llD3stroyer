extends SceneTree
## Cutscenes: every scene plays to the end and puts everyone back; SKIP works (only after
## 1 second); the intro plays on a new file at home and is saved as seen; MOVIES replays
## seen scenes; a funny scene can pop up at home.
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/scene_check.gd

var g
var f := 0
var step := 0
var failed := ""
var queue: Array = []
var cur := ""
var before := {}
var wait_f := 0


func _initialize() -> void:
	Look.loaded = true
	OpenPlay.select_enabled = false   # tests/quest_check.gd covers the star select
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = true
	Saves.prefix = "user://test_scene"
	_clean()
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for ext in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + ext):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + ext))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d (%s): %s" % [step, cur, why]


func ready_in(st: String) -> bool:
	g = Boot.current
	return is_instance_valid(g) and g.is_inside_tree() and g.state == st


func _process(_dt: float) -> bool:
	f += 1
	match step:
		0:
			if ready_in("play"):
				g.invincible = true
				check(Worlds.ORDER.size() >= 5 and float(Worlds.info("moon").get("gravity", 1.0)) < 1.0, "the moon should have low gravity")
				queue = Cutscene.ORDER.duplicate()
				step = 1
		1:   # play every scene to the end
			if cur == "":
				if queue.is_empty():
					check(g.scenes_seen.size() == Cutscene.ORDER.size(), "every scene should be marked seen (%s)" % str(g.scenes_seen))
					var d: Dictionary = g.save_data(true)
					check(d.get("scenes_seen", []).size() == Cutscene.ORDER.size(), "scenes_seen should be saved")
					step = 2
					f = 0
				else:
					cur = queue.pop_front()
					before = {}
					for k in g.heroes:
						before[k] = [g.heroes[k].position, g.heroes[k].visible]
					check(g.play_scene(cur), "play_scene refused")
					check(g.state == "scene", "state should be scene")
					wait_f = 0
			else:
				wait_f += 1
				if g.state == "play":
					print("scene %s: %.1f s" % [cur, wait_f / 60.0])
					check(wait_f > 60 * 4, "the scene ended too soon (%d frames)" % wait_f)
					for k in before:
						check(g.heroes[k].position.distance_to(before[k][0]) < 0.01, "%s wasn't put back" % k)
						check(g.heroes[k].visible == before[k][1], "%s visibility wasn't put back" % k)
					check(g.cutscene == null, "the cutscene should be gone")
					cur = ""
				elif wait_f > 60 * 45:
					check(false, "never ended")
					cur = ""
		2:   # SKIP: not in the first second, then yes
			if f == 1:
				cur = "skip"
				g.play_scene("intro")
			if f == 20:
				g.scene_tap()
				check(g.state == "scene", "a tap in the first second shouldn't skip")
			if f == 80:
				g.scene_tap()
				check(g.state == "play", "tapping should skip the scene")
				# a new file at home: the intro plays by itself
				Saves.write(1, {"v": 2, "mon": "fire", "coins": 5})
				g.go("home", 1)
				step = 3
				f = 0
		3:
			if ready_in("scene"):
				cur = "home intro"
				check(g.cutscene.id == "intro", "a new file should get the intro (got %s)" % g.cutscene.id)
				step = 4
				f = 0
			elif f > 60 * 20:
				check(false, "the intro never started at home (state %s)" % (g.state if is_instance_valid(g) else "?"))
				step = 9
		4:
			if f == 90:
				g.scene_tap()
				check(g.state == "home", "after the intro we should be home (state %s)" % g.state)
				check("intro" in Saves.read(1).get("scenes_seen", []), "the intro should be saved as seen")
				# the MOVIES TV
				g.leader().position = g.MOVIE_SPOT + Vector3(1.2, 0, 0.6)
				check(g.near_tv(), "standing by the TV should show MOVIES")
				g.open_movies()
				check(g.state == "movies", "MOVIES should open")
				g.movie_pick(Cutscene.ORDER.find("banana"))
				check(g.state == "movies", "an unseen movie shouldn't play")
				g.movie_pick(0)
				check(g.state == "scene", "a seen movie should play")
				cur = "movies"
				step = 5
				f = 0
		5:
			if f == 80:
				g.scene_tap()
				check(g.state == "movies", "after a movie we should be back at MOVIES")
				g.hud._movies_tap(g.hud.movies_back_rect().get_center())
				check(g.state == "home", "BACK should go home")
				# a funny scene at home
				g.leader().position = Vector3(Level.HOME_OX + 20.5, Level.HOME_GY, Level.HOME_OZ + 7.5)
				g.leader().on_floor = true
				Cutscene.last_funny_ms = -10000000
				g.maybe_funny(1.0)
				check(g.state == "scene" and g.cutscene.id in Cutscene.FUNNY, "a funny scene should play")
				g.maybe_funny(1.0)
				cur = "funny"
				step = 6
				f = 0
		6:
			if g.state == "home":
				check(f > 60 * 4, "the funny scene ended too soon")
				Cutscene.last_funny_ms = Time.get_ticks_msec()
				var was: String = Cutscene.last_funny
				g.maybe_funny(1.0)
				check(g.state == "home", "no second funny scene right away")
				check(was in Cutscene.FUNNY, "last_funny should be remembered")
				step = 9
			elif f > 60 * 30:
				check(false, "the funny scene never ended")
				step = 9
		9:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	if f > 60 * 400:
		print("TEST FAILED: timed out at step %d" % step)
		quit(1)
		return true
	return false
