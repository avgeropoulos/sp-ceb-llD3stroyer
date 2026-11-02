class_name Mega
extends Node3D
## MEGA EVOLUTION: when the partner is at its final form and the SUPER bar is full, the MEGA
## button lights up. Tapping it plays a short scene (everything freezes, the camera circles,
## a shell of light grows round the partner and bursts) and the MEGA form comes out: bigger,
## gold armour, a crown of light, an aura. For a while its attacks and SUPER are much stronger,
## then it turns back. At Lv 16+ the MEGA form lasts longer.

const TIME := 20.0            # how long the MEGA form lasts
const TIME_HIGH := 30.0       # ... at Lv 16 or more
const HIGH_LEVEL := 16
const SCENE := 2.6            # the transformation scene
const BURST := 1.7            # when the MEGA form bursts out

var game
var t := -1.0                 # >= 0 while the scene plays
var left := 0.0               # MEGA time left (0 = normal)
var shell: MeshInstance3D
var shell_mat: StandardMaterial3D
var cam_yaw0 := 0.0
var back_to := "play"


## The size of the partner's picture (and the MEGA picture is a bit bigger).
static func picture_size(line: String, st: int, mega := false) -> float:
	var size: float = Mons.STAGE_SIZE[st][0]
	if line == "spark":
		size = 1.95 * (1.3 if st >= 1 else 1.0)
	return size * (1.25 if mega else 1.0)


static func final_stage(line: String) -> int:
	return Mons.LINES[line].names.size() - 1


func setup(g) -> void:
	game = g
	shell_mat = StandardMaterial3D.new()
	shell_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	shell_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	shell_mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	shell_mat.albedo_color = Color(1, 0.95, 0.7, 0.6)
	shell_mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	shell = MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = 1.0
	sm.height = 2.0
	sm.radial_segments = 24
	sm.rings = 12
	shell.mesh = sm
	shell.material_override = shell_mat
	shell.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	shell.visible = false
	add_child(shell)


## True when the MEGA button should light up.
func ready() -> bool:
	var m: Hero = game.heroes.mon
	return left <= 0 and t < 0 and game.state == "play" and m.stage == final_stage(m.line) \
		and game.super_charge >= 1.0


func active() -> bool:
	return left > 0


## Starts the MEGA EVOLUTION scene (uses the full SUPER bar).
func start() -> bool:
	if not ready():
		return false
	var m: Hero = game.heroes.mon
	game.super_charge = 0.0
	game.note("MEGA EVOLUTION")
	t = 0.0
	back_to = game.state
	game.state = "mega"
	cam_yaw0 = game.cam_yaw
	shell.visible = true
	shell.position = m.position + Vector3(0, m.height * 0.6, 0)
	shell.scale = Vector3.ONE * 0.2
	game.hud.say("MEGA EVOLUTION!", game.display_name("mon") + " is powering up!", 2.4)
	game.speak(game.display_name("mon").capitalize() + ", mega evolve!", "rocco")
	game.sfx("alert")
	return true


## The transformation scene (the game is frozen while it plays).
func scene_step(dt: float) -> void:
	var m: Hero = game.heroes.mon
	var was := t
	t += dt
	var c := m.position + Vector3(0, m.height * 0.6, 0)
	# the camera circles the partner
	var yaw := cam_yaw0 + t * 1.3
	var dist := 4.5
	game.cam.position = c + Vector3(sin(yaw) * dist, 1.2, cos(yaw) * dist)
	game.cam.look_at(c, Vector3.UP)
	# a shell of light grows and pulses, then bursts
	if t < BURST:
		var k := t / BURST
		shell.position = c
		shell.scale = Vector3.ONE * (0.4 + k * 1.3 + sin(t * 30) * 0.05 * k)
		shell_mat.albedo_color = Color(1, 0.95 - k * 0.2, 0.7 + k * 0.3, 0.35 + k * 0.45)
		m.squash = Vector2.ONE * (1.0 + sin(t * 25) * 0.08 * k)
		if int(t * 10) != int(was * 10):
			game.fx.burst(c + Vector3(randf_range(-1, 1), randf_range(-0.5, 1), randf_range(-1, 1)), Color.from_hsv(randf(), 0.4, 1), 4, 3, 2)
	if was < BURST and t >= BURST:
		shell.visible = false
		m.set_form(m.line, m.stage, true)
		m.squash = Vector2(1.4, 1.4)
		game.fx.shake = 1.0
		game.sfx("boss_down")
		game.sfx("clear")
		for i in 8:
			game.fx.burst(c + Vector3(randf_range(-1, 1), randf_range(-0.5, 1.2), randf_range(-1, 1)), Color.from_hsv(randf(), 0.5, 1), 14, 6, 6)
		game.fx.ring(m.position, 5.0)
		left = TIME_HIGH if game.mon_level() >= HIGH_LEVEL else TIME
		game.hud.say("MEGA " + Mons.name_of(m.line, m.stage) + "!", "Super strong for %d seconds!" % int(left), 2.4)
	m.animate(dt)
	if t >= SCENE:
		t = -1.0
		game.state = back_to


## While MEGA: count down, sparkle now and then, turn back at the end.
func step(dt: float) -> void:
	if left <= 0:
		return
	left -= dt
	var m: Hero = game.heroes.mon
	if randf() < dt * 6:
		game.fx.burst(m.position + Vector3(randf_range(-0.6, 0.6), randf_range(0.2, 1.6), randf_range(-0.6, 0.6)), Color("ffe36a"), 2, 1.5, 2, 0.6)
	if left <= 0:
		end()


func end() -> void:
	left = 0.0
	var m: Hero = game.heroes.mon
	if m.mega:
		m.set_form(m.line, m.stage)
		game.fx.burst(m.position + Vector3(0, 0.8, 0), Color.WHITE, 12, 4, 4)
		game.sfx("swap")
		game.hud.say(game.display_name("mon") + "!", "back to normal", 1.4)


## Extra damage for the partner's attacks and SUPER while MEGA.
func bonus() -> int:
	return 2 if left > 0 else 0
