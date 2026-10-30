class_name OpenPlay
extends Node3D
## The open worlds: 5 star challenges (see Level.objectives), big floating names over
## each one so you can find them from far away, the gold gate that opens with 5 stars,
## and the flag that appears when Krag Jr. is beaten.

const STARS_NEEDED := 5

var game
var level: Level
var got := {}                # star id -> true
var stars := {}              # star id -> the spinning star (only while it's out)
var beacons := {}            # star id -> floating name
var flag: Node3D
var t := 0.0


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	for o in level.objectives:
		var b := Label3D.new()
		b.text = "★ " + o.name
		b.font_size = 64
		b.outline_size = 16
		b.fixed_size = true
		b.pixel_size = 0.0009
		b.no_depth_test = true
		b.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		b.modulate = Color("ffe14a")
		b.outline_modulate = Color(0.25, 0.12, 0.02)
		b.position = o.pos + Vector3(0, 3.2, 0)
		add_child(b)
		beacons[o.id] = b
		if o.id in ["tower", "cave", "sky"]:
			_make_star(o.id)


## Called after loading a save: stars already found stay found.
func restore(ids: Array) -> void:
	for id in ids:
		got[String(id)] = true
		if stars.has(id):
			stars[id].queue_free()
			stars.erase(id)
		if beacons.has(id):
			beacons[id].visible = false
	if got.size() >= STARS_NEEDED:
		game.gate_open = true


func count() -> int:
	return got.size()


func objective(id: String) -> Dictionary:
	for o in level.objectives:
		if o.id == id:
			return o
	return {}


## The nearest challenge not done yet (for the hint on screen).
func nearest() -> Dictionary:
	var best := {}
	var bd := 16.0
	var p: Vector3 = game.leader().position
	for o in level.objectives:
		if got.has(o.id):
			continue
		var d := Vector2(o.pos.x - p.x, o.pos.z - p.z).length()
		if d < bd:
			bd = d
			best = o
	return best


static func star_model() -> Node3D:
	var n := Node3D.new()
	if Look.toy:
		var mi := MeshInstance3D.new()
		mi.mesh = _star_mesh()
		mi.material_override = Toys.mat(Color("ffd23a"), true, 0.6)
		n.add_child(mi)
		for s in [-1, 1]:
			var e := MeshInstance3D.new()
			var sm := SphereMesh.new()
			sm.radius = 0.06
			sm.height = 0.16
			e.mesh = sm
			e.material_override = Toys.mat(Color("1e2a44"), false)
			e.position = Vector3(s * 0.12, 0.08, 0.13)
			n.add_child(e)
	else:
		var sp := Sprite3D.new()
		sp.texture = load("res://art/star.png")
		sp.pixel_size = 1.1 / sp.texture.get_height()
		sp.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		sp.shaded = false
		sp.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		n.add_child(sp)
	return n


## A puffy 5-point star (a star outline pushed out front and back).
static func _star_mesh() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var pts := []
	for i in 10:
		var a := PI / 2 + TAU * i / 10.0
		var r := 0.55 if i % 2 == 0 else 0.24
		pts.append(Vector3(cos(a) * r, sin(a) * r, 0))
	for side: float in [1.0, -1.0]:
		var c := Vector3(0, 0, 0.16 * side)
		for i in 10:
			var a: Vector3 = pts[i]
			var b: Vector3 = pts[(i + 1) % 10]
			var nrm: Vector3 = (b - a).cross(c - a).normalized() * side
			st.set_normal(nrm)
			if side > 0:   # Godot's front faces go clockwise
				st.add_vertex(b); st.add_vertex(a); st.add_vertex(c)
			else:
				st.add_vertex(a); st.add_vertex(b); st.add_vertex(c)
	st.generate_normals()
	return st.commit()


func _make_star(id: String) -> void:
	if stars.has(id) or got.has(id):
		return
	var s := star_model()
	s.position = objective(id).pos
	add_child(s)
	stars[id] = s


func _drop_star(id: String, why: String) -> void:
	_make_star(id)
	game.sfx("item")
	game.hud.say("A STAR APPEARED!", why, 2.4)
	game.fx.burst(objective(id).pos, Color("ffe14a"), 18, 5, 6)


func step(dt: float) -> void:
	t += dt
	var l: Hero = game.leader()
	for id in stars.keys():
		var s: Node3D = stars[id]
		s.rotation.y = t * 2.5
		s.position.y = objective(id).pos.y + sin(t * 3.0) * 0.12
		if s.position.distance_to(l.position + Vector3(0, l.height * 0.5, 0)) < 1.3:
			_collect(id)
	# the camp: beat all its bad guys
	if not got.has("camp") and not stars.has("camp"):
		var left := 0
		for f in game.foes:
			if is_instance_valid(f) and f.dying < 0 and f.get_meta("camp", false):
				left += 1
		if left == 0:
			_drop_star("camp", "You beat the whole camp!")
	# the coin hunt
	if not got.has("coins") and not stars.has("coins") and game.coins_taken.size() >= Level.STAR_COINS:
		_drop_star("coins", "%d coins! It's back at the start." % Level.STAR_COINS)
	# Krag Jr. beaten: the flag comes down from the sky
	if game.boss_done and flag == null:
		flag = level.prop("flag", level.flag_pos + Vector3(0, 6, 0), 4.6)
		var tw := create_tween()
		tw.tween_property(flag, "position:y", level.flag_pos.y, 1.0).set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
	if flag and game.boss_done and Vector2(l.position.x - level.flag_pos.x, l.position.z - level.flag_pos.z).length() < 1.3:
		game.clear_world()


func _collect(id: String) -> void:
	got[id] = true
	stars[id].queue_free()
	stars.erase(id)
	if beacons.has(id):
		beacons[id].visible = false
	var o := objective(id)
	game.coins += 10
	game.sfx("clear")
	game.fx.burst(o.pos, Color("ffe14a"), 24, 6, 8)
	game.fx.text(o.pos + Vector3(0, 1.2, 0), "+10", Color("ffd23a"), 48)
	game.speak("We got a star!", "rocco")
	if count() >= STARS_NEEDED:
		open_gate()
	else:
		game.hud.say("STAR %d / %d!" % [count(), STARS_NEEDED], o.name, 2.4)
	game.save_game()
	if count() < STARS_NEEDED:
		game.maybe_funny()


func open_gate() -> void:
	game.gate_open = true
	for c in level.gate:
		level.set_cell(c.x, c.y, c.z, Level.AIR)
		game.fx.burst(Vector3(c) + Vector3(0.5, 0.5, 0.5), Color("ffd23a"), 6, 3)
	game.hud.say("ALL 5 STARS!", "The gold gate is open. Go get Krag Jr.!", 3.0)
	game.fx.shake = 0.6
	game.sfx("boss_down")
