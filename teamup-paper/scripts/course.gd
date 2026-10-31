class_name CoursePlay
extends Node3D
## The moving parts of a Mario 64-style course (the shapes are built by level.gd
## `_course_heights` / `_course_build`):
## * the CANNON: walk into it, aim with the stick (the dotted line shows where you'll land),
##   JUMP or ACTION fires you in an arc, by default onto the floating island;
## * the SLIDE (Frosty Peaks): the ice lane of the mountain path whooshes you downhill;
## * QUICKSAND / mud: slows you down.

const FLIGHT_T := 1.7         # seconds in the air when fired
const AIM_SPEED := 9.0        # how fast the stick moves the landing spot
const AIM_MAX := 14.0         # how far from the island you can aim
const SLIDE_SPEED := 13.0

var game
var level: Level
var cannon: Node3D
var barrel: Node3D
var dots: Array[MeshInstance3D] = []
var inside := false           # the hero is in the cannon, aiming
var aim := Vector3.ZERO       # where the shot lands
var fly_t := -1.0             # >= 0 while flying out of the cannon
var fly_from := Vector3.ZERO
var fly_v := Vector3.ZERO
var cool := 0.0               # can't get straight back in after landing
var sliding := false


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	if level.cannon_spot == Vector3.ZERO:
		return
	cannon = Node3D.new()
	cannon.position = level.cannon_spot
	add_child(cannon)
	var base := MeshInstance3D.new()
	var bm := CylinderMesh.new()
	bm.top_radius = 0.75
	bm.bottom_radius = 0.85
	bm.height = 0.35
	base.mesh = bm
	base.material_override = Toys.mat(Color("5a5e6a"))
	base.position.y = 0.17
	cannon.add_child(base)
	barrel = Node3D.new()
	barrel.position.y = 0.45
	cannon.add_child(barrel)
	var tube := MeshInstance3D.new()
	var tm := CylinderMesh.new()
	tm.top_radius = 0.42
	tm.bottom_radius = 0.5
	tm.height = 1.5
	tube.mesh = tm
	tube.material_override = Toys.mat(Color("2a2c34"))
	tube.position.y = 0.6
	barrel.add_child(tube)
	var lip := MeshInstance3D.new()
	var lm := TorusMesh.new()
	lm.inner_radius = 0.36
	lm.outer_radius = 0.52
	lip.mesh = lm
	lip.material_override = Toys.mat(Color("ffd23a"), false, 0.3)
	lip.position.y = 1.35
	barrel.add_child(lip)
	var dm := SphereMesh.new()
	dm.radius = 0.13
	dm.height = 0.26
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.albedo_color = Color("ffe14a")
	mat.no_depth_test = true
	for i in 16:
		var d := MeshInstance3D.new()
		d.mesh = dm
		d.material_override = mat
		d.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		d.visible = false
		add_child(d)
		dots.append(d)
	aim = level.island_spot
	_point_barrel()


func gravity() -> float:
	return Actor.GRAVITY * float(game.grav)


## The starting speed that lands on `to` after FLIGHT_T seconds.
func launch_velocity(from: Vector3, to: Vector3) -> Vector3:
	var v := (to - from) / FLIGHT_T
	v.y += 0.5 * gravity() * FLIGHT_T
	return v


func _muzzle() -> Vector3:
	return level.cannon_spot + Vector3(0, 1.6, 0)


func _point_barrel() -> void:
	if barrel == null:
		return
	var v := launch_velocity(_muzzle(), aim + Vector3(0, 0.3, 0))
	var flat := Vector2(v.x, v.z)
	barrel.rotation = Vector3.ZERO
	barrel.rotate_y(atan2(v.x, v.z))
	barrel.rotate_object_local(Vector3.RIGHT, atan2(flat.length(), v.y))


func enter() -> void:
	var l: Hero = game.leader()
	inside = true
	aim = level.island_spot
	l.visible = false
	l.vel = Vector3.ZERO
	game.sfx("item")
	game.hud.say("CANNON!", "Push the stick to aim, JUMP to fire!", 2.4)
	_point_barrel()


func fire() -> void:
	var l: Hero = game.leader()
	inside = false
	for d in dots:
		d.visible = false
	fly_from = _muzzle()
	fly_v = launch_velocity(fly_from, aim + Vector3(0, 0.3, 0))
	fly_t = 0.0
	l.visible = true
	l.position = fly_from
	if "trick" in l:
		l.trick = ""
	l.slam = ""
	l.long_t = 0.0
	l.spin()
	game.sfx("boss_hit")
	game.fx.burst(fly_from, Color(0.9, 0.9, 0.9), 16, 5, 6)
	game.fx.shake = 0.4


func step(dt: float) -> void:
	var l: Hero = game.leader()
	cool = maxf(0, cool - dt)
	if cannon:
		_step_cannon(l, dt)
	_step_ground(dt)


func _step_cannon(l: Hero, dt: float) -> void:
	if fly_t >= 0:   # flying in an arc (the flight is worked out, so it always lands on target)
		fly_t += dt
		var t := minf(fly_t, FLIGHT_T)
		var p := fly_from + fly_v * t + Vector3(0, -0.5 * gravity() * t * t, 0)
		var v := fly_v + Vector3(0, -gravity() * t, 0)
		if t > 0.25 and level.box_hit(p, l.hw, l.height) != null:   # bumped into something: drop
			fly_t = -1.0
			l.vel = v * 0.2
			cool = 1.0
			return
		l.position = p
		l.vel = v
		l.on_floor = false
		if fly_t >= FLIGHT_T:
			fly_t = -1.0
			cool = 1.0
		return
	if not inside:
		var d := Vector2(l.position.x - level.cannon_spot.x, l.position.z - level.cannon_spot.z).length()
		if d < 0.9 and l.on_floor and cool <= 0 and absf(l.position.y - level.cannon_spot.y) < 1.2 and not game.flying:
			enter()
		return
	# aiming: the stick moves the landing spot (in the camera's directions)
	l.position = level.cannon_spot + Vector3(0, 0.3, 0)
	l.vel = Vector3.ZERO
	l.visible = false
	var mv: Vector2 = game.move_vec
	if mv.length() > 0.1:
		var dir := Vector3(mv.x, 0, mv.y).rotated(Vector3.UP, game.cam_yaw)
		aim += dir * AIM_SPEED * dt
		var off := aim - level.island_spot
		off.y = 0
		if off.length() > AIM_MAX:
			off = off.normalized() * AIM_MAX
		aim = level.island_spot + off
		aim.y = maxf(level.ground_below(aim.x, aim.z, 40.0), 1.0)
		_point_barrel()
	# the dotted line to where you'll land
	var from := _muzzle()
	var v0 := launch_velocity(from, aim + Vector3(0, 0.3, 0))
	for i in dots.size():
		var t := FLIGHT_T * float(i + 1) / dots.size()
		dots[i].position = from + v0 * t + Vector3(0, -0.5 * gravity() * t * t, 0)
		dots[i].visible = true
	if game.jump_pressed or game.action_pressed:
		fire()


## The ground under the team: the ice slide pushes you downhill, mud slows you down.
func _step_ground(dt: float) -> void:
	sliding = false
	for h in [game.leader(), game.follower()]:
		if not h.visible:
			continue
		var cx := floori(h.position.x)
		var cz := floori(h.position.z)
		var cell := Vector2i(cx, cz)
		if level.slide and level.ramp_s.has(cell):
			var gy := level.ground_at(cx, cz)
			if level.get_cell(cx, gy - 1, cz) == Level.ICE and h.position.y - gy < 1.6 and h.vel.y < 4.0:
				var down: Vector3 = level.ramp_down(level.ramp_s[cell])
				var going_down: bool = Vector2(h.vel.x, h.vel.z).dot(Vector2(down.x, down.z)) > 1.0
				var no_stick: bool = h != game.leader() or game.move_vec.length() < 0.2
				if going_down or no_stick:   # WHOOSH down the slide (walk up on the snowy outside lane)
					# keep to the middle of the lane round the bends (or you'd fly into the wall)
					var sv: float = level.ramp_s[cell]
					var mid: Vector3 = level.ramp_point(sv)
					var to_mid := Vector3(mid.x - h.position.x, 0, mid.z - h.position.z)
					var want := down * SLIDE_SPEED + to_mid * 6.0
					var k := 1.0 - exp(-dt * 12.0)
					h.vel.x = lerpf(h.vel.x, want.x, k)
					h.vel.z = lerpf(h.vel.z, want.z, k)
					if h == game.leader():
						sliding = true
				continue
		if not h.on_floor:
			continue
		var below: int = level.get_cell(cx, floori(h.position.y - 0.1), cz)
		if below == Level.MUD:   # quicksand / mud: about half speed
			var flat := Vector2(h.vel.x, h.vel.z)
			if flat.length() > h.speed * 0.5:
				flat = flat.normalized() * h.speed * 0.5
				h.vel.x = flat.x
				h.vel.z = flat.y
