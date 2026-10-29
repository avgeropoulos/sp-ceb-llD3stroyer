extends Node3D
## Rocco & Blocky: Team Up! Paper 3D — one level, built like the real game:
## one hero out at a time (Rocco or Blocky) plus Sparky, SWAP between them,
## mine blocks for ores, bonk ? blocks, stomp bad guys and beat Krag Jr.

const MAX_HEARTS := 5
const SUPER_TIME := 25.0
const STATS := {
	"rocco": {"speed": 5.6, "jump": 12.6, "hw": 0.3, "h": 1.45, "size": 1.8, "feet": 28.0},
	"blocky": {"speed": 5.0, "jump": 10.8, "hw": 0.32, "h": 1.5, "size": 1.9, "feet": 30.0},
	"sparky": {"speed": 6.6, "jump": 13.2, "hw": 0.32, "h": 0.9, "size": 1.95, "feet": 48.0},
}
const ORE_OF := {Level.STONE: "stone", Level.IRON: "iron", Level.GOLD: "gold", Level.DIAMOND: "diamond"}
const GO_LINES := ["Here we go!", "Andiamo! Let's go!", "Okey dokey!"]
const OUCH_LINES := ["Mamma mia!", "Oh no!", "Ow ow ow!"]

var state := "title"
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

# input for this frame
var move_vec := Vector2.ZERO
var jump_held := false
var jump_pressed := false
var action_pressed := false
var swap_pressed := false
var super_pressed := false
var pause_pressed := false

# testing helpers
var bot := false
var invincible := false

var updater: Updater
var original_cells := PackedByteArray()
var sfx_players: Array[AudioStreamPlayer] = []
var sfx_cache := {}
var music: AudioStreamPlayer
var voice_it := ""
var voice_en := ""


func _ready() -> void:
	Look.load_choice()
	_setup_input()
	_setup_world()
	level = Level.new()
	add_child(level)
	level.generate()
	fx = Fx.new()
	add_child(fx)
	for w in ["rocco", "blocky", "sparky"]:
		var h := Hero.new()
		level.add_child(h)
		h.init(level, w, STATS[w])
		heroes[w] = h
	heroes.blocky.visible = false
	heroes.rocco.position = Vector3(4, 5, 6.8)
	heroes.blocky.position = heroes.rocco.position
	heroes.sparky.position = Vector3(2.6, 5, 5.6)
	original_cells = level.cells.duplicate()
	for si in level.spawns.size():
		var s: Dictionary = level.spawns[si]
		var f := Foe.new()
		level.add_child(f)
		f.init(level, s.kind, s.pos)
		f.set_meta("spawn", si)
		foes.append(f)
		if s.kind == "kragjr":
			boss = f
	for ci in level.coin_spots.size():
		var c := level.prop("coin", level.coin_spots[ci] - Vector3(0, 0.35, 0), 0.7)
		c.set_meta("i", ci)
		coin_nodes.append(c)
	level.prop("flag", Vector3(Level.CHECKPOINT_X, 5, 3.9), 3.2)
	level.prop("flag", Vector3(Level.FLAG_X, 5, 3.9), 4.6)
	var hl := CanvasLayer.new()
	add_child(hl)
	hud = Hud.new()
	hud.game = self
	hl.add_child(hud)
	_setup_audio()
	play_music("theme")
	updater = Updater.new()
	add_child(updater)
	if not bot:
		updater.check()
	cam_focus = heroes.rocco.position
	_update_camera(1.0)


func _setup_input() -> void:
	var keys := {"left": [KEY_LEFT, KEY_A], "right": [KEY_RIGHT, KEY_D], "up": [KEY_UP, KEY_W], "down": [KEY_DOWN, KEY_S],
		"jump": [KEY_SPACE, KEY_K], "action": [KEY_X, KEY_J], "swap": [KEY_C, KEY_L], "super": [KEY_V, KEY_I], "pause": [KEY_ESCAPE, KEY_P]}
	var pads := {"jump": JOY_BUTTON_A, "action": JOY_BUTTON_B, "swap": JOY_BUTTON_X, "super": JOY_BUTTON_Y, "pause": JOY_BUTTON_START,
		"left": JOY_BUTTON_DPAD_LEFT, "right": JOY_BUTTON_DPAD_RIGHT, "up": JOY_BUTTON_DPAD_UP, "down": JOY_BUTTON_DPAD_DOWN}
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
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color("3f8fe6")
	sm.sky_horizon_color = Color("c5e6ff")
	sm.ground_horizon_color = Color("c5e6ff")
	sm.ground_bottom_color = Color("7fb2dd")
	sm.sun_angle_max = 20
	sky.sky_material = sm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("dce8f5")
	env.ambient_light_energy = 0.55
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 1.0
	env.tonemap_white = 6.0
	env.fog_enabled = true
	env.fog_mode = Environment.FOG_MODE_DEPTH
	env.fog_light_color = Color("bfe0ff")
	env.fog_depth_begin = 28.0
	env.fog_depth_end = 140.0
	env.fog_density = 0.55
	env.fog_sky_affect = 0.0
	env.adjustment_enabled = true
	env.adjustment_saturation = 1.12
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, 152, 0)   # from above and behind the camera, a bit to the left
	sun.light_color = Color("fff1d6")
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
	var pitch := {"rocco": 1.15, "blocky": 0.8, "sparky": 1.9}.get(who, 1.0) as float
	DisplayServer.tts_stop()
	DisplayServer.tts_speak(text, v, 80, pitch, 1.1 if who == "sparky" else 1.0)


# ------------------------------------------------------------------ who's who
func leader() -> Hero:
	return heroes.sparky if control == "mon" else heroes[human]


func follower() -> Hero:
	return heroes[human] if control == "mon" else heroes.sparky


func leader_name() -> String:
	return leader().who


func next_swap_name() -> String:
	if control == "mon":
		return "rocco"
	return "blocky" if human == "rocco" else "sparky"


func action_label() -> String:
	match leader().who:
		"rocco":
			return "LONG JUMP" if leader().on_floor else "SLAM"
		"blocky":
			return "MINE"
	return "ZAP"


func do_swap() -> void:
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
		heroes.sparky.position = heroes.sparky.position   # Sparky is already out
		speak("Sparky!", "sparky")
	else:
		control = "human"
		var was := human
		human = "rocco"
		heroes[was].visible = false
		heroes[was].shadow.visible = false
		var f: Vector3 = heroes.sparky.position
		_pop_in(heroes.rocco, heroes[was].position, heroes[was].facing)
		heroes.sparky.position = f
		speak("Okey dokey!", "rocco")
	sfx("swap")
	fx.burst(leader().position + Vector3(0, 0.8, 0), Color.WHITE, 14, 4, 4)
	hud.say(leader_name().to_upper() + "!", "", 0.8)


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
	jump_held = hud.is_held("jump") or Input.is_action_pressed("jump")
	jump_pressed = hud.pressed_now.has("jump") or Input.is_action_just_pressed("jump")
	action_pressed = hud.pressed_now.has("action") or Input.is_action_just_pressed("action")
	swap_pressed = hud.pressed_now.has("swap") or Input.is_action_just_pressed("swap")
	super_pressed = hud.pressed_now.has("super") or Input.is_action_just_pressed("super")
	pause_pressed = hud.pressed_now.has("pause") or Input.is_action_just_pressed("pause")
	hud.pressed_now.clear()
	if bot:
		_bot_input()


func on_tap() -> void:
	match state:
		"title":
			open_slots()
		"clear":
			if clear_t > 1.5:
				get_tree().reload_current_scene()


# ------------------------------------------------------------------ save files
func open_slots() -> void:
	state = "slots"
	erase_armed = 0
	sfx("swap", -6)


func slot_pick(n: int) -> void:
	erase_armed = 0
	var d := Saves.read(n)
	start_game(n)
	if not d.is_empty():
		_apply_save(d)


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
	var d := {"v": 1, "level": "1-1", "time": Time.get_unix_time_from_system(),
		"hearts": hearts, "coins": coins, "ores": ores.duplicate(), "beaten": beaten,
		"super": super_charge, "cleared": cleared, "human": human if in_level else "rocco", "in_level": in_level}
	if in_level:
		var cells := []
		var lc := level.cells
		for i in lc.size():
			if lc[i] != original_cells[i]:
				var x := i % Level.LX
				var y := (i / Level.LX) % Level.LY
				var z := i / (Level.LX * Level.LY)
				cells.append([x, y, z, lc[i]])
		d.merge({"control": control, "pos": _v2a(safe_pos), "checkpoint": _v2a(checkpoint),
			"checkpoint_hit": checkpoint_hit, "boss_done": boss_done, "cells": cells,
			"coins_taken": coins_taken.duplicate(), "foes_gone": foes_gone.duplicate()})
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
	if not d.get("in_level", false):
		if cleared > 0:
			hud.say("1-1  MEADOW MINE", "Cleared %d time%s! Go again!" % [cleared, "" if cleared == 1 else "s"], 2.6)
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
			state = "play"
		1:
			if save_game():
				save_note = "SAVED TO FILE %d!" % slot
				sfx("heart")
			else:
				save_note = "COULDN'T SAVE"
				sfx("bonk")
			save_note_t = 2.5
		2:
			save_game()
			get_tree().reload_current_scene()


## Shows every effect, shot, prop and text size once (tiny, behind the title) so the
## tablet gets them ready before play. Otherwise the first sparkle / thunderbolt / "+1"
## would freeze the game for a moment while the graphics chip prepares it.
func _warm_up() -> void:
	warm_t += 1
	var at := cam.global_position + (-cam.global_basis.z) * 70.0   # far away = a few pixels big
	if warm_t == 2:
		fx.burst(at, Color.WHITE, 4, 0.1, 0.0, 0.2)
		fx.bolt(at + Vector3(0, -6, 0))
		for sz in [36, 48, 52, 56, 60, 64]:
			fx.text(at, "+0123456789/ABCDEFGHIJKLMNOPQRSTUVWXYZ!→", Color.WHITE, sz, 0.3)
		for k in ["spark", "arrow", "spikeball", "coin", "heart"]:
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
	elif warm_t == 12:
		for n in warm_nodes:
			n.queue_free()
		warm_nodes.clear()
		for h in heroes.values():
			h.bar.visible = false
		warm_t = -1


## PAPER <-> 3D TOYS (title screen). Rebuilds everything in the other look.
func toggle_look() -> void:
	Look.toy = not Look.toy
	Look.save_choice()
	get_tree().reload_current_scene()


func start_game(n: int = 0) -> void:
	slot = n
	state = "play"
	play_t = 0
	play_music("meadow")
	hud.say("1-1  MEADOW MINE", "Go, team, go!", 2.6)
	speak(GO_LINES.pick_random(), "rocco")


## Runs once per screen refresh (60, 90 or 120 times a second, whatever the tablet does),
## so movement is smooth on fast screens. Long frames are split into small steps so
## nothing can fly through a block; taps only count in the first step.
func _process(delta: float) -> void:
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
			state = "play"
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
			get_tree().reload_current_scene()
		_update_camera(dt)
		return
	if pause_pressed:
		state = "paused"
		pause_sel = 0
		save_note_t = 0
		return
	play_t += dt
	hurt_t = maxf(0, hurt_t - dt)
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
		var target := dir * h.speed
		var accel := 45.0 if h.on_floor else 25.0
		h.vel.x = move_toward(h.vel.x, target.x, accel * dt)
		h.vel.z = move_toward(h.vel.z, target.z, accel * dt)
	if dir.length() > 0.15:
		h.facing = dir.normalized()
	# jumping (with a little forgiveness: buffer + coyote time)
	if h.on_floor:
		h.coyote = 0.1
		h.jumps_left = 1 if h.who == "rocco" else 0
		h.long_t = 0
	else:
		h.coyote -= dt
	jump_buf -= dt
	if jump_pressed:
		jump_buf = 0.14
	if jump_buf > 0 and h.slam == "":
		if h.coyote > 0:
			h.vel.y = h.jump_v
			h.coyote = 0
			jump_buf = 0
			sfx("jump")
		elif h.jumps_left > 0:
			h.jumps_left -= 1
			h.vel.y = h.jump_v * 0.83
			jump_buf = 0
			h.spin()
			sfx("jump2")
	if not jump_held and h.vel.y > 0 and h.long_t <= 0:
		h.vel.y -= Actor.GRAVITY * 1.4 * dt
	h.vel.y = maxf(h.vel.y - Actor.GRAVITY * dt, -26)
	if action_pressed:
		_action(h)
	h.move(dt)
	if bot or invincible:
		pass
	if boss_on and not boss_done:
		h.position.x = clampf(h.position.x, Level.BOSS_X0 + 0.6, Level.BOSS_X1 + 0.4)
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
	elif h.action_t > 0 and h.who == "sparky":
		h.play("zap")
	elif h.slam == "fall":
		h.play("slam")
	elif not h.on_floor:
		h.play("jump" if h.vel.y > 0 else "fall")
	elif Vector2(h.vel.x, h.vel.z).length() > 0.5:
		h.play("run")
	else:
		h.blink_t -= dt
		if h.blink_t < 0:
			h.play("blink" if h.frames.has("blink") else "idle")
			if h.blink_t < -0.12:
				h.blink_t = randf_range(2, 4)
		else:
			h.play("idle")
	h.squash = Vector2.ONE.lerp(h.squash, exp(-dt * 12))
	h.animate(dt, 10 if h.who != "blocky" or h.action_t <= 0 else 7)
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
		"sparky":
			if h.action_t <= 0:
				h.action_t = 0.28
				var aim := h.facing
				var best := 9.0
				for f in foes:
					if f.dying < 0 and is_instance_valid(f):
						var d := f.position + Vector3(0, f.height * 0.5, 0) - h.position - Vector3(0, 0.5, 0)
						if d.length() < best and d.normalized().dot(Vector3(h.facing.x, 0, h.facing.z).normalized()) > 0.5:
							best = d.length()
							aim = d.normalized()
				spawn_shot("spark", h.position + Vector3(0, 0.55, 0), aim * 13.0, h)
				sfx("zap", -4)


func _slam_land(h: Hero) -> void:
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
	if t == Level.BEDROCK or t == Level.USED or c.z < 3:
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
	sfx("thunder")
	fx.shake = 0.9
	hud.say("THUNDERBOLT!", "Sparky's SUPER", 1.6)
	speak("Spar-ky!", "sparky")
	var lx := leader().position.x
	var hit := 0
	for f in foes:
		if is_instance_valid(f) and f.dying < 0 and absf(f.position.x - lx) < 15:
			fx.bolt(f.position)
			f.hurt(3 if f.is_boss else 4, self)
			hit += 1
	for i in maxi(0, 4 - hit):
		var x := lx + randf_range(-8, 8)
		fx.bolt(Vector3(x, level.ground_below(x, 6, 16), randf_range(4, 9)))


# ------------------------------------------------------------------ the friend who follows
func _update_follower(h: Hero, dt: float) -> void:
	var l := leader()
	var side := 1.0 if l.position.z < 6.5 else -1.0
	var want := l.position + Vector3(-signf(l.facing.x if absf(l.facing.x) > 0.1 else 1.0) * 1.4, 0, 0.9 * side)
	var d := want - h.position
	var flat := Vector2(d.x, d.z)
	var far := h.position.distance_to(l.position) > 10 or h.position.y < -3
	if far:
		h.position = l.position + Vector3(0, 0.5, 0)
		h.vel = Vector3.ZERO
		fx.burst(h.position + Vector3(0, 0.5, 0), Color.WHITE, 8, 3, 4)
	if flat.length() > 0.35:
		var sp := h.speed * clampf(flat.length() / 1.5, 0.3, 1.15)
		var v := flat.normalized() * sp
		h.vel.x = move_toward(h.vel.x, v.x, 40 * dt)
		h.vel.z = move_toward(h.vel.z, v.y, 40 * dt)
		if absf(v.x) > 0.3:
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
		h.position.x = clampf(h.position.x, Level.BOSS_X0 + 0.6, Level.BOSS_X1 + 0.4)
	h.action_t = maxf(0, h.action_t - dt)
	# Sparky zaps a nearby bad guy every few seconds when he's the helper
	if h.who == "sparky":
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
				var from := h.position + Vector3(0, 0.55, 0)
				var to := best.position + Vector3(0, best.height * 0.5, 0)
				spawn_shot("spark", from, (to - from).normalized() * 13.0, h)
				h.action_t = 0.3
				h.zap_t = 3.0
				sfx("zap", -6)
			else:
				h.zap_t = 0.5
	if h.action_t > 0:
		h.play("zap" if h.who == "sparky" else "mine")
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


func spawn_shot(kind: String, pos: Vector3, v: Vector3, owner: Node3D) -> void:
	var size := {"spark": 0.55, "arrow": 0.32, "spikeball": 0.8}[kind] as float
	var s := level.prop({"spark": "spark", "arrow": "arrow", "spikeball": "spikeball"}[kind], pos - Vector3(0, size * 0.5, 0), size)
	if not (s is Sprite3D):   # 3D shots: put the node's origin at the shot's middle, like the paper ones
		s.position = pos
		for ch in s.get_children():
			ch.position.y -= size * 0.5
	if kind == "arrow":
		if s is Sprite3D:
			s.flip_h = v.x < 0
		else:
			s.look_at(s.global_position - v, Vector3.UP)
	shots.append({"node": s, "vel": v, "kind": kind, "owner": owner, "life": 5.0 if kind == "spikeball" else 2.0, "bounces": 0})


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
			if level.solid(floori(nxt.x + signf(v.x) * 0.4), floori(nxt.y), floori(nxt.z)):
				v.x = -v.x
			s.position = nxt
			s.rotation.z -= v.x * dt * 2
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
			if sh.kind == "spark":
				for f in foes:
					if is_instance_valid(f) and f.dying < 0 and absf(f.position.x - s.position.x) < f.hw + 0.35 \
							and absf(f.position.z - s.position.z) < f.hw + 0.7 and s.position.y > f.position.y - 0.2 and s.position.y < f.position.y + f.height + 0.3:
						f.hurt(2 if sh.owner == leader() else 1, self)
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
	beaten += 1
	foes_gone.append(int(f.get_meta("spawn")))
	coins += 2
	super_charge = minf(1.0, super_charge + 0.06)
	fx.text(f.position + Vector3(0, f.height + 0.4, 0.4), "+2", Color("ffd23a"), 48)
	if f.is_boss:
		boss_done = true
		fx.shake = 1.2
		hud.say("KRAG JR. BEATEN!", "Run to the flag!", 3.0)
		play_music("meadow")
		speak("We did it! Woo hoo!", "rocco")
		save_game()
		for i in 6:
			fx.burst(f.position + Vector3(randf_range(-1, 1), randf_range(0, 2), 0), Color.from_hsv(randf(), 0.6, 1), 14, 6, 8)


func start_boss(f: Foe) -> void:
	boss_on = true
	checkpoint = Vector3(Level.BOSS_X0 + 2, 5, 6.5)
	hud.say("KRAG JR.!", "Stomp him when he swoops down!", 2.8)
	play_music("boss")
	sfx("alert")


# ------------------------------------------------------------------ checkpoint, flag, camera
func _update_progress(dt: float) -> void:
	var l := leader()
	if not checkpoint_hit and l.position.x > Level.CHECKPOINT_X:
		checkpoint_hit = true
		checkpoint = Vector3(Level.CHECKPOINT_X + 0.5, 5, 6.5)
		hud.say("CHECKPOINT!", "Saved" if slot > 0 else "", 1.4)
		sfx("heart")
		safe_pos = checkpoint
		save_game()
	if boss_done and l.position.x > Level.FLAG_X - 0.4:
		state = "clear"
		clear_t = 0
		cleared += 1
		save_game(false)   # keeps coins and ores; the level starts fresh next time
		play_music("fanfare", false)
		sfx("clear")
		speak("Wahoo! Team up!", "rocco")
		for i in 8:
			fx.burst(Vector3(Level.FLAG_X + randf_range(-2, 2), 8 + randf_range(0, 3), 4), Color.from_hsv(randf(), 0.6, 1), 16, 6, 6)
	if not dennis_done and play_t > 1.2:
		dennis_done = true
		_dennis()


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
