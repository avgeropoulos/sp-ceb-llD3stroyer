class_name DayNight
extends Node3D
## Day and night: the sun (and a big moon) move across the sky, sunrise and sunset
## paint it orange and pink, at night stars twinkle, shooting stars zip by, lanterns
## and windows glow and fireflies come out. At home a full day takes 6 minutes; in the
## worlds time moves slowly and only tints the world's own sky a little (never too dark).
## Also: the sun's glow, a rainbow after the rain, and shimmering water.

const HOME_DAY := 360.0        # seconds for a whole day at home
const WORLD_DAY := 900.0       # in the worlds
const MORNING := 0.28          # time of day after SLEEP (0 = midnight, 0.5 = noon)

var game
var env: Environment
var sun: DirectionalLight3D
var sky_mat: ProceduralSkyMaterial
var base := {}                 # the place's own colours
var at_home := false
var tod := 0.36                # time of day 0..1
var night := 0.0               # 0 = full day, 1 = full night (how dark it really looks)
var dusk := 0.0                # 0..1: how much sunrise / sunset colour
var cloudy := 0.0              # set by the weather (rain makes it grey)
var strength := 1.0            # how much day/night changes this place (worlds: gentle)
var stars: MultiMeshInstance3D
var star_mat: ShaderMaterial
var moon: MeshInstance3D
var moon_mat: StandardMaterial3D
var glow: MeshInstance3D       # the sun's soft glow
var glow_mat: StandardMaterial3D
var rays: Array = []           # fake sun shafts (big soft additive cards)
var ray_mat: StandardMaterial3D
var rainbow: MeshInstance3D
var rainbow_mat: StandardMaterial3D
var rainbow_t := 0.0
var fireflies: GPUParticles3D
var shooting: Array = []       # [node, velocity, life]
var shoot_t := 4.0
var upd_t := 0.0
var t := 0.0


func setup(g, e: Environment, s: DirectionalLight3D, sm: ProceduralSkyMaterial, wi: Dictionary, place: String) -> void:
	game = g
	env = e
	sun = s
	sky_mat = sm
	at_home = place == "home"
	strength = 1.0 if at_home else 0.45
	base = {"top": sm.sky_top_color, "hor": sm.sky_horizon_color, "gbot": sm.ground_bottom_color,
		"amb": e.ambient_light_color, "fog": e.fog_light_color, "sun": s.light_color}
	# worlds start at a random nice time (mostly day), home at morning
	var r := randf()
	tod = 0.34 if at_home else (0.42 if r < 0.6 else (0.7 if r < 0.85 else 0.27))
	_make_stars()
	_make_moon()
	_make_glow()
	_make_rainbow()
	_make_fireflies(place)
	_update_sky(true)


# ------------------------------------------------------------------ building the bits
static func _additive(col: Color, tex: Texture2D = null) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	m.albedo_color = col
	m.disable_fog = true
	m.no_depth_test = false
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	if tex:
		m.albedo_texture = tex
	return m


static func _soft_dot(size := 64) -> Texture2D:
	var g := Gradient.new()
	g.set_color(0, Color(1, 1, 1, 1))
	g.set_color(1, Color(1, 1, 1, 0))
	var t := GradientTexture2D.new()
	t.gradient = g
	t.fill = GradientTexture2D.FILL_RADIAL
	t.fill_from = Vector2(0.5, 0.5)
	t.fill_to = Vector2(1.0, 0.5)
	t.width = size
	t.height = size
	return t


func _make_stars() -> void:
	var sh := Shader.new()
	sh.code = """
shader_type spatial;
render_mode unshaded, blend_add, depth_draw_never, cull_disabled, fog_disabled;
uniform float alpha = 0.0;
varying float tw;
void vertex() {
	// always face the camera
	MODELVIEW_MATRIX = VIEW_MATRIX * mat4(INV_VIEW_MATRIX[0], INV_VIEW_MATRIX[1], INV_VIEW_MATRIX[2], MODEL_MATRIX[3]);
	MODELVIEW_MATRIX = MODELVIEW_MATRIX * mat4(vec4(length(MODEL_MATRIX[0].xyz), 0, 0, 0), vec4(0, length(MODEL_MATRIX[1].xyz), 0, 0), vec4(0, 0, 1, 0), vec4(0, 0, 0, 1));
	tw = 0.55 + 0.45 * sin(TIME * (1.5 + float(INSTANCE_ID % 7)) + float(INSTANCE_ID) * 1.7);
}
void fragment() {
	vec2 d = UV - vec2(0.5);
	float r = length(d) * 2.0;
	float a = clamp(1.0 - r, 0.0, 1.0);
	a = a * a + max(0.0, 1.0 - abs(d.x) * 14.0) * max(0.0, 1.0 - abs(d.y) * 2.2) * 0.4 + max(0.0, 1.0 - abs(d.y) * 14.0) * max(0.0, 1.0 - abs(d.x) * 2.2) * 0.4;
	ALBEDO = COLOR.rgb * 1.4;
	ALPHA = a * alpha * tw;
}
"""
	star_mat = ShaderMaterial.new()
	star_mat.shader = sh
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	var q := QuadMesh.new()
	q.size = Vector2(1, 1)
	mm.mesh = q
	mm.instance_count = 260
	var rng := RandomNumberGenerator.new()
	rng.seed = 99
	for i in mm.instance_count:
		var d := Vector3(rng.randf_range(-1, 1), rng.randf_range(0.08, 1), rng.randf_range(-1, 1)).normalized()
		var s := rng.randf_range(0.8, 2.2) * (2.6 if rng.randf() < 0.06 else 1.0)
		mm.set_instance_transform(i, Transform3D(Basis.IDENTITY.scaled(Vector3(s, s, s)), d * 170.0))
		mm.set_instance_color(i, [Color(1, 1, 1), Color(1, 0.95, 0.75), Color(0.75, 0.85, 1), Color(1, 0.8, 0.9)][rng.randi() % 4])
	stars = MultiMeshInstance3D.new()
	stars.multimesh = mm
	stars.material_override = star_mat
	stars.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	stars.extra_cull_margin = 400
	add_child(stars)


func _make_moon() -> void:
	var img := Image.create(128, 128, false, Image.FORMAT_RGBA8)
	for y in 128:
		for x in 128:
			var d := Vector2(x - 63.5, y - 63.5).length() / 60.0
			if d > 1.0:
				img.set_pixel(x, y, Color(0, 0, 0, 0))
				continue
			var c := 0.93 - d * 0.08
			for cr in [[40, 44, 12], [80, 70, 9], [58, 88, 15], [86, 40, 6], [36, 82, 7]]:
				if Vector2(x - cr[0], y - cr[1]).length() < cr[2]:
					c -= 0.13
			img.set_pixel(x, y, Color(c, c * 0.98, c * 0.9, clampf((1.0 - d) * 12.0, 0, 1)))
	moon_mat = StandardMaterial3D.new()
	moon_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	moon_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	moon_mat.albedo_texture = ImageTexture.create_from_image(img)
	moon_mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	moon_mat.disable_fog = true
	moon = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(22, 22)
	moon.mesh = q
	moon.material_override = moon_mat
	moon.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	moon.extra_cull_margin = 400
	add_child(moon)
	# a soft halo round the moon
	var halo := MeshInstance3D.new()
	var hq := QuadMesh.new()
	hq.size = Vector2(60, 60)
	halo.mesh = hq
	var hm := _additive(Color(0.6, 0.7, 1.0, 0.35), _soft_dot())
	hm.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	halo.material_override = hm
	halo.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	halo.position.z = -0.5
	moon.add_child(halo)


func _make_glow() -> void:
	glow_mat = _additive(Color(1, 0.9, 0.6, 0.6), _soft_dot())
	glow_mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	glow = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(70, 70)
	glow.mesh = q
	glow.material_override = glow_mat
	glow.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	glow.extra_cull_margin = 400
	add_child(glow)
	# sun shafts: a few long soft cards fanning down from the sun
	var g := Gradient.new()
	# (set offsets/colours in one go: add_point re-sorts, so set_color(1) would hit the wrong point)
	g.offsets = PackedFloat32Array([0.0, 0.25, 1.0])
	g.colors = PackedColorArray([Color(1, 1, 1, 0), Color(1, 1, 1, 1), Color(1, 1, 1, 0)])
	var gt := GradientTexture2D.new()
	gt.gradient = g
	gt.fill_from = Vector2(0, 0.5)
	gt.fill_to = Vector2(1, 0.5)
	gt.width = 64
	gt.height = 8
	ray_mat = _additive(Color(1, 0.92, 0.7, 0.0), gt)
	ray_mat.depth_draw_mode = BaseMaterial3D.DEPTH_DRAW_DISABLED
	for i in 5:
		var r := MeshInstance3D.new()
		var q2 := QuadMesh.new()
		q2.size = Vector2(1, 1)
		r.mesh = q2
		r.material_override = ray_mat
		r.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		r.extra_cull_margin = 400
		add_child(r)
		rays.append(r)


func _make_rainbow() -> void:
	var img := Image.create(256, 128, false, Image.FORMAT_RGBA8)
	var cols := [Color("ff3b3b"), Color("ff9a2e"), Color("ffe14a"), Color("4cd964"), Color("3fa9f5"), Color("5856d6"), Color("b86bff")]
	for y in 128:
		for x in 256:
			var d := Vector2(x - 127.5, 127.5 - y).length() / 127.5
			var band := (d - 0.62) / 0.36
			if band < 0 or band >= 1 or y > 127:
				img.set_pixel(x, y, Color(0, 0, 0, 0))
				continue
			var c: Color = cols[int(band * cols.size())]
			c.a = sin(band * PI) * 0.75
			img.set_pixel(x, y, c)
	rainbow_mat = StandardMaterial3D.new()
	rainbow_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	rainbow_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	rainbow_mat.albedo_texture = ImageTexture.create_from_image(img)
	rainbow_mat.disable_fog = true
	rainbow_mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	rainbow_mat.albedo_color = Color(1, 1, 1, 0)
	rainbow = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(240, 120)
	rainbow.mesh = q
	rainbow.material_override = rainbow_mat
	rainbow.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	rainbow.extra_cull_margin = 400
	rainbow.visible = false
	add_child(rainbow)


func _make_fireflies(place: String) -> void:
	var w := String(Boot.level) if place in ["level", "bonus"] else "home"
	if not w in ["home", "meadow", "jungle", "swamp", "beach", "candy"]:
		return
	fireflies = GPUParticles3D.new()
	fireflies.amount = 40
	fireflies.lifetime = 5.0
	fireflies.local_coords = false
	var pm := ParticleProcessMaterial.new()
	pm.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	pm.emission_box_extents = Vector3(14, 2.5, 14)
	pm.gravity = Vector3.ZERO
	pm.direction = Vector3(0, 1, 0)
	pm.spread = 180
	pm.initial_velocity_min = 0.1
	pm.initial_velocity_max = 0.5
	pm.turbulence_enabled = true
	pm.turbulence_noise_strength = 0.6
	var ramp := Gradient.new()
	ramp.offsets = PackedFloat32Array([0.0, 0.2, 0.5, 0.8, 1.0])
	ramp.colors = PackedColorArray([Color(1, 1, 1, 0), Color(1, 1, 1, 1), Color(1, 1, 1, 0.3), Color(1, 1, 1, 1), Color(1, 1, 1, 0)])
	var rt := GradientTexture1D.new()
	rt.gradient = ramp
	pm.color_ramp = rt
	fireflies.process_material = pm
	var q := QuadMesh.new()
	q.size = Vector2(0.22, 0.22)
	var m := _additive(Color(0.85, 1.0, 0.45, 1.0), _soft_dot(32))
	m.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	m.vertex_color_use_as_albedo = true
	q.material = m
	fireflies.draw_pass_1 = q
	fireflies.emitting = false
	fireflies.visibility_aabb = AABB(Vector3(-30, -10, -30), Vector3(60, 20, 60))
	add_child(fireflies)


# ------------------------------------------------------------------ time
func is_night() -> bool:
	return night > 0.5


var away := false


## Hides the sky bits (stars, moon, glow, rainbow, fireflies) and gives full daylight while
## the game is somewhere else (the kart race in space, the arcade games).
func set_away(on: bool) -> void:
	if on == away:
		return
	away = on
	for n in [stars, moon, glow, rainbow]:
		if n:
			n.visible = false
	for r in rays:
		r.visible = false
	if fireflies:
		fireflies.emitting = false
	if on:
		sun.light_energy = 1.0
		sun.light_color = base.sun
		sun.shadow_enabled = true
	else:
		_update_sky(true)


## SLEEP at home: skip to morning.
func sleep() -> void:
	tod = MORNING
	_update_sky(true)


func show_rainbow() -> void:
	if night < 0.3:
		rainbow_t = 30.0


## How high the sun is (-1 at midnight, 1 at noon).
func sun_height() -> float:
	return -cos(tod * TAU)


func step(dt: float) -> void:
	t += dt
	tod = fposmod(tod + dt / (HOME_DAY if at_home else WORLD_DAY), 1.0)
	upd_t -= dt
	if upd_t <= 0:   # colours don't need to change every frame
		upd_t = 0.15
		_update_sky(false)
	_follow_camera(dt)
	_shooting_stars(dt)
	if rainbow_t > 0:
		rainbow_t -= dt
		var a := clampf(minf(rainbow_t / 4.0, (30.0 - rainbow_t) / 1.5), 0, 1) * (1.0 - night)
		rainbow_mat.albedo_color.a = a
		rainbow.visible = a > 0.01
	elif rainbow.visible:
		rainbow.visible = false


func _update_sky(_now: bool) -> void:
	var h := sun_height()                       # -1..1
	var raw_night := clampf((0.12 - h) / 0.35, 0, 1)
	night = raw_night * strength
	dusk = clampf(1.0 - absf(h - 0.02) / 0.32, 0, 1) * (1.0 if at_home else 0.6)
	var NIGHT_TOP := Color("070b26")
	var NIGHT_HOR := Color("1d2457")
	var DUSK_TOP := Color("3b4fa0")
	var DUSK_HOR := Color("ff8a4a")
	var top: Color = base.top
	var hor: Color = base.hor
	top = top.lerp(DUSK_TOP, dusk * 0.7)
	hor = hor.lerp(DUSK_HOR, dusk * 0.85)
	top = top.lerp(NIGHT_TOP, night)
	hor = hor.lerp(NIGHT_HOR, night)
	var grey := Color(0.55, 0.58, 0.64)
	top = top.lerp(grey * 0.85, cloudy * 0.6 * (1.0 - night))
	hor = hor.lerp(grey, cloudy * 0.5 * (1.0 - night))
	sky_mat.sky_top_color = top
	sky_mat.sky_horizon_color = hor
	sky_mat.ground_horizon_color = hor
	sky_mat.ground_bottom_color = (base.gbot as Color).lerp(Color("05061a"), night)
	# the sun: across the sky, low and orange at the ends of the day; gone at night
	var ang := tod * TAU                          # 0 = midnight (below), PI = noon (above)
	var dir := Vector3(sin(ang) * 0.9, -cos(ang), 0.45).normalized()   # direction TO the sun
	if at_home:
		sun.look_at_from_position(Vector3.ZERO, -dir, Vector3.UP if absf(dir.y) < 0.98 else Vector3.FORWARD)
	var day_e := clampf(h * 3.0 + 0.3, 0, 1)
	var sun_col: Color = (base.sun as Color).lerp(Color("ff9a55"), dusk * 0.7)
	if at_home:
		sun.light_energy = lerpf(0.35, 1.0, day_e) * (1.0 - cloudy * 0.45)   # bright moonlight: you can still play
		sun.light_color = sun_col.lerp(Color("8fa0ff"), raw_night)   # moonlight at night
		sun.shadow_enabled = day_e > 0.15
	else:
		sun.light_energy = lerpf(1.0, 0.55, night) * (1.0 - cloudy * 0.35)
		sun.light_color = sun_col.lerp(Color("aab4ff"), night)
	env.ambient_light_color = (base.amb as Color).lerp(Color("8090dd"), night).lerp(Color("ffb48a"), dusk * 0.25)
	env.ambient_light_energy = lerpf(0.55, 0.62, night) * (1.0 - cloudy * 0.2)
	env.fog_light_color = (base.fog as Color).lerp(Color("141a40"), night * 0.85).lerp(Color("ffb07a"), dusk * 0.3).lerp(grey, cloudy * 0.4)
	star_mat.set_shader_parameter("alpha", clampf((night - 0.25) / 0.5, 0, 1) * (1.0 - cloudy))
	stars.visible = night > 0.25 and cloudy < 0.95
	moon_mat.albedo_color = Color(1, 1, 1, clampf((night - 0.15) / 0.4, 0, 1))
	moon.visible = night > 0.15
	glow_mat.albedo_color = Color(1, lerpf(0.9, 0.6, dusk), lerpf(0.65, 0.35, dusk), clampf(h * 4 + 0.4, 0, 1) * 0.55 * (1.0 - cloudy))
	glow.visible = h > -0.1 and cloudy < 0.9
	ray_mat.albedo_color = Color(1, 0.9, 0.65, clampf(0.16 - absf(h - 0.35) * 0.2, 0, 0.12) * (1.0 - cloudy) * (1.0 - night))
	# lanterns and windows glow at night
	if game.level and game.level._mats.size() > Level.LANTERN:
		var lm: StandardMaterial3D = game.level._mats[Level.LANTERN]
		lm.emission_energy_multiplier = 1.3 + night * 2.4
		var wm: StandardMaterial3D = game.level._mats[Level.WINDOW]
		if wm.emission_enabled:
			wm.emission_energy_multiplier = night * 1.8
		var water: StandardMaterial3D = game.level._mats[Level.WATER]
		water.roughness = 0.12 + 0.08 * sin(t * 2.0)
	if fireflies:
		fireflies.emitting = night > 0.45 and cloudy < 0.5
	_sun_dir = dir


var _sun_dir := Vector3.UP


func _follow_camera(_dt: float) -> void:
	var cam: Camera3D = game.cam
	if cam == null:
		return
	var c := cam.global_position
	stars.global_position = c
	moon.global_position = c - _sun_dir * 150.0 + Vector3(0, 8, 0)
	glow.global_position = c + _sun_dir * 150.0
	if fireflies and game.leader():
		fireflies.global_position = game.leader().global_position + Vector3(0, 1.5, 0)
	# the rainbow stands far away, opposite the sun
	var away := Vector3(-_sun_dir.x, 0, -_sun_dir.z)
	if away.length() < 0.1:
		away = Vector3(0, 0, -1)
	away = away.normalized()
	rainbow.global_position = c + away * 150.0 + Vector3(0, -12, 0)
	rainbow.look_at(c + Vector3(0, -12, 0), Vector3.UP)
	# sun shafts fan down from the sun toward the player
	var visible_rays: bool = ray_mat.albedo_color.a > 0.005
	for i in rays.size():
		var r: MeshInstance3D = rays[i]
		r.visible = visible_rays
		if not visible_rays:
			continue
		var from := c + _sun_dir * 60.0
		var to := c + Vector3((i - 2) * 9.0, -6.0, (i % 2) * 7.0 - 3.0)
		var mid := (from + to) * 0.5
		var len := from.distance_to(to)
		r.global_position = mid
		r.look_at(mid + (to - from).cross(Vector3.UP).normalized() + Vector3(0, 0.001, 0), (to - from).normalized())
		r.scale = Vector3(4.0 + i % 3 * 2.5, len, 1)


func _shooting_stars(dt: float) -> void:
	for s in shooting.duplicate():
		var n: MeshInstance3D = s[0]
		s[2] -= dt
		n.global_position += s[1] * dt
		(n.material_override as StandardMaterial3D).albedo_color.a = clampf(s[2] / 0.4, 0, 1)
		if s[2] <= 0:
			n.queue_free()
			shooting.erase(s)
	if night < 0.6 or cloudy > 0.5 or game.cam == null:
		return
	shoot_t -= dt
	if shoot_t > 0:
		return
	shoot_t = randf_range(3.0, 8.0)
	var n := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(9, 0.35)
	n.mesh = q
	var m := _additive(Color(1, 1, 0.9, 1))
	m.billboard_mode = BaseMaterial3D.BILLBOARD_DISABLED
	n.material_override = m
	n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	n.extra_cull_margin = 400
	add_child(n)
	var c: Vector3 = game.cam.global_position
	var a := randf() * TAU
	var start := c + Vector3(cos(a) * 110, randf_range(55, 85), sin(a) * 110)
	var v := Vector3(-sin(a), -0.35, cos(a)).normalized() * 90.0
	n.global_position = start
	n.look_at(start + v.cross(Vector3.UP), Vector3.UP)
	n.rotation.z = 0
	n.look_at_from_position(start, start + (c - start).normalized(), Vector3.UP)
	n.rotate_object_local(Vector3.FORWARD, atan2(v.y, Vector2(v.x, v.z).length()))
	shooting.append([n, v, 1.1])
