class_name SkyMap
extends Node3D
## The sky map: floating islands (your home and every world) and the rocket you
## steer between them with the thumb stick. Fly over an island and LAND.
## Pushing the stick all the way gives a boost (like sprinting).

const HEIGHT := 7.0          # how high the rocket flies above the islands
const SPEED := 9.0
const BOOST := 15.0
const NEAR := 7.0            # how close (sideways) you must be to land (generous!)
const PULL := 2.2            # over an island with the stick let go, the rocket drifts to its middle
const AUTO_SPEED := 13.0     # autopilot (tap an island or its button)
const BOUNDS := Rect2(-36, -36, 72, 64)

var game
var level: Level
var rocket: Node3D
var vel := Vector3.ZERO
var islands := []            # [{id, pos, node, label}]
var near_id := ""            # the island under the rocket ("" = none)
var landing_t := -1.0
var landing_to := ""
var t := 0.0
var auto_to := ""            # autopilot: flying to this island, then landing
## The islands in button order: home first, then every world (new worlds show up by themselves).
var order: Array = []


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	_island("home", Worlds.HOME_MAP)
	order = ["home"]
	for w in Worlds.ORDER:
		_island(w, Worlds.info(w).map)
		order.append(w)
	rocket = level.prop("rocket", Vector3.ZERO, 3.0)
	var start: Vector3 = Worlds.HOME_MAP
	for isl in islands:
		if isl.id == Boot.from_level:
			start = isl.pos
	rocket.position = start + Vector3(0, HEIGHT, 3.0)
	# far-away clouds below and around
	for i in 40:
		var c := level.prop("cloud", Vector3(randf_range(-60, 60), randf_range(-14, -4), randf_range(-60, 40)), randf_range(4, 9))
		if c is Sprite3D:
			c.billboard = BaseMaterial3D.BILLBOARD_ENABLED


func _tex_mat(name: String, scale: float) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://art/t_%s.png" % name)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	m.uv1_scale = Vector3(scale, scale, scale)
	m.uv1_triplanar = true
	m.roughness = 1.0
	return m


## One floating island: a block of ground with a rocky bottom and a few things on top.
func _island(id: String, pos: Vector3) -> void:
	var n := Node3D.new()
	n.position = pos
	add_child(n)
	var info: Dictionary = Worlds.info(id) if id != "home" else {"top": "grass_top", "side": "grass_side", "short": "HOME"}
	var size := 8.0 if id == "home" else 9.0
	var top := MeshInstance3D.new()
	var tb := BoxMesh.new()
	tb.size = Vector3(size, 0.5, size)
	top.mesh = tb
	top.material_override = _tex_mat(info.top, size / 1.0 * 0.25)
	top.position.y = -0.25
	n.add_child(top)
	var side := MeshInstance3D.new()
	var sb := BoxMesh.new()
	sb.size = Vector3(size - 0.02, 1.6, size - 0.02)
	side.mesh = sb
	side.material_override = _tex_mat("dirt" if id != "dunes" else "sandstone", 0.25 * size)
	side.position.y = -1.3
	n.add_child(side)
	var rock := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = size * 0.62
	cm.bottom_radius = 0.6
	cm.height = 5.0
	cm.radial_segments = 8
	rock.mesh = cm
	rock.material_override = _tex_mat("stone", 1.5)
	rock.position.y = -4.6
	n.add_child(rock)
	# things on top
	var things: Array = {"home": [["tree_a", -2.6, -2.4, 2.6], ["rocket", 2.4, 1.6, 2.0]],
		"meadow": [["tree_a", -2.8, -2.8, 2.8], ["tree_b", 2.6, -2.0, 2.4], ["bush", -1.0, 2.6, 0.8], ["flag", 2.6, 2.6, 2.2]],
		"frost": [["pine_a", -2.6, -2.6, 3.0], ["pine_b", 2.4, -2.4, 2.6], ["snowman", -2.0, 2.4, 1.4], ["flag", 2.6, 2.6, 2.2]],
		"dunes": [["palm", -2.6, -2.4, 3.0], ["cactus_a", 2.4, -2.2, 2.6], ["desert_rock", -1.4, 2.6, 0.8], ["flag", 2.6, 2.6, 2.2]]}[id]
	for th in things:
		level.prop(th[0], pos + Vector3(th[1], 0, th[2]), th[3], true)
	if id == "home":   # a little house
		var house := MeshInstance3D.new()
		var hb := BoxMesh.new()
		hb.size = Vector3(2.4, 1.6, 2.0)
		house.mesh = hb
		house.material_override = _tex_mat("planks", 1.0)
		house.position = Vector3(-0.6, 0.8, 0.6)
		n.add_child(house)
		var roof := MeshInstance3D.new()
		var pm := PrismMesh.new()
		pm.size = Vector3(2.8, 1.0, 2.3)
		roof.mesh = pm
		roof.material_override = _tex_mat("wool_red", 1.0)
		roof.position = Vector3(-0.6, 2.1, 0.6)
		n.add_child(roof)
	var label := Label3D.new()
	label.font_size = 64
	label.outline_size = 14
	label.pixel_size = 0.012
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = true
	label.position.y = 4.4
	n.add_child(label)
	islands.append({"id": id, "pos": pos, "node": n, "label": label})
	_refresh_label(islands.back())


func _refresh_label(isl: Dictionary) -> void:
	var id: String = isl.id
	var l: Label3D = isl.label
	if id == "home":
		l.text = "HOME"
		l.modulate = Color("8ff0a0")
		return
	var info: Dictionary = Worlds.info(id)
	if not Worlds.unlocked(id, game.cleared_w):
		l.text = info.short + "\nLOCKED"
		l.modulate = Color(0.75, 0.75, 0.8)
	elif int(game.cleared_w.get(id, 0)) > 0:
		l.text = info.short + "\nCLEARED!"
		l.modulate = Color("ffd23a")
	else:
		l.text = info.short + "\nNEW!"
		l.modulate = Color.WHITE


func refresh() -> void:
	for isl in islands:
		_refresh_label(isl)


## Which island the rocket is over, and whether you're allowed to land there.
func near_info() -> Dictionary:
	if near_id == "":
		return {}
	if near_id == "home":
		return {"id": "home", "name": "HOME", "ok": true, "why": ""}
	var info: Dictionary = Worlds.info(near_id)
	var ok := Worlds.unlocked(near_id, game.cleared_w)
	var why := "" if ok else "Beat %s first!" % Worlds.info(info.after).short
	return {"id": near_id, "name": info.name, "ok": ok, "why": why}


## Is this island open to land on? (home always is)
func can_land(id: String) -> bool:
	return id == "home" or Worlds.unlocked(id, game.cleared_w)


func short_name(id: String) -> String:
	return "HOME" if id == "home" else String(Worlds.info(id).short)


## Autopilot: fly to an island by itself and land there. Locked worlds say why instead.
func fly_to(id: String) -> bool:
	if landing_t >= 0 or not id in order:
		return false
	if not can_land(id):
		game.sfx("bonk")
		game.hud.say("LOCKED", "Beat %s first!" % Worlds.info(Worlds.info(id).after).short, 1.6)
		return false
	auto_to = id
	game.sfx("swap", -6)
	return true


## A tap on the screen: if it's on (or near) an island or its name, fly there.
func tap_screen(pos: Vector2, cam: Camera3D) -> bool:
	var best := ""
	var bd := 110.0
	for isl in islands:
		for pt in [isl.pos, isl.pos + Vector3(0, 4.4, 0)]:   # the island and its floating name
			if cam.is_position_behind(pt):
				continue
			var d := cam.unproject_position(pt).distance_to(pos)
			if d < bd:
				bd = d
				best = isl.id
	if best == "":
		return false
	fly_to(best)
	return true


func step(dt: float, move: Vector2, boost: bool) -> void:
	t += dt
	if move.length() > 0.25:
		auto_to = ""   # the stick always wins over the autopilot
	if landing_t >= 0:   # going down onto the island
		landing_t += dt
		rocket.position = rocket.position.move_toward(_island_pos(landing_to) + Vector3(0, 0.2, 0), dt * 9.0)
		rocket.rotation = rocket.rotation.lerp(Vector3.ZERO, 1 - exp(-dt * 6))
		if landing_t > 1.0:
			landing_t = -1.0
			game.land_on(landing_to)
		return
	var want := Vector3(move.x, 0, move.y) * (BOOST if boost else SPEED)
	if auto_to != "":
		var to := _island_pos(auto_to) - rocket.position
		to.y = 0
		var d := to.length()
		want = to.normalized() * minf(AUTO_SPEED, d * 2.0 + 2.0)
		if d < 1.2:
			near_id = auto_to
			_begin_land(auto_to)
			return
	elif move.length() < 0.15 and near_id != "" and can_land(near_id):   # gently drift to the middle
		var to := _island_pos(near_id) - rocket.position
		to.y = 0
		want = to * PULL
	vel = vel.lerp(want, 1 - exp(-dt * 2.5))
	rocket.position += vel * dt
	rocket.position.x = clampf(rocket.position.x, BOUNDS.position.x, BOUNDS.end.x)
	rocket.position.z = clampf(rocket.position.z, BOUNDS.position.y, BOUNDS.end.y)
	rocket.position.y = HEIGHT + sin(t * 2.0) * 0.25
	# lean into the turn like a real rocket
	if Look.toy:
		rocket.rotation.x = lerpf(rocket.rotation.x, vel.z / BOOST * 0.6, 1 - exp(-dt * 5))
		rocket.rotation.z = lerpf(rocket.rotation.z, -vel.x / BOOST * 0.6, 1 - exp(-dt * 5))
		if rocket.has_meta("flame"):
			var fl: Node3D = rocket.get_meta("flame")
			fl.scale = Vector3.ONE * (1.0 + (0.6 if boost or auto_to != "" else 0.2) + sin(t * 40) * 0.15)
	elif rocket is Sprite3D:
		rocket.rotation.z = lerpf(rocket.rotation.z, -vel.x / BOOST * 0.5, 1 - exp(-dt * 5))
	if (boost and move.length() > 0.3 or auto_to != "") and int(t * 20) % 2 == 0:
		game.fx.burst(rocket.position + Vector3(0, -0.2, 0), Color(1, 0.8, 0.4), 2, 1.5, -2, 1.2)
	near_id = ""
	var best := NEAR
	for isl in islands:
		var d := Vector2(rocket.position.x - isl.pos.x, rocket.position.z - isl.pos.z).length()
		if d < best:
			best = d
			near_id = isl.id
	for isl in islands:   # the island under you bobs a little
		var n: Node3D = isl.node
		n.scale = Vector3.ONE * (1.0 + (0.03 * sin(t * 6) if isl.id == near_id else 0.0))


func _island_pos(id: String) -> Vector3:
	for isl in islands:
		if isl.id == id:
			return isl.pos
	return Vector3.ZERO


## LAND: returns false (and says why) if that world is still locked.
func land() -> bool:
	var ni := near_info()
	if ni.is_empty() or landing_t >= 0:
		return false
	if not ni.ok:
		game.sfx("bonk")
		game.hud.say("LOCKED", ni.why, 1.6)
		return false
	_begin_land(ni.id)
	return true


func _begin_land(id: String) -> void:
	if landing_t >= 0:
		return
	auto_to = ""
	landing_to = id
	landing_t = 0.0
	game.sfx("item")
