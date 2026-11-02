class_name Fireworks
extends Node3D
## Fireworks shows: rockets whistle up with a sparkly trail and burst into colourful
## balls (rings, willows and crackles). Small show for a star, bigger for the gate,
## huge for beating a boss, and the launcher at home makes one whenever you like.
## Each burst is one GPU particle node that frees itself, so nothing piles up.

const SHOWS := {"star": [6, 1.6], "gate": [14, 3.0], "boss": [28, 6.0], "home": [18, 4.5], "test": [3, 0.3]}
const COLORS := [Color("ff4f6d"), Color("ffd23a"), Color("54c8ff"), Color("7dff6a"), Color("c77dff"), Color("ff9a2e"), Color("ffffff"), Color("ff6fd8")]
const MAX_LIVE := 40

var game
var queue: Array = []          # [time_left, position]
var rockets: Array = []        # {node, trail, vel, life, burst_at, kind}
var bursts: Array = []         # [node, life]
static var _rocket_mat: StandardMaterial3D
static var _spark_mesh: QuadMesh
static var _trail_pm: ParticleProcessMaterial
static var _burst_mesh: QuadMesh   # bright solid sparks (show up in daylight too)


func setup(g) -> void:
	game = g
	if _rocket_mat == null:
		_rocket_mat = DayNight._additive(Color(1, 0.95, 0.75, 1))
		_rocket_mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
		_rocket_mat.albedo_texture = DayNight._soft_dot(32)
		var sm := DayNight._additive(Color(1, 1, 1, 1), DayNight._soft_dot(32))
		sm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
		sm.vertex_color_use_as_albedo = true
		_spark_mesh = QuadMesh.new()
		_spark_mesh.size = Vector2(0.38, 0.38)
		_spark_mesh.material = sm
		var bm := StandardMaterial3D.new()
		bm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		bm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		bm.vertex_color_use_as_albedo = true
		bm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
		bm.albedo_texture = _star_spark()
		bm.disable_fog = true
		_burst_mesh = QuadMesh.new()
		_burst_mesh.size = Vector2(0.6, 0.6)
		_burst_mesh.material = bm
		_trail_pm = ParticleProcessMaterial.new()
		_trail_pm.direction = Vector3(0, -1, 0)
		_trail_pm.spread = 15
		_trail_pm.initial_velocity_min = 0.5
		_trail_pm.initial_velocity_max = 1.5
		_trail_pm.gravity = Vector3(0, -2, 0)
		_trail_pm.scale_min = 0.3
		_trail_pm.scale_max = 0.6
		_trail_pm.color = Color(1, 0.8, 0.5, 0.9)
		var ramp := Gradient.new()
		ramp.set_color(0, Color(1, 1, 1, 1))
		ramp.set_color(1, Color(1, 1, 1, 0))
		var rt := GradientTexture1D.new()
		rt.gradient = ramp
		_trail_pm.color_ramp = rt


## A little 4-point sparkle picture with a bright middle.
static func _star_spark() -> Texture2D:
	var img := Image.create(32, 32, false, Image.FORMAT_RGBA8)
	for y in 32:
		for x in 32:
			var d := Vector2(x - 15.5, y - 15.5)
			var r := d.length() / 15.5
			var a := clampf(1.0 - r * 1.6, 0, 1)
			a = maxf(a, clampf(1.0 - absf(d.x) / 2.5, 0, 1) * clampf(1.0 - absf(d.y) / 15.5, 0, 1))
			a = maxf(a, clampf(1.0 - absf(d.y) / 2.5, 0, 1) * clampf(1.0 - absf(d.x) / 15.5, 0, 1))
			img.set_pixel(x, y, Color(1, 1, 1, a))
	return ImageTexture.create_from_image(img)


## Starts a show of this kind around `at` (the rockets go up nearby).
func play_show(kind: String, at: Vector3) -> void:
	var s: Array = SHOWS.get(kind, SHOWS.star)
	for i in int(s[0]):
		var when := randf() * float(s[1]) + (0.0 if i == 0 else 0.1)
		var p := at + Vector3(randf_range(-6, 6), 0, randf_range(-6, 6))
		queue.append([when, p, kind])


## A show where you can see it: a bit in front of the camera.
func play_show_ahead(kind: String) -> void:
	var c: Camera3D = game.cam
	var l: Vector3 = game.leader().global_position
	var fwd := -c.global_transform.basis.z
	fwd.y = 0
	fwd = fwd.normalized() if fwd.length() > 0.01 else Vector3(0, 0, -1)
	play_show(kind, l + fwd * 18.0)


func busy() -> bool:
	return not queue.is_empty() or not rockets.is_empty() or not bursts.is_empty()


func live_nodes() -> int:
	return rockets.size() * 2 + bursts.size()


func step(dt: float) -> void:
	for q in queue.duplicate():
		q[0] -= dt
		if q[0] <= 0:
			queue.erase(q)
			if rockets.size() + bursts.size() < MAX_LIVE:
				_launch(q[1], q[2])
	for r in rockets.duplicate():
		r.life += dt
		var n: MeshInstance3D = r.node
		r.vel.y -= 6.0 * dt
		n.global_position += r.vel * dt
		r.trail.global_position = n.global_position
		if r.life >= r.burst_at:
			_burst(n.global_position, r.kind)
			n.queue_free()
			r.trail.emitting = false
			bursts.append([r.trail, 0.8])   # let the trail fade, then free it
			rockets.erase(r)
	for b in bursts.duplicate():
		b[1] -= dt
		if b[1] <= 0:
			if is_instance_valid(b[0]):
				b[0].queue_free()
			bursts.erase(b)


func _launch(from: Vector3, kind: String) -> void:
	var n := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(0.35, 0.35)
	n.mesh = q
	n.material_override = _rocket_mat
	n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(n)
	n.global_position = from + Vector3(0, 0.5, 0)
	var trail := GPUParticles3D.new()
	trail.amount = 24
	trail.lifetime = 0.6
	trail.local_coords = false
	trail.process_material = _trail_pm
	trail.draw_pass_1 = _spark_mesh
	trail.visibility_aabb = AABB(Vector3(-20, -30, -20), Vector3(40, 40, 40))
	add_child(trail)
	trail.global_position = n.global_position
	var up := randf_range(11, 13) if kind != "test" else 12.0
	rockets.append({"node": n, "trail": trail, "vel": Vector3(randf_range(-1.5, 1.5), up, randf_range(-1.5, 1.5)),
		"life": 0.0, "burst_at": randf_range(0.85, 1.1) if kind != "test" else 0.2, "kind": kind})
	game.sfx("jump2", -14)


func _burst(at: Vector3, kind: String) -> void:
	var p := GPUParticles3D.new()
	var big := kind in ["boss", "gate", "home"]
	p.amount = 90 if big else 60
	p.lifetime = 1.7
	p.one_shot = true
	p.explosiveness = 1.0
	p.local_coords = false
	p.visibility_aabb = AABB(Vector3(-20, -20, -20), Vector3(40, 40, 40))
	var pm := ParticleProcessMaterial.new()
	pm.direction = Vector3(0, 1, 0)
	pm.spread = 180
	var style := randi() % 3   # 0 ball, 1 willow (droopy gold), 2 ring
	pm.initial_velocity_min = 7.0 if style != 2 else 9.0
	pm.initial_velocity_max = 10.0 if style != 2 else 9.5
	pm.gravity = Vector3(0, -3.0 if style != 1 else -7.0, 0)
	pm.damping_min = 3.0
	pm.damping_max = 4.0
	if style == 2:   # a flat ring
		pm.direction = Vector3(1, 0, 0)
		pm.flatness = 1.0
	var c: Color = COLORS[randi() % COLORS.size()] if style != 1 else Color("ffcf55")
	var c2: Color = COLORS[randi() % COLORS.size()]
	var ramp := Gradient.new()
	ramp.offsets = PackedFloat32Array([0.0, 0.15, 0.7, 1.0])
	ramp.colors = PackedColorArray([Color(1, 1, 1, 1), c, c2.lerp(c, 0.5), Color(c2.r, c2.g, c2.b, 0)])
	var rt := GradientTexture1D.new()
	rt.gradient = ramp
	pm.color_ramp = rt
	pm.scale_min = 0.7
	pm.scale_max = 1.3
	p.process_material = pm
	p.draw_pass_1 = _burst_mesh
	add_child(p)
	p.global_position = at
	if style == 2:
		p.rotation = Vector3(randf_range(-0.6, 0.6), randf() * TAU, randf_range(-0.6, 0.6))
	p.restart()   # one-shot particles must be (re)started once they're in the tree
	bursts.append([p, p.lifetime + 0.3])
	game.sfx("boss_hit" if big else "break", -6 if big else -9)
