class_name Foe
extends Actor
## Bad guys. Each kind is picked with `kind` and runs its own little brain in tick().

var kind := "grumbler"
var dir := -1.0
var t := 0.0
var state := "idle"
var state_t := 0.0
var cool := 0.0
var dying := -1.0
var home := Vector3.ZERO
var target := Vector3.ZERO
var stompable := true
var is_boss := false
var safe_t := 0.0
var alert: Label3D
var heading := Vector3.RIGHT  # open worlds: which way it's walking (any direction)
var chain_root: Node3D        # CHOMPY: its post and chain
var grow := 1.0               # SNOWBALL ROLLER: gets bigger as it rolls
var turn_t := 0.0


func init(lv: Level, k: String, pos: Vector3) -> void:
	kind = k
	home = pos
	match k:
		"grumbler":
			setup(lv, {"walk": ["grumbler_1", "grumbler_2"], "flat": ["grumbler_flat"]}, 1.2, 16, k)
			hw = 0.42; height = 0.95; hp = 3
		"skelly":
			setup(lv, {"walk": ["skelly_1", "skelly_2"], "aim": ["skelly_aim"]}, 1.9, 21, k)
			hw = 0.3; height = 1.5; hp = 4
		"penguin":
			setup(lv, {"walk": ["penguin_1", "penguin_2"], "slide": ["penguin_slide"], "flat": ["penguin_flat"]}, 1.5, 18, k)
			hw = 0.36; height = 1.0; hp = 3
		"scorpion":
			setup(lv, {"walk": ["scorpion_1", "scorpion_2"], "aim": ["scorpion_strike"], "flat": ["scorpion_flat"]}, 1.2, 16, k)
			hw = 0.45; height = 0.7; hp = 4
		"buzzer":
			setup(lv, {"walk": ["buzzer_1", "buzzer_2"], "dive": ["buzzer_dive"]}, 1.5, 80, k)
			hw = 0.35; height = 0.8; hp = 1
		"hopper":
			setup(lv, {"walk": ["hopper_1"], "jump": ["hopper_jump"]}, 1.6, 16, k)
			hw = 0.42; height = 0.9; hp = 2
		"boohoo":
			setup(lv, {"walk": ["boohoo_1"], "shy": ["boohoo_shy"]}, 1.7, 72, k)
			hw = 0.4; height = 1.0; hp = 2
		"bomber":
			setup(lv, {"walk": ["bomber_1", "bomber_2"], "lit": ["bomber_lit"]}, 1.5, 12, k)
			hw = 0.38; height = 0.95; hp = 1
		"chompy":
			setup(lv, {"walk": ["chompy_1"], "bite": ["chompy_bite"]}, 1.9, 24, k)
			hw = 0.55; height = 1.1; hp = 6; stompable = false
		"spiky":
			setup(lv, {"walk": ["spiky_1", "spiky_2"]}, 1.5, 12, k)
			hw = 0.42; height = 0.75; hp = 3; stompable = false
		"bat":
			setup(lv, {"hang": ["bat_hang"], "walk": ["bat_1", "bat_2"]}, 1.4, 120, k)
			hw = 0.3; height = 0.6; hp = 1; state = "hang"
		"mummy":
			setup(lv, {"walk": ["mummy_1", "mummy_2"]}, 1.9, 16, k)
			hw = 0.3; height = 1.5; hp = 4
		"roller":
			setup(lv, {"walk": ["roller_1", "roller_2"]}, 1.6, 20, k)
			hw = 0.45; height = 1.0; hp = 1
		"cactusman":
			setup(lv, {"walk": ["cactusman_1"], "throw": ["cactusman_throw"]}, 1.8, 16, k)
			hw = 0.35; height = 1.5; hp = 3; stompable = false
		"biggrumbler":
			setup(lv, {"walk": ["grumbler_1", "grumbler_2"], "charge": ["grumbler_1", "grumbler_2"], "flat": ["grumbler_flat"]}, 2.6, 16, k)
			hw = 0.85; height = 2.0; hp = 12
		"kragjr":
			setup(lv, {"fly": ["kragjr_1", "kragjr_2"], "angry": ["kragjr_angry"], "hurt": ["kragjr_hurt"]}, 2.6, 46, k)
			hw = 0.95; height = 1.7; hp = 18; is_boss = true; state = "sleep"
	max_hp = hp
	position = pos
	play(sprite_anim_default())
	if k == "bat":
		play("hang")
	if k == "biggrumbler":
		show_health()   # a mini-boss: you can see how much health it has left
	if k == "chompy":
		_make_chain(lv, pos)
	alert = Label3D.new()
	alert.text = "!"
	alert.font_size = 96
	alert.outline_size = 18
	alert.modulate = Color("ff3b3b")
	alert.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	alert.no_depth_test = true
	alert.pixel_size = 0.01
	alert.position.y = height + 0.9
	alert.visible = false
	add_child(alert)


func sprite_anim_default() -> String:
	return "fly" if kind == "kragjr" else "walk"


func hurt(n: int, game) -> void:
	if dying >= 0 or safe_t > 0:
		return
	if kind == "bomber" and state != "lit":   # hitting a bomb lights its fuse
		_light(game)
		return

	hp -= n
	flash_t = 0.35
	if is_boss:
		safe_t = 0.55
		game.sfx("boss_hit")
	show_health()
	game.fx.burst(position + Vector3(0, height * 0.5, 0.3), Color(1, 1, 1), 8, 3.0)
	if hp <= 0:
		die(game)


func die(game) -> void:
	dying = 0.0
	alert.visible = false
	if bar:
		bar.visible = false
	game.sfx("boss_down" if is_boss else "stomp")
	if kind in ["grumbler", "penguin", "scorpion", "biggrumbler"]:
		play("flat")
	if kind == "biggrumbler":   # a mini-boss: a big reward
		game.coins += 8
		game.fx.text(position + Vector3(0, 2.6, 0), "+10!", Color("ffd23a"), 64)
		game.hud.say("BIG GRUMBLER BEATEN!", "Bonk the ? block it was guarding!", 2.2)
	if chain_root:
		chain_root.queue_free()
		chain_root = null
	game.on_foe_beaten(self)


func remove() -> void:
	if chain_root:
		chain_root.queue_free()
		chain_root = null
	super()


func tick(dt: float, game) -> void:
	t += dt
	safe_t = maxf(0, safe_t - dt)
	if dying >= 0:
		dying += dt
		if kind in ["grumbler", "penguin", "scorpion", "biggrumbler"]:
			squash = Vector2(1.0, 1.0)
		elif kind in FOES2:   # squish flat and pop
			squash = Vector2(1.0 + dying * 1.2, maxf(0.15, 1.0 - dying * 2.5))
		elif kind == "skelly":
			spin_t = 0.3
			position.y -= dt * 3
		else:
			spin_t = 0.3
			position += Vector3(dt * 4, dt * 9, 0)
		if dying > (2.2 if is_boss else 0.7):
			remove()
		update_shadow()
		animate(dt)
		return
	var lead: Actor = game.leader()
	if level.open:
		_tick_open(dt, game, lead)
		if position.y < -6:
			remove()
			return
		animate(dt, 5 if kind != "kragjr" else 6)
		update_shadow()
		return
	match kind:
		"grumbler":
			_grumbler(dt, game, lead)
		"skelly":
			_skelly(dt, game, lead)
		"kragjr":
			_kragjr(dt, game, lead)
		"penguin":
			_penguin(dt, game, lead)
		"scorpion":
			_scorpion(dt, game, lead)
	if position.y < -6:
		remove()
		return
	animate(dt, 5 if kind != "kragjr" else 6)
	update_shadow()


# ------------------------------------------------------------------ open worlds: walk any way
func _tick_open(dt: float, game, lead: Actor) -> void:
	var to := lead.position - position
	var flat := Vector3(to.x, 0, to.z)
	var dist := flat.length()
	if kind != "kragjr" and dist > 40:
		return   # far away: sleep (saves time on the tablet)
	state_t += dt
	match kind:
		"grumbler":
			_wander(dt, 1.7)
			squash = Vector2(1.0 + sin(t * 10) * 0.04, 1.0 - sin(t * 10) * 0.04)
		"penguin", "scorpion":
			var near := 7.0 if kind == "penguin" else 3.8
			var fast := 7.5 if kind == "penguin" else 8.0
			match state:
				"idle":
					play("walk")
					_wander(dt, 1.2 if kind == "penguin" else 1.4)
					if dist < near and absf(to.y) < 1.6 and state_t > 1.4:
						state = "ready"
						state_t = 0
						heading = flat.normalized()
						alert.visible = true
						game.sfx("alert")
				"ready":
					play("aim" if kind == "scorpion" else "walk")
					facing = heading
					_walk(dt, 0.0)
					if state_t > (0.45 if kind == "penguin" else 0.5):
						alert.visible = false
						state = "go"
						state_t = 0
				"go":
					if kind == "penguin":
						play("slide")
					_walk(dt, fast)
					if state_t > (1.1 if kind == "penguin" else 0.35) or walls_hit:
						state = "idle"
						state_t = 0
		"skelly":
			_walk(dt, 0.0)
			if dist > 0.1:
				facing = flat.normalized()
			if dist > 11 or absf(to.y) > 6:
				state = "idle"
			match state:
				"idle":
					play("walk")
					if dist < 10 and state_t > 1.6:
						state = "aim"
						state_t = 0
						alert.visible = true
						game.sfx("alert")
				"aim":
					play("aim")
					if state_t > 0.85:
						alert.visible = false
						var from := position + Vector3(0, 1.0, 0) + facing * 0.4
						var aim := lead.position + Vector3(0, lead.height * 0.5, 0)
						game.spawn_shot("arrow", from, (aim - from).normalized() * 9.0, self)
						game.sfx("arrow")
						state = "idle"
						state_t = -0.6
		"kragjr":
			_kragjr_open(dt, game, lead)
		_:
			if kind in FOES2:
				_tick_foe2(dt, game, lead, to, flat, dist)


## Walks along `heading` at `speed` (turns at walls and cliff edges, wanders back home).
func _walk(dt: float, speed: float) -> void:
	vel.x = heading.x * speed
	vel.z = heading.z * speed
	vel.y -= GRAVITY * dt
	move(dt)
	if speed > 0 and on_floor:
		var ahead := position + heading * (hw + 0.3)
		if not level.solid(floori(ahead.x), floori(position.y - 0.5), floori(ahead.z)) and not level.solid(floori(ahead.x), floori(position.y - 1.5), floori(ahead.z)):
			heading = -heading   # don't walk off a cliff
	if walls_hit and speed > 0:
		heading = heading.rotated(Vector3.UP, PI * randf_range(0.6, 1.4))
	if heading.length() > 0.1:
		facing = heading


func _wander(dt: float, speed: float) -> void:
	turn_t -= dt
	if turn_t <= 0:
		turn_t = randf_range(1.5, 3.5)
		var back := Vector3(home.x - position.x, 0, home.z - position.z)
		if back.length() > 7:
			heading = back.normalized()
		else:
			heading = Vector3.RIGHT.rotated(Vector3.UP, randf() * TAU)
	_walk(dt, speed)


## Krag Jr. in his castle: circles above the middle, throws spike balls at you,
## then shouts "!" and dives where you were standing.
func _kragjr_open(dt: float, game, lead: Actor) -> void:
	var ar: Rect2 = level.arena
	var mid := Vector3(ar.get_center().x + 0.5, level.arena_y, ar.get_center().y + 0.5)
	var ground := float(level.arena_y)
	match state:
		"sleep":
			position.y = ground + 4.5 + sin(t * 2) * 0.3
			if ar.grow(-1).has_point(Vector2(lead.position.x, lead.position.z)) and game.gate_open:
				state = "hover"
				state_t = 0
				game.start_boss(self)
		"hover":
			play("fly")
			var want := mid + Vector3(sin(t * 0.7) * 9.0, 4.6 + sin(t * 2.2) * 0.4, cos(t * 0.5) * 5.0)
			position = position.lerp(want, 1 - exp(-dt * 2.0))
			var f := Vector3(lead.position.x - position.x, 0, lead.position.z - position.z)
			if f.length() > 0.1:
				facing = f.normalized()
			cool -= dt
			if cool <= 0:
				cool = 2.4 if hp > max_hp / 2 else 1.6
				game.spawn_shot("spikeball", position + Vector3(0, 0.2, 0), facing * 3.0 + Vector3(0, 2, 0), self)
			if state_t > (4.0 if hp > max_hp / 2 else 2.8):
				state = "warn"
				state_t = 0
				alert.visible = true
				game.sfx("alert")
		"warn":
			play("angry")
			sprite.position.x = sin(t * 60) * 0.06
			if state_t > 0.8:
				sprite.position.x = 0
				alert.visible = false
				target = lead.position
				state = "dive"
				state_t = 0
		"dive":
			var to := target + Vector3(0, 0.2, 0)
			position = position.move_toward(to, dt * 12.0)
			if position.distance_to(to) < 0.2 or state_t > 1.4:
				state = "low"
				state_t = 0
		"low":
			if state_t > 0.9:
				state = "rise"
				state_t = 0
		"rise":
			play("fly")
			position.y += dt * 4.5
			if position.y > ground + 4.4:
				state = "hover"
				state_t = 0
	if flash_t > 0:
		play("hurt")
	elif state != "warn":
		play("fly")


## Penguin: waddles about; when you're close it shouts "!" and belly-slides at you.
func _penguin(dt: float, game, lead: Actor) -> void:
	vel.y -= GRAVITY * dt
	state_t += dt
	var dx := lead.position.x - position.x
	match state:
		"idle":
			play("walk")
			vel.x = dir * 1.2
			vel.z = 0
			if absf(dx) < 7 and absf(lead.position.y - position.y) < 1.6 and state_t > 1.5:
				state = "ready"
				state_t = 0
				dir = signf(dx)
				alert.visible = true
				game.sfx("alert")
		"ready":
			vel.x = 0
			if state_t > 0.45:
				alert.visible = false
				state = "slide"
				state_t = 0
		"slide":
			play("slide")
			vel.x = dir * 7.5
			if state_t > 1.1 or walls_hit:
				state = "idle"
				state_t = 0
	facing = Vector3(dir, 0, 0)
	move(dt)
	if walls_hit and state == "idle":
		dir = -dir
	if on_floor and state == "idle":
		var ax := position.x + dir * (hw + 0.2)
		if not level.solid(floori(ax), floori(position.y - 0.5), floori(position.z)):
			dir = -dir


## Scorpion: creeps along; when you're close it raises its tail ("!") and lunges.
func _scorpion(dt: float, game, lead: Actor) -> void:
	vel.y -= GRAVITY * dt
	state_t += dt
	var dx := lead.position.x - position.x
	match state:
		"idle":
			play("walk")
			vel.x = dir * 1.4
			if absf(dx) < 3.6 and absf(lead.position.y - position.y) < 1.2 and state_t > 1.2:
				state = "ready"
				state_t = 0
				dir = signf(dx)
				alert.visible = true
				game.sfx("alert")
		"ready":
			play("aim")
			vel.x = 0
			if state_t > 0.5:
				alert.visible = false
				state = "lunge"
				state_t = 0
		"lunge":
			vel.x = dir * 8.0
			if state_t > 0.35:
				state = "idle"
				state_t = 0
	facing = Vector3(dir, 0, 0)
	move(dt)
	if walls_hit and state == "idle":
		dir = -dir
	if on_floor and state == "idle":
		var ax := position.x + dir * (hw + 0.2)
		if not level.solid(floori(ax), floori(position.y - 0.5), floori(position.z)):
			dir = -dir


func _grumbler(dt: float, game, lead: Actor) -> void:
	if absf(lead.position.x - position.x) > 24:
		return
	vel.x = dir * 1.7
	vel.z = 0
	vel.y -= GRAVITY * dt
	move(dt)
	if walls_hit:
		dir = -dir
	if on_floor:
		var ax := position.x + dir * (hw + 0.2)
		if not level.solid(floori(ax), floori(position.y - 0.5), floori(position.z)):
			dir = -dir
	facing = Vector3(dir, 0, 0)
	squash = Vector2(1.0 + sin(t * 10) * 0.04, 1.0 - sin(t * 10) * 0.04)


func _skelly(dt: float, game, lead: Actor) -> void:
	vel.y -= GRAVITY * dt
	vel.x = 0
	vel.z = 0
	var dx := lead.position.x - position.x
	facing = Vector3(signf(dx) if dx != 0 else 1.0, 0, 0)
	state_t += dt
	if absf(dx) > 11 or absf(lead.position.y - position.y) > 6:
		state = "idle"
	match state:
		"idle":
			play("walk")
			vel.x = sin(t * 0.8) * 0.8
			if absf(dx) < 10 and state_t > 1.6:
				state = "aim"
				state_t = 0
				alert.visible = true
				game.sfx("alert")
		"aim":
			play("aim")
			if state_t > 0.85:
				alert.visible = false
				var from := position + Vector3(0.4 * facing.x, 1.0, 0.1)
				var to := lead.position + Vector3(0, lead.height * 0.5, 0)
				game.spawn_shot("arrow", from, (to - from).normalized() * 9.0, self)
				game.sfx("arrow")
				state = "idle"
				state_t = -0.6
	move(dt)


func _kragjr(dt: float, game, lead: Actor) -> void:
	var ground := float(level.heights[clampi(int(position.x), 0, Level.LX - 1)])
	state_t += dt
	match state:
		"sleep":
			position.y = ground + 4.5 + sin(t * 2) * 0.3
			if lead.position.x > Level.BOSS_X0 + 3:
				state = "hover"
				state_t = 0
				game.start_boss(self)
		"hover":
			play("fly")
			var mid := (Level.BOSS_X0 + Level.BOSS_X1) * 0.5 + 0.5
			var want := Vector3(mid + sin(t * 0.7) * 10.0, ground + 4.6 + sin(t * 2.2) * 0.4, lerpf(position.z, lead.position.z, 0.5))
			position = position.lerp(want, 1 - exp(-dt * 2.0))
			facing = Vector3(signf(lead.position.x - position.x), 0, 0)
			cool -= dt
			if cool <= 0:
				cool = 2.4 if hp > max_hp / 2 else 1.6
				game.spawn_shot("spikeball", position + Vector3(0, 0.2, 0), Vector3(facing.x * 2.5, 2, 0), self)
			if state_t > (4.0 if hp > max_hp / 2 else 2.8):
				state = "warn"
				state_t = 0
				alert.visible = true
				game.sfx("alert")
		"warn":
			play("angry")
			sprite.position.x = sin(t * 60) * 0.06
			if state_t > 0.8:
				sprite.position.x = 0
				alert.visible = false
				target = lead.position
				state = "dive"
				state_t = 0
		"dive":
			var to := target + Vector3(0, 0.2, 0)
			position = position.move_toward(to, dt * 12.0)
			if position.distance_to(to) < 0.2 or state_t > 1.4:
				state = "low"
				state_t = 0
		"low":
			if state_t > 0.9:
				state = "rise"
				state_t = 0
		"rise":
			play("fly")
			position.y += dt * 4.5
			if position.y > ground + 4.4:
				state = "hover"
				state_t = 0
	if flash_t > 0:
		play("hurt")
	elif state != "warn":
		play("fly")


# ------------------------------------------------------------------ more bad guys (open worlds)
const FOES2 := ["buzzer", "hopper", "boohoo", "bomber", "chompy", "spiky", "bat", "mummy", "roller", "cactusman", "biggrumbler"]


func _tick_foe2(dt: float, game, lead: Actor, to: Vector3, flat: Vector3, dist: float) -> void:
	match kind:
		"buzzer":
			_buzzer(dt, game, lead, flat, dist)
		"hopper":
			_hopper(dt, game, flat, dist)
		"boohoo":
			_boohoo(dt, game, lead, dist)
		"bomber":
			_bomber(dt, game, flat, dist)
		"chompy":
			_chompy(dt, game, lead, flat, dist)
		"spiky":
			play("walk")
			_wander(dt, 1.3)
		"bat":
			_bat(dt, game, lead, dist)
		"mummy":
			play("walk")
			if dist < 12 and absf(to.y) < 3:
				heading = flat.normalized()
				_walk(dt, 1.4)
			else:
				_wander(dt, 0.9)
		"roller":
			_roller(dt, game, flat, dist)
		"cactusman":
			_cactusman(dt, game, lead, flat, dist)
		"biggrumbler":
			_big_grumbler(dt, game, flat, dist)


func _say_alert(game) -> void:
	alert.visible = true
	game.sfx("alert")


## BUZZER: buzzes round in wavy circles, then "!" and dives at you.
func _buzzer(dt: float, game, lead: Actor, flat: Vector3, dist: float) -> void:
	match state:
		"idle":
			play("walk")
			var a := t * 1.2
			var want := home + Vector3(cos(a) * 3.0, 1.8 + sin(t * 3.0) * 0.4, sin(a) * 3.0)
			position = position.lerp(want, 1 - exp(-dt * 3.0))
			facing = Vector3(-sin(a), 0, cos(a))
			if dist < 7 and state_t > 2.0:
				state = "ready"; state_t = 0
				_say_alert(game)
		"ready":
			facing = flat.normalized()
			if state_t > 0.5:
				alert.visible = false
				target = lead.position + Vector3(0, 0.4, 0)
				state = "dive"; state_t = 0
		"dive":
			play("dive")
			position = position.move_toward(target, dt * 8.0)
			if position.distance_to(target) < 0.2 or state_t > 1.2:
				state = "back"; state_t = 0
		"back":
			play("walk")
			position = position.move_toward(home + Vector3(0, 1.8, 0), dt * 3.5)
			if state_t > 1.5:
				state = "idle"; state_t = 0


## HOPPER: sits, then takes a big frog hop toward you.
func _hopper(dt: float, game, flat: Vector3, dist: float) -> void:
	vel.y -= GRAVITY * dt
	if on_floor:
		vel.x = move_toward(vel.x, 0, 20 * dt)
		vel.z = move_toward(vel.z, 0, 20 * dt)
		play("walk")
		if state_t > 1.3:
			state_t = 0
			if dist < 12 and dist > 0.5:
				heading = flat.normalized()
			else:
				var back := Vector3(home.x - position.x, 0, home.z - position.z)
				heading = back.normalized() if back.length() > 5 else Vector3.RIGHT.rotated(Vector3.UP, randf() * TAU)
			vel = heading * 4.5 + Vector3(0, 10.5, 0)
			on_floor = false
			game.sfx("jump", -14)
	else:
		play("jump")
	facing = heading
	move(dt)


## BOOHOO: a shy ghost. When the camera looks at it, it hides its face and stops.
## When you look away, it floats after you (straight through walls).
func _boohoo(dt: float, game, lead: Actor, dist: float) -> void:
	var cam: Camera3D = game.cam
	var to_me := position + Vector3(0, 0.6, 0) - cam.global_position
	var seen := (-cam.global_transform.basis.z).dot(to_me.normalized()) > 0.7 and to_me.length() < 22
	if seen:
		state = "shy"
		play("shy")
		position.y = lerpf(position.y, home.y + 0.3 + sin(t * 2) * 0.2, 1 - exp(-dt * 2))
		return
	state = "idle"
	play("walk")
	if dist < 16:
		var want := lead.position + Vector3(0, 0.3, 0)
		position = position.move_toward(want, dt * 1.8)
		var f := Vector3(want.x - position.x, 0, want.z - position.z)
		if f.length() > 0.1:
			facing = f.normalized()


func is_shy() -> bool:
	return kind == "boohoo" and state == "shy"


## BOMBER: walks about; gets close, lights up and chases you, then BOOM (breaks blocks).
func _bomber(dt: float, game, flat: Vector3, dist: float) -> void:
	match state:
		"idle":
			play("walk")
			_wander(dt, 1.2)
			if dist < 5.5:
				_light(game)
		"lit":
			play("lit")
			if dist > 0.6:
				heading = flat.normalized()
			_walk(dt, 3.2)
			if state_t > 2.2:
				_explode(game)


func _light(game) -> void:
	state = "lit"
	state_t = 0
	_say_alert(game)
	game.sfx("hurt", -8)
	alert.text = "!!"


func _explode(game) -> void:
	var c := position + Vector3(0, 0.5, 0)
	game.fx.burst(c, Color("ffb02e"), 30, 7, 6)
	game.fx.burst(c, Color(0.3, 0.3, 0.32), 20, 4, 4)
	game.fx.shake = 0.6
	game.sfx("break")
	if not level.is_home:   # break the blocks around it (not the castle, not the bottom)
		for x in range(floori(c.x) - 1, floori(c.x) + 2):
			for y in range(floori(c.y) - 1, floori(c.y) + 2):
				for z in range(floori(c.z) - 1, floori(c.z) + 2):
					var cell := Vector3i(x, y, z)
					var bt := level.get_cell(x, y, z)
					if y > 0 and bt != Level.AIR and bt != Level.BEDROCK and not level.locked.has(cell) and bt != Level.MYSTERY:
						level.set_cell(x, y, z, Level.AIR)
	for h in [game.leader(), game.follower()]:
		if h.visible and (h.position + Vector3(0, 0.7, 0)).distance_to(c) < 2.3 and h == game.leader():
			game.hurt_hero(c)
	hp = 0
	die(game)


## CHOMPY: a big iron ball on a chain. It bounces round its post and lunges when
## you come close, but the chain stops it. Can't be stomped: use attacks.
func _chompy(dt: float, game, lead: Actor, flat: Vector3, dist: float) -> void:
	const REACH := 4.5
	vel.y -= GRAVITY * dt
	match state:
		"idle":
			play("walk")
			if on_floor and state_t > 0.6:
				state_t = 0
				heading = Vector3.RIGHT.rotated(Vector3.UP, randf() * TAU)
				vel = heading * 2.0 + Vector3(0, 4, 0)
			var pd := Vector3(lead.position.x - home.x, 0, lead.position.z - home.z).length()
			if pd < REACH + 1.5 and absf(lead.position.y - position.y) < 2 and cool <= 0:
				state = "ready"; state_t = 0
				_say_alert(game)
		"ready":
			vel.x = 0; vel.z = 0
			facing = flat.normalized()
			if state_t > 0.5:
				alert.visible = false
				heading = flat.normalized()
				state = "lunge"; state_t = 0
				game.sfx("stomp", -6)
		"lunge":
			play("bite")
			vel.x = heading.x * 11
			vel.z = heading.z * 11
			if state_t > 0.45:
				state = "idle"; state_t = 0
				cool = 1.2
	cool -= dt
	move(dt)
	var off := Vector3(position.x - home.x, 0, position.z - home.z)
	if off.length() > REACH:   # the chain is tight
		off = off.normalized() * REACH
		position.x = home.x + off.x
		position.z = home.z + off.z
	if facing.length() < 0.1 or state == "idle":
		facing = Vector3(lead.position.x - position.x, 0, lead.position.z - position.z).normalized()
	_update_chain()


func _make_chain(lv: Level, pos: Vector3) -> void:
	chain_root = Node3D.new()
	lv.add_child(chain_root)
	var post := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.12
	cm.bottom_radius = 0.14
	cm.height = 0.8
	post.mesh = cm
	post.material_override = Toys.mat(Color("8a5a35"))
	post.position = pos + Vector3(0, 0.4, 0)
	chain_root.add_child(post)
	var link := SphereMesh.new()
	link.radius = 0.09
	link.height = 0.18
	for i in 6:
		var l := MeshInstance3D.new()
		l.mesh = link
		l.material_override = Toys.mat(Color("4a4f5c"), false)
		chain_root.add_child(l)


func _update_chain() -> void:
	if chain_root == null:
		return
	var a := home + Vector3(0, 0.75, 0)
	var b := position + Vector3(0, 0.5, 0)
	for i in 6:
		var k := (i + 1) / 7.0
		var l: Node3D = chain_root.get_child(i + 1)
		l.position = a.lerp(b, k) - Vector3(0, sin(k * PI) * 0.25, 0)


## BAT: hangs upside down until you walk under it, then swoops down at you and flies back.
func _bat(dt: float, game, lead: Actor, dist: float) -> void:
	match state:
		"hang":
			play("hang")
			position = position.lerp(home, 1 - exp(-dt * 4))
			if dist < 6 and lead.position.y < position.y and state_t > 1.5:
				state = "swoop"; state_t = 0
				target = lead.position + Vector3(0, 0.5, 0)
				game.sfx("alert", -6)
		"swoop":
			play("walk")
			position = position.move_toward(target, dt * 6.5)
			var f := Vector3(target.x - position.x, 0, target.z - position.z)
			if f.length() > 0.1:
				facing = f.normalized()
			if position.distance_to(target) < 0.3 or state_t > 1.6:
				state = "back"; state_t = 0
		"back":
			play("walk")
			position = position.move_toward(home, dt * 4.0)
			if position.distance_to(home) < 0.2:
				state = "hang"; state_t = 0


## SNOWBALL ROLLER: "!" then rolls at you, getting bigger. It crumbles when it hits something.
func _roller(dt: float, game, flat: Vector3, dist: float) -> void:
	match state:
		"idle":
			play("walk")
			vel.y -= GRAVITY * dt
			vel.x = 0; vel.z = 0
			move(dt)
			if dist < 11 and state_t > 2.0:
				state = "ready"; state_t = 0
				heading = flat.normalized()
				_say_alert(game)
		"ready":
			facing = heading
			if state_t > 0.5:
				alert.visible = false
				state = "roll"; state_t = 0
		"roll":
			play("walk")
			grow = minf(1.9, grow + dt * 0.35)
			scale = Vector3.ONE * grow
			hw = 0.45 * grow
			height = 1.0 * grow
			_walk(dt, 5.5)
			if walls_hit or state_t > 4.0:
				game.fx.burst(position + Vector3(0, 0.6, 0), Color("f4f8ff"), 18, 5, 4)
				game.sfx("break", -6)
				position = home
				grow = 1.0
				scale = Vector3.ONE
				hw = 0.45
				height = 1.0
				state = "idle"; state_t = -1.0


## CACTUS-MAN: stands still and throws needles at you. Spiky: don't stomp it.
func _cactusman(dt: float, game, lead: Actor, flat: Vector3, dist: float) -> void:
	vel.y -= GRAVITY * dt
	vel.x = 0; vel.z = 0
	move(dt)
	if dist > 0.1:
		facing = flat.normalized()
	play("throw" if state == "throw" else "walk")
	if state == "throw" and state_t > 0.4:
		state = "idle"; state_t = 0
	if state == "idle" and dist < 10 and state_t > 2.4:
		state = "throw"; state_t = 0
		var from := position + Vector3(0, 1.2, 0) + facing * 0.4
		var aim := lead.position + Vector3(0, lead.height * 0.5, 0)
		game.spawn_shot("arrow", from, (aim - from).normalized() * 8.0, self)
		game.sfx("arrow")


## BIG GRUMBLER: a mini-boss guarding a ? block. "!" then it charges. Lots of health.
func _big_grumbler(dt: float, game, flat: Vector3, dist: float) -> void:
	match state:
		"idle":
			play("walk")
			_wander(dt, 1.0)
			if dist < 8 and state_t > 2.2:
				state = "ready"; state_t = 0
				heading = flat.normalized()
				_say_alert(game)
		"ready":
			facing = heading
			_walk(dt, 0.0)
			squash = Vector2(1.1, 0.92)
			if state_t > 0.6:
				alert.visible = false
				state = "charge"; state_t = 0
				game.fx.shake = 0.3
		"charge":
			play("charge")
			_walk(dt, 7.0)
			if state_t > 1.0 or walls_hit:
				state = "idle"; state_t = 0
	if state != "ready":
		squash = Vector2(1.0 + sin(t * 8) * 0.03, 1.0 - sin(t * 8) * 0.03)
