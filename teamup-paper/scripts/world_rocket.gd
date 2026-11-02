class_name WorldRocket
extends Node3D
## Leave a world whenever you like, by rocket:
##  * a BIG rocket waits on a launch pad near the start of every open world: walk onto the pad;
##  * or pick "ROCKET: LEAVE" in the pause menu and the rocket swoops down to wherever you are.
## Either way the team climbs in, it blasts off, and you're back on the SKY MAP over this world.
## Stars found stay found (they're saved as soon as you get them).

const SIZE := 5.0

var game
var level: Level
var pad := Vector3.ZERO
var rocket: Node3D
var ring: MeshInstance3D
var leaving := -1.0          # >= 0 while blasting off
var swoop := false           # the rocket is coming down to you (from the pause menu)
var from := Vector3.ZERO
var t := 0.0
var near := false            # the hero is standing on the pad (the HUD shows BLAST OFF!)


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	pad = _pad_spot()
	var base := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 1.8
	cm.bottom_radius = 1.9
	cm.height = 0.18
	base.mesh = cm
	base.material_override = Toys.mat(Color("8a8e96"))
	base.position = pad + Vector3(0, 0.09, 0)
	add_child(base)
	ring = MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 1.45
	tm.outer_radius = 1.62
	ring.mesh = tm
	ring.material_override = Toys.mat(Color("ffcf33"), false, 0.8)
	ring.position = pad + Vector3(0, 0.2, 0)
	add_child(ring)
	rocket = level.prop("rocket", pad + Vector3(0, 0.18, 0), SIZE)
	var label := Label3D.new()
	label.text = "ROCKET\nleave any time!"
	label.font_size = 44
	label.outline_size = 12
	label.pixel_size = 0.009
	label.modulate = Color("ffd23a")
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.position = pad + Vector3(0, SIZE + 1.0, 0)
	add_child(label)


## A flat spot a few blocks from the start, with nothing above it.
func _pad_spot() -> Vector3:
	var s: Vector3 = level.start_pos
	# things the pad must keep away from (so walking a quest home never takes off by accident)
	var avoid: Array = [level.dog_home, level.dog_spot]
	for o in level.objectives:
		avoid.append(o.pos)
	for off: Vector2i in [Vector2i(5, 3), Vector2i(-5, 3), Vector2i(5, -4), Vector2i(-5, -4), Vector2i(6, 0), Vector2i(-6, 0),
			Vector2i(0, 6), Vector2i(0, -6), Vector2i(7, 5), Vector2i(-7, 5), Vector2i(7, -6), Vector2i(-7, -6)]:
		var x: int = floori(s.x) + off.x
		var z: int = floori(s.z) + off.y
		var g: int = level.ground_at(x, z)
		var ok := absf(g - s.y) < 0.6
		for a in avoid:
			if a is Vector3 and a != Vector3.ZERO and Vector2(a.x - x, a.z - z).length() < 4.5:
				ok = false
		for dx in range(-1, 2):
			for dz in range(-1, 2):
				if level.ground_at(x + dx, z + dz) != g:
					ok = false
				for y in range(g, g + 6):
					if level.get_cell(x + dx, y, z + dz) != Level.AIR:
						ok = false
		if ok:
			return Vector3(x + 0.5, g, z + 0.5)
	return s + Vector3(5, 0, 3)


## True when the hero walks onto the pad.
func at_pad(p: Vector3) -> bool:
	return Vector2(p.x - pad.x, p.z - pad.z).length() < 1.4 and absf(p.y - pad.y) < 1.5


func busy() -> bool:
	return leaving >= 0 or swoop


## Blast off from the pad.
func take_off() -> void:
	if busy():
		return
	leaving = 0.0
	from = rocket.position
	_board()


## From the pause menu: the rocket swoops down onto the hero, then blasts off.
func call_down() -> void:
	if busy():
		return
	swoop = true
	t = 0.0
	var l: Vector3 = game.leader().position
	from = l + Vector3(0, 0.05, 0)
	rocket.position = from + Vector3(0, 18, 0)
	game.hud.say("HERE COMES THE ROCKET!", "", 1.2)
	game.sfx("item")


func _board() -> void:
	for h in game.heroes.values():
		h.visible = false
		if h.shadow:
			h.shadow.visible = false
	game.hud.say("BLAST OFF!", "Back to the sky map", 1.6)
	game.speak("Here we go!", "rocco")
	game.sfx("jump2")
	game.fx.shake = 0.5


## Runs every frame while busy (the game skips everything else).
func step(dt: float) -> void:
	t += dt
	ring.scale = Vector3.ONE * (1.0 + sin(t * 4) * 0.06)
	if swoop:
		var target := from
		rocket.position = rocket.position.move_toward(target, dt * 22.0)
		if rocket.position.distance_to(target) < 0.05:
			swoop = false
			leaving = 0.0
			_board()
		return
	if leaving < 0:
		return
	leaving += dt
	rocket.position = from + Vector3(0, pow(leaving, 2.2) * 14.0, 0)
	rocket.rotation.y += dt * 3.0
	if int(leaving * 30) % 2 == 0:
		game.fx.burst(rocket.position + Vector3(0, -0.3, 0), Color(1, 0.7, 0.3), 4, 2.5, -3, 1.0)
	if leaving > 1.6:
		leaving = -1.0
		game.leave_by_rocket()
