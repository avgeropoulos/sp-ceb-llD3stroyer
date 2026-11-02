class_name Weather
extends Node3D
## Weather that follows the camera: rain with splashes and thunder flashes, snowfall,
## blowing sand, floating embers, candy sparkles and star dust. It changes now and then,
## and when the rain stops a rainbow comes out. Everything is GPU particles (cheap).

const KINDS := {
	"home": ["rain", "snow"], "meadow": ["rain"], "jungle": ["rain"], "swamp": ["rain"], "beach": ["rain"],
	"frost": ["snow"], "dunes": ["sand"], "lava": ["embers"], "candy": ["sparkles"], "cloud": ["stardust"],
	"moon": ["stardust"],
}
## Ambient kinds stay on most of the time; rain and snow come and go.
const AMBIENT := ["sand", "embers", "sparkles", "stardust"]

var game
var daynight: DayNight
var where := "home"
var now := "clear"             # what's falling right now
var timer := 0.0               # until the next change
var fall: GPUParticles3D       # the falling stuff
var splash: GPUParticles3D     # rain splashes on the ground
var thunder_t := 0.0
var flash := 0.0               # 0..1 lightning flash (the HUD draws it)
var cloud := 0.0               # how grey the sky is (eases in and out)
var enabled := true


func setup(g, dn: DayNight, place: String) -> void:
	game = g
	daynight = dn
	where = String(Boot.level) if place in ["level", "bonus"] else "home"
	if place == "map" or place == "title":
		where = "none"
	timer = randf_range(20, 50)
	var kinds: Array = KINDS.get(where, [])
	if kinds.size() > 0 and kinds[0] in AMBIENT:
		set_weather(kinds[0])   # ambient worlds start with their sparkle / embers
	elif kinds.size() > 0 and randf() < 0.3:
		set_weather(kinds[randi() % kinds.size()])


static func _dot_tex() -> Texture2D:
	return DayNight._soft_dot(32)


func _clear_nodes() -> void:
	for n in [fall, splash]:
		if n and is_instance_valid(n):
			n.queue_free()
	fall = null
	splash = null


## Switches the weather ("clear", "rain", "snow", "sand", "embers", "sparkles", "stardust").
func set_weather(kind: String) -> void:
	if kind == now:
		return
	var was := now
	now = kind
	_clear_nodes()
	if was == "rain" and kind == "clear" and daynight:
		daynight.show_rainbow()
	if kind == "clear":
		return
	fall = GPUParticles3D.new()
	fall.local_coords = false
	fall.visibility_aabb = AABB(Vector3(-30, -30, -30), Vector3(60, 60, 60))
	var pm := ParticleProcessMaterial.new()
	pm.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	var q := QuadMesh.new()
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	m.albedo_texture = _dot_tex()
	match kind:
		"rain":
			fall.amount = 420
			fall.lifetime = 1.1
			pm.emission_box_extents = Vector3(16, 1, 16)
			pm.direction = Vector3(0.12, -1, 0.05)
			pm.spread = 3
			pm.initial_velocity_min = 22
			pm.initial_velocity_max = 26
			pm.gravity = Vector3(0, -10, 0)
			pm.color = Color(0.8, 0.88, 1.0, 0.75)
			q.size = Vector2(0.07, 1.3)
			m.albedo_texture = null
			m.billboard_mode = BaseMaterial3D.BILLBOARD_FIXED_Y
			m.billboard_keep_scale = true
		"snow":
			fall.amount = 320
			fall.lifetime = 6.0
			pm.emission_box_extents = Vector3(18, 1, 18)
			pm.direction = Vector3(0, -1, 0)
			pm.spread = 25
			pm.initial_velocity_min = 1.2
			pm.initial_velocity_max = 2.2
			pm.gravity = Vector3(0, -0.4, 0)
			pm.turbulence_enabled = true
			pm.turbulence_noise_strength = 1.2
			pm.color = Color(1, 1, 1, 1)
			q.size = Vector2(0.26, 0.26)
		"sand":
			fall.amount = 260
			fall.lifetime = 2.2
			pm.emission_box_extents = Vector3(18, 4, 18)
			pm.direction = Vector3(1, 0.05, 0.3)
			pm.spread = 10
			pm.initial_velocity_min = 9
			pm.initial_velocity_max = 14
			pm.gravity = Vector3(0, -1, 0)
			pm.color = Color(0.95, 0.8, 0.55, 0.65)
			q.size = Vector2(0.18, 0.1)
		"embers":
			fall.amount = 200
			fall.lifetime = 4.0
			pm.emission_box_extents = Vector3(18, 1, 18)
			pm.direction = Vector3(0, 1, 0)
			pm.spread = 30
			pm.initial_velocity_min = 1.0
			pm.initial_velocity_max = 2.6
			pm.gravity = Vector3(0, 0.3, 0)
			pm.turbulence_enabled = true
			pm.turbulence_noise_strength = 1.0
			pm.color = Color(1.0, 0.55, 0.15, 1.0)
			q.size = Vector2(0.24, 0.24)
			m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
		"sparkles":
			fall.amount = 120
			fall.lifetime = 3.0
			pm.emission_box_extents = Vector3(16, 4, 16)
			pm.direction = Vector3(0, -1, 0)
			pm.spread = 60
			pm.initial_velocity_min = 0.4
			pm.initial_velocity_max = 1.0
			pm.gravity = Vector3(0, -0.2, 0)
			pm.hue_variation_min = -1.0
			pm.hue_variation_max = 1.0
			pm.color = Color(1, 0.6, 0.85, 1)
			q.size = Vector2(0.24, 0.24)
			m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
		"stardust":
			fall.amount = 120
			fall.lifetime = 5.0
			pm.emission_box_extents = Vector3(18, 5, 18)
			pm.direction = Vector3(0, 1, 0)
			pm.spread = 180
			pm.initial_velocity_min = 0.2
			pm.initial_velocity_max = 0.6
			pm.gravity = Vector3.ZERO
			pm.color = Color(0.85, 0.9, 1, 1)
			q.size = Vector2(0.2, 0.2)
			m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	if kind != "rain":
		var ramp := Gradient.new()
		ramp.set_color(0, Color(1, 1, 1, 0))
		ramp.add_point(0.15, Color(1, 1, 1, 1))
		ramp.add_point(0.8, Color(1, 1, 1, 1))
		ramp.set_color(1, Color(1, 1, 1, 0))
		var rt := GradientTexture1D.new()
		rt.gradient = ramp
		pm.color_ramp = rt
	q.material = m
	fall.process_material = pm
	fall.draw_pass_1 = q
	add_child(fall)
	if kind == "rain":   # little splashes where the drops land
		splash = GPUParticles3D.new()
		splash.amount = 90
		splash.lifetime = 0.35
		splash.local_coords = false
		splash.visibility_aabb = AABB(Vector3(-20, -5, -20), Vector3(40, 10, 40))
		var sp := ParticleProcessMaterial.new()
		sp.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
		sp.emission_box_extents = Vector3(10, 0.05, 10)
		sp.direction = Vector3(0, 1, 0)
		sp.spread = 40
		sp.initial_velocity_min = 1.5
		sp.initial_velocity_max = 2.5
		sp.gravity = Vector3(0, -14, 0)
		sp.color = Color(0.85, 0.92, 1, 0.7)
		sp.scale_min = 0.6
		sp.scale_max = 1.2
		splash.process_material = sp
		var sq := QuadMesh.new()
		sq.size = Vector2(0.1, 0.1)
		var sm := m.duplicate()
		sm.albedo_texture = _dot_tex()
		sm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
		sq.material = sm
		splash.draw_pass_1 = sq
		add_child(splash)
		thunder_t = randf_range(6, 14)


func step(dt: float) -> void:
	if where == "none" or not enabled:
		return
	timer -= dt
	if timer <= 0:
		_change()
	# follow the camera (rain falls around wherever you look)
	if game.cam:
		var c: Vector3 = game.cam.global_position
		if fall:
			var up := 14.0 if now in ["rain", "snow"] else (0.0 if now in ["sand", "sparkles", "stardust"] else -8.0)
			fall.global_position = c + Vector3(0, up, 0) - game.cam.global_transform.basis.z * 6.0
		if splash and game.leader():
			var l: Vector3 = game.leader().global_position
			splash.global_position = Vector3(l.x, l.y + 0.05, l.z)
	var want := 1.0 if now == "rain" else (0.45 if now == "snow" else 0.0)
	cloud = move_toward(cloud, want, dt * 0.25)
	if daynight:
		daynight.cloudy = cloud
	if now == "rain":
		thunder_t -= dt
		if thunder_t <= 0:
			thunder_t = randf_range(8, 18)
			flash = 1.0
			game.sfx("thunder", -6)
	flash = maxf(0, flash - dt * 2.5)


## Picks the next weather for this place.
func _change() -> void:
	var kinds: Array = KINDS.get(where, [])
	timer = randf_range(45, 90)
	if kinds.is_empty():
		return
	var k: String = kinds[randi() % kinds.size()]
	if k in AMBIENT:
		set_weather(k if randf() < 0.8 else "clear")
	elif now == "clear":
		set_weather(k if randf() < 0.55 else "clear")
	else:
		set_weather("clear")


func particle_count() -> int:
	var n := 0
	for p in [fall, splash]:
		if p and is_instance_valid(p):
			n += p.amount
	return n
