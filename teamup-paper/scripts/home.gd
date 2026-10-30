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
const PAD := Vector3(Level.HOME_OX + Level.PORTAL_X, 5, Level.HOME_OZ + 6.5)   # the rocket pad

var game
var level: Level
var mode := ""               # "", "build", "decor"
var sel := {"build": 0, "decor": 0}
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
		rocket_node.position.y = 5.16 + absf(sin(spin * 2.0)) * 0.05


## True when the hero walks into the portal.
func at_portal(p: Vector3) -> bool:
	return absf(p.x - PAD.x) < 0.8 and absf(p.z - PAD.z) < 1.3 and p.y < 7.5


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
		_put_decor(target, Decor.ITEMS[sel.decor].id, _auto_rot())
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
	if decor.has(under):
		var d: Dictionary = decor[under]
		_pay({"coins": int(Decor.find(d.id).get("coins", 0))}, 1)
		d.node.queue_free()
		decor.erase(under)
	else:
		var t := level.get_cell(under.x, under.y, under.z)
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


## New decorations face the hero (so you see the front of a bench or a bed).
func _auto_rot() -> int:
	var f: Vector3 = game.leader().facing
	if absf(f.x) >= absf(f.z):
		return 3 if f.x > 0 else 1
	return 2 if f.z > 0 else 0


func _put_decor(c: Vector3i, id: String, r: int) -> void:
	var n: Node3D
	if Look.toy:
		n = Decor.model(id)
	else:
		var s := Sprite3D.new()
		s.texture = load("res://art/decor_%s.png" % id)
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.shaded = false
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		var h := 2.6 if id in ["tree", "lamp"] else (1.4 if id in ["fountain", "mailbox", "trophy"] else 1.0)
		s.pixel_size = h / s.texture.get_height()
		s.position.y = h * 0.5
		n = Node3D.new()
		n.add_child(s)
	n.position = Vector3(c) + Vector3(0.5, 0, 0.5)
	n.rotation.y = r * PI / 2 if Look.toy else 0.0
	add_child(n)
	decor[c] = {"id": id, "r": r, "node": n}


# ------------------------------------------------------------------ saving
## Saved as only what changed from the starting world (blocks placed, dug or chopped).
func data() -> Dictionary:
	var blocks := []
	for c in edits:
		blocks.append([c.x, c.y, c.z, edits[c]])
	var ds := []
	for c in decor:
		ds.append([decor[c].id, c.x, c.y, c.z, decor[c].r])
	return {"v": 2, "blocks": blocks, "decor": ds}


## Old saves (from the small 44 x 10 home) are moved into the middle of the big one.
func load_data(d: Dictionary) -> void:
	var old := int(d.get("v", 1)) < 2
	var off := Vector3i(Level.HOME_OX, 0, Level.HOME_OZ) if old else Vector3i.ZERO
	for b in d.get("blocks", []):
		if not (b is Array and b.size() == 4):
			continue
		var c := Vector3i(int(b[0]), int(b[1]), int(b[2])) + off
		var t := int(b[3])
		if old and c.y < GROUND_Y:
			continue
		if c.y <= 0 or not level.inside(c.x, c.y, c.z) or t < 0 or t >= Level.BLOCK_TYPES:
			continue
		level.cells[level.idx(c.x, c.y, c.z)] = t   # all at once, then one redraw
		edits[c] = t
	if not edits.is_empty():
		level.rebuild()
	for e in d.get("decor", []):
		if e is Array and e.size() == 5 and not Decor.find(String(e[0])).is_empty():
			_put_decor(Vector3i(int(e[1]), int(e[2]), int(e[3])) + off, String(e[0]), int(e[4]))


## A new home gets a mailbox and some flowers by the sign.
func starter() -> void:
	var o := Vector3i(Level.HOME_OX, 0, Level.HOME_OZ)
	_put_decor(o + Vector3i(6, 5, 3), "mailbox", 0)
	_put_decor(o + Vector3i(7, 5, 3), "flowers", 0)
	_put_decor(o + Vector3i(9, 5, 3), "bush", 0)
