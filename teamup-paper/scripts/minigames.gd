class_name MiniGames
extends Node3D
## The ARCADE's mini games, played against bad guys for fun (and coins):
##   KRAG BOWL      bowling vs a Grumbler: aim, power, roll; 3 frames, most pins wins
##   COIN SCRAMBLE  grab the most coins in 30 s vs 3 Grumblers (stomp one to steal a coin)
##   PENGUIN RACE   race 3 penguins down an icy track, JUMP the hurdles
## Each game is built far away from the home world (ORIGIN) with its own little floor
## and its own simple movement, so it never touches the home's blocks or saves.
## The game (main.gd) calls step() every frame while state == "minigame" and the HUD
## calls draw(). When it's over, game.minigame_done(prize) takes you back home.

const GAMES := [
	{"id": "bowling", "name": "KRAG BOWL", "vs": "vs a GRUMBLER", "about": "Tap to aim, tap for power. 3 frames: most pins wins!", "color": Color("e8413b")},
	{"id": "coins", "name": "COIN SCRAMBLE", "vs": "vs 3 GRUMBLERS", "about": "Grab the most coins in 30 seconds! Stomp a Grumbler to steal one.", "color": Color("e0a800")},
	{"id": "race", "name": "PENGUIN RACE", "vs": "vs 3 PENGUINS", "about": "Race down the ice! JUMP over the hurdles.", "color": Color("2c9ad8")},
]
const PRIZE_WIN := 20
const PRIZE_TRY := 5
const ORIGIN := Vector3(-400, 0, -400)   # far away from everything else

var game
var level: Level
var id := ""
var t := 0.0
var phase := "intro"         # intro -> play (per game) -> result
var result_text := ""
var result_sub := ""
var prize := 0
var won := false
var arena: Node3D
var player: Hero
var rivals: Array = []       # Foe nodes driven by this script (not the world's bad guys)
var pvel := Vector3.ZERO     # the player's own simple movement
var p_floor := 0.0
var cam_pos := Vector3.ZERO
var cam_look := Vector3.ZERO
var autopilot := false       # the test robot plays (also when game.bot)

# ------------------------------------------------ bowling
const FRAMES := 3
const LANE_W := 1.1
const HEAD_Z := -16.0
const BALL_R := 0.22
const PIN_R := 0.12
var turn := "you"            # "you" or "cpu"
var frame := 0
var ball_n := 0              # 0 = first ball of the frame, 1 = second
var aim := 0.0               # -1..1 (the arrow)
var aim_dir := 1.0
var power := 0.0
var power_dir := 1.0
var bphase := "aim"          # aim, power, roll, settle, show
var bphase_t := 0.0
var ball: Node3D
var ball_v := Vector3.ZERO
var pins: Array = []         # {node, pos, vel, down, gone, tip}
var first_hit := true
var pocket := false
var scores := {"you": [], "cpu": []}   # per frame: [ball1, ball2]
var frame_pins := []         # pins knocked this frame so far (by ball)
var note := ""
var note_t := 0.0
var arrow: MeshInstance3D
var cpu_aim := 0.0
var cpu_power := 0.0

# ------------------------------------------------ coin scramble
const SCRAMBLE_TIME := 30.0
const ARENA_R := 9.0
var coins_nodes: Array = []  # {node, pos}
var counts := {}             # "you" / 0 / 1 / 2 -> coins
var stun := {}               # rival index -> seconds stunned
var time_left := 0.0

# ------------------------------------------------ penguin race
const TRACK_LEN := 70.0
const HURDLE_EVERY := 9.0
var hurdles: Array = []      # z positions
var finish_order: Array = [] # "you" / 0 / 1 / 2
var r_speed: Array = []      # rival top speeds
var r_vel: Array = []        # rival current forward speed
var r_y: Array = []          # rival heights (jumping)
var r_vy: Array = []
var trip_t := {}             # who -> seconds slowed
var race_t := 0.0


func start(g, which: String) -> void:
	game = g
	level = g.level
	id = which
	player = g.leader()
	arena = Node3D.new()
	add_child(arena)
	position = Vector3.ZERO
	t = 0.0
	phase = "intro"
	match id:
		"bowling":
			_bowling_build()
		"coins":
			_coins_build()
		"race":
			_race_build()
	game.play_music("silly")
	_place_camera(1.0)


static func info(which: String) -> Dictionary:
	for gm in GAMES:
		if gm.id == which:
			return gm
	return {}


func title() -> String:
	return info(id).name


## The JUMP button's label while playing.
func button_label() -> String:
	if phase == "result":
		return "OK"
	if id == "bowling":
		if turn != "you":
			return "WAIT"
		return {"aim": "AIM!", "power": "ROLL!"}.get(bphase, "...")
	return "JUMP"


func uses_stick() -> bool:
	return id != "bowling"


# ------------------------------------------------------------------ shared bits
func _mat_tex(tex: String, scale: float, tint := Color.WHITE) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://art/t_%s.png" % tex)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	m.uv1_triplanar = true
	m.uv1_scale = Vector3(scale, scale, scale)
	m.albedo_color = tint
	m.roughness = 0.9
	return m


func _box(size: Vector3, pos: Vector3, m: Material) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var b := BoxMesh.new()
	b.size = size
	mi.mesh = b
	mi.material_override = m
	mi.position = pos
	arena.add_child(mi)
	return mi


func _label(text: String, pos: Vector3, col: Color, size := 64) -> Label3D:
	var l := Label3D.new()
	l.text = text
	l.font_size = size
	l.outline_size = 14
	l.pixel_size = 0.012
	l.modulate = col
	l.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	l.position = pos
	arena.add_child(l)
	return l


func _rival(kind: String, pos: Vector3) -> Foe:
	var f := Foe.new()
	level.add_child(f)
	f.init(level, kind, pos)
	f.alert.visible = false
	rivals.append(f)
	return f


## The robot's stick (x, y) and jump (z > 0) for COIN SCRAMBLE / PENGUIN RACE.
func _auto_input() -> Vector3:
	var p := player.position
	if id == "coins":
		var best := p
		var bd := 999.0
		for c in coins_nodes:
			var d: float = Vector2(c.pos.x - p.x, c.pos.z - p.z).length()
			if d < bd:
				bd = d
				best = c.pos
		var to := Vector2(best.x - p.x, best.z - p.z)
		return Vector3(to.normalized().x, to.normalized().y, 0) if to.length() > 0.2 else Vector3.ZERO
	if id == "race":
		var o := ORIGIN + Vector3(120, 0, 0)
		var jump := 0.0
		for hz in hurdles:
			var ahead: float = (p.z - o.z) - hz
			if ahead > 0.6 and ahead < 1.6:
				jump = 1.0
		return Vector3(0, -1, jump)
	return Vector3.ZERO


## The player's own simple movement on a flat floor (the arenas aren't made of blocks).
func _move_player(dt: float, speed: float, accel: float) -> void:
	var dir := Vector3(game.move_vec.x, 0, game.move_vec.y)
	var jump_now: bool = game.jump_pressed
	if autopilot or game.bot:
		var ap := _auto_input()
		dir = Vector3(ap.x, 0, ap.y)
		jump_now = ap.z > 0
	var want := dir * speed
	pvel.x = move_toward(pvel.x, want.x, accel * dt)
	pvel.z = move_toward(pvel.z, want.z, accel * dt)
	var on_floor := player.position.y <= p_floor + 0.001
	if on_floor and jump_now:
		pvel.y = 11.5
		game.sfx("jump")
		player.squash = Vector2(0.85, 1.18)
	pvel.y -= Actor.GRAVITY * dt
	player.position += pvel * dt
	if player.position.y <= p_floor:
		if pvel.y < -4:
			player.squash = Vector2(1.2, 0.82)
		player.position.y = p_floor
		pvel.y = 0
	if dir.length() > 0.15:
		player.facing = dir.normalized()
	if player.position.y > p_floor + 0.01:
		player.play("jump" if pvel.y > 0 else "fall")
	elif Vector2(pvel.x, pvel.z).length() > 0.5:
		player.play("run")
	else:
		player.play("idle")
	player.squash = Vector2.ONE.lerp(player.squash, exp(-dt * 12))
	player.animate(dt)


func _place_camera(k: float) -> void:
	game.cam.position = game.cam.position.lerp(cam_pos, k)
	game.cam.look_at(cam_look, Vector3.UP)


func step(dt: float) -> void:
	t += dt
	note_t = maxf(0, note_t - dt)
	if phase == "result":
		player.play("win" if won else "idle")
		player.animate(dt)
		for f in rivals:
			if is_instance_valid(f):
				f.animate(dt)
		if t > 1.2 and (game.jump_pressed or game.action_pressed):
			game.minigame_done(prize)
		_place_camera(1 - exp(-dt * 4))
		return
	match id:
		"bowling":
			_bowling_step(dt)
		"coins":
			_coins_step(dt)
		"race":
			_race_step(dt)
	_place_camera(1 - exp(-dt * 5))


## Ends the game: place 1 = you won.
func _finish(place: int, text: String, sub: String) -> void:
	phase = "result"
	t = 0.0
	won = place == 1
	prize = PRIZE_WIN if won else PRIZE_TRY
	result_text = text
	result_sub = sub + "   +%d coins" % prize
	game.sfx("clear" if won else "heart")
	game.play_music("fanfare", false)
	if won:
		game.speak("Wahoo! We won!", "rocco")
		for i in 6:
			game.fx.burst(player.position + Vector3(randf_range(-2, 2), 2 + randf(), randf_range(-2, 2)), Color.from_hsv(randf(), 0.6, 1), 14, 5, 6)


func cleanup() -> void:
	for f in rivals:
		if is_instance_valid(f):
			f.remove()
	rivals.clear()
	queue_free()


# ================================================================== KRAG BOWL
func _bowling_build() -> void:
	var o := ORIGIN
	var wood := _mat_tex("planks", 1.6)
	_box(Vector3(LANE_W * 2, 0.2, 22), o + Vector3(0, -0.1, -9), wood)
	var gut := Toys.mat(Color("3a3f4e"), false)
	for s in [-1, 1]:
		_box(Vector3(0.36, 0.1, 22), o + Vector3(s * (LANE_W + 0.18), -0.16, -9), gut)
		_box(Vector3(0.4, 0.5, 22), o + Vector3(s * (LANE_W + 0.56), 0.05, -9), Toys.mat(Color("8a5bc4"), false))
	_box(Vector3(6, 3, 0.4), o + Vector3(0, 1.2, HEAD_Z - 3.2), Toys.mat(Color("2b2140"), false))
	_box(Vector3(14, 0.2, 8), o + Vector3(0, -0.15, 4), _mat_tex("wool_red", 0.5))
	# the floor around the lane, and coloured lights
	_box(Vector3(30, 0.1, 40), o + Vector3(0, -0.4, -8), Toys.mat(Color("1d1830"), false))
	for i in 6:
		var light := _box(Vector3(0.5, 0.5, 0.5), o + Vector3(-2.6 if i % 2 == 0 else 2.6, 2.6, -2 - i * 3.0), Toys.mat(Color.from_hsv(i / 6.0, 0.7, 1), false, 2.0))
		light.rotation.y = i
	_label("KRAG BOWL", o + Vector3(0, 3.6, HEAD_Z - 3.0), Color("ffd23a"), 96)
	# the ball and the aiming arrow
	ball = Node3D.new()
	var bm := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = BALL_R
	sm.height = BALL_R * 2
	bm.mesh = sm
	bm.material_override = Toys.mat(Color("3a5bd9"), true, 0.15)
	ball.add_child(bm)
	for k in 3:   # finger holes
		var h := MeshInstance3D.new()
		var hs := SphereMesh.new()
		hs.radius = 0.035
		hs.height = 0.07
		h.mesh = hs
		h.material_override = Toys.mat(Color("101018"), false)
		h.position = Vector3(-0.06 + k * 0.06, 0.15 + (0.03 if k == 1 else 0.0), 0.14)
		ball.add_child(h)
	arena.add_child(ball)
	arrow = MeshInstance3D.new()
	var am := PrismMesh.new()
	am.size = Vector3(0.3, 1.4, 0.05)
	arrow.mesh = am
	arrow.material_override = Toys.mat(Color("ffd23a"), false, 1.2)
	arena.add_child(arrow)
	_rival("grumbler", o + Vector3(2.2, 0, 1.5))
	scores = {"you": [], "cpu": []}
	frame = 0
	turn = "you"
	_new_frame()
	player.facing = Vector3(0, 0, -1)
	p_floor = 0.0
	cam_pos = o + Vector3(0.3, 2.1, 4.2)
	cam_look = o + Vector3(0, 0.1, -10)
	phase = "play"
	note = "YOUR TURN!"
	note_t = 1.6


func _new_frame() -> void:
	for p in pins:
		p.node.queue_free()
	pins.clear()
	var o := ORIGIN
	for r in 4:
		for i in r + 1:
			var x := (i - r * 0.5) * 0.5
			_add_pin(o + Vector3(x, 0, HEAD_Z - r * 0.43))
	ball_n = 0
	frame_pins = []
	scores[turn].append([])
	_ready_ball()


func _add_pin(p: Vector3) -> void:
	var n := Node3D.new()
	var body := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.07
	cm.bottom_radius = PIN_R
	cm.height = 0.46
	body.mesh = cm
	body.material_override = Toys.mat(Color("f6f6f2"))
	body.position.y = 0.23
	n.add_child(body)
	var head := MeshInstance3D.new()
	var hs := SphereMesh.new()
	hs.radius = 0.085
	hs.height = 0.17
	head.mesh = hs
	head.material_override = Toys.mat(Color("f6f6f2"))
	head.position.y = 0.5
	n.add_child(head)
	var stripe := MeshInstance3D.new()
	var st := CylinderMesh.new()
	st.top_radius = 0.075
	st.bottom_radius = 0.08
	st.height = 0.05
	stripe.mesh = st
	stripe.material_override = Toys.mat(Color("e8413b"), false)
	stripe.position.y = 0.4
	n.add_child(stripe)
	n.position = p
	arena.add_child(n)
	pins.append({"node": n, "pos": p, "vel": Vector3.ZERO, "down": false, "gone": false, "tip": Vector3.ZERO})


func _ready_ball() -> void:
	var o := ORIGIN
	ball.position = o + Vector3(0, BALL_R, 0.6)
	ball.visible = true
	ball_v = Vector3.ZERO
	bphase = "aim"
	bphase_t = 0.0
	aim = 0.0
	power = 0.0
	first_hit = true
	pocket = false
	arrow.visible = true
	var bowler: Node3D = player if turn == "you" else rivals[0]
	var waiter: Node3D = rivals[0] if turn == "you" else player
	bowler.position = o + Vector3(-0.75, 0, 1.0)   # beside the ball, so you can see it
	waiter.position = o + Vector3(3.4, 0, -1.5)
	bowler.facing = Vector3(0, 0, -1)
	if turn == "cpu":
		cpu_aim = clampf(randfn(0.0, 0.32), -1, 1)
		cpu_power = randf_range(0.7, 1.0)


func standing() -> int:
	var n := 0
	for p in pins:
		if not p.down and not p.gone:
			n += 1
	return n


## Rolls the ball: aim -1..1 (0 = straight down the middle), power 0..1.
func bowl(a: float, pw: float) -> void:
	aim = clampf(a, -1, 1)
	power = clampf(pw, 0, 1)
	var ang := aim * 0.10
	var speed := 7.0 + 11.0 * power
	ball_v = Vector3(sin(ang), 0, -cos(ang)) * speed
	bphase = "roll"
	bphase_t = 0.0
	arrow.visible = false
	game.sfx("slam", -4)
	var bowler: Hero = player
	if turn == "you":
		bowler.squash = Vector2(1.15, 0.85)


func _bowling_step(dt: float) -> void:
	bphase_t += dt
	var o := ORIGIN
	var you := turn == "you"
	var tap: bool = game.jump_pressed or game.action_pressed
	# the bowler and the one waiting just stand and watch (and bounce a bit)
	player.play("idle" if bphase != "roll" or not you else "win")
	player.animate(dt)
	for f in rivals:
		f.facing = Vector3(0, 0, -1)
		f.play("walk")
		f.animate(dt)
	match bphase:
		"aim":
			if you:
				aim += aim_dir * dt * 0.9
				if absf(aim) > 1:
					aim = clampf(aim, -1, 1)
					aim_dir = -aim_dir
				if tap:
					bphase = "power"
					bphase_t = 0
					game.sfx("swap", -8)
			else:
				aim = lerpf(aim, cpu_aim, 1 - exp(-dt * 3))
				if bphase_t > 1.0:
					bphase = "power"
					bphase_t = 0
			arrow.position = ball.position + Vector3(sin(aim * 0.10) * 1.2, 0.02, -1.2)
			arrow.rotation = Vector3(-PI / 2, -aim * 0.10, 0)
		"power":
			if you:
				power += power_dir * dt * 1.25
				if power > 1 or power < 0:
					power = clampf(power, 0, 1)
					power_dir = -power_dir
				if tap:
					bowl(aim, power)
			else:
				power = minf(power + dt * 1.2, cpu_power)
				if bphase_t > 1.0:
					bowl(aim, cpu_power)
		"roll":
			_roll_ball(dt)
			var done := ball.position.z < o.z + HEAD_Z - 2.5 or ball_v.length() < 0.3
			if done:
				ball.visible = false
				bphase = "settle"
				bphase_t = 0
		"settle":
			_move_pins(dt)
			if bphase_t > 1.3:
				_count_ball()
		"show":
			_move_pins(dt)
			if bphase_t > 1.2:
				_next_ball()
	if bphase == "roll":
		_move_pins(dt)
		cam_pos = ball.position + Vector3(0, 1.5, 3.2)
		cam_look = ball.position + Vector3(0, 0, -4)
	elif bphase in ["settle", "show"]:
		cam_pos = o + Vector3(0, 2.6, HEAD_Z + 5.0)
		cam_look = o + Vector3(0, 0, HEAD_Z - 0.6)
	else:
		cam_pos = o + Vector3(0.3, 2.1, 4.2)
		cam_look = o + Vector3(0, 0.1, -10)


func _roll_ball(dt: float) -> void:
	var o := ORIGIN
	ball.position += ball_v * dt
	ball.rotation.x -= ball_v.length() * dt / BALL_R
	var x := ball.position.x - o.x
	if absf(x) > LANE_W - BALL_R * 0.4 and ball.position.y > -0.1:   # into the gutter
		ball.position.x = o.x + signf(x) * (LANE_W + 0.18)
		ball.position.y = BALL_R - 0.12
		ball_v.x = 0
		if first_hit:
			first_hit = false
			note = "GUTTER BALL!"
			note_t = 1.4
			game.sfx("bonk", -4)
	if ball.position.y < BALL_R - 0.05:
		return   # in the gutter: misses every pin
	for p in pins:
		if p.down or p.gone:
			continue
		var d := Vector2(p.pos.x - ball.position.x, p.pos.z - ball.position.z)
		if d.length() < BALL_R + PIN_R:
			if first_hit:
				first_hit = false
				# the "pocket": right on the head pin, hard enough = a STRIKE, like real bowling
				var head_pin: bool = absf(p.pos.z - (o.z + HEAD_Z)) < 0.05
				if head_pin and ball_n == 0 and absf(ball.position.x - p.pos.x) < 0.22 and power > 0.8:
					pocket = true
			_knock(p, ball.position, ball_v)
			ball_v = ball_v * 0.93 + Vector3(-d.normalized().x, 0, 0) * 0.6
	if pocket:   # everything goes flying
		for p in pins:
			if not p.down and not p.gone:
				_knock(p, ball.position + Vector3(randf_range(-0.3, 0.3), 0, 0.4), ball_v * randf_range(0.6, 1.0))


func _knock(p: Dictionary, from: Vector3, v: Vector3) -> void:
	p.down = true
	var away := Vector3(p.pos.x - from.x, 0, p.pos.z - from.z)
	if away.length() < 0.01:
		away = Vector3(randf_range(-1, 1), 0, -1)
	p.vel = away.normalized() * maxf(2.5, v.length() * 0.55) + Vector3(v.x, 0, v.z) * 0.35
	p.tip = Vector3(p.vel.z, 0, -p.vel.x).normalized()
	game.sfx("break", -10)


func _move_pins(dt: float) -> void:
	for p in pins:
		if not p.down or p.gone:
			continue
		var v: Vector3 = p.vel
		p.pos += v * dt
		p.vel = v.move_toward(Vector3.ZERO, 6.0 * dt)
		var n: Node3D = p.node
		n.position = p.pos
		var tip: Vector3 = p.tip
		if tip.length() > 0.1 and n.rotation.length() < PI / 2:
			n.rotate(tip, dt * 9.0)
		# a flying pin knocks the ones it bumps into
		if v.length() > 1.2:
			for q in pins:
				if q.down or q.gone:
					continue
				if Vector2(q.pos.x - p.pos.x, q.pos.z - p.pos.z).length() < PIN_R * 2 + 0.06:
					_knock(q, p.pos, v * 0.8)


func _count_ball() -> void:
	var knocked := 0
	for p in pins:
		if p.down and not p.gone:
			knocked += 1
			p.gone = true
			p.node.visible = false
	var fr: Array = scores[turn][frame]
	fr.append(knocked)
	if ball_n == 0 and knocked == 10:
		note = "STRIKE!"
		game.sfx("clear")
		game.fx.burst(ORIGIN + Vector3(0, 1, HEAD_Z), Color("ffd23a"), 24, 6, 8)
	elif ball_n == 1 and fr[0] + knocked == 10:
		note = "SPARE!"
		game.sfx("item")
	else:
		note = "%d PIN%s" % [knocked, "" if knocked == 1 else "S"]
	note_t = 1.3
	bphase = "show"
	bphase_t = 0


func _next_ball() -> void:
	var fr: Array = scores[turn][frame]
	if ball_n == 0 and fr[0] < 10:
		ball_n = 1
		_ready_ball()
		return
	# this bowler's frame is done
	if turn == "you":
		turn = "cpu"
		note = "GRUMBLER'S TURN"
		note_t = 1.2
		_new_frame()
		return
	turn = "you"
	frame += 1
	if frame >= FRAMES:
		var a := total("you")
		var b := total("cpu")
		if a > b:
			_finish(1, "YOU WIN!", "%d to %d" % [a, b])
		elif a == b:
			_finish(2, "A TIE!", "%d to %d" % [a, b])
		else:
			_finish(2, "THE GRUMBLER WINS!", "%d to %d - so close!" % [a, b])
		return
	note = "FRAME %d - YOUR TURN!" % (frame + 1)
	note_t = 1.4
	_new_frame()


## A frame's score: the pins, +10 for a STRIKE, +5 for a SPARE (simple kid rules).
static func frame_score(fr: Array) -> int:
	var s := 0
	for k in fr:
		s += int(k)
	if fr.size() >= 1 and int(fr[0]) == 10:
		s += 10
	elif fr.size() >= 2 and int(fr[0]) + int(fr[1]) == 10:
		s += 5
	return s


func total(who: String) -> int:
	var s := 0
	for fr in scores[who]:
		s += frame_score(fr)
	return s


# ================================================================== COIN SCRAMBLE
func _coins_build() -> void:
	var o := ORIGIN + Vector3(60, 0, 0)
	var fl := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = ARENA_R + 0.6
	cm.bottom_radius = ARENA_R + 1.2
	cm.height = 2.0
	cm.radial_segments = 32
	fl.mesh = cm
	fl.material_override = _mat_tex("grass_top", 0.5)
	fl.position = o + Vector3(0, -1.0, 0)
	arena.add_child(fl)
	for i in 16:   # a ring of colourful posts
		var a := TAU * i / 16.0
		_box(Vector3(0.5, 1.0, 0.5), o + Vector3(cos(a) * (ARENA_R + 0.3), 0.5, sin(a) * (ARENA_R + 0.3)),
			Toys.mat(Color.from_hsv(i / 16.0, 0.6, 1.0)))
	_label("COIN SCRAMBLE", o + Vector3(0, 4.5, -ARENA_R - 1), Color("ffd23a"), 96)
	counts = {"you": 0}
	stun = {}
	for i in 3:
		var a := TAU * (i + 1) / 4.0 + PI / 2
		_rival("grumbler", o + Vector3(cos(a) * 5, 0, sin(a) * 5))
		counts[i] = 0
		stun[i] = 0.0
	for i in 8:
		_spawn_coin()
	player.position = o + Vector3(0, 0, 5)
	player.facing = Vector3(0, 0, -1)
	pvel = Vector3.ZERO
	p_floor = 0.0
	time_left = SCRAMBLE_TIME
	cam_pos = o + Vector3(0, 11, 10)
	cam_look = o + Vector3(0, 0, 0.8)
	phase = "count"
	note = "READY..."
	note_t = 2.0


func _spawn_coin() -> void:
	var o := ORIGIN + Vector3(60, 0, 0)
	var a := randf() * TAU
	var r := sqrt(randf()) * (ARENA_R - 1.0)
	var p := o + Vector3(cos(a) * r, 0.4, sin(a) * r)
	var c := level.prop("coin", p - Vector3(0, 0.4, 0), 0.9)
	coins_nodes.append({"node": c, "pos": p})


func _coins_step(dt: float) -> void:
	var o := ORIGIN + Vector3(60, 0, 0)
	for i in coins_nodes.size():
		var c: Node3D = coins_nodes[i].node
		if c is Sprite3D:
			c.scale.x = cos(t * 3.5 + i)
		else:
			c.rotation.y = t * 3 + i
	if phase == "count":
		player.play("idle")
		player.animate(dt)
		for f in rivals:
			f.play("walk")
			f.animate(dt)
		if t > 2.0:
			phase = "play"
			note = "GO!"
			note_t = 0.8
			game.sfx("item")
		return
	time_left -= dt
	_move_player(dt, 6.0, 40.0)
	_keep_in_ring(player, o)
	_grab_coins("you", player.position)
	for i in rivals.size():
		var f: Foe = rivals[i]
		stun[i] = maxf(0, stun[i] - dt)
		if stun[i] > 0:
			f.play("flat" if f.frames.has("flat") or f.model else "walk")
			f.squash = Vector2(1.5, 0.3)
			f.animate(dt)
			continue
		f.squash = Vector2(1.0 + sin(t * 10 + i) * 0.05, 1.0 - sin(t * 10 + i) * 0.05)
		# head for the nearest coin (each one a little slower / dizzier)
		var best: Vector3 = f.position
		var bd := 999.0
		for c in coins_nodes:
			var d: float = Vector2(c.pos.x - f.position.x, c.pos.z - f.position.z).length() + i * 0.7 * randf()
			if d < bd:
				bd = d
				best = c.pos
		var to := Vector3(best.x - f.position.x, 0, best.z - f.position.z)
		var sp := [3.5, 3.1, 2.8][i] as float
		if to.length() > 0.1:
			f.facing = to.normalized()
			f.position += to.normalized() * minf(sp * dt, to.length())
		f.play("walk")
		f.animate(dt)
		_keep_in_ring(f, o)
		_grab_coins(i, f.position)
		# stomp! landing on a Grumbler steals a coin from it
		var d := Vector2(f.position.x - player.position.x, f.position.z - player.position.z).length()
		if d < 0.9 and pvel.y < -1 and player.position.y > 0.3 and player.position.y < 1.4:
			stun[i] = 1.6
			pvel.y = 9
			game.sfx("stomp")
			if counts[i] > 0:
				counts[i] -= 1
				counts["you"] += 1
				game.fx.text(f.position + Vector3(0, 1.4, 0), "STOLE 1!", Color("ffd23a"), 48)
	if time_left <= 0:
		_coins_end()


func _keep_in_ring(a: Node3D, o: Vector3) -> void:
	var d := Vector2(a.position.x - o.x, a.position.z - o.z)
	if d.length() > ARENA_R - 0.4:
		d = d.normalized() * (ARENA_R - 0.4)
		a.position.x = o.x + d.x
		a.position.z = o.z + d.y


func _grab_coins(who, at: Vector3) -> void:
	for i in range(coins_nodes.size() - 1, -1, -1):
		var c: Dictionary = coins_nodes[i]
		if Vector2(c.pos.x - at.x, c.pos.z - at.z).length() < 0.8 and absf(c.pos.y - at.y) < 1.4:
			counts[who] += 1
			c.node.queue_free()
			coins_nodes.remove_at(i)
			if who is String:
				game.sfx("coin", -3)
				game.fx.burst(c.pos, Color("ffd23a"), 5, 2.5, 6)
			_spawn_coin()


func _coins_end() -> void:
	var mine: int = counts["you"]
	var place := 1
	var best_rival := 0
	for i in 3:
		best_rival = maxi(best_rival, counts[i])
		if counts[i] > mine:
			place += 1
	if place == 1 and best_rival == mine:
		place = 2   # a tie isn't a win
		_finish(place, "A TIE!", "You %d - Grumbler %d" % [mine, best_rival])
	elif place == 1:
		_finish(1, "YOU WIN!", "You got %d coins!" % mine)
	else:
		_finish(place, "%s PLACE" % ["", "1ST", "2ND", "3RD", "4TH"][place], "You %d - best Grumbler %d" % [mine, best_rival])


# ================================================================== PENGUIN RACE
func _race_build() -> void:
	var o := ORIGIN + Vector3(120, 0, 0)
	_box(Vector3(9, 1, TRACK_LEN + 16), o + Vector3(0, -0.5, -TRACK_LEN * 0.5 + 2), _mat_tex("ice", 0.6))
	for s in [-1, 1]:
		_box(Vector3(0.6, 1.0, TRACK_LEN + 16), o + Vector3(s * 4.8, 0.5, -TRACK_LEN * 0.5 + 2), _mat_tex("snow_top", 0.6))
	_box(Vector3(9, 0.04, 0.6), o + Vector3(0, 0.02, -TRACK_LEN), Toys.mat(Color("f2f2f2"), false))
	for k in 9:   # checkered finish line
		_box(Vector3(1.0, 0.05, 0.6), o + Vector3(-4 + k, 0.03, -TRACK_LEN), Toys.mat(Color("1e1e28") if k % 2 == 0 else Color.WHITE, false))
	_label("FINISH", o + Vector3(0, 3.2, -TRACK_LEN), Color("ffd23a"), 96)
	for s in [-1, 1]:
		_box(Vector3(0.3, 3.0, 0.3), o + Vector3(s * 4.6, 1.5, -TRACK_LEN), Toys.mat(Color("e8413b")))
	hurdles = []
	var z := -HURDLE_EVERY
	while z > -TRACK_LEN + 4:
		hurdles.append(z)
		_box(Vector3(8.6, 0.12, 0.18), o + Vector3(0, 0.45, z), Toys.mat(Color("ff8a1f")))
		for s in [-1, 0, 1]:
			_box(Vector3(0.12, 0.45, 0.12), o + Vector3(s * 4.0, 0.22, z), Toys.mat(Color("f2f2f2")))
		z -= HURDLE_EVERY
	for i in 3:
		_rival("penguin", o + Vector3(-1.0 + i * 1.8, 0, 0))
	r_speed = [6.4, 6.9, 7.3]
	r_speed.shuffle()
	r_vel = [0.0, 0.0, 0.0]
	r_y = [0.0, 0.0, 0.0]
	r_vy = [0.0, 0.0, 0.0]
	trip_t = {"you": 0.0, 0: 0.0, 1: 0.0, 2: 0.0}
	finish_order = []
	player.position = o + Vector3(-3.0, 0, 0)
	player.facing = Vector3(0, 0, -1)
	pvel = Vector3.ZERO
	p_floor = 0.0
	race_t = 0.0
	cam_pos = o + Vector3(0, 4.5, 7)
	cam_look = o + Vector3(0, 0, -6)
	phase = "count"
	note = "3"
	note_t = 1.0


func _race_step(dt: float) -> void:
	var o := ORIGIN + Vector3(120, 0, 0)
	if phase == "count":
		var left := 3 - int(t)
		if left != int(note) and left > 0:
			note = str(left)
			note_t = 1.0
			game.sfx("swap", -6)
		player.play("idle")
		player.animate(dt)
		for f in rivals:
			f.play("walk")
			f.animate(dt)
		if t >= 3.0:
			phase = "play"
			note = "GO!"
			note_t = 0.8
			game.sfx("item")
		cam_pos = player.position + Vector3(1.5, 4.0, 7.5)
		cam_look = player.position + Vector3(1.5, 0, -6)
		return
	race_t += dt
	# you: slippery ice, push UP to go; trip over a hurdle if you don't jump
	for k in trip_t:
		trip_t[k] = maxf(0, trip_t[k] - dt)
	var top := 7.6 if trip_t["you"] <= 0 else 2.0
	var before_z := player.position.z
	_move_player(dt, top, 9.0)
	player.position.x = clampf(player.position.x, o.x - 4.1, o.x + 4.1)
	player.position.z = minf(player.position.z, o.z + 1.0)
	if _crossed_hurdle(before_z - o.z, player.position.z - o.z) and player.position.y < 0.5 and trip_t["you"] <= 0:
		trip_t["you"] = 1.0
		pvel.z *= 0.2
		player.squash = Vector2(1.3, 0.7)
		game.sfx("bonk")
		game.fx.text(player.position + Vector3(0, 2, 0), "OOPS!", Color("ffb0a8"), 48)
	if player.position.z <= o.z - TRACK_LEN and not "you" in finish_order:
		finish_order.append("you")
		game.sfx("clear")
	# the penguins: belly-slide, jump most hurdles
	for i in rivals.size():
		var f: Foe = rivals[i]
		if i in finish_order:
			f.play("walk")
			f.animate(dt)
			continue
		var top_r: float = r_speed[i] if trip_t[i] <= 0 else 2.0
		r_vel[i] = move_toward(r_vel[i], top_r, 6.0 * dt)
		var bz: float = f.position.z - o.z
		var nz: float = bz - r_vel[i] * dt
		for hz in hurdles:   # get ready to jump just before each hurdle (85% of the time)
			if bz > hz + 1.2 and nz <= hz + 1.2 and r_y[i] <= 0.0 and randf() < 0.85:
				r_vy[i] = 9.0
		r_vy[i] -= Actor.GRAVITY * dt
		r_y[i] = maxf(0.0, r_y[i] + r_vy[i] * dt)
		if r_y[i] <= 0.0:
			r_vy[i] = 0.0
		f.position = Vector3(f.position.x, r_y[i], o.z + nz)
		if _crossed_hurdle(bz, nz) and r_y[i] < 0.5 and trip_t[i] <= 0:
			trip_t[i] = 1.0
			r_vel[i] *= 0.2
			game.fx.text(f.position + Vector3(0, 1.6, 0), "OOPS!", Color("cfe8ff"), 40)
		f.facing = Vector3(0, 0, -1)
		f.play("slide" if r_vel[i] > 4 else "walk")
		f.animate(dt)
		if f.position.z <= o.z - TRACK_LEN:
			finish_order.append(i)
	cam_pos = player.position + Vector3(1.5, 4.0, 7.5)
	cam_look = player.position + Vector3(1.5, 0, -6)
	if "you" in finish_order or race_t > 40.0:
		var place: int = finish_order.find("you") + 1
		if place == 0:
			place = 4
		if place == 1:
			_finish(1, "YOU WIN!", "First over the line in %.1f s!" % race_t)
		else:
			_finish(place, "%s PLACE" % ["", "1ST", "2ND", "3RD", "4TH"][place], "The penguins are fast!")


func _crossed_hurdle(z0: float, z1: float) -> bool:
	for hz in hurdles:
		if z0 > hz and z1 <= hz:
			return true
	return false


# ================================================================== HUD
func draw(h) -> void:
	var s: Vector2 = h.size
	h.draw_rect(Rect2(0, 0, s.x, 60), Color(0.05, 0.05, 0.12, 0.55))
	h.txt(title(), Vector2(20, 44), 36, Color("ffd23a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 8)
	match id:
		"bowling":
			_draw_bowling(h)
		"coins":
			var line := "YOU %d" % counts["you"]
			for i in 3:
				line += "     GRUMBLER %d: %d" % [i + 1, counts[i]]
			h.txt(line, Vector2(0, 96), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
			h.txt("%d" % ceili(maxf(0, time_left)), Vector2(s.x - 160, 46), 40, Color("ffd23a") if time_left > 5 else Color("ff6b5a"), HORIZONTAL_ALIGNMENT_CENTER, 100, 8)
		"race":
			var o := ORIGIN + Vector3(120, 0, 0)
			var prog := clampf(-(player.position.z - o.z) / TRACK_LEN, 0, 1)
			var bw := minf(500.0, s.x * 0.4)
			var bx := (s.x - bw) * 0.5
			h.draw_rect(Rect2(bx, 24, bw, 16), Color(0.1, 0.1, 0.2))
			for f in rivals:
				var rp := clampf(-(f.position.z - o.z) / TRACK_LEN, 0, 1)
				h.draw_circle(Vector2(bx + bw * rp, 32), 9, Color("cfe8ff"))
			h.draw_circle(Vector2(bx + bw * prog, 32), 11, Color("e8413b"))
			h.txt("%.1f s" % race_t, Vector2(s.x - 170, 46), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, 110, 6)
	if note_t > 0 and phase != "result":
		var a := clampf(note_t * 3, 0, 1)
		h.txt(note, Vector2(0, s.y * 0.34), 64, Color(1, 0.86, 0.3, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	if phase == "result":
		h.draw_rect(Rect2(0, s.y * 0.26, s.x, 190), Color(0.05, 0.05, 0.15, 0.7))
		h.txt(result_text, Vector2(0, s.y * 0.26 + 82), 76, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		h.txt(result_sub, Vector2(0, s.y * 0.26 + 136), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		if t > 1.2:
			h.txt("Tap OK to go home", Vector2(0, s.y * 0.26 + 176), 24, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	elif id != "bowling" and phase == "play" and t < 4:
		h.txt("Stick to move, JUMP to jump" + (" - stomp to steal!" if id == "coins" else " the hurdles!"), Vector2(0, 100), 24, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)


func _draw_bowling(h) -> void:
	var s: Vector2 = h.size
	# the scoreboard
	var x0 := s.x * 0.5 - 250
	for row in 2:
		var who: String = ["you", "cpu"][row]
		var y := 72.0 + row * 40
		h.draw_rect(Rect2(x0, y, 520, 36), Color(0.95, 0.93, 0.85, 0.92) if turn != who or phase == "result" else Color(1, 0.93, 0.55, 0.95))
		h.txt("YOU" if row == 0 else "GRUMBLER", Vector2(x0 + 8, y + 27), 22, Color(0.15, 0.1, 0.2), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
		for k in FRAMES:
			var cx := x0 + 150 + k * 100
			h.draw_rect(Rect2(cx, y + 3, 92, 30), Color(1, 1, 1))
			if k < scores[who].size():
				var fr: Array = scores[who][k]
				var marks := ""
				if fr.size() >= 1:
					marks = "X" if int(fr[0]) == 10 else str(fr[0])
				if fr.size() >= 2:
					marks += "  " + ("/" if int(fr[0]) + int(fr[1]) == 10 else str(fr[1]))
				h.txt(marks, Vector2(cx, y + 27), 22, Color(0.15, 0.1, 0.2), HORIZONTAL_ALIGNMENT_CENTER, 92, 0)
		h.txt(str(total(who)), Vector2(x0 + 452, y + 27), 24, Color(0.6, 0.1, 0.1), HORIZONTAL_ALIGNMENT_CENTER, 64, 0)
	if phase == "result":
		return
	# power bar
	if bphase in ["aim", "power"]:
		var bx := s.x - 120.0
		var bh := 260.0
		var by := s.y * 0.5 - bh * 0.5 - 60
		h.draw_rect(Rect2(bx, by, 44, bh), Color(0.1, 0.1, 0.2, 0.8))
		var p := power if bphase == "power" else 0.0
		h.draw_rect(Rect2(bx + 4, by + bh - 4 - (bh - 8) * p, 36, (bh - 8) * p), Color.from_hsv(0.33 * p, 0.85, 1.0))
		h.draw_rect(Rect2(bx, by, 44, bh), Color.WHITE, false, 3.0)
		h.txt("POWER", Vector2(bx - 20, by - 10), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, 84, 6)
		if turn == "you":
			var tip := "Tap AIM! when the arrow points at the middle pin" if bphase == "aim" else "Tap ROLL! when the bar is full!"
			h.txt(tip, Vector2(0, s.y - 150), 28, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		else:
			h.txt("The Grumbler is aiming...", Vector2(0, s.y - 150), 28, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	h.txt("FRAME %d / %d   BALL %d" % [mini(frame + 1, FRAMES), FRAMES, ball_n + 1], Vector2(20, 100), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
