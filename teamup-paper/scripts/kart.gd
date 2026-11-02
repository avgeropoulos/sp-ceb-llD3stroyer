class_name KartRace
extends Node3D
## RAINBOW ROAD: a kart race on a glowing rainbow road floating in space.
## You (the team leader) race 5 karts (Grumbler, Penguin, Krag Jr., Spotty, Zoomy) for 3 laps.
##  * The kart drives by itself: the stick steers left / right.
##  * JUMP = hop. Hold JUMP while steering to DRIFT (sparks!), let go for a mini boost.
##  * ACTION = use your item: MUSHROOM (boost), BANANA (drop behind), GREEN SHELL (fire ahead).
##  * Rainbow ? boxes give items, orange arrows are boost pads, pink ramps launch you
##    (press JUMP in the air for a trick = boost when you land).
##  * Fall off the road and FLUFFY the cloud fishes you back up.
## Everything lives in "track space": s = how far along the road, lat = how far left (-)
## or right (+) of the middle, y = height above the road. That keeps the karts on the
## twisty road without any block collisions, and makes the AI easy.
## Built far away from everything (ORIGIN). The game (main.gd) calls step() while
## state == "kart" and the HUD calls draw(); when it's over, game.kart_done(place, prize).

const ORIGIN := Vector3(3000, 260, 3000)
const W := 11.0              # road width
const LAPS := 3
const TOP := 25.0            # your kart's top speed
const BOOST_SPEED := 38.0
const GRAV := 34.0
const ITEMS := ["mushroom", "banana", "shell"]
const PRIZES := [50, 20, 10, 5, 5, 5]
const RIVALS := [
	{"id": "grumbler", "name": "GRUMBLER", "color": Color("8a5bc4"), "top": 23.4},
	{"id": "penguin", "name": "PENGUIN", "color": Color("2c9ad8"), "top": 23.8},
	{"id": "kragjr", "name": "KRAG JR.", "color": Color("4cb048"), "top": 24.2},
	{"id": "spotty", "name": "SPOTTY", "color": Color("ff6fa8"), "top": 23.0},
	{"id": "zoomy", "name": "ZOOMY", "color": Color("ffb020"), "top": 24.0},
]
const RAMPS := [118.0, 330.0]                       # s of each ramp
const PADS := [[60.0, -2.0], [205.0, 2.5], [275.0, 0.0], [395.0, -2.5]]   # boost pads [s, lat]
const BOX_ROWS := [88.0, 245.0, 360.0]              # rainbow ? box rows

var game
var back := "map"            # where to go after the race
var phase := "count"         # count -> race -> podium
var t := 0.0
var count_t := 0.0
var race_t := 0.0
var autopilot := false
# the road, sampled every 1 unit
var P := PackedVector3Array()
var F := PackedVector3Array()
var R := PackedVector3Array()
var K := PackedFloat32Array()   # signed curvature (how hard the road turns here)
var L := 0.0
var n := 0
var racers: Array = []
var you: Dictionary
var boxes: Array = []        # {s, lat, node, cool}
var bananas: Array = []      # {s, lat, node}
var shells: Array = []       # {s, lat, owner, node, life}
var finish_order: Array = []
var place := 0               # your final place (1 = first)
var prize := 0
var star_won := false
var env: Environment
var fluffy: Node3D           # the cloud that holds the lights and fishes karts back
var lights: Array = []
var podium: Node3D
var cam_pos := Vector3.ZERO
var cam_look := Vector3.ZERO
var note := ""
var note_t := 0.0
var road_mat: ShaderMaterial
var podium_t := 0.0


func start(g, from: String) -> void:
	game = g
	back = from
	autopilot = game.bot
	position = Vector3.ZERO
	_build_track()
	_build_space()
	_build_road()
	_build_props()
	_make_racers()
	_make_fluffy()
	phase = "count"
	count_t = 0.0
	var p0: Vector3 = _world(you)
	cam_pos = p0 + Vector3(0, 30, 30)
	cam_look = p0
	game.play_music("silly")
	game.sfx("alert", -4)


# ------------------------------------------------------------------ the track
func _build_track() -> void:
	# a big twisty loop with hills: control points around the space station
	var pts := [Vector3(0, 0, 0), Vector3(36, 3, -14), Vector3(70, 9, -2), Vector3(86, 14, 30), Vector3(70, 10, 62),
		Vector3(40, 4, 70), Vector3(22, 0, 52), Vector3(0, -4, 64), Vector3(-30, -2, 84), Vector3(-62, 4, 66),
		Vector3(-74, 10, 30), Vector3(-58, 12, -4), Vector3(-36, 6, -26), Vector3(-14, 2, -18)]
	var c := Curve3D.new()
	c.bake_interval = 0.5
	var m := pts.size()
	for i in m + 1:
		var p: Vector3 = pts[i % m]
		var tan: Vector3 = (pts[(i + 1) % m] - pts[(i - 1 + m) % m]) * 0.22
		c.add_point(ORIGIN + p, -tan, tan)
	L = floorf(c.get_baked_length())
	n = int(L)
	P.resize(n)
	for i in n:
		P[i] = c.sample_baked(float(i), true)
	F.resize(n)
	R.resize(n)
	K.resize(n)
	for i in n:
		var f := (P[(i + 1) % n] - P[(i - 1 + n) % n])
		F[i] = f.normalized()
		var flat := Vector3(f.x, 0, f.z).normalized()
		R[i] = Vector3(-flat.z, 0, flat.x)   # right of the road (flat, no banking)
	for i in n:
		var a := Vector2(F[(i - 2 + n) % n].x, F[(i - 2 + n) % n].z)
		var b := Vector2(F[(i + 2) % n].x, F[(i + 2) % n].z)
		K[i] = a.angle_to(b) / 4.0   # + = turning right


func _idx(s: float) -> int:
	return int(floorf(fposmod(s, L))) % n


## A point on the road: s along, lat across, y up.
func at(s: float, lat: float, y := 0.0) -> Vector3:
	s = fposmod(s, L)
	var i := int(floorf(s)) % n
	var j := (i + 1) % n
	var k := s - floorf(s)
	var p := P[i].lerp(P[j], k)
	var r := R[i].lerp(R[j], k).normalized()
	return p + r * lat + Vector3(0, y + 0.05, 0)


func _world(rc: Dictionary) -> Vector3:
	return at(rc.s, rc.lat, rc.y)


# ------------------------------------------------------------------ space, road, props
func _build_space() -> void:
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var pm := PanoramaSkyMaterial.new()
	pm.panorama = _star_texture()
	sky.sky_material = pm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.75, 0.72, 0.95)
	env.ambient_light_energy = 1.0
	env.glow_enabled = true
	env.glow_intensity = 0.9
	env.glow_bloom = 0.15
	env.glow_hdr_threshold = 1.1
	env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	game.cam.environment = env
	game.cam.far = 2500.0
	# planets far away
	var planets := [[Vector3(-260, 60, -380), 70.0, Color("ff8a5a")], [Vector3(380, -40, 160), 45.0, Color("6ad0ff")],
		[Vector3(120, 140, -320), 26.0, Color("c88aff")], [Vector3(-320, -90, 260), 34.0, Color("8cffb0")]]
	for pl in planets:
		var mi := MeshInstance3D.new()
		var sm := SphereMesh.new()
		sm.radius = pl[1]
		sm.height = pl[1] * 2
		mi.mesh = sm
		var m := StandardMaterial3D.new()
		m.albedo_color = pl[2]
		m.emission_enabled = true
		m.emission = Color(pl[2]).darkened(0.35)
		mi.material_override = m
		mi.position = ORIGIN + pl[0]
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mi)
	var ring := MeshInstance3D.new()   # rings round the big planet
	var tm := TorusMesh.new()
	tm.inner_radius = 95
	tm.outer_radius = 125
	ring.mesh = tm
	var rm := StandardMaterial3D.new()
	rm.albedo_color = Color(1, 0.85, 0.6, 0.55)
	rm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	rm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	ring.material_override = rm
	ring.position = ORIGIN + Vector3(-260, 60, -380)
	ring.rotation = Vector3(0.35, 0, 0.2)
	add_child(ring)


func _star_texture() -> Texture2D:
	var w := 1024
	var h := 512
	var img := Image.create(w, h, false, Image.FORMAT_RGB8)
	img.fill(Color(0.02, 0.01, 0.07))
	var rng := RandomNumberGenerator.new()
	rng.seed = 64
	for b in 7:   # soft nebula clouds
		var cx := rng.randi_range(0, w - 1)
		var cy := rng.randi_range(80, h - 80)
		var rad := rng.randi_range(40, 90)
		var col := Color.from_hsv(rng.randf_range(0.55, 0.95), 0.7, 0.35)
		for y in range(maxi(0, cy - rad), mini(h, cy + rad)):
			for x in range(cx - rad, cx + rad):
				var d := Vector2(x - cx, y - cy).length() / rad
				if d < 1.0:
					var xx := posmod(x, w)
					var c0 := img.get_pixel(xx, y)
					img.set_pixel(xx, y, c0.lerp(col, (1.0 - d) * (1.0 - d) * 0.6))
	for i in 2600:
		var x := rng.randi_range(0, w - 1)
		var y := rng.randi_range(0, h - 1)
		var br := rng.randf_range(0.5, 1.0)
		var tint := Color.from_hsv(rng.randf(), rng.randf_range(0.0, 0.3), br)
		img.set_pixel(x, y, tint)
		if br > 0.95 and x + 1 < w:
			img.set_pixel(x + 1, y, tint.darkened(0.3))
	return ImageTexture.create_from_image(img)


const ROAD_SHADER := """
shader_type spatial;
render_mode unshaded, cull_disabled;
uniform float speed = 0.35;
vec3 hsv(float h) {
	vec3 k = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
	return k;
}
void fragment() {
	float across = UV.x;                      // 0..1 over the road
	float along = UV.y;                       // distance along the road
	float band = floor(across * 7.0) / 7.0;
	vec3 c = hsv(fract(band * 0.86 + along * 0.004 - TIME * speed * 0.2));
	c = mix(c, vec3(1.0), 0.04);
	c *= 0.8 + 0.2 * sin(along * 0.6 - TIME * 3.0);   // light ripples running down the road
	float stripe = smoothstep(0.42, 0.5, abs(fract(along * 0.25 - TIME * speed * 2.0) - 0.5));
	c += vec3(0.25) * stripe * step(0.47, abs(across - 0.5)) ;
	float edge = smoothstep(0.46, 0.5, abs(across - 0.5));
	c = mix(c, vec3(1.6, 1.6, 1.8), edge);
	ALBEDO = c * 0.95;
	ALPHA = 0.93;
}
"""


func _build_road() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	for i in n:
		var j := (i + 1) % n
		var a0 := at(i, -W * 0.5, -0.05)
		var a1 := at(i, W * 0.5, -0.05)
		var b0 := at(i + 1, -W * 0.5, -0.05)
		var b1 := at(i + 1, W * 0.5, -0.05)
		var v0 := float(i)
		var v1 := float(i + 1)
		for q in [[a0, Vector2(0, v0)], [a1, Vector2(1, v0)], [b1, Vector2(1, v1)], [a0, Vector2(0, v0)], [b1, Vector2(1, v1)], [b0, Vector2(0, v1)]]:
			st.set_uv(q[1])
			st.add_vertex(q[0] - ORIGIN)
	var road := MeshInstance3D.new()
	road.mesh = st.commit()
	road_mat = ShaderMaterial.new()
	var sh := Shader.new()
	sh.code = ROAD_SHADER
	road_mat.shader = sh
	road.material_override = road_mat
	road.position = ORIGIN
	road.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(road)
	# glowing posts along both edges
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	var bm := BoxMesh.new()
	bm.size = Vector3(0.22, 0.9, 0.22)
	mm.mesh = bm
	var cnt := int(n / 4) * 2
	mm.instance_count = cnt
	var k := 0
	for i in range(0, n - 3, 4):
		for side in [-1.0, 1.0]:
			if k >= cnt:
				break
			mm.set_instance_transform(k, Transform3D(Basis.IDENTITY, at(i, side * (W * 0.5 + 0.15), 0.4) - ORIGIN))
			mm.set_instance_color(k, Color.from_hsv(fmod(i * 0.01, 1.0), 0.5, 1.0))
			k += 1
	mm.visible_instance_count = k
	var posts := MultiMeshInstance3D.new()
	posts.multimesh = mm
	var pmat := StandardMaterial3D.new()
	pmat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	pmat.vertex_color_use_as_albedo = true
	pmat.albedo_color = Color(1.4, 1.4, 1.4)
	posts.material_override = pmat
	posts.position = ORIGIN
	add_child(posts)


func _glow(c: Color, alpha := 1.0) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(c.r * 1.3, c.g * 1.3, c.b * 1.3, alpha)
	if alpha < 1.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	return m


func _mesh_at(mesh: Mesh, mat: Material, pos: Vector3, basis := Basis.IDENTITY) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat
	mi.transform = Transform3D(basis, pos)
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(mi)
	return mi


func _road_basis(s: float) -> Basis:
	var i := _idx(s)
	return Basis.looking_at(-F[i], Vector3.UP)   # -Z points backwards, so +Z points along the road


func _build_props() -> void:
	# the finish line: a checkered strip and a glowing arch
	var img := Image.create(8, 2, false, Image.FORMAT_RGB8)
	for x in 8:
		for y in 2:
			img.set_pixel(x, y, Color.WHITE if (x + y) % 2 == 0 else Color(0.08, 0.08, 0.1))
	var cm := StandardMaterial3D.new()
	cm.albedo_texture = ImageTexture.create_from_image(img)
	cm.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	cm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	var q := QuadMesh.new()
	q.size = Vector2(W, 1.4)
	q.orientation = PlaneMesh.FACE_Y
	_mesh_at(q, cm, at(0.0, 0.0, 0.03), _road_basis(0.0))
	for side in [-1.0, 1.0]:
		_mesh_at(Toys.cyl(0.35, 0.35, 8.0, 10), _glow(Color("ffd23a")), at(0.0, side * (W * 0.5 + 0.6), 4.0))
	var bar := BoxMesh.new()
	bar.size = Vector3(W + 2.4, 1.3, 0.4)
	_mesh_at(bar, _glow(Color("ff4fa8")), at(0.0, 0.0, 8.0), _road_basis(0.0))
	var sign := Label3D.new()
	sign.text = "RAINBOW ROAD"
	sign.font_size = 96
	sign.outline_size = 22
	sign.pixel_size = 0.012
	sign.modulate = Color.WHITE
	sign.outline_modulate = Color("5a1a8a")
	sign.transform = Transform3D(_road_basis(0.0).rotated(Vector3.UP, PI), at(0.0, 0.0, 8.0) - F[0] * 0.3)
	add_child(sign)
	# boost pads: glowing orange arrows on the road
	for pd in PADS:
		var b := _road_basis(pd[0])
		var pad := BoxMesh.new()
		pad.size = Vector3(3.0, 0.06, 3.2)
		_mesh_at(pad, _glow(Color("ff8a1f")), at(pd[0], pd[1], 0.05), b)
		for k in 2:
			var arm := BoxMesh.new()
			arm.size = Vector3(0.3, 0.08, 1.7)
			for sgn in [-1.0, 1.0]:
				var ab := b.rotated(b.y, sgn * 0.7)
				_mesh_at(arm, _glow(Color("fff2a8")), at(pd[0] - 0.4 + k * 1.2, pd[1] + sgn * 0.45, 0.1), ab)
	# ramps: pink wedges
	for rs in RAMPS:
		var pm := PrismMesh.new()
		pm.size = Vector3(3.0, 1.2, W * 0.8)
		pm.left_to_right = 1.0   # the slope rises along +X, turned to point down the road
		_mesh_at(pm, _glow(Color("ff6fd8")), at(rs - 1.5, 0.0, 0.6), _road_basis(rs) * Basis(Vector3.UP, -PI * 0.5))
	# rainbow ? boxes
	for rs in BOX_ROWS:
		for lat in [-3.6, -1.2, 1.2, 3.6]:
			var node := _item_box()
			node.position = at(rs, lat, 1.0)
			add_child(node)
			boxes.append({"s": rs, "lat": lat, "node": node, "cool": 0.0})
	# podium (shown at the end), on a little platform past the finish line
	podium = Node3D.new()
	podium.position = at(-14.0, 0.0, 0.0) + R[_idx(-14.0)] * 14.0
	podium.visible = false
	add_child(podium)
	var heights := [3.0, 2.0, 1.3]
	var xs := [0.0, -2.6, 2.6]
	var cols := [Color("ffd23a"), Color("dcdce2"), Color("d08a4a")]
	for k in 3:
		var bb := BoxMesh.new()
		bb.size = Vector3(2.4, heights[k], 2.4)
		var mi := MeshInstance3D.new()
		mi.mesh = bb
		mi.material_override = Toys.mat(cols[k], true, 0.3)
		mi.position = Vector3(xs[k], heights[k] * 0.5, 0)
		podium.add_child(mi)
		var num := Label3D.new()
		num.text = str(k + 1)
		num.font_size = 120
		num.outline_size = 20
		num.pixel_size = 0.01
		num.position = Vector3(xs[k], heights[k] * 0.5, 1.25)
		podium.add_child(num)
	var base := BoxMesh.new()
	base.size = Vector3(10, 0.4, 7)
	var bmi := MeshInstance3D.new()
	bmi.mesh = base
	bmi.material_override = _glow(Color("6a3cc8"))
	bmi.position = Vector3(0, -0.2, 0)
	podium.add_child(bmi)


func _item_box() -> Node3D:
	var n0 := Node3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(1.1, 1.1, 1.1)
	var mi := MeshInstance3D.new()
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(1, 1, 1, 0.55)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.emission_enabled = true
	m.emission = Color("9a6cff")
	m.emission_energy_multiplier = 1.2
	mi.material_override = m
	n0.add_child(mi)
	var lb := Label3D.new()
	lb.text = "?"
	lb.font_size = 110
	lb.outline_size = 18
	lb.pixel_size = 0.008
	lb.modulate = Color("ffe14a")
	lb.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	n0.add_child(lb)
	return n0


# ------------------------------------------------------------------ karts
func _make_racers() -> void:
	var lead: String = game.leader().who if game.leader() else "rocco"
	if lead == "mon":
		lead = "rocco"
	var list := [{"id": lead, "name": Team.info(lead).get("name", "ROCCO") if Team.INFO.has(lead) else "YOU", "color": Color("e8413b"), "top": TOP, "you": true}]
	for r in RIVALS:
		list.append(r)
	# start grid: 2 per row, you at the back so there's someone to pass
	var order := [1, 2, 3, 4, 5, 0]
	for slot in order.size():
		var d: Dictionary = list[order[slot]]
		var row := slot / 2
		var rc := {"id": d.id, "name": d.name, "you": d.get("you", false), "top": d.top, "s": 14.0 - row * 4.0,
			"lat": -2.4 if slot % 2 == 0 else 2.4, "lap": 0, "speed": 0.0, "ang": 0.0, "vy": 0.0, "y": 0.0, "air": false,
			"spin": 0.0, "boost": 0.0, "item": "", "fall": -1.0, "rescue": -1.0, "done": false, "finish": 0.0,
			"drift": 0.0, "drift_dir": 0.0, "trick": false, "item_t": randf_range(1.0, 3.0), "aim_lat": 0.0, "place": slot + 1}
		rc["node"] = _kart_model(d.id, d.color)
		add_child(rc.node)
		racers.append(rc)
		if rc.you:
			you = rc
	for rc in racers:
		_place(rc, 1.0)


func _kart_model(who: String, col: Color) -> Node3D:
	var k := Node3D.new()
	var body := Node3D.new()
	body.name = "Body"
	k.add_child(body)
	Toys.part(body, Toys.box(Vector3(1.5, 0.45, 2.3)), Vector3(0, 0.45, 0), col)
	Toys.part(body, Toys.box(Vector3(1.1, 0.35, 0.8)), Vector3(0, 0.55, -1.2), col.lightened(0.2))   # nose
	Toys.part(body, Toys.box(Vector3(1.7, 0.12, 0.4)), Vector3(0, 0.35, -1.55), Color("34343e"))   # bumper
	Toys.part(body, Toys.box(Vector3(1.6, 0.1, 0.5)), Vector3(0, 1.15, 1.15), col.darkened(0.2))   # spoiler
	for sx in [-0.55, 0.55]:
		Toys.part(body, Toys.box(Vector3(0.1, 0.5, 0.1)), Vector3(sx, 0.9, 1.15), Color("34343e"), Vector3.ONE, Vector3.ZERO, false)
		Toys.part(body, Toys.cyl(0.11, 0.11, 0.5, 8), Vector3(sx * 0.6, 0.55, 1.35), Color("b8c4cf"), Vector3.ONE, Vector3(PI / 2, 0, 0))   # exhaust
	var flames := Node3D.new()
	flames.name = "Flames"
	body.add_child(flames)
	for sx in [-0.33, 0.33]:
		Toys.part(flames, Toys.cyl(0.0, 0.16, 0.7, 8), Vector3(sx, 0.55, 1.85), Color("ffb02e"), Vector3.ONE, Vector3(-PI / 2, 0, 0), false, 2.0)
	flames.visible = false
	for wx in [-0.85, 0.85]:
		for wz in [-0.8, 0.8]:
			Toys.part(body, Toys.cyl(0.36, 0.36, 0.32, 14), Vector3(wx, 0.36, wz), Color("1e1e26"), Vector3.ONE, Vector3(0, 0, PI / 2))
			Toys.part(body, Toys.cyl(0.18, 0.18, 0.34, 10), Vector3(wx, 0.36, wz), Color("ffd23a"), Vector3.ONE, Vector3(0, 0, PI / 2), false)
	Toys.part(body, Toys.cyl(0.22, 0.22, 0.05, 12), Vector3(0, 1.0, -0.45), Color("34343e"), Vector3.ONE, Vector3(1.0, 0, 0), false)   # wheel
	var sparks := Node3D.new()
	sparks.name = "Sparks"
	body.add_child(sparks)
	for wx in [-0.85, 0.85]:
		Toys.part(sparks, Toys.sph(0.18), Vector3(wx, 0.15, 1.0), Color("7fd8ff"), Vector3.ONE, Vector3.ZERO, false, 3.0)
	sparks.visible = false
	# the driver sits in the middle
	var drv: Node3D
	var toy_ok := Look.toy and (Team.INFO.has(who) or who in ["grumbler", "penguin", "kragjr"])
	if toy_ok:
		drv = Toys.build(who)
		drv.scale = Vector3.ONE * (0.75 if who != "kragjr" else 0.6)
		drv.rotation.y = PI   # toys face +Z; the kart's front is -Z
		drv.position = Vector3(0, 0.62 if who != "kragjr" else 0.2, 0.2)
	else:
		var s := Sprite3D.new()
		var pic := _driver_pic(who)
		s.texture = load("res://art/%s.png" % pic)
		s.pixel_size = 1.35 / s.texture.get_height()
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.shaded = false
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		s.position = Vector3(0, 1.25, 0.25)
		drv = s
	body.add_child(drv)
	return k


func _driver_pic(who: String) -> String:
	var opts := {"grumbler": "grumbler_1", "penguin": "penguin_1", "kragjr": "kragjr_1", "zoomy": "zoomy_1", "spotty": "spotty_idle"}
	var pic: String = opts.get(who, who + "_idle")
	if not ResourceLoader.exists("res://art/%s.png" % pic):
		var fr: Dictionary = Team.frames(who)
		if fr.has("idle"):
			pic = fr.idle[0]
		else:
			pic = "rocco_idle"
	return pic


func _place(rc: Dictionary, k: float) -> void:
	var nd: Node3D = rc.node
	var pos := _world(rc)
	var i := _idx(rc.s)
	var fwd := F[i].rotated(Vector3.UP, -rc.ang)
	var b := Basis.looking_at(fwd, Vector3.UP)
	if rc.spin > 0:
		b = b.rotated(Vector3.UP, rc.spin * 14.0)
	nd.transform = Transform3D(b, pos)
	var body: Node3D = nd.get_node("Body")
	body.rotation.z = lerpf(body.rotation.z, -rc.ang * 0.5 if rc.drift > 0 else 0.0, 1 - exp(-k * 8))
	body.position.y = absf(sin(t * 30.0)) * 0.03 if rc.speed > 3 else 0.0
	body.get_node("Flames").visible = rc.boost > 0
	body.get_node("Sparks").visible = rc.drift > 0.5


# ------------------------------------------------------------------ FLUFFY the cloud
func _make_fluffy() -> void:
	fluffy = Node3D.new()
	add_child(fluffy)
	for p in [Vector3(0, 0, 0), Vector3(0.7, 0.1, 0), Vector3(-0.7, 0.1, 0), Vector3(0.3, 0.45, 0), Vector3(-0.35, 0.4, 0.1)]:
		Toys.part(fluffy, Toys.sph(0.6), p, Color("f6f8ff"), Vector3.ONE, Vector3.ZERO, true)
	for sx in [-0.22, 0.22]:
		Toys.part(fluffy, Toys.sph(0.1), Vector3(sx, 0.15, 0.55), Color("1e2a44"), Vector3.ONE, Vector3.ZERO, false)
	Toys.part(fluffy, Toys.box(Vector3(0.9, 0.08, 0.1)), Vector3(0, 0.35, 0.56), Color("34343e"), Vector3.ONE, Vector3.ZERO, false)   # goggles strap
	# the start lights on a fishing rod
	Toys.part(fluffy, Toys.cyl(0.04, 0.04, 1.6, 6), Vector3(0, -1.0, 0.3), Color("a8743f"), Vector3.ONE, Vector3.ZERO, false)
	Toys.part(fluffy, Toys.box(Vector3(0.5, 1.5, 0.3)), Vector3(0, -2.4, 0.3), Color("34343e"))
	for k in 3:
		var l := Toys.part(fluffy, Toys.sph(0.17), Vector3(0, -1.9 - k * 0.45, 0.48), Color(0.25, 0.08, 0.08), Vector3.ONE, Vector3.ZERO, false)
		l.material_override = _glow(Color(0.3, 0.1, 0.1))
		lights.append(l)
	fluffy.position = at(18.0, 0.0, 6.0)
	fluffy.scale = Vector3.ONE * 1.4


func _set_light(k: int, c: Color) -> void:
	(lights[k] as MeshInstance3D).material_override = _glow(c)


# ------------------------------------------------------------------ playing
func step(dt: float) -> void:
	t += dt
	note_t = maxf(0, note_t - dt)
	for b in boxes:
		b.node.rotation.y = t * 2.0
		b.node.position.y = at(b.s, b.lat, 1.0).y + sin(t * 3 + b.lat) * 0.15
		if b.cool > 0:
			b.cool -= dt
			if b.cool <= 0:
				b.node.visible = true
	match phase:
		"count":
			_countdown(dt)
		"race":
			race_t += dt
			for rc in racers:
				_drive(rc, dt)
			_update_items(dt)
			_rank()
			if you.done and race_t - you.finish > 1.6:
				_start_podium()
		"podium":
			_podium_step(dt)
	for rc in racers:
		_place(rc, dt)
	_fluffy_step(dt)
	_camera(dt)


## 3 - 2 - 1 - GO! FLUFFY's lights go red, red, red, green.
func _countdown(dt: float) -> void:
	var before := count_t
	count_t += dt
	for k in 3:
		var at_t := 0.5 + k * 0.9
		if before < at_t and count_t >= at_t:
			_set_light(k, Color("ff3b3b"))
			game.sfx("bonk", -2)
			note = str(3 - k)
			note_t = 0.85
	if before < 3.2 and count_t >= 3.2:
		for k in 3:
			_set_light(k, Color("3fff6a"))
		game.sfx("item")
		note = "GO!"
		note_t = 1.2
		phase = "race"


func _input_for(rc: Dictionary, dt: float) -> Dictionary:
	if rc.you and not autopilot:
		var steer: float = game.move_vec.x
		return {"steer": steer, "hop": game.jump_pressed, "hold": game.jump_held, "item": game.action_pressed}
	return _ai(rc, dt)


## The computer drivers: aim for a racing line, dodge bananas, use items now and then.
func _ai(rc: Dictionary, dt: float) -> Dictionary:
	var ahead := _idx(rc.s + 10.0)
	var curve := K[ahead]
	var want: float = clampf(-curve * 22.0, -3.0, 3.0) + sin(rc.s * 0.03 + float(rc.top)) * 1.2
	for b in bananas:
		var ds := fposmod(b.s - rc.s, L)
		if ds < 14 and absf(b.lat - want) < 1.6:
			want += 2.2 if b.lat < want else -2.2
	want = clampf(want, -W * 0.5 + 1.4, W * 0.5 - 1.4)
	# steer to cancel the push in the turns and reach the line
	var push: float = K[_idx(rc.s)] * rc.speed * rc.speed * 0.045
	var steer := clampf((want - rc.lat) * 0.35 + push * 0.6, -1.0, 1.0)
	var use := false
	rc.item_t -= dt
	if rc.item != "" and rc.item_t <= 0:
		use = true
		rc.item_t = randf_range(1.5, 4.0)
	return {"steer": steer, "hop": false, "hold": false, "item": use}


func _drive(rc: Dictionary, dt: float) -> void:
	if rc.rescue >= 0:   # FLUFFY carries you back onto the road
		rc.rescue += dt
		rc.y = lerpf(6.0, 0.0, clampf(rc.rescue / 1.1, 0, 1))
		if rc.rescue > 1.2:
			rc.rescue = -1.0
			rc.y = 0.0
			rc.air = false
		return
	if rc.fall >= 0:   # off the edge!
		rc.fall += dt
		rc.vy -= GRAV * dt
		rc.y += rc.vy * dt
		rc.lat += signf(rc.lat) * 6.0 * dt
		rc.s += rc.speed * 0.5 * dt
		if rc.fall > 1.1:
			rc.fall = -1.0
			rc.lat = 0.0
			rc.vy = 0.0
			rc.speed = 0.0
			rc.rescue = 0.0
			rc.ang = 0.0
			if rc.you:
				note = "FLUFFY TO THE RESCUE!"
				note_t = 1.6
				game.sfx("heart", -4)
		return
	var inp := _input_for(rc, dt)
	if rc.done and not rc.you:
		inp.item = false
	if rc.you and rc.done:
		inp = _ai(rc, dt)
		inp.item = false
	# items
	if inp.item and rc.item != "":
		_use_item(rc)
	# speed
	var top: float = rc.top
	if not rc.you and not you.is_empty():   # rubber band: keeps the race close and fun
		var gap := _progress(rc) - _progress(you)
		if gap > 30:
			top *= 0.9
		elif gap < -50:
			top *= 1.08
	if rc.boost > 0:
		rc.boost -= dt
		top = BOOST_SPEED
	if rc.spin > 0:
		rc.spin -= dt
		top = 4.0
	rc.speed = move_toward(rc.speed, top, (30.0 if rc.speed < top else 22.0) * dt)
	# hop / drift
	if inp.hop and not rc.air:
		rc.air = true
		rc.vy = 5.5
		rc.drift = 0.0
		rc.drift_dir = signf(inp.steer)
		if absf(inp.steer) < 0.3:
			rc.drift_dir = 0.0
	if inp.hold and rc.drift_dir != 0 and not rc.air:
		rc.drift += dt
	elif rc.drift > 0 and not rc.air:
		if rc.drift > 0.8:   # let go: MINI TURBO
			rc.boost = 0.6 if rc.drift < 1.8 else 1.1
			if rc.you:
				note = "TURBO!" if rc.drift < 1.8 else "SUPER TURBO!"
				note_t = 0.8
				game.sfx("zap", -6)
		rc.drift = 0.0
		rc.drift_dir = 0.0
	var steer: float = inp.steer
	if rc.drift > 0:
		steer = clampf(rc.drift_dir * 0.7 + steer * 0.5, -1.0, 1.0)
	rc.ang = lerpf(rc.ang, steer * 0.5, 1 - exp(-dt * 7))
	# move along the road; turns push you outwards unless you steer
	var i := _idx(rc.s)
	var push: float = K[i] * rc.speed * rc.speed * 0.045
	rc.s += rc.speed * cos(rc.ang) * dt
	rc.lat += (rc.speed * sin(rc.ang) - push) * dt
	# ramps
	for rs in RAMPS:
		var before: float = fposmod(rc.s - rc.speed * dt - rs, L)
		if before > L - rc.speed * dt - 0.01 and not rc.air and absf(rc.lat) < W * 0.4:
			rc.air = true
			rc.vy = 11.0
			rc.trick = false
			if rc.you:
				game.sfx("jump2", -4)
	# boost pads
	for pd in PADS:
		var ds := fposmod(rc.s - pd[0], L)
		if ds < rc.speed * dt + 0.5 and absf(rc.lat - pd[1]) < 1.8 and not rc.air:
			if rc.boost < 0.4:
				rc.boost = 1.0
				if rc.you:
					game.sfx("zap", -6)
	# air
	if rc.air:
		if rc.you and inp.hop and rc.vy < 8.0 and not rc.trick and rc.y > 0.8:
			rc.trick = true
			note = "TRICK!"
			note_t = 0.7
		rc.vy -= GRAV * dt
		rc.y += rc.vy * dt
		if rc.y <= 0:
			rc.y = 0.0
			rc.vy = 0.0
			rc.air = false
			if rc.trick:
				rc.trick = false
				rc.boost = 0.8
	# off the road?
	if not rc.air and absf(rc.lat) > W * 0.5 + 0.25:
		rc.fall = 0.0
		rc.vy = 0.0
		rc.drift = 0.0
		if rc.you:
			game.sfx("hurt", -6)
	# laps
	if rc.s >= L:
		rc.s -= L
		rc.lap += 1
		if rc.lap >= LAPS and not rc.done:
			rc.done = true
			rc.finish = race_t
			finish_order.append(rc)
			if rc.you:
				place = finish_order.size()
				game.sfx("clear")
				note = "FINISH!"
				note_t = 2.0
		elif rc.you and not rc.done:
			note = "FINAL LAP!" if rc.lap == LAPS - 1 else "LAP %d" % (rc.lap + 1)
			note_t = 1.4
			game.sfx("item", -4)
	# ? boxes
	for b in boxes:
		if b.cool > 0 or rc.item != "" or rc.y > 1.8:
			continue
		var ds: float = absf(fposmod(rc.s - b.s + L * 0.5, L) - L * 0.5)
		if ds < 1.0 and absf(rc.lat - b.lat) < 1.3:
			b.cool = 3.0
			b.node.visible = false
			rc.item = _roll_item(rc)
			if rc.you:
				game.sfx("coin", -4)
	# bananas and other karts
	for bn in bananas.duplicate():
		var ds: float = absf(fposmod(rc.s - bn.s + L * 0.5, L) - L * 0.5)
		if ds < 0.9 and absf(rc.lat - bn.lat) < 1.0 and rc.y < 1.0:
			_spin_out(rc, "BANANA!")
			bn.node.queue_free()
			bananas.erase(bn)
	for o in racers:
		if o == rc or o.fall >= 0 or o.rescue >= 0:
			continue
		var ds: float = fposmod(o.s - rc.s + L * 0.5, L) - L * 0.5
		if absf(ds) < 1.6 and absf(o.lat - rc.lat) < 1.3 and absf(o.y - rc.y) < 1.0:
			var away := signf(rc.lat - o.lat) if rc.lat != o.lat else 1.0
			rc.lat += away * 2.0 * dt
			if ds > 0 and rc.speed > o.speed:
				rc.speed = maxf(o.speed - 1.0, 0.0)


func _progress(rc: Dictionary) -> float:
	return rc.lap * L + rc.s + (10000.0 * (LAPS + 1) - rc.finish * 10.0 if rc.done else 0.0)


func _rank() -> void:
	var sorted := racers.duplicate()
	sorted.sort_custom(func(a, b): return _progress(a) > _progress(b))
	for i in sorted.size():
		sorted[i].place = i + 1


func _roll_item(rc: Dictionary) -> String:
	if rc.place >= 4:
		return ["mushroom", "shell", "mushroom", "banana"][randi() % 4]
	if rc.place == 1:
		return ["banana", "banana", "shell", "mushroom"][randi() % 4]
	return ITEMS[randi() % ITEMS.size()]


func give_item(rc: Dictionary, item: String) -> void:
	rc.item = item


func _use_item(rc: Dictionary) -> void:
	var it: String = rc.item
	rc.item = ""
	match it:
		"mushroom":
			rc.boost = 1.4
			game.fx.burst(_world(rc) + Vector3(0, 1, 0), Color("e8413b"), 10, 4, 4)
			if rc.you:
				game.sfx("zap", -4)
		"banana":
			var bs: float = fposmod(rc.s - 2.5, L)
			var node := _banana_model()
			node.position = at(bs, rc.lat, 0.0)
			add_child(node)
			bananas.append({"s": bs, "lat": rc.lat, "node": node})
			if rc.you:
				game.sfx("swap", -6)
		"shell":
			var node := Node3D.new()
			Toys.part(node, Toys.hemi(0.42), Vector3(0, 0.2, 0), Color("3fc06a"))
			Toys.part(node, Toys.cyl(0.46, 0.46, 0.12, 14), Vector3(0, 0.2, 0), Color("f2f2f6"))
			add_child(node)
			shells.append({"s": rc.s + 2.2, "lat": rc.lat, "owner": rc, "node": node, "life": 5.0})
			if rc.you:
				game.sfx("arrow", -2)


func _banana_model() -> Node3D:
	var n0 := Node3D.new()
	for k in 3:
		Toys.part(n0, Toys.sph(0.2), Vector3(0, 0.2 + k * 0.05, (k - 1) * 0.22), Color("ffd23a"), Vector3(1, 0.8, 1.4), Vector3.ZERO, k == 1)
	Toys.part(n0, Toys.cyl(0.04, 0.04, 0.2, 6), Vector3(0, 0.38, -0.36), Color("6b4226"), Vector3.ONE, Vector3.ZERO, false)
	return n0


func _spin_out(rc: Dictionary, why: String) -> void:
	if rc.spin > 0:
		return
	rc.spin = 1.1
	rc.boost = 0.0
	rc.drift = 0.0
	game.fx.burst(_world(rc) + Vector3(0, 1, 0), Color("ffd23a"), 8, 4, 8)
	if rc.you:
		note = why + " Spin out!"
		note_t = 1.2
		game.sfx("bonk", -2)


func _update_items(dt: float) -> void:
	for sh in shells.duplicate():
		sh.life -= dt
		sh.s += 42.0 * dt
		sh.lat -= K[_idx(sh.s)] * 0.0   # shells follow the road
		sh.node.position = at(sh.s, sh.lat, 0.1)
		sh.node.rotation.y += dt * 14
		var hit := false
		for rc in racers:
			if rc == sh.owner or rc.fall >= 0 or rc.rescue >= 0:
				continue
			var ds: float = absf(fposmod(rc.s - sh.s + L * 0.5, L) - L * 0.5)
			if ds < 1.6 and absf(rc.lat - sh.lat) < 1.7 and rc.y < 1.5:
				_spin_out(rc, "SHELL!")
				hit = true
				break
		if hit or sh.life <= 0:
			game.fx.burst(sh.node.position + Vector3(0, 0.4, 0), Color("3fc06a"), 8, 4, 8)
			sh.node.queue_free()
			shells.erase(sh)
	for bn in bananas:
		bn.node.rotation.y += dt * 2


func _fluffy_step(dt: float) -> void:
	var want: Vector3
	if phase == "count":
		want = at(18.0, 0.0, 6.0) + Vector3(0, sin(t * 2) * 0.2, 0)
	else:
		var rescue := {}
		for rc in racers:
			if rc.you and (rc.rescue >= 0 or rc.fall > 0.3):
				rescue = rc
		if not rescue.is_empty():
			want = _world(rescue) + Vector3(0, 3.2, 0)
		else:
			want = cam_pos + (cam_look - cam_pos).normalized() * 6 + Vector3(-5, 4, 0) if phase == "race" else at(0, 0, 12)
	fluffy.position = fluffy.position.lerp(want, 1 - exp(-dt * 3.0))
	fluffy.rotation.y = sin(t * 1.5) * 0.3
	if phase != "count" and count_t < 5.0:
		count_t += dt
		if count_t > 4.2:   # lights off after the start
			for i in 3:
				_set_light(i, Color(0.25, 0.08, 0.08))


func _camera(dt: float) -> void:
	var c: Camera3D = game.cam
	var want_pos: Vector3
	var want_look: Vector3
	if phase == "podium":
		var p := podium.position
		var a := podium_t * 0.35
		want_pos = p + Vector3(sin(a) * 9.0, 4.5, cos(a) * 9.0)
		want_look = p + Vector3(0, 2.2, 0)
	else:
		var i := _idx(you.s)
		var fwd := F[i].rotated(Vector3.UP, -you.ang * 0.5)
		var pos := _world(you)
		if you.rescue >= 0 or you.fall >= 0:
			pos = at(you.s, clampf(you.lat, -3, 3), 0.0)
		want_pos = pos - Vector3(fwd.x, 0, fwd.z).normalized() * 7.5 + Vector3(0, 3.4, 0)
		want_look = pos + fwd * 6.0 + Vector3(0, 1.0, 0)
		if phase == "count":
			var k := clampf(count_t / 2.6, 0, 1)
			want_pos = (pos + Vector3(14, 10, 10)).lerp(want_pos, k * k)
	cam_pos = cam_pos.lerp(want_pos, 1 - exp(-dt * 6.0))
	cam_look = cam_look.lerp(want_look, 1 - exp(-dt * 8.0))
	c.position = cam_pos
	c.look_at(cam_look, Vector3.UP)


# ------------------------------------------------------------------ the end: podium
func _start_podium() -> void:
	phase = "podium"
	podium_t = 0.0
	podium.visible = true
	_rank()
	# your place (if you somehow didn't finish yet, your current place)
	if place == 0:
		place = you.place
	prize = PRIZES[clampi(place - 1, 0, PRIZES.size() - 1)]
	star_won = place == 1
	var by_place := racers.duplicate()
	by_place.sort_custom(func(a, b): return a.place < b.place)
	var heights := [3.0, 2.0, 1.3]
	var xs := [0.0, -2.6, 2.6]
	for k in 3:
		var rc: Dictionary = by_place[k]
		var who: String = rc.id
		var d: Node3D
		if Look.toy and (Team.INFO.has(who) or who in ["grumbler", "penguin", "kragjr"]):
			d = Toys.build(who)
			d.scale = Vector3.ONE * (1.0 if who != "kragjr" else 0.75)
		else:
			var s := Sprite3D.new()
			s.texture = load("res://art/%s.png" % _driver_pic(who))
			s.pixel_size = 1.9 / s.texture.get_height()
			s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
			s.shaded = false
			s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
			s.position.y = 0.95
			var holder := Node3D.new()
			holder.add_child(s)
			d = holder
		d.position = Vector3(xs[k], heights[k], 0)
		podium.add_child(d)
	if star_won:
		var st := OpenPlay.star_model()
		st.position = Vector3(0, heights[0] + 3.2, 0)
		st.scale = Vector3.ONE * 1.6
		st.name = "Star"
		podium.add_child(st)
	game.play_music("fanfare", false)
	if place == 1:
		game.speak("We won the Rainbow Cup!", "rocco")


func _podium_step(dt: float) -> void:
	podium_t += dt
	var st := podium.get_node_or_null("Star")
	if st:
		st.rotation.y = podium_t * 2.5
	if int(podium_t * 4) != int((podium_t - dt) * 4):   # fireworks!
		var p := podium.position + Vector3(randf_range(-8, 8), randf_range(7, 12), randf_range(-6, 2))
		game.fx.burst(p, Color.from_hsv(randf(), 0.7, 1.0), 22, 7, 3, 1.4)
	for rc in racers:   # everyone keeps cruising round
		_drive(rc, dt)


func can_leave() -> bool:
	return phase == "podium" and podium_t > 2.0


func places_text() -> Array:
	var by_place := racers.duplicate()
	by_place.sort_custom(func(a, b): return a.place < b.place)
	var out := []
	for rc in by_place:
		out.append([rc.place, rc.name, rc.you])
	return out


func cleanup() -> void:
	if game and game.cam:
		game.cam.environment = null
	queue_free()


# ------------------------------------------------------------------ HUD (called by hud.gd)
const ORD := ["", "1ST", "2ND", "3RD", "4TH", "5TH", "6TH"]


func draw(h) -> void:
	var s: Vector2 = h.size
	if phase == "podium":
		_draw_results(h)
		return
	# lap + place
	h.draw_rect(Rect2(18, 16, 250, 104), Color(0.06, 0.03, 0.15, 0.7))
	h.txt("LAP %d/%d" % [mini(you.lap + 1, LAPS), LAPS], Vector2(34, 58), 38, Color.WHITE, HORIZONTAL_ALIGNMENT_LEFT, -1, 8)
	h.txt("%.1f s" % race_t, Vector2(34, 100), 26, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
	var pc := [Color("ffd23a"), Color("dcdce2"), Color("e09a5a")]
	var ptxt: String = ORD[clampi(you.place, 1, 6)]
	h.txt(ptxt, Vector2(s.x - 330, s.y - 300), 96, pc[you.place - 1] if you.place <= 3 else Color.WHITE, HORIZONTAL_ALIGNMENT_LEFT, -1, 14)
	# item box
	var ib := Rect2(s.x * 0.5 - 52, 14, 104, 104)
	h.draw_rect(ib, Color(0.06, 0.03, 0.15, 0.75))
	h.draw_rect(ib, Color("ffd23a") if you.item != "" else Color(1, 1, 1, 0.6), false, 4.0)
	_item_icon(h, you.item, ib.get_center())
	if you.item != "":
		h.txt("ACTION!", Vector2(ib.position.x, ib.end.y + 26), 22, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, ib.size.x, 6)
	# minimap
	var mr := Rect2(s.x - 230, 70, 200, 200)
	h.draw_rect(mr, Color(0.06, 0.03, 0.15, 0.55))
	var lo := Vector2(INF, INF)
	var hi := Vector2(-INF, -INF)
	for i in range(0, n, 4):
		lo = Vector2(minf(lo.x, P[i].x), minf(lo.y, P[i].z))
		hi = Vector2(maxf(hi.x, P[i].x), maxf(hi.y, P[i].z))
	var sc := (mr.size.x - 24) / maxf(hi.x - lo.x, hi.y - lo.y)
	var off := mr.position + Vector2(12, 12)
	var line := PackedVector2Array()
	for i in range(0, n, 4):
		line.append(off + (Vector2(P[i].x, P[i].z) - lo) * sc)
	line.append(line[0])
	h.draw_polyline(line, Color(1, 1, 1, 0.85), 4.0)
	for rc in racers:
		var wp := _world(rc)
		var mp := off + (Vector2(wp.x, wp.z) - lo) * sc
		if rc.you:
			h.draw_circle(mp, 8, Color("ffd23a"))
			h.draw_arc(mp, 8, 0, TAU, 16, Color.BLACK, 2)
		else:
			h.draw_circle(mp, 5, Color("ff6a6a"))
	# messages
	if note_t > 0 and note != "":
		var big := note.length() <= 4
		h.txt(note, Vector2(0, s.y * 0.38), 120 if big else 60, Color("ffe14a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 16)
	if phase == "count" and count_t < 0.9:
		h.txt("RAINBOW ROAD", Vector2(0, s.y * 0.3), 72, Color("ff8ae0"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		h.txt("Steer with the stick - hold JUMP to drift - ACTION uses items", Vector2(0, s.y * 0.3 + 48), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)


func _item_icon(h, item: String, c: Vector2) -> void:
	match item:
		"mushroom":
			h.draw_circle(c + Vector2(0, -6), 30, Color("e8413b"))
			h.draw_rect(Rect2(c + Vector2(-14, 4), Vector2(28, 26)), Color("fff2dc"))
			for p in [Vector2(-12, -14), Vector2(12, -14), Vector2(0, -26)]:
				h.draw_circle(c + p, 7, Color.WHITE)
		"banana":
			h.draw_arc(c + Vector2(-8, -10), 28, 0.2, 1.9, 16, Color("ffd23a"), 14)
			h.draw_circle(c + Vector2(-2, 22), 4, Color("6b4226"))
		"shell":
			h.draw_circle(c, 28, Color("3fc06a"))
			h.draw_arc(c, 28, 0, TAU, 24, Color.WHITE, 6)
			for p in [Vector2(-10, -6), Vector2(10, -6), Vector2(0, 10)]:
				h.draw_circle(c + p, 6, Color("2a8a4a"))
		_:
			h.txt("?", Vector2(c.x - 40, c.y + 22), 60, Color(1, 1, 1, 0.35), HORIZONTAL_ALIGNMENT_CENTER, 80, 0)


func _draw_results(h) -> void:
	var s: Vector2 = h.size
	var title := "YOU WIN THE RAINBOW CUP!" if place == 1 else "%s PLACE!" % ORD[clampi(place, 1, 6)]
	h.txt(title, Vector2(0, 92), 64, Color("ffe14a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	var sub := "+%d coins" % prize
	if star_won:
		sub = "RAINBOW STAR!   " + sub
	h.txt(sub, Vector2(0, 140), 36, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 9)
	var y := s.y - 290.0
	h.draw_rect(Rect2(24, y - 40, 300, 260), Color(0.06, 0.03, 0.15, 0.7))
	for row in places_text():
		h.txt("%s  %s" % [ORD[row[0]], row[1]], Vector2(42, y), 28, Color("ffd23a") if row[2] else Color.WHITE, HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
		y += 38
	if can_leave():
		h.txt("TAP TO CONTINUE", Vector2(0, s.y - 40), 30, Color(1, 1, 1, 0.6 + 0.4 * sin(podium_t * 4)), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
