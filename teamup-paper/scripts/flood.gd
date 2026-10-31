class_name Flood
extends Node3D
## POSEIDON FLOOD (from the 2D game): the sea rises over the whole open world, every
## bad guy on the ground is washed away (you get their coins), the team floats on top,
## then it drains. Started from the PAUSE menu with a second tap to be sure, once per
## world visit, never at home or in Krag Jr.'s fight (main.gd `poseidon_tap`).

const RISE := 2.5
const HOLD := 6.0
const DRAIN := 2.5
const TOP := 13.0          # how high the water gets (covers nearly all the ground)

var game
var level: Level
var t := 0.0
var water_y := -1.0
var plane: MeshInstance3D
var mat: StandardMaterial3D
var washed := 0
var done := false


func start(g) -> void:
	game = g
	level = g.level
	plane = MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(level.sx + 300, level.sz + 300)
	plane.mesh = pm
	plane.material_override = material()
	plane.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	plane.position = Vector3(level.sx * 0.5, water_y, level.sz * 0.5)
	add_child(plane)
	mat = plane.material_override
	game.sfx("thunder")
	game.fx.shake = 0.8
	game.hud.say("POSEIDON FLOOD!", "The sea washes the bad guys away!", 2.6)
	game.speak("Poseidon! Flood it!", "rocco")


## The see-through sea (shared with the warm-up so the first flood doesn't stutter).
static func material() -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.2, 0.55, 0.95, 0.62)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.roughness = 0.15
	m.metallic_specular = 0.8
	m.emission_enabled = true
	m.emission = Color(0.05, 0.2, 0.45)
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	return m


func step(dt: float) -> void:
	t += dt
	var top := TOP
	if t < RISE:
		water_y = lerpf(-1.0, top, smoothstep(0.0, 1.0, t / RISE))
	elif t < RISE + HOLD:
		water_y = top + sin(t * 2.2) * 0.12
	elif t < RISE + HOLD + DRAIN:
		water_y = lerpf(top, -1.0, smoothstep(0.0, 1.0, (t - RISE - HOLD) / DRAIN))
	else:
		done = true
		queue_free()
		return
	plane.position.y = water_y
	mat.albedo_color.a = 0.62 + sin(t * 3.0) * 0.05
	# the team floats on top
	for h in [game.leader(), game.follower()]:
		if h.visible and h.position.y < water_y - 0.55:
			h.vel.y = maxf(h.vel.y, 5.0)
			h.on_floor = false
	# bad guys under the water are washed away
	for f in game.foes.duplicate():
		if is_instance_valid(f) and f.alive and f.dying < 0 and not f.is_boss and f.position.y + 0.3 < water_y:
			washed += 1
			game.fx.burst(Vector3(f.position.x, water_y, f.position.z), Color(0.75, 0.9, 1.0), 10, 4, 6)
			f.hurt(99, game)
	if int(t * 6) % 3 == 0 and t < RISE + HOLD:   # splashes around the hero
		var l: Hero = game.leader()
		game.fx.burst(Vector3(l.position.x + randf_range(-6, 6), water_y, l.position.z + randf_range(-6, 6)), Color(0.8, 0.95, 1), 3, 2.5, 4, 0.6)
