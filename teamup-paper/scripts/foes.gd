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
		"kragjr":
			setup(lv, {"fly": ["kragjr_1", "kragjr_2"], "angry": ["kragjr_angry"], "hurt": ["kragjr_hurt"]}, 2.6, 46, k)
			hw = 0.95; height = 1.7; hp = 18; is_boss = true; state = "sleep"
	max_hp = hp
	position = pos
	play(sprite_anim_default())
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
	if kind in ["grumbler", "penguin", "scorpion"]:
		play("flat")
	game.on_foe_beaten(self)


func tick(dt: float, game) -> void:
	t += dt
	safe_t = maxf(0, safe_t - dt)
	if dying >= 0:
		dying += dt
		if kind in ["grumbler", "penguin", "scorpion"]:
			squash = Vector2(1.0, 1.0)
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
