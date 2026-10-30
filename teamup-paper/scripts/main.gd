extends Node3D
## Rocco & Blocky: Team Up! Paper 3D — one level, built like the real game:
## one hero out at a time (Rocco or Blocky) plus Sparky, SWAP between them,
## mine blocks for ores, bonk ? blocks, stomp bad guys and beat Krag Jr.

const MAX_HEARTS := 5
const SUPER_TIME := 25.0
const SPRINT_SPEED := 1.55
const START_COINS := 30       # a new file starts with some coins to build with
const STATS := {
	"rocco": {"speed": 5.6, "jump": 12.6, "hw": 0.3, "h": 1.45, "size": 1.8, "feet": 28.0},
	"blocky": {"speed": 5.0, "jump": 10.8, "hw": 0.32, "h": 1.5, "size": 1.9, "feet": 30.0},
	"mon": {"speed": 6.6, "jump": 13.2, "hw": 0.32, "h": 0.9, "size": 1.95, "feet": 48.0},
}
const ORE_OF := {Level.STONE: "stone", Level.IRON: "iron", Level.GOLD: "gold", Level.DIAMOND: "diamond"}
const GO_LINES := ["Here we go!", "Andiamo! Let's go!", "Okey dokey!"]
const OUCH_LINES := ["Mamma mia!", "Oh no!", "Ow ow ow!"]

var state := "loading"   # a short LOADING... screen first, then "title"
var level: Level
var fx: Fx
var hud: Hud
var cam: Camera3D
var heroes := {}
var human := "rocco"          # which of Rocco / Blocky is out
var control := "human"        # "human" or "mon" (Sparky)
var hearts := MAX_HEARTS
var coins := 0
var ores := {"stone": 0, "iron": 0, "gold": 0, "diamond": 0}
var beaten := 0
var super_charge := 0.0
var foes: Array[Foe] = []
var shots: Array = []
var coin_nodes: Array = []
var hurt_t := 0.0
var safe_pos := Vector3(3, 5, 6.5)
var checkpoint := Vector3(3, 5, 6.5)
var checkpoint_hit := false
var boss: Foe
var boss_on := false
var boss_done := false
var clear_t := 0.0
var play_t := 0.0
var cam_focus := Vector3.ZERO
var jump_buf := 0.0
var jump_hold_t := 0.0        # every jump goes at least this high, even on a quick tap
var cam_yaw := 0.0            # the home camera turns around the hero (drag the screen)
var cam_pitch := 0.42
var flying := false           # FLY at home, like Minecraft creative
var break_t := 0.0            # holding a finger on a block breaks it (then keeps breaking)
var last_ouch := -10.0
var dennis_done := false
var warm_t := 0
var warm_nodes: Array = []

# save files
var slot := 0                 # 1-3 while playing a save file (0 = not saving, e.g. tests)
var slot_sel := 0             # highlighted file on the save screen (controller)
var erase_armed := 0          # the file whose ERASE was tapped once (tap again to erase)
var erase_t := 0.0
var pause_sel := 0
var save_note := ""           # "SAVED!" under the pause buttons
var save_note_t := 0.0
var coins_taken: Array = []   # which coins (by number) are collected
var foes_gone: Array = []     # which bad guys (by spawn number) are beaten
var cleared := 0              # times this file finished the level

# home base
var home: HomeBase            # only at home
var open_play: OpenPlay       # only in the open worlds (stars, gate, flag)
var gate_open := false        # Krag Jr.'s gold gate (5 stars)
var sky: SkyMap               # only on the sky map
var cleared_w := {}           # world id -> times cleared
var home_saved := {}          # the home from the save file (kept while you're in 1-1)
var paused_from := "play"     # "play" or "home": where RESUME goes back to
var partner_from := ""        # "new" (new file), "home" or "" (tests)
var taps := {}                # touch buttons pressed this frame (home buttons)

# partners
var mon_line := "spark"       # water / grass / fire / spark
var mon_xp := {"water": 0, "grass": 0, "fire": 0, "spark": 0}
var partner_sel := 0          # highlighted card on PICK YOUR PARTNER
var evolve_t := -1.0          # >= 0 while the evolution scene plays
var evolve_to := 0
var evolve_from := ""

# input for this frame
var move_vec := Vector2.ZERO
var jump_held := false
var jump_pressed := false
var action_pressed := false
var swap_pressed := false
var super_pressed := false
var pause_pressed := false
var sprinting := false        # full-tilt stick for a moment, Shift, or R1 / R2
var tilt_t := 0.0
var dust_t := 0.0

# testing helpers
var bot := false
var invincible := false

var updater: Updater
var original_cells := PackedByteArray()

# lag finder: remembers slow frames and what was happening (shown in the pause menu)
var doing := "starting"        # what's happening right now, e.g. "SUPER HYDRO WAVE"
var doing_t := 0.0
var slow: Array = []           # [ms, what] for the last few slow frames
var slow_count := 0
var sfx_players: Array[AudioStreamPlayer] = []
var sfx_cache := {}
var music: AudioStreamPlayer
var voice_it := ""
var voice_en := ""


func _ready() -> void:
	Boot.current = self
	Look.load_choice()
	_setup_input()
	_setup_world()
	level = Level.new()
	add_child(level)
	if Boot.place == "home":
		level.generate_home()
	elif Boot.place == "map":
		level.generate_empty()
	elif Boot.place == "level":
		level.generate(Boot.level)
	else:
		level.generate("meadow")
	fx = Fx.new()
	add_child(fx)
	for w in ["rocco", "blocky", "mon"]:
		var h := Hero.new()
		level.add_child(h)
		h.init(level, w, STATS[w])
		heroes[w] = h
	heroes.blocky.visible = false
	heroes.rocco.position = Vector3(4, 5, 6.8)
	heroes.blocky.position = heroes.rocco.position
	heroes.mon.position = Vector3(2.6, 5, 5.6)
	original_cells = level.cells.duplicate()
	if level.is_home:
		_ready_home()
		return
	if Boot.place == "map":
		_ready_map()
		return
	_ready_level()


func _ready_map() -> void:
	for h in heroes.values():
		h.visible = false
	_ready_common()
	sky = SkyMap.new()
	add_child(sky)
	sky.setup(self, level)
	play_music("theme")


## The parts of the scene every place has (HUD, sound, updater, camera).
func _ready_common() -> void:
	var hl := CanvasLayer.new()
	add_child(hl)
	hud = Hud.new()
	hud.game = self
	hl.add_child(hud)
	_setup_audio()
	updater = Updater.new()
	add_child(updater)
	cam_focus = heroes.rocco.position
	_update_camera(1.0)


func _ready_home() -> void:
	var o := Vector3(Level.HOME_OX, 0, Level.HOME_OZ)
	heroes.rocco.position = o + Vector3(10, 5, 6.8)
	heroes.blocky.position = heroes.rocco.position
	heroes.mon.position = o + Vector3(8.4, 5, 5.6)
	home = HomeBase.new()
	level.add_child(home)
	home.setup(self, level)
	_ready_common()
	play_music("cozy")


func _ready_level() -> void:
	if level.open:   # start at the start of the open world
		heroes.rocco.position = level.start_pos
		heroes.blocky.position = level.start_pos
		heroes.mon.position = level.start_pos + Vector3(-1.4, 0.3, 0.6)
		safe_pos = level.start_pos
		checkpoint = level.start_pos
	for si in level.spawns.size():
		var s: Dictionary = level.spawns[si]
		var f := Foe.new()
		level.add_child(f)
		f.init(level, s.kind, s.pos)
		f.set_meta("spawn", si)
		f.set_meta("camp", s.get("camp", false))
		foes.append(f)
		if s.kind == "kragjr":
			boss = f
			f.hp = int(world_info().boss_hp)
			f.max_hp = f.hp
	for ci in level.coin_spots.size():
		var c := level.prop("coin", level.coin_spots[ci] - Vector3(0, 0.35, 0), 0.7)
		c.set_meta("i", ci)
		coin_nodes.append(c)
	if level.open:
		open_play = OpenPlay.new()
		level.add_child(open_play)
		open_play.setup(self, level)
	else:
		level.prop("flag", Vector3(Level.CHECKPOINT_X, 5, 3.9), 3.2)
		level.prop("flag", Vector3(Level.FLAG_X, 5, 3.9), 4.6)
	_ready_common()
	if Boot.place == "title":
		play_music("theme")
		if not bot:
			updater.check()


## Called when the LOADING screen is done: go where Boot says.
func _after_loading() -> void:
	match Boot.place:
		"home":
			enter_home()
		"map":
			slot = Boot.slot
			_apply_save(Saves.read(slot))
			sky.refresh()
			state = "map"
			note("sky map")
			hud.say("SKY MAP", "Fly to a world and LAND!", 2.4)
		"level":
			var d := Saves.read(Boot.slot)
			start_game(Boot.slot)
			if not d.is_empty():
				_apply_save(d)
				if not d.get("in_level", false):
					hud.say(world_info().name, world_info().hello, 2.6)
		_:
			state = "title"


## Switches to the home base, 1-1 or the title (a fresh scene, with a short LOADING screen).
func go(place: String, n: int = 0) -> void:
	Boot.place = place
	Boot.slot = n
	if get_tree().current_scene == self:
		get_tree().reload_current_scene()
	else:   # tests run the game without a current scene
		var nm: Node = load("res://main.tscn").instantiate()
		nm.bot = bot
		nm.invincible = invincible
		get_parent().add_child(nm)
		queue_free()


# ------------------------------------------------------------------ the home base
func enter_home() -> void:
	slot = Boot.slot
	var d := Saves.read(slot)
	_apply_save(d)
	hearts = MAX_HEARTS
	var hd: Dictionary = d.get("home", {})
	if hd.get("blocks", []).is_empty() and hd.get("decor", []).is_empty():
		home.starter()   # a brand-new home
	else:
		home.load_data(hd)
	state = "home"
	play_t = 0
	note("home")
	hud.say("HOME", "BUILD and DECOR your base. Walk to the ROCKET to fly to the worlds!", 2.8)


func _step_home(dt: float) -> void:
	if pause_pressed:
		_pause("home")
		return
	play_t += dt
	hurt_t = maxf(0, hurt_t - dt)
	_turn_camera(dt)
	if taps.has("build"):
		home.mode = "" if home.mode == "build" else "build"
		sfx("swap", -6)
	if taps.has("decor"):
		home.mode = "" if home.mode == "decor" else "decor"
		sfx("swap", -6)
	if taps.has("done"):
		home.mode = ""
		save_game(false)
		sfx("heart", -6)
	if taps.has("fly") or Input.is_action_just_pressed("fly"):
		set_flying(not flying)
	if taps.has("partner"):
		partner_from = "home"
		partner_sel = maxi(0, Mons.ORDER.find(mon_line))
		state = "partner"
		return
	if swap_pressed:
		do_swap()
	# building: the middle of the screen aims; a quick tap places where you tapped,
	# holding a finger on a block breaks it. Controller: ACTION places, SUPER removes.
	if home.mode != "":
		var vs := get_viewport().get_visible_rect().size
		_aim_at(vs * 0.5)
		if taps.has("place") or action_pressed:
			home.place()
		if taps.has("remove") or super_pressed:
			home.remove()
		if taps.has("rotate"):
			home.rotate_front()
		if taps.has("paint"):
			home.paint_front()
		for p in hud.world_taps:
			_aim_at(p)
			home.place()
		if hud.hold_pos.x >= 0:
			break_t -= dt
			if break_t <= 0:
				break_t = 0.3
				_aim_at(hud.hold_pos)
				home.remove()
		else:
			break_t = 0.0
		if hud.world_taps.size() > 0 or hud.hold_pos.x >= 0:
			_aim_at(vs * 0.5)   # back to showing the middle of the screen
		action_pressed = false
	else:
		home.ghost.visible = false
		home.outline.visible = false
		if Input.is_action_just_pressed("action"):
			home.mode = "build"
	hud.world_taps.clear()
	_update_leader(leader(), dt)
	_update_follower(follower(), dt)
	if home.mode == "" and not flying and home.at_portal(leader().position) and play_t > 0.8:
		note("taking off")
		sfx("item")
		save_game(false)
		Boot.from_level = "home"
		go("map", slot)
		return
	_update_home_camera(dt)


## Points the building aim through a spot on the screen.
func _aim_at(p: Vector2) -> void:
	home.aim_ray(cam.project_ray_origin(p), cam.project_ray_normal(p), leader())


func set_flying(on: bool) -> void:
	flying = on
	sfx("swap" if on else "heart", -6)
	if on:
		leader().vel.y = 6.0
		hud.say("FLYING!", "Hold UP to go higher, DOWN to go lower", 1.8)


## Drag on the right side of the screen (or the right stick) to turn the camera.
func _turn_camera(dt: float) -> void:
	var look: Vector2 = hud.look_delta * 0.0065
	hud.look_delta = Vector2.ZERO
	var pads := Input.get_connected_joypads()
	if pads.size() > 0:
		var r := Vector2(Input.get_joy_axis(pads[0], JOY_AXIS_RIGHT_X), Input.get_joy_axis(pads[0], JOY_AXIS_RIGHT_Y))
		if r.length() > 0.2:
			look += r * dt * 2.6
	cam_yaw -= look.x
	cam_pitch = clampf(cam_pitch + look.y, -0.25, 1.35)
	Actor.view_right = Vector3.RIGHT.rotated(Vector3.UP, cam_yaw)
	Actor.view_back = Vector3.BACK.rotated(Vector3.UP, cam_yaw)


## The home camera: behind the hero, turned by cam_yaw / cam_pitch, pulled in when
## a wall is in the way. While building it looks a little over the shoulder.
func _update_home_camera(dt: float) -> void:
	var l := leader()
	var building: bool = home != null and home.mode != ""
	var dist := 6.5 if building else 8.0
	var want := l.position + Vector3(0, 1.4, 0)
	if building:
		want += Actor.view_right * 0.9
	if state == "title" or state == "loading" or state == "slots" or state == "partner":   # a slow turn around the start
		cam_yaw += dt * 0.08
		cam_pitch = 0.32
		dist = 13.0
		want = level.start_pos + Vector3(0, 2.0, 0)
	if boss_on and not boss_done:
		dist = 10.0
		var b := boss.position if is_instance_valid(boss) else want
		want = want.lerp(Vector3(b.x, want.y, b.z), 0.3)
	cam_focus = cam_focus.lerp(want, 1 - exp(-dt * 10))
	var back := Vector3(sin(cam_yaw) * cos(cam_pitch), sin(cam_pitch), cos(cam_yaw) * cos(cam_pitch))
	var d := dist
	var step := 0.25
	var t := 0.6
	while t < dist:   # stop in front of a wall instead of going through it
		var q := cam_focus + back * t
		if level.solid(floori(q.x), floori(q.y), floori(q.z)) and q.y > 0:
			d = maxf(t - 0.35, 0.8)
			break
		t += step
	cam.position = cam_focus + back * d
	cam.look_at(cam_focus, Vector3.UP)


## The rocket landed on an island.
func land_on(id: String) -> void:
	save_game(false)
	if id == "home":
		go("home", slot)
	else:
		Boot.level = id
		go("level", slot)


func _step_map(dt: float) -> void:
	if pause_pressed:
		_pause("map")
		return
	play_t += dt
	var land_tap: bool = taps.has("land") or jump_pressed or action_pressed
	if land_tap:
		sky.land()
	sky.step(dt, move_vec, sprinting)
	var r := sky.rocket.position
	cam_focus = cam_focus.lerp(r, 1 - exp(-dt * 4))
	cam.position = cam_focus + Vector3(0, 7.5, 12.5)
	cam.look_at(cam_focus + Vector3(0, -1.5, 0), Vector3.UP)


func _pause(from: String) -> void:
	paused_from = from
	state = "paused"
	pause_sel = 0
	save_note_t = 0


func _setup_input() -> void:
	var keys := {"left": [KEY_LEFT, KEY_A], "right": [KEY_RIGHT, KEY_D], "up": [KEY_UP, KEY_W], "down": [KEY_DOWN, KEY_S],
		"jump": [KEY_SPACE, KEY_K], "action": [KEY_X, KEY_J], "swap": [KEY_C, KEY_L], "super": [KEY_V, KEY_I], "pause": [KEY_ESCAPE, KEY_P],
		"sprint": [KEY_SHIFT, KEY_SEMICOLON], "fly": [KEY_F], "sink": [KEY_Q]}
	var pads := {"jump": JOY_BUTTON_A, "action": JOY_BUTTON_B, "swap": JOY_BUTTON_X, "super": JOY_BUTTON_Y, "pause": JOY_BUTTON_START,
		"left": JOY_BUTTON_DPAD_LEFT, "right": JOY_BUTTON_DPAD_RIGHT, "up": JOY_BUTTON_DPAD_UP, "down": JOY_BUTTON_DPAD_DOWN,
		"sprint": JOY_BUTTON_RIGHT_SHOULDER, "fly": JOY_BUTTON_LEFT_SHOULDER}
	for a in keys:
		if not InputMap.has_action(a):
			InputMap.add_action(a)
		for k in keys[a]:
			var e := InputEventKey.new()
			e.physical_keycode = k
			InputMap.action_add_event(a, e)
		if pads.has(a):
			var j := InputEventJoypadButton.new()
			j.button_index = pads[a]
			InputMap.action_add_event(a, j)


func _setup_world() -> void:
	var wi: Dictionary = Worlds.info(Boot.level if Boot.place == "level" else "meadow")
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color(wi.sky[0])
	sm.sky_horizon_color = Color(wi.sky[1])
	sm.ground_horizon_color = Color(wi.sky[1])
	sm.ground_bottom_color = Color(wi.sky[2])
	sm.sun_angle_max = 20
	sky.sky_material = sm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(wi.ambient)
	env.ambient_light_energy = 0.55
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 1.0
	env.tonemap_white = 6.0
	env.fog_enabled = true
	env.fog_mode = Environment.FOG_MODE_DEPTH
	env.fog_light_color = Color(wi.fog)
	env.fog_depth_begin = 28.0
	env.fog_depth_end = 140.0
	env.fog_density = 0.55
	if Boot.place == "home":   # the big home: see your whole city
		env.fog_depth_begin = 50.0
		env.fog_depth_end = 200.0
		env.fog_density = 0.35
	env.fog_sky_affect = 0.0
	env.adjustment_enabled = true
	env.adjustment_saturation = 1.12
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, 152, 0)   # from above and behind the camera, a bit to the left
	sun.light_color = Color(wi.sun)
	sun.light_energy = 1.0
	sun.shadow_enabled = true
	sun.shadow_opacity = 0.75
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	sun.directional_shadow_max_distance = 30
	add_child(sun)
	cam = Camera3D.new()
	cam.fov = 50
	cam.far = 200
	add_child(cam)
	cam.current = true


func _setup_audio() -> void:
	for i in 10:
		var p := AudioStreamPlayer.new()
		add_child(p)
		sfx_players.append(p)
	music = AudioStreamPlayer.new()
	music.volume_db = -7
	add_child(music)
	var it := DisplayServer.tts_get_voices_for_language("it")
	if it.size() > 0:
		voice_it = it[0]
	var en := DisplayServer.tts_get_voices_for_language("en")
	if en.size() > 0:
		voice_en = en[0]


func sfx(name: String, vol: float = 0.0) -> void:
	if not sfx_cache.has(name):
		sfx_cache[name] = load("res://sfx/%s.wav" % name)
	for p in sfx_players:
		if not p.playing:
			p.stream = sfx_cache[name]
			p.volume_db = vol
			p.play()
			return


func play_music(name: String, loop := true) -> void:
	var s: AudioStreamOggVorbis = load("res://music/%s.ogg" % name)
	s.loop = loop
	music.stream = s
	music.play()


func speak(text: String, who: String) -> void:
	var v := voice_it if who == "rocco" else voice_en
	if v == "":
		v = voice_en
	if v == "" or bot:
		return
	var pitch := {"rocco": 1.15, "blocky": 0.8, "mon": 1.8}.get(who, 1.0) as float
	DisplayServer.tts_stop()
	DisplayServer.tts_speak(text, v, 80, pitch, 1.1 if who == "mon" else 1.0)


# ------------------------------------------------------------------ who's who
func leader() -> Hero:
	return heroes.mon if control == "mon" else heroes[human]


func follower() -> Hero:
	return heroes[human] if control == "mon" else heroes.mon


func leader_name() -> String:
	return leader().who


func next_swap_name() -> String:
	if control == "mon":
		return "rocco"
	return "blocky" if human == "rocco" else "mon"


func action_label() -> String:
	match leader().who:
		"rocco":
			return "LONG JUMP" if leader().on_floor else "SLAM"
		"blocky":
			return "MINE"
	return Mons.LINES[mon_line].action


## The picture for a hero on menus ("rocco", "blocky" or "mon").
func face(who: String) -> String:
	if who == "mon":
		return Mons.face(heroes.mon.line, heroes.mon.stage)
	return who + "_idle"


func display_name(who: String) -> String:
	if who == "mon":
		return Mons.name_of(heroes.mon.line, heroes.mon.stage)
	return who.to_upper()


func mon_level(l: String = "") -> int:
	return Mons.level_of(int(mon_xp[mon_line if l == "" else l]))


func do_swap() -> void:
	note("SWAP")
	var l := leader()
	var p := l.position
	if control == "human" and human == "rocco":
		human = "blocky"
		heroes.rocco.visible = false
		heroes.rocco.shadow.visible = false
		_pop_in(heroes.blocky, p, heroes.rocco.facing)
		speak("Let's dig!", "blocky")
	elif control == "human":
		control = "mon"
		speak(display_name("mon").capitalize() + "!", "mon")
	else:
		control = "human"
		var was := human
		human = "rocco"
		heroes[was].visible = false
		heroes[was].shadow.visible = false
		var f: Vector3 = heroes.mon.position
		_pop_in(heroes.rocco, heroes[was].position, heroes[was].facing)
		heroes.mon.position = f
		speak("Okey dokey!", "rocco")
	sfx("swap")
	fx.burst(leader().position + Vector3(0, 0.8, 0), Color.WHITE, 14, 4, 4)
	hud.say(display_name(leader_name()) + "!", "", 0.8)


func _pop_in(h: Hero, p: Vector3, f: Vector3) -> void:
	h.position = p
	if level.box_hit(p, h.hw, h.height) != null:
		h.position.y = ceilf(p.y) + 0.01
	h.vel = Vector3.ZERO
	h.facing = f
	h.visible = true
	h.squash = Vector2(0.2, 0.2)
	h.spin()


# ------------------------------------------------------------------ main loop
func _gather_input() -> void:
	var mv := hud.stick
	var k := Input.get_vector("left", "right", "up", "down")
	if k.length() > 0.1:
		mv = k
	var pads := Input.get_connected_joypads()
	if pads.size() > 0:
		var a := Vector2(Input.get_joy_axis(pads[0], JOY_AXIS_LEFT_X), Input.get_joy_axis(pads[0], JOY_AXIS_LEFT_Y))
		if a.length() > 0.2:
			mv = a
	move_vec = mv.limit_length(1.0)
	# SPRINT: push the thumb stick all the way to the edge (keyboard: Shift; controller: R1 or R2)
	var stick_tilt := hud.stick.length()
	if pads.size() > 0:
		stick_tilt = maxf(stick_tilt, Vector2(Input.get_joy_axis(pads[0], JOY_AXIS_LEFT_X), Input.get_joy_axis(pads[0], JOY_AXIS_LEFT_Y)).length())
	var held_button := Input.is_action_pressed("sprint") or (pads.size() > 0 and Input.get_joy_axis(pads[0], JOY_AXIS_TRIGGER_RIGHT) > 0.5)
	if stick_tilt > 0.93:
		tilt_t += get_process_delta_time()
	else:
		tilt_t = 0.0
	sprinting = move_vec.length() > 0.3 and (tilt_t > 0.2 or held_button)
	jump_held = hud.is_held("jump") or Input.is_action_pressed("jump")
	jump_pressed = hud.pressed_now.has("jump") or Input.is_action_just_pressed("jump")
	action_pressed = hud.pressed_now.has("action") or Input.is_action_just_pressed("action")
	swap_pressed = hud.pressed_now.has("swap") or Input.is_action_just_pressed("swap")
	super_pressed = hud.pressed_now.has("super") or Input.is_action_just_pressed("super")
	pause_pressed = hud.pressed_now.has("pause") or Input.is_action_just_pressed("pause")
	taps = hud.pressed_now.duplicate()
	hud.pressed_now.clear()
	if bot:
		_bot_input()


func on_tap() -> void:
	match state:
		"title":
			open_slots()
		"clear":
			if clear_t > 1.5:
				_after_clear()


# ------------------------------------------------------------------ save files
func _after_clear() -> void:
	if slot > 0:
		Boot.from_level = level.world
		go("map", slot)
	else:
		get_tree().reload_current_scene()


func open_slots() -> void:
	state = "slots"
	erase_armed = 0
	sfx("swap", -6)


func slot_pick(n: int) -> void:
	erase_armed = 0
	var d := Saves.read(n)
	if d.get("in_level", false):   # in the middle of a level: carry on with the same partner
		var w := String(d.get("level", "meadow"))
		if not w in Worlds.ORDER:
			w = "meadow"
		if w != level.world:
			Boot.level = w
			go("level", n)
			return
		start_game(n)
		_apply_save(d)
		return
	if not d.is_empty():   # an old file: straight to the home base
		go("home", n)
		return
	slot = n   # a new file: pick a partner first
	_apply_save(d)
	partner_from = "new"
	partner_sel = maxi(0, Mons.ORDER.find(mon_line))
	state = "partner"
	sfx("swap", -6)


## PICK YOUR PARTNER: tap once to look, again (or GO!) to start with it.
func partner_tap(i: int) -> void:
	if partner_sel == i:
		choose_partner(Mons.ORDER[i])
	else:
		partner_sel = i
		sfx("bonk", -8)


func choose_partner(l: String) -> void:
	set_partner(l)
	match partner_from:
		"home":
			state = "home"
			save_game(false)
			hud.say(display_name("mon") + "!", display_name("mon") + " joins the team! Lv %d" % mon_level(), 2.2)
		"new":
			coins = START_COINS
			save_game(false)
			go("home", slot)
		_:
			start_game(slot)
			hud.say("1-1  MEADOW MINE", display_name("mon") + " joins the team! Lv %d" % mon_level(), 2.6)


## BACK on PICK YOUR PARTNER.
func partner_back() -> void:
	state = "home" if partner_from == "home" else "slots"


func set_partner(l: String) -> void:
	mon_line = l
	heroes.mon.set_form(l, Mons.stage_of(l, mon_level()))


## ERASE needs two taps; the file goes to a backup so UNDO ERASE can bring it back.
func slot_erase(n: int) -> void:
	if not Saves.exists(n):
		return
	if erase_armed == n:
		Saves.erase(n)
		erase_armed = 0
		sfx("break")
	else:
		erase_armed = n
		erase_t = 3.0
		sfx("alert")


func slot_undo(n: int) -> void:
	Saves.undo_erase(n)
	sfx("heart")


## Everything about this run, so SAVE & QUIT comes back to the same spot.
func save_data(in_level := true) -> Dictionary:
	var d := {"v": 2, "level": level.world if level else "meadow", "cleared_w": cleared_w.duplicate(), "time": Time.get_unix_time_from_system(),
		"hearts": hearts, "coins": coins, "ores": ores.duplicate(), "beaten": beaten,
		"super": super_charge, "cleared": cleared, "human": human if in_level else "rocco", "in_level": in_level,
		"mon": mon_line, "mon_xp": mon_xp.duplicate(),
		"home": home.data() if home else home_saved}
	if in_level:
		var cells := []
		var lc := level.cells
		for i in lc.size():
			if lc[i] != original_cells[i]:
				var x := i % level.sx
				var y := (i / level.sx) % level.sy
				var z := i / (level.sx * level.sy)
				cells.append([x, y, z, lc[i]])
		d.merge({"control": control, "pos": _v2a(safe_pos), "checkpoint": _v2a(checkpoint),
			"checkpoint_hit": checkpoint_hit, "boss_done": boss_done, "cells": cells,
			"coins_taken": coins_taken.duplicate(), "foes_gone": foes_gone.duplicate(),
			"stars": open_play.got.keys() if open_play else []})
	return d


func save_game(in_level := true) -> bool:
	if slot <= 0:
		return false
	return Saves.write(slot, save_data(in_level))


func _apply_save(d: Dictionary) -> void:
	hearts = clampi(int(d.get("hearts", MAX_HEARTS)), 1, MAX_HEARTS)
	coins = int(d.get("coins", 0))
	var o: Dictionary = d.get("ores", {})
	for k in ores:
		ores[k] = int(o.get(k, 0))
	beaten = int(d.get("beaten", 0))
	super_charge = clampf(float(d.get("super", 0)), 0, 1)
	cleared = int(d.get("cleared", 0))
	cleared_w = {}
	var cw: Dictionary = d.get("cleared_w", {"meadow": cleared} if cleared > 0 else {})
	for k in cw:
		cleared_w[k] = int(cw[k])
	home_saved = d.get("home", {})
	var mx: Dictionary = d.get("mon_xp", {})
	for k in mon_xp:
		mon_xp[k] = int(mx.get(k, 0))
	mon_line = String(d.get("mon", "spark"))
	if not mon_line in Mons.ORDER:
		mon_line = "spark"
	set_partner(mon_line)
	if not d.get("in_level", false):
		return
	for c in d.get("cells", []):
		level.set_cell(int(c[0]), int(c[1]), int(c[2]), int(c[3]))
	coins_taken = []
	for i in d.get("coins_taken", []):
		coins_taken.append(int(i))
	for c in coin_nodes.duplicate():
		if int(c.get_meta("i")) in coins_taken:
			c.queue_free()
			coin_nodes.erase(c)
	foes_gone = []
	for i in d.get("foes_gone", []):
		foes_gone.append(int(i))
	for f in foes:
		if is_instance_valid(f) and int(f.get_meta("spawn")) in foes_gone:
			f.remove()
	boss_done = bool(d.get("boss_done", false))
	if open_play:
		open_play.restore(d.get("stars", []))
	checkpoint_hit = bool(d.get("checkpoint_hit", false))
	checkpoint = _a2v(d.get("checkpoint", [3, 5, 6.5]))
	# who's out
	var h := String(d.get("human", "rocco"))
	if h != human and h in ["rocco", "blocky"]:
		heroes[human].visible = false
		heroes[human].shadow.visible = false
		human = h
		heroes[human].visible = true
	control = String(d.get("control", "human"))
	if control != "mon":
		control = "human"
	var p := _a2v(d.get("pos", [4, 5, 6.8]))
	if level.box_hit(p, 0.35, 1.5) != null:   # somehow inside a block: use the checkpoint
		p = checkpoint
	safe_pos = p
	leader().position = p
	follower().position = p + Vector3(-1.2, 0.3, -0.8)
	cam_focus = p
	dennis_done = true
	hud.say("WELCOME BACK!", "File %d" % slot, 2.2)


static func _v2a(v: Vector3) -> Array:
	return [snappedf(v.x, 0.01), snappedf(v.y, 0.01), snappedf(v.z, 0.01)]


static func _a2v(a) -> Vector3:
	if a is Array and a.size() == 3:
		return Vector3(float(a[0]), float(a[1]), float(a[2]))
	return Vector3(3, 5, 6.5)


## Pause menu: 0 RESUME, 1 SAVE, 2 SAVE & QUIT.
func pause_choice(i: int) -> void:
	match i:
		0:
			state = paused_from
		1:
			if save_game(paused_from == "play"):   # home / map saves don't keep a level
				save_note = "SAVED TO FILE %d!" % slot
				sfx("heart")
			else:
				save_note = "COULDN'T SAVE"
				sfx("bonk")
			save_note_t = 2.5
		2:
			save_game(paused_from == "play")
			go("title")


## Shows every effect, shot, prop and text size once (tiny, behind the title) so the
## tablet gets them ready before play. Otherwise the first sparkle / thunderbolt / "+1"
## would freeze the game for a moment while the graphics chip prepares it.
func _warm_up() -> void:
	warm_t += 1
	var at := cam.global_position + (-cam.global_basis.z) * 70.0   # far away = a few pixels big
	if warm_t == 2:
		fx.burst(at, Color.WHITE, 4, 0.1, 0.0, 0.2)
		fx.bolt(at + Vector3(0, -6, 0))
		fx.wave(at, null)
		fx.beam(at, 1.0, 2.0)
		fx.ring(at, 1.0)
		fx.vine(at, Vector3.RIGHT, 1.0)

		if home:
			for it in Decor.ITEMS:
				home._put_decor(Vector3i(0, 17, 0), it.id, 0, 1 if it.get("paint", false) else 0)   # painted: warms the paint shader too
				var n: Node3D = home.decor[Vector3i(0, 17, 0)].node
				home.decor.erase(Vector3i(0, 17, 0))
				n.position = at
				n.scale = Vector3.ONE * 0.05
				warm_nodes.append(n)
		for k in ["spark", "arrow", "spikeball", "coin", "heart", "shot_water", "shot_fire"]:
			var n := level.prop(k, at, 0.3)
			n.scale = Vector3.ONE * 0.05
			warm_nodes.append(n)
		for h in heroes.values():
			h.show_health()
			h.bar.visible = true
		for name in ["jump", "jump2", "coin", "stomp", "break", "mine", "bonk", "hurt", "zap", "swap", "slam",
				"thunder", "boss_hit", "arrow", "item", "heart", "woof", "alert", "clear", "boss_down"]:
			if not sfx_cache.has(name):
				sfx_cache[name] = load("res://sfx/%s.wav" % name)
	elif warm_t >= 4 and warm_t < 10:   # one text size per frame, so the title never stutters
		var sz: int = [36, 40, 48, 52, 56, 60][warm_t - 4]
		fx.text(at, "+0123456789/ABCDEFGHIJKLMNOPQRSTUVWXYZ!→", Color.WHITE, sz, 0.3)
	elif warm_t == 12:
		for n in warm_nodes:
			n.queue_free()
		warm_nodes.clear()
		for h in heroes.values():
			h.bar.visible = false
	elif warm_t > 12 and (hud.warm_sizes.is_empty() or state not in ["loading", "title"] or warm_t > 90):
		warm_t = -1
		if state == "loading":
			_after_loading()


## The partner earns XP. Level ups pop up; at Lv 4 and Lv 8 it evolves (the evolution scene).
func gain_xp(n: int) -> void:
	var before := mon_level()
	mon_xp[mon_line] = int(mon_xp[mon_line]) + n
	var after := mon_level()
	var m: Hero = heroes.mon
	fx.text(m.position + Vector3(0, m.sprite_h * 0.6 + 0.3, 0.3), "+%d XP" % n, Color("9fe8ff"), 40, 0.8)
	if after > before:
		sfx("heart")
		fx.text(m.position + Vector3(0, m.sprite_h * 0.6 + 1.0, 0.3), "LEVEL UP! Lv %d" % after, Color("fff2a8"), 56, 1.4)
		var st := Mons.stage_of(mon_line, after)
		if st > m.stage and state == "play":
			start_evolve(st)


func start_evolve(st: int) -> void:
	note("evolving")
	evolve_from = display_name("mon")
	evolve_to = st
	evolve_t = 0.0
	state = "evolve"
	hud.say("WHAT?", evolve_from + " is evolving!", 1.6)
	speak("Whoa! " + evolve_from.capitalize() + " is evolving!", "rocco")
	sfx("alert")


func _step_evolve(dt: float) -> void:
	var m: Hero = heroes.mon
	var was := evolve_t
	evolve_t += dt
	var pulse := 1.0 + sin(evolve_t * (8 + evolve_t * 10)) * 0.25
	m.squash = Vector2(pulse, pulse)
	if m.sprite.visible:
		m.sprite.modulate = Color(3, 3, 3) if int(evolve_t * 12) % 2 == 0 else Color.WHITE
	m.animate(dt)
	if was < 1.8 and evolve_t >= 1.8:
		m.set_form(mon_line, evolve_to)
		m.squash = Vector2(1.4, 1.4)
		fx.shake = 0.6
		sfx("clear")
		for i in 5:
			fx.burst(m.position + Vector3(randf_range(-0.6, 0.6), randf_range(0.3, 1.6), 0.4), Color.from_hsv(randf(), 0.4, 1), 12, 5, 4)
		hud.say(display_name("mon") + "!", evolve_from + " evolved into " + display_name("mon") + "!", 2.4)
		speak(display_name("mon").capitalize() + "!", "mon")
		save_game()
	if evolve_t > 3.4:
		evolve_t = -1
		m.sprite.modulate = Color.WHITE
		state = "play"
	_update_camera(dt)


## Remembers a frame that took longer than 50 ms (a visible hitch) and what was happening.
func _watch_lag(delta: float) -> void:
	doing_t -= delta
	if doing_t <= 0:
		doing = state.to_upper() if state != "play" else "playing"
	if delta > 0.05 and state in ["title", "play", "evolve"] and not bot:
		slow_count += 1
		slow.push_front([int(delta * 1000), doing])
		if slow.size() > 5:
			slow.pop_back()


## Marks what's happening for the next second (for the lag finder).
func note(what: String) -> void:
	doing = what
	doing_t = 1.0


## PAPER <-> 3D TOYS (title screen). Rebuilds everything in the other look.
func toggle_look() -> void:
	Look.toy = not Look.toy
	Look.save_choice()
	get_tree().reload_current_scene()


func world_info() -> Dictionary:
	return Worlds.info(level.world)


func start_game(n: int = 0) -> void:
	note("starting " + level.world)
	slot = n
	state = "play"
	play_t = 0
	play_music(world_info().song)
	hud.say(world_info().name, world_info().hello, 2.6)
	speak(GO_LINES.pick_random(), "rocco")


## Runs once per screen refresh (60, 90 or 120 times a second, whatever the tablet does),
## so movement is smooth on fast screens. Long frames are split into small steps so
## nothing can fly through a block; taps only count in the first step.
func _process(delta: float) -> void:
	_watch_lag(delta)
	_gather_input()
	var dt := minf(delta, 0.1)
	var steps := maxi(1, ceili(dt * 60.0 - 0.01))
	for i in steps:
		_step(dt / steps)
		jump_pressed = false
		action_pressed = false
		swap_pressed = false
		super_pressed = false
		pause_pressed = false
	if warm_t >= 0:
		_warm_up()


func _step(dt: float) -> void:
	if state == "loading":
		_update_camera(dt)
		return
	if state == "title":
		for h in heroes.values():
			h.vel = Vector3.ZERO
			h.play("idle")
			h.animate(dt)
			h.update_shadow()
		if Input.is_key_pressed(KEY_U):
			updater.download()
		elif Input.is_action_just_pressed("swap"):
			toggle_look()
		elif jump_pressed or action_pressed:
			open_slots()
		_update_camera(dt)
		return
	if state == "evolve":
		_step_evolve(dt)
		return
	if state == "partner":
		if Input.is_action_just_pressed("left"):
			partner_sel = (partner_sel + 3) % 4
		if Input.is_action_just_pressed("right"):
			partner_sel = (partner_sel + 1) % 4
		if jump_pressed or action_pressed:
			choose_partner(Mons.ORDER[partner_sel])
		elif pause_pressed:
			partner_back()
		for h in heroes.values():
			h.animate(dt)
		_update_camera(dt)
		return
	if state == "slots":
		erase_t -= dt
		if erase_t <= 0:
			erase_armed = 0
		if Input.is_action_just_pressed("left"):
			slot_sel = (slot_sel + 2) % 3
		if Input.is_action_just_pressed("right"):
			slot_sel = (slot_sel + 1) % 3
		if jump_pressed or action_pressed:
			slot_pick(slot_sel + 1)
		elif pause_pressed:
			state = "title"
		_update_camera(dt)
		return
	if state == "paused":
		save_note_t -= dt
		if Input.is_action_just_pressed("up"):
			pause_sel = (pause_sel + 2) % 3
		if Input.is_action_just_pressed("down"):
			pause_sel = (pause_sel + 1) % 3
		if jump_pressed or action_pressed:
			pause_choice(pause_sel)
		elif pause_pressed:
			state = paused_from
		return
	if state == "clear":
		clear_t += dt
		for h in heroes.values():
			if h.visible:
				h.vel.x = 0
				h.vel.z = 0
				h.vel.y -= Actor.GRAVITY * dt
				h.move(dt)
				h.play("win")
				h.animate(dt)
				h.update_shadow()
		if clear_t > 1.5 and (jump_pressed or action_pressed):
			_after_clear()
		_update_camera(dt)
		return
	if state == "home":
		_step_home(dt)
		return
	if state == "map":
		_step_map(dt)
		return
	if pause_pressed:
		_pause("play")
		return
	play_t += dt
	hurt_t = maxf(0, hurt_t - dt)
	if level.open:
		_turn_camera(dt)
		hud.world_taps.clear()
	super_charge = minf(1.0, super_charge + dt / SUPER_TIME)
	if swap_pressed:
		do_swap()
	if super_pressed and super_charge >= 1.0:
		_thunderbolt()
	_update_leader(leader(), dt)
	_update_follower(follower(), dt)
	_update_foes(dt)
	_update_shots(dt)
	_update_coins(dt)
	_update_progress(dt)
	_update_camera(dt)


# ------------------------------------------------------------------ the hero you steer
func _update_leader(h: Hero, dt: float) -> void:
	var dir := Vector3(move_vec.x, 0, move_vec.y)
	if level.open:   # the stick goes where the camera looks
		dir = dir.rotated(Vector3.UP, cam_yaw)
	if flying:
		_fly(h, dir, dt)
		return
	h.action_t = maxf(0, h.action_t - dt)
	if h.slam == "wind":
		h.slam_t += dt
		h.vel = Vector3.ZERO
		h.play("slam")
		h.squash = Vector2(1.1, 0.9)
		if h.slam_t > 0.15:
			h.slam = "fall"
			h.vel.y = -24
		h.animate(dt)
		h.update_shadow()
		return
	if h.long_t > 0 and not h.on_floor:
		h.long_t -= dt
	else:
		var target := dir * h.speed * (SPRINT_SPEED if sprinting else 1.0)
		var accel := 45.0 if h.on_floor else 25.0
		if h.on_floor and level.get_cell(floori(h.position.x), floori(h.position.y - 0.1), floori(h.position.z)) == Level.ICE:
			accel = 7.0   # ice: slow to start, slow to stop
		h.vel.x = move_toward(h.vel.x, target.x, accel * dt)
		h.vel.z = move_toward(h.vel.z, target.z, accel * dt)
	if dir.length() > 0.15:
		h.facing = dir.normalized()
	elif level.is_home and home and home.mode != "":
		h.facing = -Actor.view_back   # building: look where the camera looks
	# jumping (with a little forgiveness: buffer + coyote time)
	if h.on_floor:
		h.coyote = 0.1
		h.jumps_left = 1 if h.who == "rocco" else 0
		h.long_t = 0
	else:
		h.coyote -= dt
	jump_buf -= dt
	jump_hold_t -= dt
	if jump_pressed:
		jump_buf = 0.14
	if jump_buf > 0 and h.slam == "":
		if h.coyote > 0:
			h.vel.y = h.jump_v
			h.coyote = 0
			jump_buf = 0
			jump_hold_t = 0.12
			h.squash = Vector2(0.85, 1.18)
			sfx("jump")
		elif h.jumps_left > 0 and h.vel.y < h.jump_v * 0.75:
			# DOUBLE JUMP: a full, snappy second jump with a flip. You can change
			# direction with the stick, and it always goes up a good amount.
			h.jumps_left -= 1
			h.vel.y = h.jump_v * 0.98
			jump_buf = 0
			jump_hold_t = 0.2
			var want := dir * h.speed * (SPRINT_SPEED if sprinting else 1.0)
			if dir.length() > 0.15:
				h.vel.x = lerpf(h.vel.x, want.x, 0.7)
				h.vel.z = lerpf(h.vel.z, want.z, 0.7)
			h.long_t = 0
			h.squash = Vector2(0.8, 1.22)
			h.spin()
			fx.burst(h.position + Vector3(0, 0.05, 0), Color(1, 1, 1), 8, 2.6, 1.5, 0.7)
			sfx("jump2")
	if h.who == "mon" and h.line == "fire" and h.stage == 2 and jump_held and h.vel.y < -2.5:
		h.vel.y = -2.5   # BLAZE glides while you hold JUMP
	if not jump_held and h.vel.y > 0 and h.long_t <= 0 and jump_hold_t <= 0:
		h.vel.y -= Actor.GRAVITY * 1.4 * dt   # let go of JUMP early = smaller jump
	h.vel.y = maxf(h.vel.y - Actor.GRAVITY * dt, -26)
	if action_pressed:
		_action(h)
	h.move(dt)
	if bot or invincible:
		pass
	if boss_on and not boss_done:
		_keep_in_arena(h)
	if h.ceiling_hit != null:
		hit_block(h.ceiling_hit, 1, h)
		sfx("bonk")
	if h.landed and h.slam == "fall":
		_slam_land(h)
	if h.on_floor:
		if level.ground_below(h.position.x - 0.5, h.position.z, h.position.y + 0.1) > -50 and level.ground_below(h.position.x + 0.5, h.position.z, h.position.y + 0.1) > -50:
			safe_pos = h.position
	if h.position.y < -4:
		_fell(h)
	# pick the picture
	if hurt_t > 1.0:
		h.play("hurt")
	elif h.action_t > 0 and h.who == "blocky":
		h.play("mine")
	elif h.action_t > 0 and h.who == "mon":
		h.play("zap")
	elif h.slam == "fall":
		h.play("slam")
	elif not h.on_floor:
		h.play("jump" if h.vel.y > 0 else "fall")
	elif Vector2(h.vel.x, h.vel.z).length() > 0.5:
		h.play("run")
		if sprinting:   # little dust puffs behind the feet
			dust_t -= dt
			if dust_t <= 0:
				dust_t = 0.09
				fx.burst(h.position + Vector3(-h.facing.x * 0.3, 0.1, -h.facing.z * 0.3), Color(0.92, 0.88, 0.8), 3, 1.5, 3, 0.8)
	else:
		h.blink_t -= dt
		if h.blink_t < 0:
			h.play("blink" if h.frames.has("blink") else "idle")
			if h.blink_t < -0.12:
				h.blink_t = randf_range(2, 4)
		else:
			h.play("idle")
	h.squash = Vector2.ONE.lerp(h.squash, exp(-dt * 12))
	h.animate(dt * (1.45 if sprinting and h.anim == "run" else 1.0), 10 if h.who != "blocky" or h.action_t <= 0 else 7)
	h.update_shadow()


func _keep_in_arena(h: Hero) -> void:
	if level.open:
		var ar := level.arena
		h.position.x = clampf(h.position.x, ar.position.x + 1.4, ar.end.x - 0.4)
		h.position.z = clampf(h.position.z, ar.position.y + 1.4, ar.end.y - 0.4)
	else:
		h.position.x = clampf(h.position.x, Level.BOSS_X0 + 0.6, Level.BOSS_X1 + 0.4)


## Flying at home: no gravity, UP (JUMP) to rise, DOWN to sink, faster sideways.
func _fly(h: Hero, dir: Vector3, dt: float) -> void:
	var target := dir * h.speed * 1.7
	h.vel.x = move_toward(h.vel.x, target.x, 40 * dt)
	h.vel.z = move_toward(h.vel.z, target.z, 40 * dt)
	var down: bool = hud.is_held("down") or Input.is_action_pressed("sink") or Input.is_action_pressed("sprint")
	var up := 7.0 if jump_held else (-7.0 if down else 0.0)
	h.vel.y = move_toward(h.vel.y, up, 30 * dt)
	if dir.length() > 0.15:
		h.facing = dir.normalized()
	h.move(dt)
	h.position.y = minf(h.position.y, level.sy + 4)
	if h.on_floor and down:
		set_flying(false)
	h.play("jump")
	h.squash = Vector2.ONE.lerp(h.squash, exp(-dt * 12))
	h.animate(dt)
	h.update_shadow()


func _action(h: Hero) -> void:
	match h.who:
		"rocco":
			if h.on_floor:
				var f := h.facing
				if absf(f.x) < 0.3 and absf(f.z) < 0.3:
					f = Vector3.RIGHT
				h.vel.x = f.x * 9.5
				h.vel.z = f.z * 9.5
				h.vel.y = 9.0
				h.long_t = 1.0
				h.on_floor = false
				sfx("jump")
				fx.burst(h.position, Color(1, 1, 1), 6, 2.5, 8)
			elif h.slam == "":
				h.slam = "wind"
				h.slam_t = 0
				h.spin()
		"blocky":
			if h.action_t <= 0:
				h.action_t = 0.3
				h.anim_t = 0
				_mine(h)
		"mon":
			if h.action_t <= 0:
				h.action_t = 0.28
				h.anim_t = 0
				_mon_attack(h, null)


## The partner's attack: as the hero you steer (target = null: aim in front) or as the helper.
func _mon_attack(h: Hero, target: Foe) -> void:
	var dmg := 1 + h.stage
	var from := h.position + Vector3(0, h.height * 0.6, 0)
	var aim := Vector3(h.facing.x, 0, h.facing.z).normalized()
	if aim.length() < 0.1:
		aim = Vector3.RIGHT
	if target == null:   # auto-aim at a bad guy roughly in front
		var best := 9.0
		for f in foes:
			if is_instance_valid(f) and f.dying < 0:
				var d := f.position + Vector3(0, f.height * 0.5, 0) - from
				if d.length() < best and Vector3(d.x, 0, d.z).normalized().dot(aim) > 0.5:
					best = d.length()
					target = f
	if target:
		aim = (target.position + Vector3(0, target.height * 0.5, 0) - from).normalized()
	match h.line:
		"water":
			spawn_shot("water", from, aim * 14.0, h, dmg)
			sfx("zap", -6)
		"fire":
			var flat := Vector3(aim.x, 0, aim.z).normalized()
			spawn_shot("fireball", from, flat * 8.0 + Vector3(0, 2, 0), h, dmg)
			sfx("jump2", -6)
		"grass":
			_vine_whip(h, aim, dmg + 1)
		_:
			spawn_shot("spark", from, aim * 13.0, h, dmg)
			sfx("zap", -4)


## Grass: a vine lashes out in front. It hits bad guys and digs one block.
func _vine_whip(h: Hero, aim: Vector3, dmg: int) -> void:
	var reach := 2.2 + h.stage * 0.4
	var flat := Vector3(aim.x, 0, aim.z).normalized()
	fx.vine(h.position + Vector3(0, h.height * 0.55, 0), flat, reach)
	sfx("mine", -4)
	var hit := false
	for f in foes:
		if is_instance_valid(f) and f.dying < 0:
			var d := f.position - h.position
			if absf(d.y) < 1.8 and Vector2(d.x, d.z).length() < reach + f.hw and Vector2(d.x, d.z).normalized().dot(Vector2(flat.x, flat.z)) > 0.3:
				f.hurt(dmg, self)
				hit = true
	if not hit and h == leader():
		var c := Vector3i(floori(h.position.x + flat.x * (h.hw + 0.6)), floori(h.position.y + 0.3), floori(h.position.z + flat.z * (h.hw + 0.6)))
		if level.get_cell(c.x, c.y, c.z) != Level.AIR:
			hit_block(c, 1, h)


func _slam_land(h: Hero) -> void:
	note("butt slam")
	h.slam = ""
	h.vel.y = 5
	h.squash = Vector2(1.35, 0.7)
	fx.shake = 0.5
	sfx("slam")
	fx.burst(h.position + Vector3(0, 0.1, 0), Color(0.95, 0.9, 0.8), 16, 5, 14)
	var c := Vector3i(floori(h.position.x), floori(h.position.y) - 1, floori(h.position.z))
	hit_block(c, 3, h)
	for f in foes:
		if is_instance_valid(f) and f.dying < 0 and not f.is_boss and absf(f.position.x - h.position.x) < 2.8 and absf(f.position.z - h.position.z) < 2.2 and absf(f.position.y - h.position.y) < 1.2:
			f.hurt(2, self)


func _mine(h: Hero) -> void:
	# hit a bad guy in front first
	for f in foes:
		if is_instance_valid(f) and f.dying < 0:
			var d := f.position - h.position
			if absf(d.y) < 1.6 and Vector2(d.x, d.z).length() < 1.5 + f.hw and Vector2(d.x, d.z).normalized().dot(Vector2(h.facing.x, h.facing.z).normalized()) > 0.2:
				f.hurt(2, self)
				sfx("stomp")
				return
	var f := h.facing
	var cx := floori(h.position.x)
	var cz := floori(h.position.z)
	if absf(f.x) >= absf(f.z):
		cx = floori(h.position.x + signf(f.x) * (h.hw + 0.45))
	else:
		cz = floori(h.position.z + signf(f.z) * (h.hw + 0.45))
	for y in [floori(h.position.y + 0.3), floori(h.position.y + 1.2)]:
		if level.get_cell(cx, y, cz) != Level.AIR:
			hit_block(Vector3i(cx, y, cz), 1, h)
			return
	var below := Vector3i(floori(h.position.x), floori(h.position.y) - 1, floori(h.position.z))
	if level.get_cell(below.x, below.y, below.z) != Level.AIR:
		hit_block(below, 1, h)
	else:
		sfx("mine", -8)


## Hits a block with this much power. Returns true if something was there.
func hit_block(c: Vector3i, power: int, by: Hero) -> bool:
	var t := level.get_cell(c.x, c.y, c.z)
	if t == Level.AIR:
		return false
	var center := Vector3(c) + Vector3(0.5, 0.5, 0.5)
	if t == Level.MYSTERY:
		_open_mystery(c)
		return true
	if t == Level.BEDROCK or t == Level.USED or (c.z < 3 and not level.open) or level.locked.has(c):
		sfx("bonk", -4)
		fx.burst(center, Color(0.8, 0.8, 0.8), 4, 2)
		return true
	var dmg: int = level.damage.get(c, 0) + power
	if dmg >= Level.HITS[t]:
		level.set_cell(c.x, c.y, c.z, Level.AIR)
		sfx("break")
		fx.burst(center, Level.COLORS[t], 16, 5)
		if ORE_OF.has(t):
			var o: String = ORE_OF[t]
			ores[o] += 1
			var col: Color = Color(1, 1, 1) if o == "stone" else Level.COLORS[t].lightened(0.2)
			fx.text(center + Vector3(0, 0.4, 0.6), "+1 " + o.to_upper(), col, 52)
		elif t == Level.BRICK:
			coins += 1
			fx.text(center + Vector3(0, 0.4, 0.6), "+1", Color("ffd23a"), 52)
	else:
		level.damage[c] = dmg
		sfx("mine")
		fx.burst(center, Level.COLORS[t], 6, 3)
		fx.text(center + Vector3(0, 0.6, 0.6), "%d/%d" % [dmg, Level.HITS[t]], Color(1, 1, 1, 0.9), 36, 0.5)
	return true


func _open_mystery(c: Vector3i) -> void:
	note("? block")
	level.set_cell(c.x, c.y, c.z, Level.USED)
	var top := Vector3(c) + Vector3(0.5, 1.1, 0.5)
	if hearts < MAX_HEARTS and randf() < 0.4:
		hearts += 1
		sfx("heart")
		var h := level.prop("heart", top, 0.8)
		_pop_away(h)
		fx.text(top + Vector3(0, 0.6, 0.6), "+1 HEART", Color("ff8fa6"), 52)
	else:
		coins += 3
		sfx("item")
		var cn := level.prop("coin", top, 0.8)
		_pop_away(cn)
		fx.text(top + Vector3(0, 0.6, 0.6), "+3", Color("ffd23a"), 56)


func _pop_away(s: Node3D) -> void:
	var tw := create_tween()
	tw.tween_property(s, "position", s.position + Vector3(0, 1.4, 0), 0.35).set_ease(Tween.EASE_OUT).set_trans(Tween.TRANS_BACK)
	if s is Sprite3D:
		tw.tween_property(s, "modulate:a", 0.0, 0.25)
	else:
		tw.parallel().tween_property(s, "rotation:y", TAU * 2, 0.35)
		tw.tween_property(s, "scale", Vector3.ONE * 0.01, 0.2)
	tw.tween_callback(s.queue_free)


func _thunderbolt() -> void:
	super_charge = 0
	var m: Hero = heroes.mon
	note("SUPER " + Mons.LINES[m.line].super)
	var info: Dictionary = Mons.LINES[m.line]
	var power := 4 + m.stage
	fx.shake = 0.9
	hud.say(info.super + "!", display_name("mon") + "'s SUPER", 1.6)
	speak(display_name("mon").capitalize() + "!", "mon")
	var at := m.position if m.visible else leader().position
	var lx := at.x
	match m.line:
		"water":
			sfx("slam")
			fx.wave(at, level)
			for f in foes:
				if is_instance_valid(f) and f.dying < 0 and absf(f.position.x - lx) < 14 and f.position.y < at.y + 4:
					f.hurt(power - (1 if f.is_boss else 0), self)
		"grass":
			sfx("thunder")
			var dir := 1.0 if leader().facing.x >= 0 else -1.0
			fx.beam(at + Vector3(0, 0.8, 0), dir, 18.0)
			for f in foes:
				if is_instance_valid(f) and f.dying < 0 and (f.position.x - lx) * dir > -1 and absf(f.position.x - lx) < 18 and absf(f.position.y - at.y) < 6:
					f.hurt(power - (1 if f.is_boss else 0), self)
		"fire":
			sfx("boss_down")
			fx.ring(at, 7.0)
			for f in foes:
				if is_instance_valid(f) and f.dying < 0 and f.position.distance_to(at) < 7.5:
					f.hurt(power - (1 if f.is_boss else 0), self)
		_:
			sfx("thunder")
			var hit := 0
			for f in foes:
				if is_instance_valid(f) and f.dying < 0 and absf(f.position.x - lx) < 15:
					fx.bolt(f.position)
					f.hurt(power - (1 if f.is_boss else 0), self)
					hit += 1
			for i in maxi(0, 4 - hit):
				var x := lx + randf_range(-8, 8)
				fx.bolt(Vector3(x, level.ground_below(x, 6, 16), randf_range(4, 9)))
	super_charge = 0   # bad guys beaten by the SUPER don't refill it


# ------------------------------------------------------------------ the friend who follows
func _update_follower(h: Hero, dt: float) -> void:
	var l := leader()
	var side := 1.0 if l.position.z < 6.5 else -1.0
	var want := l.position + Vector3(-signf(l.facing.x if absf(l.facing.x) > 0.1 else 1.0) * 1.4, 0, 0.9 * side)
	if level.open:   # in the open world: just behind and beside you
		var f := Vector3(l.facing.x, 0, l.facing.z).normalized()
		want = l.position - f * 1.4 + Vector3(-f.z, 0, f.x) * 0.9
		want.y = h.position.y
	var d := want - h.position
	var flat := Vector2(d.x, d.z)
	var hd := Vector2(h.position.x - l.position.x, h.position.z - l.position.z).length()
	var far := hd > 10 or (not flying and h.position.distance_to(l.position) > 10) or h.position.y < -3
	if far:
		h.position = l.position + Vector3(0, 0.5, 0)
		h.vel = Vector3.ZERO
		fx.burst(h.position + Vector3(0, 0.5, 0), Color.WHITE, 8, 3, 4)
	if flat.length() > 0.35:
		var sp := h.speed * clampf(flat.length() / 1.5, 0.3, 1.15) * (SPRINT_SPEED if sprinting else 1.0)
		var v := flat.normalized() * sp
		h.vel.x = move_toward(h.vel.x, v.x, 40 * dt)
		h.vel.z = move_toward(h.vel.z, v.y, 40 * dt)
		if level.open:
			h.facing = Vector3(v.x, 0, v.y).normalized()
		elif absf(v.x) > 0.3:
			h.facing = Vector3(signf(v.x), 0, 0)
	else:
		h.vel.x = move_toward(h.vel.x, 0, 30 * dt)
		h.vel.z = move_toward(h.vel.z, 0, 30 * dt)
	var ahead := h.position.x + signf(h.vel.x) * 0.9
	var pit_ahead := absf(h.vel.x) > 0.5 and level.ground_below(ahead, h.position.z, h.position.y + 0.2) < h.position.y - 1.5
	if h.on_floor and (h.walls_hit or d.y > 0.8 or pit_ahead):
		h.vel.y = h.jump_v
	h.vel.y = maxf(h.vel.y - Actor.GRAVITY * dt, -26)
	h.move(dt)
	if boss_on and not boss_done:
		_keep_in_arena(h)
	h.action_t = maxf(0, h.action_t - dt)
	# the partner attacks a nearby bad guy every few seconds when it's the helper
	if h.who == "mon":
		h.zap_t -= dt
		if h.zap_t <= 0:
			var best: Foe = null
			var bd := 7.0
			for f in foes:
				if is_instance_valid(f) and f.dying < 0:
					var dd := f.position.distance_to(h.position)
					if dd < bd:
						bd = dd
						best = f
			if best:
				h.facing = Vector3(signf(best.position.x - h.position.x), 0, 0)
				_mon_attack(h, best)
				h.action_t = 0.3
				h.zap_t = 3.0
			else:
				h.zap_t = 0.5
	if h.action_t > 0:
		h.play("zap" if h.who == "mon" else "mine")
	elif not h.on_floor:
		h.play("jump")
	elif Vector2(h.vel.x, h.vel.z).length() > 0.5:
		h.play("run")
	else:
		h.play("idle")
	h.squash = Vector2.ONE.lerp(h.squash, exp(-dt * 12))
	h.animate(dt, 10)
	h.update_shadow()


# ------------------------------------------------------------------ bad guys, shots, coins
func _update_foes(dt: float) -> void:
	var l := leader()
	var keep: Array[Foe] = []
	for f in foes:
		if is_instance_valid(f) and f.alive:
			keep.append(f)
	foes = keep
	for f in foes.duplicate():
		if not is_instance_valid(f) or not f.alive:
			continue
		f.tick(dt, self)
		if not is_instance_valid(f) or f.dying >= 0 or not f.alive:
			continue
		if l.overlaps(f):
			var falling: bool = l.vel.y < -1.0 and l.position.y > f.position.y + f.height * 0.45
			if falling and f.stompable:
				var dmg := 3 if l.slam == "fall" else 2
				if l.who == "mon" and l.line == "water":
					dmg += l.stage   # heavy turtle stomps
				if not f.is_boss:
					dmg = 3 if f.kind == "grumbler" else dmg
				f.hurt(dmg, self)
				l.vel.y = 12.0 if jump_held else 8.5
				l.slam = ""
				l.jumps_left = 1 if l.who == "rocco" else 0
				l.squash = Vector2(1.25, 0.8)
				sfx("stomp")
				fx.burst(l.position, Color(1, 1, 1), 8, 3, 8)
			else:
				hurt_hero(f.position)


const FRIENDLY_SHOTS := ["spark", "water", "fireball"]


func spawn_shot(kind: String, pos: Vector3, v: Vector3, owner: Node3D, dmg: int = 1) -> void:
	var size := {"spark": 0.55, "arrow": 0.32, "spikeball": 0.8, "water": 0.5, "fireball": 0.55}[kind] as float
	var pic: String = {"spark": "spark", "arrow": "arrow", "spikeball": "spikeball", "water": "shot_water", "fireball": "shot_fire"}[kind]
	var s := level.prop(pic, pos - Vector3(0, size * 0.5, 0), size)
	if not (s is Sprite3D):   # 3D shots: put the node's origin at the shot's middle, like the paper ones
		s.position = pos
		for ch in s.get_children():
			ch.position.y -= size * 0.5
	if kind == "arrow":
		if s is Sprite3D:
			s.flip_h = v.x < 0
		else:
			s.look_at(s.global_position - v, Vector3.UP)
	shots.append({"node": s, "vel": v, "kind": kind, "owner": owner, "life": 5.0 if kind == "spikeball" else (2.4 if kind == "fireball" else 2.0),
		"bounces": 0, "dmg": dmg})


func _update_shots(dt: float) -> void:
	var l := leader()
	for sh in shots.duplicate():
		var s: Node3D = sh.node
		sh.life -= dt
		var gone: bool = sh.life <= 0
		var v: Vector3 = sh.vel
		var center := s.position
		if sh.kind == "spikeball":
			v.y -= 22 * dt
			var nxt: Vector3 = center + v * dt
			var g := level.ground_below(nxt.x, nxt.z, center.y + 0.5)
			if nxt.y - 0.4 < g and g > -50:
				nxt.y = g + 0.4
				v.y = 6.0 if sh.bounces == 0 else 0.0
				sh.bounces += 1
				v.x = signf(l.position.x - nxt.x) * 3.5
				if level.open:
					var to := Vector3(l.position.x - nxt.x, 0, l.position.z - nxt.z).normalized() * 3.5
					v.x = to.x
					v.z = to.z
			if level.solid(floori(nxt.x + signf(v.x) * 0.4), floori(nxt.y), floori(nxt.z)):
				v.x = -v.x
			s.position = nxt
			s.rotation.z -= v.x * dt * 2
			if nxt.y < -4:
				gone = true
		elif sh.kind == "fireball":   # bounces along the ground
			v.y -= 24 * dt
			var nxt: Vector3 = center + v * dt
			var g := level.ground_below(nxt.x, nxt.z, center.y + 0.4)
			if nxt.y - 0.25 < g and g > -50:
				nxt.y = g + 0.25
				v.y = 6.5
			if level.solid(floori(nxt.x + signf(v.x) * 0.3), floori(nxt.y), floori(nxt.z)):
				gone = true
				fx.burst(nxt, Color("ffb02e"), 6, 3)
			s.position = nxt
			if nxt.y < -4:
				gone = true
		else:
			s.position += v * dt
			if sh.kind == "spark":
				s.scale = Vector3.ONE * (1.0 + sin(sh.life * 40) * 0.15)
			if level.solid(floori(s.position.x), floori(s.position.y), floori(s.position.z)):
				gone = true
				fx.burst(s.position, Color(0.85, 0.85, 0.85), 4, 2)
		sh.vel = v
		if not gone:
			if sh.kind in FRIENDLY_SHOTS:
				for f in foes:
					if is_instance_valid(f) and f.dying < 0 and absf(f.position.x - s.position.x) < f.hw + 0.35 \
							and absf(f.position.z - s.position.z) < f.hw + 0.7 and s.position.y > f.position.y - 0.2 and s.position.y < f.position.y + f.height + 0.3:
						f.hurt(int(sh.dmg) + (1 if sh.owner == leader() else 0), self)
						gone = true
						break
			else:
				var c := l.position + Vector3(0, l.height * 0.5, 0)
				if absf(c.x - s.position.x) < l.hw + 0.3 and absf(c.z - s.position.z) < 0.8 and absf(c.y - s.position.y) < l.height * 0.5 + 0.3:
					hurt_hero(s.position)
					gone = true
		if gone:
			s.queue_free()
			shots.erase(sh)


func _update_coins(dt: float) -> void:
	var t := play_t
	var people := [leader(), follower()]
	for i in range(coin_nodes.size() - 1, -1, -1):
		var c: Node3D = coin_nodes[i]
		if c is Sprite3D:
			c.scale.x = cos(t * 3.5 + i * 0.7)
		else:
			c.rotation.y = t * 3.0 + i * 0.7
		for p in people:
			var cc: Vector3 = c.position if c is Sprite3D else c.position + Vector3(0, 0.35, 0)
			if cc.distance_to(p.position + Vector3(0, p.height * 0.5, 0)) < 0.9:
				coins += 1
				coins_taken.append(int(c.get_meta("i")))
				sfx("coin", -3)
				fx.burst(c.position, Color("ffd23a"), 5, 2.5, 6)
				c.queue_free()
				coin_nodes.remove_at(i)
				break


# ------------------------------------------------------------------ getting hurt
func hurt_hero(from: Vector3) -> void:
	if hurt_t > 0 or invincible or state != "play":
		return
	var l := leader()
	hearts -= 1
	hurt_t = 1.4
	l.flash_t = 1.4
	var push := Vector3(l.position.x - from.x, 0, 0)
	l.vel = Vector3(signf(push.x if push.x != 0 else -l.facing.x) * 6, 7, 0)
	if level.open:   # pushed straight away from what hit you
		var away := Vector3(l.position.x - from.x, 0, l.position.z - from.z)
		if away.length() < 0.05:
			away = -l.facing
		away = away.normalized() * 6
		l.vel = Vector3(away.x, 7, away.z)
	l.slam = ""
	l.long_t = 0
	sfx("hurt")
	fx.shake = 0.3
	if play_t - last_ouch > 5 and l.who == "rocco":
		last_ouch = play_t
		speak(OUCH_LINES.pick_random(), "rocco")
	if hearts <= 0:
		_knocked_out()


func _fell(h: Hero) -> void:
	if not invincible:
		hearts -= 1
	sfx("hurt")
	if hearts <= 0:
		_knocked_out()
		return
	h.position = safe_pos + Vector3(0, 0.2, 0)
	h.vel = Vector3.ZERO
	hurt_t = 1.2
	h.flash_t = 1.2
	follower().position = safe_pos + Vector3(-1, 0.5, 0.5)


func _knocked_out() -> void:
	hud.say("OOPS!", "Back to the checkpoint. You can do it!", 2.4)
	hearts = MAX_HEARTS
	var l := leader()
	l.position = checkpoint
	l.vel = Vector3.ZERO
	l.slam = ""
	follower().position = checkpoint + Vector3(-1.2, 0.3, -0.8)
	safe_pos = checkpoint
	hurt_t = 2.0
	l.flash_t = 2.0


func on_foe_beaten(f: Foe) -> void:
	gain_xp(Mons.XP_BOSS if f.is_boss else Mons.XP_FOE)
	beaten += 1
	foes_gone.append(int(f.get_meta("spawn")))
	coins += 2
	super_charge = minf(1.0, super_charge + 0.06)
	fx.text(f.position + Vector3(0, f.height + 0.4, 0.4), "+2", Color("ffd23a"), 48)
	if f.is_boss:
		boss_done = true
		fx.shake = 1.2
		hud.say("KRAG JR. BEATEN!", "Run to the flag!", 3.0)
		play_music(world_info().song)
		speak("We did it! Woo hoo!", "rocco")
		save_game()
		for i in 6:
			fx.burst(f.position + Vector3(randf_range(-1, 1), randf_range(0, 2), 0), Color.from_hsv(randf(), 0.6, 1), 14, 6, 8)


func start_boss(f: Foe) -> void:
	note("Krag Jr. arrives")
	boss_on = true
	checkpoint = Vector3(Level.BOSS_X0 + 2, 5, 6.5)
	if level.open:   # just inside the castle gate
		checkpoint = Vector3(level.arena.get_center().x + 0.5, level.arena_y, level.arena.end.y - 2.5)
	hud.say("KRAG JR.!", "Stomp him when he swoops down!", 2.8)
	play_music("boss")
	sfx("alert")


# ------------------------------------------------------------------ checkpoint, flag, camera
func _update_progress(dt: float) -> void:
	var l := leader()
	if level.open:
		open_play.step(dt)
		return
	if not checkpoint_hit and l.position.x > Level.CHECKPOINT_X:
		checkpoint_hit = true
		checkpoint = Vector3(Level.CHECKPOINT_X + 0.5, 5, 6.5)
		hud.say("CHECKPOINT!", "Saved" if slot > 0 else "", 1.4)
		sfx("heart")
		safe_pos = checkpoint
		save_game()
	if boss_done and l.position.x > Level.FLAG_X - 0.4:
		clear_world()
	if not dennis_done and play_t > 1.2 and not level.open:
		dennis_done = true
		_dennis()


## Touched the flag: the world is done.
func clear_world() -> void:
	if state == "clear":
		return
	state = "clear"
	clear_t = 0
	cleared += 1
	cleared_w[level.world] = int(cleared_w.get(level.world, 0)) + 1
	gain_xp(Mons.XP_CLEAR)
	save_game(false)   # keeps coins and ores; the level starts fresh next time
	play_music("fanfare", false)
	sfx("clear")
	speak("Wahoo! Team up!", "rocco")
	var at := leader().position
	for i in 8:
		fx.burst(at + Vector3(randf_range(-2, 2), 3 + randf_range(0, 3), randf_range(-2, 2)), Color.from_hsv(randf(), 0.6, 1), 16, 6, 6)


func _dennis() -> void:
	var d := level.prop("dennis", Vector3(-1, 5, 8.8), 1.0)
	var tw := create_tween()
	tw.tween_property(d, "position:x", 10.0, 1.4)
	tw.tween_callback(func():
		sfx("woof")
		fx.text(d.position + Vector3(0, 1.0, 0), "WOOF! WOOF! →", Color("ffe08a"), 60, 1.4))
	tw.tween_interval(1.2)
	tw.tween_property(d, "position:x", 40.0, 3.0)
	tw.tween_callback(d.queue_free)


func _update_camera(dt: float) -> void:
	if sky:
		return
	if level.open:
		_update_home_camera(dt)
		return
	var l := leader()
	var want := l.position + Vector3(clampf(l.facing.x, -1, 1) * 1.6, 0, 0)
	if state == "title":
		want = Vector3(7, 5, 6.5)
	if boss_on and not boss_done:
		want.x = clampf(want.x, Level.BOSS_X0 + 7.5, Level.BOSS_X1 - 6.5)
	cam_focus.x = lerpf(cam_focus.x, want.x, 1 - exp(-dt * 4))
	var wy := want.y
	if not l.on_floor and l.position.y > cam_focus.y - 1.5 and l.position.y < cam_focus.y + 2.5:
		wy = cam_focus.y
	cam_focus.y = lerpf(cam_focus.y, wy, 1 - exp(-dt * 2.5))
	var shake := Vector3(randf_range(-1, 1), randf_range(-1, 1), 0) * fx.shake * 0.25
	cam.position = Vector3(cam_focus.x, cam_focus.y + 3.4, 16.2) + shake
	cam.look_at(Vector3(cam_focus.x, cam_focus.y + 1.0, 6.2) + shake, Vector3.UP)


# ------------------------------------------------------------------ test bot
func _bot_input() -> void:
	var l := leader()
	if level.open:   # head for the nearest challenge, jumping over anything in the way
		var to: Vector3 = level.objectives[int(play_t / 12) % 4].pos   # camp, tower, cave, sky in turn
		var d := Vector3(to.x - l.position.x, 0, to.z - l.position.z)
		cam_yaw = 0
		move_vec = Vector2(d.x, d.z).normalized() if d.length() > 1 else Vector2(sin(play_t), cos(play_t))
		jump_held = true
		var ahead := l.position + Vector3(move_vec.x, 0, move_vec.y) * 0.8
		if l.on_floor and (level.solid(floori(ahead.x), floori(l.position.y + 0.4), floori(ahead.z)) or l.walls_hit):
			jump_pressed = true
		elif not l.on_floor and l.vel.y < 1.0 and l.walls_hit and l.jumps_left > 0:
			jump_pressed = true
		if super_charge >= 1.0:
			super_pressed = true
		return
	move_vec = Vector2(1, clampf((6.5 - l.position.z), -1, 1))
	jump_held = true
	var ahead_x := l.position.x + 0.9
	var wall := level.solid(floori(ahead_x), floori(l.position.y + 0.4), floori(l.position.z)) \
		or level.solid(floori(ahead_x), floori(l.position.y + 1.2), floori(l.position.z))
	var pit := level.ground_below(l.position.x + 1.1, l.position.z, l.position.y + 0.1) < l.position.y - 0.5
	if l.on_floor and (wall or pit):
		jump_pressed = true
	elif not l.on_floor and l.vel.y < 1.0 and (wall or pit) and l.jumps_left > 0:
		jump_pressed = true
	if super_charge >= 1.0:
		super_pressed = true
