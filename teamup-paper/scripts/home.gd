class_name HomeBase
extends Node3D
## The home base: a big world to build on. BUILD mode (place / remove blocks) and DECOR
## mode (place / rotate / pick up decorations), the rocket pad, and saving it all.
## Aim like Minecraft: the middle of the screen (or wherever you tap) picks the block;
## tap to place on the side you touched, hold to break. Removing gives back the cost.

## Blocks you can build with and what each costs (most are free, like Minecraft's
## creative mode; the shiny ones cost the ores you dig up in the worlds).
const BLOCKS := [
	{"t": Level.PLANKS, "cost": {}},
	{"t": Level.LOG, "cost": {}},
	{"t": Level.STONE_BRICK, "cost": {}},
	{"t": Level.BRICK, "cost": {}},
	{"t": Level.STONE, "cost": {}},
	{"t": Level.WINDOW, "cost": {}},
	{"t": Level.ROAD, "cost": {}},
	{"t": Level.LANTERN, "cost": {}},
	{"t": Level.GRASS, "cost": {}},
	{"t": Level.DIRT, "cost": {}},
	{"t": Level.LEAVES, "cost": {}},
	{"t": Level.SAND, "cost": {}},
	{"t": Level.SANDSTONE, "cost": {}},
	{"t": Level.SNOW, "cost": {}},
	{"t": Level.ICE, "cost": {}},
	{"t": Level.WOOL_WHITE, "cost": {}},
	{"t": Level.WOOL_RED, "cost": {}},
	{"t": Level.WOOL_YELLOW, "cost": {}},
	{"t": Level.WOOL_GREEN, "cost": {}},
	{"t": Level.WOOL_BLUE, "cost": {}},
	{"t": Level.WOOL_BLACK, "cost": {}},
	{"t": Level.IRON_BLOCK, "cost": {"iron": 1}},
	{"t": Level.GOLD_BLOCK, "cost": {"gold": 1}},
	{"t": Level.DIAMOND_BLOCK, "cost": {"diamond": 1}},
]
const GROUND_Y := 5          # the top of the grass (old saves count from here)
const REACH := 9.0           # how far from the hero you can build (blocks)
const PAD := Vector3(Level.HOME_OX + Level.PORTAL_X, Level.HOME_GY, Level.HOME_OZ + 6.5)   # the rocket pad
## Natural ore blocks give that ore when you dig them out at home (not blocks you placed).
const ORE_OF := {Level.STONE: "stone", Level.IRON: "iron", Level.GOLD: "gold", Level.DIAMOND: "diamond"}
const PAINT_W := 3.0         # castle paintings (blocks)
const PAINT_H := 2.4

var game
var level: Level
var mode := ""               # "", "build", "decor"
var sel := {"build": 0, "decor": 0}
var paint := 0               # the paint colour for new decorations (Decor.PAINTS, 0 = their own colours)
var decor := {}              # Vector3i -> {"id": String, "r": int, "node": Node3D}
var edits := {}              # Vector3i -> block type, everything changed from the starting world
var ghost: MeshInstance3D
var ghost_mat: StandardMaterial3D
var outline: MeshInstance3D  # thin box around the block you're pointing at
var target := Vector3i(-1, -1, -1)     # where PLACE puts the next thing
var under := Vector3i(-1, -1, -1)      # what REMOVE takes (the block / decor you point at)
var has_under := false
var can_place := false
var why := ""                # why you can't place (shown on screen)
var portal: Node3D
var spin := 0.0
var frames: Array = []       # castle paintings: {id, node, canvas, lock_label, locked, slot}
var entering := ""           # the world whose painting you just jumped into
var enter_t := 0.0
var paint_cool := 0.0
var _paint_check := 0.0


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	ghost_mat = StandardMaterial3D.new()
	ghost_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	ghost_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	ghost_mat.no_depth_test = false
	ghost = MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(1.02, 1.02, 1.02)
	ghost.mesh = bm
	ghost.material_override = ghost_mat
	ghost.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	ghost.visible = false
	add_child(ghost)
	outline = MeshInstance3D.new()
	outline.mesh = _wire_box()
	var om := StandardMaterial3D.new()
	om.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	om.albedo_color = Color(0.08, 0.08, 0.12)
	outline.material_override = om
	outline.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	outline.visible = false
	add_child(outline)
	_make_portal()
	_make_paintings()


## The black lines around the block you point at (like Minecraft).
func _wire_box() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_LINES)
	var e := 0.005
	var c := []
	for i in 8:
		c.append(Vector3(-e if i & 1 == 0 else 1 + e, -e if i & 2 == 0 else 1 + e, -e if i & 4 == 0 else 1 + e))
	for pr in [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]]:
		st.add_vertex(c[pr[0]])
		st.add_vertex(c[pr[1]])
	return st.commit()


func _make_portal() -> void:
	portal = Node3D.new()
	portal.position = PAD
	add_child(portal)
	var pad := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 1.5
	cm.bottom_radius = 1.6
	cm.height = 0.16
	pad.mesh = cm
	pad.material_override = Toys.mat(Color("8a8e96"))
	pad.position.y = 0.08
	portal.add_child(pad)
	var ring := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 1.2
	tm.outer_radius = 1.35
	ring.mesh = tm
	ring.material_override = Toys.mat(Color("ffcf33"), false, 0.8)
	ring.position.y = 0.18
	portal.add_child(ring)
	var r := level.prop("rocket", PAD + Vector3(0, 0.16, 0), 3.0)
	r.set_meta("rocket", true)
	rocket_node = r
	var label := Label3D.new()
	label.text = "ROCKET\nfly to the worlds!"
	label.font_size = 44
	label.outline_size = 12
	label.pixel_size = 0.008
	label.modulate = Color("ffd23a")
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.position.y = 4.0
	portal.add_child(label)


var rocket_node: Node3D


func _process(dt: float) -> void:
	spin += dt
	if portal:
		portal.get_child(1).scale = Vector3.ONE * (1.0 + sin(spin * 4) * 0.06)
	if rocket_node:
		rocket_node.position.y = PAD.y + 0.16 + absf(sin(spin * 2.0)) * 0.05
	_step_paintings(dt)


## True when the hero walks into the portal.
func at_portal(p: Vector3) -> bool:
	return absf(p.x - PAD.x) < 0.8 and absf(p.z - PAD.z) < 1.3 and p.y < PAD.y + 2.5


# ------------------------------------------------------------------ where things go
## Points along a line from the camera (through the middle of the screen, or where you
## touched) and finds the first block or decoration it hits, like Minecraft: PLACE puts
## the new thing on the side you're looking at, REMOVE takes the thing you hit.
func aim_ray(from: Vector3, dir: Vector3, h: Actor) -> void:
	has_under = false
	can_place = false
	why = "Point at a block"
	var hit := _ray(from, dir.normalized(), 40.0)
	if not hit.is_empty():
		under = hit.cell
		has_under = true
		target = hit.cell + hit.normal
		if decor.has(under) and hit.normal != Vector3i(0, 1, 0):
			target = under   # can't stick things on the side of a decoration
		_check(h)
	ghost.visible = mode != "" and has_under and can_place
	ghost.position = Vector3(target) + Vector3(0.5, 0.5, 0.5)
	ghost_mat.albedo_color = Color(0.4, 1, 0.5, 0.35)
	outline.visible = mode != "" and has_under
	outline.position = Vector3(under)


## Steps through the grid cell by cell along the line (a "voxel walk").
func _ray(from: Vector3, dir: Vector3, max_d: float) -> Dictionary:
	var c := Vector3i(floori(from.x), floori(from.y), floori(from.z))
	var step := Vector3i(1 if dir.x > 0 else -1, 1 if dir.y > 0 else -1, 1 if dir.z > 0 else -1)
	var t_max := Vector3.ZERO
	var t_delta := Vector3.ZERO
	for i in 3:
		if absf(dir[i]) < 0.00001:
			t_max[i] = INF
			t_delta[i] = INF
		else:
			var edge := float(c[i] + (1 if dir[i] > 0 else 0))
			t_max[i] = (edge - from[i]) / dir[i]
			t_delta[i] = absf(1.0 / dir[i])
	var normal := Vector3i.ZERO
	var t := 0.0
	while t < max_d:
		if level.inside(c.x, c.y, c.z) and (level.get_cell(c.x, c.y, c.z) != Level.AIR or decor.has(c)):
			return {"cell": c, "normal": normal, "t": t}
		var ax := 0
		if t_max.y < t_max[ax]:
			ax = 1
		if t_max.z < t_max[ax]:
			ax = 2
		t = t_max[ax]
		t_max[ax] += t_delta[ax]
		c[ax] += step[ax]
		normal = Vector3i.ZERO
		normal[ax] = -step[ax]
		if c.y < -1 or c.y > level.sy + 2:
			break
	return {}


func _check(h: Actor) -> void:
	can_place = true
	why = ""
	var center := Vector3(target) + Vector3(0.5, 0.5, 0.5)
	if center.distance_to(h.position + Vector3(0, 0.8, 0)) > REACH:
		can_place = false
		why = "Too far! Walk closer"
	elif not level.inside(target.x, target.y, target.z) or target.y >= level.sy - 1:
		can_place = false
		why = "That's the edge of the world"
	elif level.get_cell(target.x, target.y, target.z) != Level.AIR or decor.has(target):
		can_place = false
		why = "Something's in the way"
	elif mode == "decor" and not level.solid(target.x, target.y - 1, target.z):
		can_place = false
		why = "Decorations go on top of blocks"
	elif _hits_someone(target):
		can_place = false
		why = "Step back a little"
	elif absf(target.x + 0.5 - PAD.x) < 2.0 and absf(target.z + 0.5 - PAD.z) < 2.5 and target.y < PAD.y + 4:
		can_place = false
		why = "Keep the rocket pad clear"
	if can_place and mode != "" and not _affordable():
		can_place = false
		why = "Not enough " + _missing()


func _hits_someone(c: Vector3i) -> bool:
	for h in [game.leader(), game.follower()]:
		if h.visible and absf(h.position.x - (c.x + 0.5)) < h.hw + 0.5 and absf(h.position.z - (c.z + 0.5)) < h.hw + 0.5 \
				and h.position.y < c.y + 1 and h.position.y + h.height > c.y:
			return true
	return false


# ------------------------------------------------------------------ costs
func cost_of_sel() -> Dictionary:
	if mode == "build":
		return BLOCKS[sel.build].cost
	if mode == "decor":
		return {"coins": int(Decor.ITEMS[sel.decor].coins)}
	return {}


func _have(k: String) -> int:
	return int(game.coins) if k == "coins" else int(game.ores[k])


func _affordable() -> bool:
	if mode == "decor" and Decor.ITEMS[sel.decor].get("need_clear", false) and game.cleared <= 0:
		return false
	for k in cost_of_sel():
		if _have(k) < int(cost_of_sel()[k]):
			return false
	return true


func _missing() -> String:
	if mode == "decor" and Decor.ITEMS[sel.decor].get("need_clear", false) and game.cleared <= 0:
		return "trophies: beat Krag Jr.!"
	for k in cost_of_sel():
		if _have(k) < int(cost_of_sel()[k]):
			return k
	return ""


func _pay(cost: Dictionary, sign: int) -> void:
	for k in cost:
		if k == "coins":
			game.coins += sign * int(cost[k])
		else:
			game.ores[k] += sign * int(cost[k])


static func block_cost(t: int) -> Dictionary:
	for b in BLOCKS:
		if b.t == t:
			return b.cost
	return {}


# ------------------------------------------------------------------ PLACE / REMOVE / ROTATE
func place() -> bool:
	if not can_place:
		game.sfx("bonk", -4)
		if why != "" and has_under:
			game.fx.text(Vector3(target) + Vector3(0.5, 1.4, 0.6), why, Color("ffb0a8"), 40, 1.0)
		return false
	_pay(cost_of_sel(), -1)
	if mode == "build":
		_change(target, BLOCKS[sel.build].t)
		game.fx.burst(Vector3(target) + Vector3(0.5, 0.5, 0.5), Level.COLORS[BLOCKS[sel.build].t], 6, 2.5)
		game.sfx("mine", -2)
	else:
		var id: String = Decor.ITEMS[sel.decor].id
		_put_decor(target, id, _auto_rot(), paint if Decor.paintable(id) else 0)
		game.fx.burst(Vector3(target) + Vector3(0.5, 0.4, 0.5), Color.WHITE, 8, 3, 4)
		game.sfx("item", -4)
	can_place = false   # aim again before the next one (no double blocks on one tap)
	return true


func remove() -> bool:
	if not has_under or under.y <= 0 or Vector3(under).distance_to(game.leader().position) > REACH + 1.0:
		game.sfx("bonk", -4)
		return false
	if not decor.has(under) and decor.has(under + Vector3i(0, 1, 0)):
		game.sfx("bonk", -4)
		game.fx.text(Vector3(under) + Vector3(0.5, 2.2, 0.6), "Pick up the decoration first", Color("ffb0a8"), 40, 1.0)
		return false
	if level.locked.has(under):
		game.sfx("bonk", -4)
		game.fx.text(Vector3(under) + Vector3(0.5, 1.6, 0.6), "The castle is too strong to dig!", Color("ffb0a8"), 40, 1.0)
		return false
	if decor.has(under):
		var d: Dictionary = decor[under]
		_pay({"coins": int(Decor.find(d.id).get("coins", 0))}, 1)
		d.node.queue_free()
		decor.erase(under)
	else:
		var t := level.get_cell(under.x, under.y, under.z)
		if ORE_OF.has(t) and not edits.has(under):   # dug out of the ground: you get the ore
			var o: String = ORE_OF[t]
			game.ores[o] += 1
			game.fx.text(Vector3(under) + Vector3(0.5, 1.2, 0.6), "+1 " + o.to_upper(), Color(1, 1, 1) if o == "stone" else Level.COLORS[t].lightened(0.2), 48)
		else:
			_pay(block_cost(t), 1)
		_change(under, Level.AIR)
		game.fx.burst(Vector3(under) + Vector3(0.5, 0.5, 0.5), Level.COLORS[t], 10, 3.5)
	has_under = false
	game.sfx("break", -4)
	return true


## Changes a block and remembers it for the save.
func _change(c: Vector3i, t: int) -> void:
	level.set_cell(c.x, c.y, c.z, t)
	edits[c] = t


func rotate_front() -> void:
	if decor.has(under):
		var d: Dictionary = decor[under]
		d.r = (int(d.r) + 1) % 4
		d.node.rotation.y = d.r * PI / 2
		game.sfx("swap", -8)


## PAINT: gives the decoration you point at the chosen colour (free).
func paint_front() -> bool:
	if not has_under or not decor.has(under) or not Decor.paintable(decor[under].id):
		game.sfx("bonk", -4)
		var p: Vector3 = Vector3(under) if has_under else game.leader().position
		game.fx.text(p + Vector3(0.5, 1.6, 0.6), "Point at furniture to paint it", Color("ffb0a8"), 40, 1.0)
		return false
	var d: Dictionary = decor[under]
	if int(d.get("col", 0)) == paint:
		return false
	d.node.queue_free()
	_put_decor(under, d.id, int(d.r), paint)
	var c: Color = Decor.PAINTS[paint] if paint > 0 else Color.WHITE
	game.fx.burst(Vector3(under) + Vector3(0.5, 0.6, 0.5), c, 10, 3, 4)
	game.sfx("item", -6)
	return true


## New decorations face the hero (so you see the front of a bench or a bed).
func _auto_rot() -> int:
	var f: Vector3 = game.leader().facing
	if absf(f.x) >= absf(f.z):
		return 3 if f.x > 0 else 1
	return 2 if f.z > 0 else 0


func _put_decor(c: Vector3i, id: String, r: int, col := 0) -> void:
	var n: Node3D
	if col < 0 or col >= Decor.PAINTS.size() or not Decor.paintable(id):
		col = 0
	var h: float = Decor.find(id).get("h", 1.0)
	if Look.toy:
		n = Decor.model(id)
		Decor.recolour(n, col)
	elif col > 0 and ResourceLoader.exists("res://art/decor_%s_mask.png" % id):
		n = Node3D.new()
		n.add_child(_painted_picture(id, h, Decor.PAINTS[col]))
	else:
		var s := Sprite3D.new()
		s.texture = load("res://art/decor_%s.png" % id)
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.shaded = false
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		s.pixel_size = h / s.texture.get_height()
		s.position.y = h * 0.5
		n = Node3D.new()
		n.add_child(s)
	n.position = Vector3(c) + Vector3(0.5, 0, 0.5)
	n.rotation.y = r * PI / 2 if Look.toy else 0.0
	add_child(n)
	decor[c] = {"id": id, "r": r, "node": n, "col": col}


static var _paint_shader: Shader


## PAPER look: the decoration's picture with its paint parts coloured in (art/decor_<id>_mask.png
## says which pixels are paint, and how shaded). A flat card that turns to face the camera.
func _painted_picture(id: String, h: float, tint: Color) -> MeshInstance3D:
	if _paint_shader == null:
		_paint_shader = Shader.new()
		_paint_shader.code = """
shader_type spatial;
render_mode unshaded, cull_disabled, depth_draw_opaque, shadows_disabled;
uniform sampler2D tex : source_color, filter_linear_mipmap;
uniform sampler2D mask : filter_linear_mipmap;
uniform vec4 tint : source_color;
void vertex() {
	MODELVIEW_MATRIX = VIEW_MATRIX * mat4(vec4(normalize(cross(vec3(0.0, 1.0, 0.0), INV_VIEW_MATRIX[2].xyz)), 0.0),
		vec4(0.0, 1.0, 0.0, 0.0), vec4(normalize(cross(INV_VIEW_MATRIX[0].xyz, vec3(0.0, 1.0, 0.0))), 0.0), MODEL_MATRIX[3]);
	MODELVIEW_NORMAL_MATRIX = mat3(MODELVIEW_MATRIX);
}
void fragment() {
	vec4 c = texture(tex, UV);
	vec4 m = texture(mask, UV);
	float shade = m.r;
	float k = smoothstep(0.06, 0.16, shade) * step(0.5, m.a);
	vec3 painted = tint.rgb * clamp(shade * 1.12, 0.0, 1.0) + vec3(0.04) * shade;
	ALBEDO = mix(c.rgb, painted, k);
	ALPHA = c.a;
	ALPHA_SCISSOR_THRESHOLD = 0.5;
}
"""
	var tex: Texture2D = load("res://art/decor_%s.png" % id)
	var m := ShaderMaterial.new()
	m.shader = _paint_shader
	m.set_shader_parameter("tex", tex)
	m.set_shader_parameter("mask", load("res://art/decor_%s_mask.png" % id))
	m.set_shader_parameter("tint", tint)
	var q := QuadMesh.new()
	q.size = Vector2(h * tex.get_width() / tex.get_height(), h)
	var mi := MeshInstance3D.new()
	mi.mesh = q
	mi.material_override = m
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.position.y = h * 0.5
	return mi


# ------------------------------------------------------------------ saving
## Saved as only what changed from the starting world (blocks placed, dug or chopped).
func data() -> Dictionary:
	var blocks := []
	for c in edits:
		blocks.append([c.x, c.y, c.z, edits[c]])
	var ds := []
	for c in decor:
		ds.append([decor[c].id, c.x, c.y, c.z, decor[c].r, int(decor[c].get("col", 0))])
	return {"v": 3, "blocks": blocks, "decor": ds}


## Older homes are moved into the big one so everything stays where it was built:
## v1 (the 44 x 10 plot) goes to the start spot, v2 (the 96 x 96 home) to the middle.
func load_data(d: Dictionary) -> void:
	var v := int(d.get("v", 1))
	var dy := Level.HOME_GY - GROUND_Y
	var off := Vector3i.ZERO
	if v < 2:
		off = Vector3i(Level.HOME_OX, dy, Level.HOME_OZ)
	elif v == 2:
		off = Level.HOME_OLD
	var blocks: Array = d.get("blocks", [])
	var one_by_one := level.built and blocks.size() < 4000   # a few changes: just redraw those blocks
	for b in blocks:
		if not (b is Array and b.size() == 4):
			continue
		var c := Vector3i(int(b[0]), int(b[1]), int(b[2])) + off
		var t := int(b[3])
		if v < 2 and int(b[1]) < GROUND_Y:
			continue
		if c.y <= 0 or not level.inside(c.x, c.y, c.z) or t < 0 or t >= Level.BLOCK_TYPES or level.locked.has(c):
			continue
		if one_by_one:
			level.set_cell(c.x, c.y, c.z, t)
		else:
			level.cells[level.idx(c.x, c.y, c.z)] = t   # lots: all at once, then one redraw
			level.touched[c] = true
		edits[c] = t
	if not edits.is_empty() and not one_by_one:
		level.rebuild()
	for e in d.get("decor", []):
		if e is Array and e.size() >= 5 and not Decor.find(String(e[0])).is_empty():   # 6th = paint colour (newer saves)
			_put_decor(Vector3i(int(e[1]), int(e[2]), int(e[3])) + off, String(e[0]), int(e[4]), int(e[5]) if e.size() > 5 else 0)


## A new home gets a mailbox and some flowers by the sign.
func starter() -> void:
	var o := Vector3i(Level.HOME_OX, Level.HOME_GY, Level.HOME_OZ)
	_put_decor(o + Vector3i(6, 0, 3), "mailbox", 0)
	_put_decor(o + Vector3i(7, 0, 3), "flowers", 0)
	_put_decor(o + Vector3i(9, 0, 3), "bush", 0)


# ------------------------------------------------------------------ the painting castle
## A framed painting on the castle wall for every world (Mario 64 style): jump into one
## to go straight to that world. Locked worlds are grey with a padlock sign.
func _make_paintings() -> void:
	frames = []
	if level.paintings.is_empty():
		return
	var gold := Toys.mat(Color("ffcf33"), false, 0.6)
	for i in mini(Worlds.ORDER.size(), level.paintings.size()):
		var id: String = Worlds.ORDER[i]
		var slot: Dictionary = level.paintings[i]
		var n := Node3D.new()
		add_child(n)
		n.position = slot.pos
		var nrm: Vector3 = slot.normal
		n.look_at_from_position(slot.pos, slot.pos - nrm, Vector3.UP)   # -z of the node points into the wall
		var canvas := MeshInstance3D.new()
		var q := QuadMesh.new()
		q.size = Vector2(PAINT_W, PAINT_H)
		canvas.mesh = q
		# the quad faces +z: out of the wall
		canvas.position.z = 0.03
		n.add_child(canvas)
		for side in [[Vector3(0, PAINT_H * 0.5 + 0.1, 0.06), Vector3(PAINT_W + 0.4, 0.2, 0.12)], [Vector3(0, -PAINT_H * 0.5 - 0.1, 0.06), Vector3(PAINT_W + 0.4, 0.2, 0.12)],
				[Vector3(PAINT_W * 0.5 + 0.1, 0, 0.06), Vector3(0.2, PAINT_H + 0.4, 0.12)], [Vector3(-PAINT_W * 0.5 - 0.1, 0, 0.06), Vector3(0.2, PAINT_H + 0.4, 0.12)]]:
			var b := MeshInstance3D.new()
			var bm := BoxMesh.new()
			bm.size = side[1]
			b.mesh = bm
			b.material_override = gold
			b.position = side[0]
			n.add_child(b)
		var lbl := Label3D.new()
		lbl.text = String(Worlds.info(id).get("short", id.to_upper()))
		lbl.font_size = 40
		lbl.outline_size = 10
		lbl.pixel_size = 0.008
		lbl.modulate = Color("ffe48a")
		lbl.position = Vector3(0, -PAINT_H * 0.5 - 0.45, 0.08)

		n.add_child(lbl)
		var lock := Label3D.new()
		lock.font_size = 46
		lock.outline_size = 12
		lock.pixel_size = 0.008
		lock.modulate = Color("ffffff")
		lock.position = Vector3(0, 0, 0.1)

		n.add_child(lock)
		frames.append({"id": id, "node": n, "canvas": canvas, "lock": lock, "locked": null, "slot": slot})
	_refresh_paintings()


## Updates which paintings are locked (worlds unlock as you beat them).
func _refresh_paintings() -> void:
	for f in frames:
		var lk := not Worlds.unlocked(f.id, game.cleared_w)
		if f.locked == lk:
			continue
		f.locked = lk
		var m := StandardMaterial3D.new()
		m.albedo_texture = painting_texture(f.id, lk)
		m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		m.roughness = 0.8
		f.canvas.material_override = m
		var after := String(Worlds.info(f.id).get("after", ""))
		f.lock.text = ("LOCKED\nBeat %s first" % Worlds.info(after).get("short", after)) if lk else ""


static var _paint_cache := {}


## A little landscape for a world: its sky, a sun, hills and its ground colour.
static func painting_texture(id: String, grey: bool) -> Texture2D:
	var key := id + ("_grey" if grey else "")
	if _paint_cache.has(key):
		return _paint_cache[key]
	var info := Worlds.info(id)
	var sky: Array = info.get("sky", ["3f8fe6", "c5e6ff", "7fb2dd"])
	var ground := Color("60be50")
	var gt := load("res://art/t_%s.png" % String(info.get("top", "grass_top"))) as Texture2D
	if gt:
		var gi := gt.get_image()
		if gi:
			if gi.is_compressed():
				gi.decompress()
			gi.resize(1, 1, Image.INTERPOLATE_BILINEAR)
			ground = gi.get_pixel(0, 0)
	var w := 96
	var h := 72
	var img := Image.create(w, h, false, Image.FORMAT_RGBA8)
	var top := Color(sky[0])
	var hor := Color(sky[1])
	var hill := Color(sky[2]).darkened(0.15)
	for y in h:
		img.fill_rect(Rect2i(0, y, w, 1), top.lerp(hor, float(y) / (h * 0.7)))
	var sun := Color(String(info.get("sun", "fff1d6")))
	for y in range(8, 26):
		for x in range(64, 82):
			if Vector2(x - 73, y - 17).length() < 8:
				img.set_pixel(x, y, sun.lightened(0.3))
	for x in w:
		var hy := int(42 + sin(x * 0.11) * 6 + sin(x * 0.29 + 1.0) * 3)
		for y in range(hy, h):
			img.set_pixel(x, y, hill)
		var gy := int(54 + sin(x * 0.07 + 2.0) * 3)
		for y in range(gy, h):
			img.set_pixel(x, y, ground.darkened(0.08 * (y - gy) / 6.0))
	if grey:
		for y in h:
			for x in w:
				var c := img.get_pixel(x, y)
				var v := c.get_luminance() * 0.75
				img.set_pixel(x, y, Color(v, v, v * 1.05))
	var t := ImageTexture.create_from_image(img)
	_paint_cache[key] = t
	return t


## Jumping into a painting: a wobble, then off to that world. Locked: bonk!
func _step_paintings(dt: float) -> void:
	if frames.is_empty() or game == null:
		return
	paint_cool = maxf(0.0, paint_cool - dt)
	_paint_check -= dt
	if _paint_check <= 0:
		_paint_check = 1.0
		_refresh_paintings()
	if entering != "":
		enter_t += dt
		for f in frames:
			if f.id == entering:
				f.canvas.scale = Vector3.ONE * (1.0 + sin(enter_t * 30.0) * 0.06 * (1.0 - enter_t))
		var h: Node3D = game.leader()
		h.scale = Vector3.ONE * maxf(0.05, 1.0 - enter_t * 1.6)
		if enter_t > 0.6:
			var id := entering
			entering = ""
			h.scale = Vector3.ONE
			game.land_on(id)
		return
	if game.state != "home" or mode != "" or paint_cool > 0:
		return
	var f = painting_at(game.leader().position)
	if f == null:
		return
	if f.locked:
		paint_cool = 1.2
		game.sfx("bonk")
		var after := String(Worlds.info(f.id).get("after", ""))
		game.hud.say("LOCKED", "Beat %s first!" % Worlds.info(after).get("short", after), 1.8)
		var nrm: Vector3 = f.slot.normal
		game.leader().vel = nrm * 6.0 + Vector3(0, 3, 0)
		return
	enter_painting(f.id)


## The painting the hero is jumping into (or null).
func painting_at(p: Vector3):
	var c := p + Vector3(0, 0.75, 0)
	for f in frames:
		var nrm: Vector3 = f.slot.normal
		var v: Vector3 = c - f.slot.pos
		var dn := v.dot(nrm)
		if dn < -0.3 or dn > 0.85:
			continue
		var along := Vector3(-nrm.z, 0, nrm.x)
		if absf(v.dot(along)) < PAINT_W * 0.5 and absf(v.y) < PAINT_H * 0.5:
			return f
	return null


func enter_painting(id: String) -> void:
	entering = id
	enter_t = 0.0
	game.sfx("item")
	game.hud.say(String(Worlds.info(id).get("name", id)), "Into the painting!", 1.2)
	for f in frames:
		if f.id == id:
			game.fx.burst(f.slot.pos, Color(1, 1, 1), 16, 4, 6)
