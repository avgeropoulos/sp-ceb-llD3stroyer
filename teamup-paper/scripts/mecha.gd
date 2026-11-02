class_name MechaKrag
extends Node3D
## MECHA KRAG ATTACKS! A giant robot King Krag (about 20 blocks tall) drops out of the sky
## at the edge of the home world, stomps closer and fires missiles (a red X shows where
## each one lands first). Beat him by hitting his 4 glowing WEAK SPOTS:
##  * the 2 on his FEET: jump into them (or ground pound them),
##  * the 2 on his BACK: some missiles are DUDS (they land without blowing up and blink):
##    GRAB one with ACTION and THROW it back at him (it flies to a back weak spot by itself).
##    Partner shots and fireballs hit weak spots too.
## Then he wobbles, blows up and falls over, and it rains coins. Kid-friendly: he never
## touches the blocks of your home (all the booms are just effects), and you can say
## NOT NOW when he shows up. main.gd: start_mecha / decline_mecha, `mecha.step()` at home.

const SPEED := 2.2           # walking speed (blocks per second)
const MISSILE_EVERY := 2.4
const DUD_CHANCE := 0.4
const COIN_RAIN := 60

var game
var level: Level
var active := false
var phase := "land"          # land -> walk -> fight -> boom -> coins -> done
var t := 0.0
var phase_t := 0.0
var pos := Vector3.ZERO      # where his feet are
var stop := Vector3.ZERO     # where he stops walking
var face := 0.0              # which way he looks (yaw)
var model: Node3D
var legs: Array = []
var arms: Array = []
var flames: Array = []
var spots: Array = []        # {node, local, alive, back}
var missiles: Array = []     # {node, from, to, t, dur, dud, marker}
var duds: Array = []         # {node, t}
var carrying: Dictionary = {}
var thrown: Array = []       # {node, from, spot, t}
var rain: Array = []         # {node, vel, landed, t}
var hits := 0
var missile_t := 1.5
var stomp_t := 5.0
var step_phase := 0.0
var home_spot := Vector3.ZERO   # where you get put back if you run out of hearts
var coins_got := 0
var note := ""
var note_t := 0.0


func setup(g, lv: Level, player_pos: Vector3) -> void:
	game = g
	level = lv
	home_spot = player_pos
	# he lands up to the north of you, and walks a bit closer
	var dir := Vector3(0, 0, -1)
	var land := player_pos + dir * 36.0
	land.x = clampf(land.x, 16, level.sx - 16)
	land.z = clampf(land.z, 16, level.sz - 16)
	stop = player_pos + dir * 19.0
	stop.x = clampf(stop.x, 14, level.sx - 14)
	stop.z = clampf(stop.z, 14, level.sz - 14)
	pos = Vector3(land.x, _ground(land.x, land.z), land.z)
	face = atan2(player_pos.x - pos.x, player_pos.z - pos.z)
	_build()
	model.position = pos + Vector3(0, 46, 0)
	model.rotation.y = face + PI   # his face is on the model's -Z side
	phase = "land"
	phase_t = 0.0
	active = true


func _ground(x: float, z: float) -> float:
	var g := level.ground_below(x, z, level.sy - 1)
	return g if g > -50 else float(Level.HOME_GY)


# ------------------------------------------------------------------ the giant robot
func _glow(c: Color, energy := 2.0) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.emission_enabled = true
	m.emission = c
	m.emission_energy_multiplier = energy
	return m


func _part(parent: Node3D, mesh: Mesh, p: Vector3, c: Color, rot := Vector3.ZERO, glow := 0.0) -> MeshInstance3D:
	var mi := Toys.part(parent, mesh, p, c, Vector3.ONE, rot, glow <= 0.0, glow)
	return mi


func _build() -> void:
	model = Node3D.new()
	add_child(model)
	var METAL := Color("8a8e96")
	var DARK := Color("4a4e58")
	var GREEN := Color("3f9a48")
	var GOLD := Color("ffcf33")
	# legs (pivots at the hips so they can swing)
	for sx in [-1, 1]:
		var hip := Toys.piv(model, Vector3(sx * 2.2, 8.0, 0))
		_part(hip, Toys.box(Vector3(1.8, 4.0, 1.8)), Vector3(0, -2.0, 0), METAL)
		_part(hip, Toys.sph(1.0), Vector3(0, -4.2, 0), DARK)   # knee
		_part(hip, Toys.box(Vector3(1.6, 3.6, 1.6)), Vector3(0, -6.0, 0), METAL)
		_part(hip, Toys.box(Vector3(2.6, 1.0, 3.6)), Vector3(0, -7.6, -0.6), DARK)   # foot
		for k in 3:   # claws
			_part(hip, Toys.cyl(0.0, 0.3, 0.8, 6), Vector3(-0.8 + k * 0.8, -7.6, -2.6), Color("fff2dc"), Vector3(-PI / 2, 0, 0))
		legs.append(hip)
	# body: a big metal belly and a spiky green shell
	_part(model, Toys.box(Vector3(6.4, 6.0, 4.6)), Vector3(0, 11.0, 0), METAL)
	_part(model, Toys.box(Vector3(4.4, 4.4, 0.4)), Vector3(0, 10.8, -2.4), Color("e8c890"))   # belly plate
	for k in 3:
		_part(model, Toys.box(Vector3(4.0, 0.25, 0.45)), Vector3(0, 9.4 + k * 1.3, -2.55), Color("c4a060"))
	_part(model, Toys.hemi(3.6), Vector3(0, 11.4, 2.2), GREEN, Vector3(PI / 2, 0, 0))   # shell on the back
	for p in [Vector3(-1.6, 13.0, 4.6), Vector3(1.6, 13.0, 4.6), Vector3(0, 11.0, 5.4), Vector3(-2.4, 10.0, 4.4), Vector3(2.4, 10.0, 4.4)]:
		_part(model, Toys.cyl(0.0, 0.45, 1.3, 8), p, Color("fff2dc"), Vector3(PI / 2, 0, 0))
	# jet packs on the back (they fire when he lands)
	for sx in [-1, 1]:
		_part(model, Toys.cyl(0.7, 0.7, 2.6, 12), Vector3(sx * 3.4, 12.0, 3.4), DARK)
		var fl := _part(model, Toys.cyl(0.0, 0.65, 2.6, 10), Vector3(sx * 3.4, 9.4, 3.4), Color("ff8a1f"), Vector3(PI, 0, 0), 2.5)
		flames.append(fl)
	# arms with missile pods
	for sx in [-1, 1]:
		var sh := Toys.piv(model, Vector3(sx * 3.8, 13.0, 0))
		_part(sh, Toys.sph(1.2), Vector3.ZERO, DARK)
		_part(sh, Toys.box(Vector3(1.4, 4.0, 1.4)), Vector3(sx * 0.4, -2.2, 0), METAL)
		_part(sh, Toys.box(Vector3(2.0, 1.6, 2.4)), Vector3(sx * 0.4, -4.6, -0.3), DARK)   # fist
		_part(sh, Toys.box(Vector3(2.2, 1.6, 2.6)), Vector3(sx * 0.2, 1.6, 0), Color("e8413b"))   # missile pod
		for k in 3:
			_part(sh, Toys.cyl(0.25, 0.25, 0.4, 8), Vector3(sx * 0.2 - 0.6 + k * 0.6, 2.5, -0.5), Color("fff2dc"))
		arms.append(sh)
	# the head: a metal King Krag with glowing red eyes
	var head := Toys.piv(model, Vector3(0, 15.6, -0.6))
	_part(head, Toys.box(Vector3(3.6, 2.8, 3.2)), Vector3.ZERO, METAL)
	_part(head, Toys.box(Vector3(3.0, 1.2, 1.4)), Vector3(0, -0.9, -1.9), METAL)   # jaw
	for k in 4:
		_part(head, Toys.cyl(0.0, 0.22, 0.5, 6), Vector3(-1.0 + k * 0.66, -0.4, -2.55), Color("fff2dc"), Vector3(PI, 0, 0))
	for sx in [-1, 1]:
		_part(head, Toys.sph(0.42), Vector3(sx * 0.85, 0.5, -1.62), Color("ff2a2a"), Vector3.ZERO, 4.0)
		_part(head, Toys.box(Vector3(1.0, 0.25, 0.3)), Vector3(sx * 0.85, 1.05, -1.6), Color("2a2a32"), Vector3(0, 0, -sx * 0.35))
		_part(head, Toys.cyl(0.0, 0.42, 1.6, 8), Vector3(sx * 1.4, 2.0, 0.2), Color("fff2dc"), Vector3(0, 0, -sx * 0.5))   # horns
	_part(head, Toys.box(Vector3(2.2, 0.5, 2.4)), Vector3(0, 1.55, 0.2), Color("e8413b"))   # red hair plate
	_part(head, Toys.cyl(1.0, 1.4, 0.9, 6), Vector3(0, 1.9, 0.3), GOLD)   # crown
	# the 4 weak spots
	var spot_at := [[Vector3(-2.2, 1.4, -2.7), false], [Vector3(2.2, 1.4, -2.7), false], [Vector3(-1.6, 12.0, 5.9), true], [Vector3(1.6, 12.0, 5.9), true]]
	for sp in spot_at:
		var n := Node3D.new()
		n.position = sp[0]
		model.add_child(n)
		var orb := MeshInstance3D.new()
		var sm := SphereMesh.new()
		sm.radius = 0.75
		sm.height = 1.5
		orb.mesh = sm
		orb.material_override = _glow(Color("ff4fd8"), 3.0)
		n.add_child(orb)
		var ring := MeshInstance3D.new()
		var tm := TorusMesh.new()
		tm.inner_radius = 0.8
		tm.outer_radius = 1.0
		ring.mesh = tm
		ring.material_override = _glow(Color("ffe14a"), 2.0)
		ring.rotation.x = PI / 2
		n.add_child(ring)
		spots.append({"node": n, "alive": true, "back": sp[1]})
	for mi in Toys._all_meshes(model):
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	# one big shadow-ish dark circle under him
	var sh2 := MeshInstance3D.new()
	var cyl := CylinderMesh.new()
	cyl.top_radius = 5.0
	cyl.bottom_radius = 5.0
	cyl.height = 0.05
	sh2.mesh = cyl
	var sm2 := StandardMaterial3D.new()
	sm2.albedo_color = Color(0, 0, 0, 0.35)
	sm2.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	sm2.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	sh2.material_override = sm2
	sh2.name = "Shadow"
	add_child(sh2)


func spot_pos(sp: Dictionary) -> Vector3:
	return (sp.node as Node3D).global_position


func spots_left() -> int:
	var k := 0
	for sp in spots:
		if sp.alive:
			k += 1
	return k


## Where the camera should look (his chest).
func focus() -> Vector3:
	return model.position + Vector3(0, 10, 0) if model else pos


## How the camera frames him: [distance, how much to look at him (0..1), pitch].
func framing() -> Array:
	match phase:
		"land":
			return [22.0, 0.75, 0.12]
		"boom":
			return [24.0, 0.6, 0.18]
		"coins":
			return [11.0, 0.0, 0.42]
	return [21.0, 0.55, 0.02]


# ------------------------------------------------------------------ every frame
func step(dt: float) -> void:
	t += dt
	phase_t += dt
	note_t = maxf(0, note_t - dt)
	var p: Hero = game.leader()
	for sp in spots:   # weak spots pulse
		if sp.alive:
			sp.node.scale = Vector3.ONE * (1.0 + sin(t * 8.0) * 0.15)
	match phase:
		"land":
			var k := clampf(phase_t / 2.6, 0, 1)
			model.position = pos + Vector3(0, 46.0 * pow(1.0 - k, 2.0), 0)
			for fl in flames:
				fl.visible = true
				fl.scale = Vector3.ONE * (1.0 + sin(t * 40) * 0.25)
			if int(t * 20) % 2 == 0:
				game.fx.burst(model.position + Vector3(randf_range(-3, 3), 9, 3.4), Color(1, 0.7, 0.3), 3, 2, -3, 1.6)
			if k >= 1.0:
				_land_boom()
				phase = "walk"
				phase_t = 0.0
		"walk":
			var to := Vector3(stop.x - pos.x, 0, stop.z - pos.z)
			if to.length() < 0.5:
				phase = "fight"
				phase_t = 0.0
			else:
				pos += to.normalized() * SPEED * dt
				pos.y = lerpf(pos.y, _ground(pos.x, pos.z), 1 - exp(-dt * 3))
				_walk_anim(dt, true)
			_turn_to(p.position, dt)
			_fire(dt, p)
		"fight":
			_turn_to(p.position, dt)
			_walk_anim(dt, false)
			_fire(dt, p)
			stomp_t -= dt
			if stomp_t <= 0:
				stomp_t = 6.0
				_stomp(p)
		"boom":
			_boom_step(dt)
		"coins":
			_rain_step(dt, p)
	if phase in ["walk", "fight"]:
		model.position = pos
		model.rotation.y = face + PI
		_missiles_step(dt, p)
		_duds_step(dt, p)
		_thrown_step(dt)
		_check_feet(p)
		_check_shots()
	var sh := get_node_or_null("Shadow")
	if sh:
		sh.position = Vector3(model.position.x, _ground(model.position.x, model.position.z) + 0.05, model.position.z)
		sh.visible = phase != "done" and phase != "coins"


func _turn_to(target: Vector3, dt: float) -> void:
	var want := atan2(target.x - pos.x, target.z - pos.z)
	face = lerp_angle(face, want, 1 - exp(-dt * 0.8))


func _walk_anim(dt: float, walking: bool) -> void:
	var before := step_phase
	step_phase += dt * (2.2 if walking else 0.6)
	var s := sin(step_phase * PI)
	legs[0].rotation.x = s * (0.35 if walking else 0.05)
	legs[1].rotation.x = -s * (0.35 if walking else 0.05)
	arms[0].rotation.x = -s * 0.25
	arms[1].rotation.x = s * 0.25
	model.rotation.z = s * 0.03
	if walking and int(before) != int(step_phase):   # a foot comes down: BOOM
		game.fx.shake = maxf(game.fx.shake, 0.35)
		game.sfx("slam", -6)
		var side := 1 if int(step_phase) % 2 == 0 else -1
		var foot := model.global_transform * Vector3(side * 2.2, 0.2, -0.6)
		game.fx.burst(foot, Color(0.85, 0.8, 0.7), 10, 4, 6, 1.4)
	for fl in flames:
		fl.visible = false


func _land_boom() -> void:
	game.fx.shake = 1.4
	game.sfx("boss_down")
	for i in 16:
		var a := TAU * i / 16.0
		game.fx.burst(pos + Vector3(cos(a) * 5, 0.5, sin(a) * 5), Color(0.9, 0.85, 0.75), 6, 5, 6, 1.6)
	game.hud.say("MECHA KRAG ATTACKS!", "Hit his 4 glowing WEAK SPOTS! Throw back the dud missiles!", 3.2)
	game.speak("Mamma mia! A giant robot!", "rocco")
	game.play_music("boss")


## Missiles: a red X shows where one will land, then it comes down.
func _fire(dt: float, p: Hero) -> void:
	missile_t -= dt
	if missile_t > 0:
		return
	missile_t = MISSILE_EVERY * (0.75 if spots_left() <= 2 else 1.0)
	var arm: Node3D = arms[randi() % 2]
	var from := arm.global_transform * Vector3(0, 2.6, -0.5)
	var off := Vector2(randf_range(-2.5, 2.5), randf_range(-2.5, 2.5))
	var to := Vector3(p.position.x + off.x, 0, p.position.z + off.y)
	to.y = _ground(to.x, to.z)
	var dud := randf() < DUD_CHANCE or duds.is_empty() and carrying.is_empty() and thrown.is_empty() and spots_left() > 0 and _back_left()
	var node := _missile_model()
	node.position = from
	add_child(node)
	var marker := _x_marker()
	marker.position = to + Vector3(0, 0.06, 0)
	add_child(marker)
	missiles.append({"node": node, "from": from, "to": to, "t": 0.0, "dur": 1.6, "dud": dud, "marker": marker})
	game.sfx("arrow", -2)


func _back_left() -> bool:
	for sp in spots:
		if sp.alive and sp.back:
			return true
	return false


func _missile_model() -> Node3D:
	var n := Node3D.new()
	Toys.part(n, Toys.cyl(0.35, 0.35, 1.6, 10), Vector3.ZERO, Color("dcdce2"), Vector3.ONE, Vector3(PI / 2, 0, 0))
	Toys.part(n, Toys.cyl(0.0, 0.35, 0.7, 10), Vector3(0, 0, -1.15), Color("e8413b"), Vector3.ONE, Vector3(-PI / 2, 0, 0))
	for k in 4:
		var a := k * PI / 2
		Toys.part(n, Toys.box(Vector3(0.08, 0.5, 0.5)), Vector3(cos(a) * 0.35, sin(a) * 0.35, 0.7), Color("e8413b"), Vector3.ONE, Vector3(0, 0, a), false)
	for sx in [-1, 1]:
		Toys.part(n, Toys.sph(0.09), Vector3(sx * 0.15, 0.28, -0.75), Color.WHITE, Vector3.ONE, Vector3.ZERO, false)
	var fl := Toys.part(n, Toys.cyl(0.0, 0.25, 0.8, 8), Vector3(0, 0, 1.2), Color("ffb02e"), Vector3.ONE, Vector3(PI / 2, 0, 0), false, 2.5)
	fl.name = "Flame"
	return n


func _x_marker() -> Node3D:
	var n := Node3D.new()
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(1, 0.15, 0.15, 0.9)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	for a in [PI / 4, -PI / 4]:
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(3.2, 0.06, 0.5)
		mi.mesh = bm
		mi.material_override = m
		mi.rotation.y = a
		n.add_child(mi)
	var ring := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 1.9
	tm.outer_radius = 2.2
	ring.mesh = tm
	ring.material_override = m
	n.add_child(ring)
	return n


func _missiles_step(dt: float, p: Hero) -> void:
	for ms in missiles.duplicate():
		ms.t += dt
		var k: float = clampf(ms.t / ms.dur, 0, 1)
		var at: Vector3 = ms.from.lerp(ms.to, k) + Vector3(0, sin(k * PI) * 9.0, 0)
		var nd: Node3D = ms.node
		var ahead: Vector3 = ms.from.lerp(ms.to, minf(k + 0.02, 1.0)) + Vector3(0, sin(minf(k + 0.02, 1.0) * PI) * 9.0, 0)
		if ahead.distance_to(at) > 0.001:
			nd.look_at_from_position(at, ahead, Vector3.UP)
		nd.position = at
		ms.marker.visible = int(t * 8) % 2 == 0
		ms.marker.scale = Vector3.ONE * (1.2 - k * 0.4)
		if k >= 1.0:
			ms.marker.queue_free()
			missiles.erase(ms)
			if ms.dud:   # lands without exploding: grab it and throw it back!
				nd.rotation = Vector3(0, randf() * TAU, 0)
				nd.position = ms.to + Vector3(0, 0.35, 0)
				nd.get_node("Flame").visible = false
				var lb := Label3D.new()
				lb.text = "GRAB ME!"
				lb.font_size = 64
				lb.outline_size = 14
				lb.pixel_size = 0.008
				lb.billboard = BaseMaterial3D.BILLBOARD_ENABLED
				lb.modulate = Color("ffe14a")
				lb.position.y = 1.2
				lb.name = "Grab"
				nd.add_child(lb)
				duds.append({"node": nd, "t": 0.0})
				game.fx.burst(ms.to + Vector3(0, 0.3, 0), Color(0.7, 0.7, 0.7), 8, 3, 6)
				game.sfx("bonk", -2)
			else:
				_explode(ms.to, 1.0)
				if Vector2(p.position.x - ms.to.x, p.position.z - ms.to.z).length() < 2.6 and absf(p.position.y - ms.to.y) < 2.5:
					hurt(ms.to)
				nd.queue_free()


func _explode(at: Vector3, size: float) -> void:
	game.fx.burst(at + Vector3(0, 0.5, 0), Color("ff8a1f"), int(16 * size), 7 * size, 4, 1.8 * size)
	game.fx.burst(at + Vector3(0, 0.8, 0), Color("ffe14a"), int(10 * size), 5 * size, 2, 1.4 * size)
	game.fx.burst(at + Vector3(0, 0.6, 0), Color(0.35, 0.35, 0.38), int(8 * size), 3 * size, -1, 2.0 * size)
	game.fx.shake = maxf(game.fx.shake, 0.4 * size)
	game.sfx("boss_hit", -2)


func _stomp(p: Hero) -> void:
	game.fx.shake = 1.0
	game.sfx("slam")
	game.fx.ring(pos + Vector3(0, 0.3, 0), 9.0)
	note = "STOMP! Jump!"
	note_t = 1.2
	if p.on_floor and Vector2(p.position.x - pos.x, p.position.z - pos.z).length() < 9.0:
		hurt(pos)


## Ouch. (At home there's no checkpoint: run out of hearts and you're back at the start.)
func hurt(from: Vector3) -> void:
	if game.hurt_t > 0 or game.invincible:
		return
	var p: Hero = game.leader()
	game.hearts -= 1
	game.hurt_t = 1.4
	p.flash_t = 1.4
	var away := Vector3(p.position.x - from.x, 0, p.position.z - from.z)
	if away.length() < 0.1:
		away = Vector3(0, 0, 1)
	away = away.normalized() * 7.0
	p.vel = Vector3(away.x, 8, away.z)
	game.sfx("hurt")
	if game.hearts <= 0:
		game.hearts = game.MAX_HEARTS
		p.position = home_spot
		p.vel = Vector3.ZERO
		game.hud.say("OOPS!", "Back to the start. You can do it!", 2.2)


# ------------------------------------------------------------------ weak spots
func _hit_spot(sp: Dictionary, why: String) -> void:
	if not sp.alive or phase in ["boom", "coins", "done"]:
		return
	sp.alive = false
	var at := spot_pos(sp)
	sp.node.visible = false
	_explode(at, 1.4)
	hits += 1
	model.rotation.x = -0.12   # he staggers
	game.sfx("boss_down", -4)
	var left := spots_left()
	if left > 0:
		game.hud.say("WEAK SPOT! %s" % why, "%d left!" % left, 1.8)
	else:
		_start_boom()


func hit_spot_index(i: int) -> void:
	_hit_spot(spots[i], "")


func _check_feet(p: Hero) -> void:
	for sp in spots:
		if not sp.alive or sp.back:
			continue
		var at := spot_pos(sp)
		var d := Vector2(p.position.x - at.x, p.position.z - at.z).length()
		var mid := p.position.y + p.height * 0.5
		var attacking: bool = not p.on_floor or p.slam != "" or p.action_t > 0
		if d < 1.7 and absf(mid - at.y) < 1.8 and attacking:
			_hit_spot(sp, "BONK!")
			p.vel.y = 9.0


## Partner shots and fireballs hit weak spots too.
func _check_shots() -> void:
	for sh in game.shots:
		if not (sh.kind in game.FRIENDLY_SHOTS) or not is_instance_valid(sh.node):
			continue
		for sp in spots:
			if sp.alive and (sh.node as Node3D).position.distance_to(spot_pos(sp)) < 1.8:
				_hit_spot(sp, "ZAP!")
				sh.life = 0.0


# ------------------------------------------------------------------ duds: grab & throw back
func _duds_step(dt: float, p: Hero) -> void:
	for d in duds.duplicate():
		d.t += dt
		var nd: Node3D = d.node
		var lb := nd.get_node_or_null("Grab")
		if lb:
			lb.visible = int(t * 4) % 2 == 0
		if int(t * 6) % 3 == 0 and randf() < 0.3:
			game.fx.burst(nd.position + Vector3(0, 0.5, 0), Color(0.5, 0.5, 0.5), 1, 1, -2, 0.8)
		if d.t > 14.0:   # fizzles out
			game.fx.burst(nd.position, Color(0.6, 0.6, 0.6), 6, 2, 4)
			nd.queue_free()
			duds.erase(d)
	if not carrying.is_empty():
		var nd: Node3D = carrying.node
		nd.position = p.position + Vector3(0, p.height + 0.7, 0)
		nd.rotation = Vector3(0, face + PI / 2, 0)


func near_dud(p: Hero) -> Dictionary:
	var best := {}
	var bd := 2.2
	for d in duds:
		var dd: float = (d.node as Node3D).position.distance_to(p.position + Vector3(0, 0.4, 0))
		if dd < bd:
			bd = dd
			best = d
	return best


## ACTION while he's attacking: grab a dud / throw it. Returns true if it used the press.
func on_action() -> bool:
	if not active or not phase in ["walk", "fight"]:
		return false
	var p: Hero = game.leader()
	if not carrying.is_empty():
		_throw()
		return true
	var d := near_dud(p)
	if d.is_empty():
		return false
	duds.erase(d)
	carrying = d
	var lb := (d.node as Node3D).get_node_or_null("Grab")
	if lb:
		lb.queue_free()
	game.sfx("swap", -4)
	note = "THROW it at his back!"
	note_t = 1.6
	return true


func _throw() -> void:
	var nd: Node3D = carrying.node
	carrying = {}
	# it flies to a weak spot by itself (back spots first)
	var target := {}
	for sp in spots:
		if sp.alive and sp.back:
			target = sp
			break
	if target.is_empty():
		for sp in spots:
			if sp.alive:
				target = sp
				break
	thrown.append({"node": nd, "from": nd.position, "spot": target, "t": 0.0})
	game.sfx("jump2", -4)


func _thrown_step(dt: float) -> void:
	for th in thrown.duplicate():
		th.t += dt
		var k: float = clampf(th.t / 1.0, 0, 1)
		var to: Vector3 = spot_pos(th.spot) if not th.spot.is_empty() else th.from + Vector3(0, 0, -6)
		var nd: Node3D = th.node
		nd.position = th.from.lerp(to, k) + Vector3(0, sin(k * PI) * 6.0, 0)
		nd.rotation.x += dt * 12
		if k >= 1.0:
			thrown.erase(th)
			nd.queue_free()
			if not th.spot.is_empty():
				_hit_spot(th.spot, "KA-BOOM!")


func label() -> String:
	if not active or not phase in ["walk", "fight"]:
		return ""
	if not carrying.is_empty():
		return "THROW"
	if not near_dud(game.leader()).is_empty():
		return "GRAB"
	return ""


# ------------------------------------------------------------------ the big finale
func _start_boom() -> void:
	phase = "boom"
	phase_t = 0.0
	for ms in missiles:
		ms.node.queue_free()
		ms.marker.queue_free()
	missiles.clear()
	for d in duds:
		d.node.queue_free()
	duds.clear()
	if not carrying.is_empty():
		carrying.node.queue_free()
		carrying = {}
	game.hud.say("MECHA KRAG IS BREAKING!", "Get back!", 2.4)
	game.speak("Look out! He's gonna blow!", "rocco")


func _boom_step(dt: float) -> void:
	# shaking and little explosions all over, then he tips over backwards and goes BOOM
	if phase_t < 2.4:
		model.position = pos + Vector3(randf_range(-0.2, 0.2), 0, randf_range(-0.2, 0.2))
		if int(phase_t * 10) != int((phase_t - dt) * 10):
			var p := model.global_transform * Vector3(randf_range(-3, 3), randf_range(3, 16), randf_range(-2, 3))
			_explode(p, 0.9)
	else:
		var k := clampf((phase_t - 2.4) / 1.4, 0, 1)
		model.rotation.x = lerpf(0.0, 1.45, k * k)   # tips over backwards, away from you
		model.position = pos
		if k >= 1.0 and phase_t - dt < 3.8:
			game.fx.shake = 2.0
			game.sfx("boss_down")
			for i in 12:
				var p := model.global_transform * Vector3(randf_range(-4, 4), randf_range(2, 16), randf_range(-3, 3))
				_explode(p, 1.8)
		if phase_t > 4.6:
			model.visible = false
			for i in 20:
				game.fx.burst(model.position + Vector3(randf_range(-6, 6), randf_range(1, 8), randf_range(-6, 6)), Color.from_hsv(randf(), 0.7, 1), 14, 7, 4, 1.6)
			_start_rain()


func _start_rain() -> void:
	phase = "coins"
	phase_t = 0.0
	var p: Hero = game.leader()
	for i in COIN_RAIN:
		var c: Node3D = level.prop("coin", Vector3.ZERO, 0.8)
		c.position = p.position + Vector3(randf_range(-8, 8), randf_range(8, 22), randf_range(-8, 8))
		rain.append({"node": c, "vy": 0.0, "landed": false})
	game.hud.say("MECHA KRAG DEFEATED!", "It's raining coins! Grab them!", 3.0)
	game.play_music("fanfare", false)
	game.speak("We did it! Wahoo!", "rocco")


func _rain_step(dt: float, p: Hero) -> void:
	for c in rain.duplicate():
		var nd: Node3D = c.node
		if not c.landed:
			c.vy -= 14.0 * dt
			nd.position.y += c.vy * dt
			var g := _ground(nd.position.x, nd.position.z)
			if nd.position.y < g + 0.4:
				nd.position.y = g + 0.4
				c.landed = true
		nd.rotation.y += dt * 4
		if nd.position.distance_to(p.position + Vector3(0, 0.7, 0)) < 1.6 or phase_t > 9.0:
			coins_got += 1
			game.coins += 1
			if phase_t <= 9.0:
				game.sfx("coin", -6)
				game.fx.burst(nd.position, Color("ffd23a"), 4, 2, 5)
			nd.queue_free()
			rain.erase(c)
	if rain.is_empty() or phase_t > 9.5:
		_finish()


func _finish() -> void:
	phase = "done"
	active = false
	game.coins += 50
	game.mecha_beaten += 1
	game.hud.say("+%d COINS!" % (coins_got + 50), "New in DECOR: the MECHA KRAG TROPHY!", 3.0)
	game.play_music("cozy")
	game.mecha_done()


func cleanup() -> void:
	queue_free()


# ------------------------------------------------------------------ HUD (called by hud.gd)
func draw(h) -> void:
	var s: Vector2 = h.size
	if phase in ["coins", "done"]:
		return
	var w := 360.0
	var x0 := (s.x - w) * 0.5
	h.txt("MECHA KRAG", Vector2(0, 46), 34, Color("ff6a3a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	for i in 4:
		var c := Vector2(x0 + 45 + i * 90, 80)
		var alive: bool = spots[i].alive
		h.draw_circle(c, 20, Color(0.1, 0.08, 0.15, 0.85))
		h.draw_circle(c, 15, Color("ff4fd8") if alive else Color(0.3, 0.3, 0.35))
		if not alive:
			h.draw_line(c + Vector2(-12, -12), c + Vector2(12, 12), Color.WHITE, 4)
			h.draw_line(c + Vector2(12, -12), c + Vector2(-12, 12), Color.WHITE, 4)
	var hint := "Jump into the glowing spots on his FEET!  GRAB the dud missiles and THROW them at his BACK!"
	if not carrying.is_empty():
		hint = "THROW it! (ACTION)"
	elif not near_dud(game.leader()).is_empty():
		hint = "GRAB the missile! (ACTION)"
	h.txt(hint, Vector2(0, 160), 22, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	if note_t > 0:
		h.txt(note, Vector2(0, s.y * 0.3), 44, Color("ffe14a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
