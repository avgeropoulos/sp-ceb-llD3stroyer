class_name Level
extends Node3D
## The block world: a grid of cubes you can stand on and mine, drawn with one
## MultiMesh per block type, plus the paper scenery (trees, hills, clouds).
## X runs along the level, Y is up, Z is depth (rows 0-2 are the back yard with
## trees; the team walks on rows 3-9). Each cell is one 1x1x1 block.

const LX := 200
const LY := 18
const LZ := 10
const WALK_Z0 := 3.35
const WALK_Z1 := 9.65

enum { AIR, GRASS, DIRT, STONE, IRON, GOLD, DIAMOND, BEDROCK, BRICK, MYSTERY, USED }
const NAMES := ["", "GRASS", "DIRT", "STONE", "IRON", "GOLD", "DIAMOND", "BEDROCK", "BRICK", "?", "BLOCK"]
## How many pickaxe hits each block takes (999 = can't be mined).
const HITS := [0, 1, 1, 2, 3, 3, 4, 999, 2, 999, 999]
## Atlas tile for the top, sides and bottom of each block type.
const FACES := [[0, 0, 0], [0, 1, 2], [2, 2, 2], [3, 3, 3], [4, 4, 4], [5, 5, 5], [6, 6, 6], [7, 7, 7], [8, 8, 8], [9, 9, 9], [10, 10, 10]]
const COLORS := [Color.WHITE, Color("60be50"), Color("96663f"), Color("8a8e96"), Color("dfbaa0"), Color("ffd23c"), Color("5ae6e6"), Color("3c3c46"), Color("c45c3a"), Color("2cbec8"), Color("967860")]
const ATLAS_TILES := 11

const CHECKPOINT_X := 91.0
const BOSS_X0 := 156
const BOSS_X1 := 184
const FLAG_X := 190.5

var cells := PackedByteArray()
var damage := {}
var heights := PackedInt32Array()
var mm_nodes: Array[MultiMeshInstance3D] = []
var built := false
var slots: Array = []        # per block type: which cell is drawn in each MultiMesh slot
var slot_of := {}           # cell -> Vector2i(type, slot)
var spawns: Array = []
var coin_spots: Array[Vector3] = []
var signs: Array = []
var rng := RandomNumberGenerator.new()


func idx(x: int, y: int, z: int) -> int:
	return x + LX * (y + LY * z)


func inside(x: int, y: int, z: int) -> bool:
	return x >= 0 and x < LX and y >= 0 and y < LY and z >= 0 and z < LZ


func get_cell(x: int, y: int, z: int) -> int:
	if not inside(x, y, z):
		return AIR
	return cells[idx(x, y, z)]


func set_cell(x: int, y: int, z: int, t: int) -> void:
	if inside(x, y, z):
		cells[idx(x, y, z)] = t
		damage.erase(Vector3i(x, y, z))
		if built:
			# only this block and its 6 neighbours can change how they look
			var c := Vector3i(x, y, z)
			_refresh(c)
			for d in [Vector3i(1, 0, 0), Vector3i(-1, 0, 0), Vector3i(0, 1, 0), Vector3i(0, -1, 0), Vector3i(0, 0, 1), Vector3i(0, 0, -1)]:
				_refresh(c + d)


func solid(x: int, y: int, z: int) -> bool:
	if x < 0 or x >= LX:
		return y < LY + 6   # invisible walls at both ends of the level
	if y < 0 or y >= LY or z < 0 or z >= LZ:
		return false
	return cells[idx(x, y, z)] != AIR


## First solid cell overlapping a box whose feet are at p (or null).
func box_hit(p: Vector3, hw: float, h: float):
	var x0 := floori(p.x - hw)
	var x1 := floori(p.x + hw - 0.0001)
	var y0 := floori(p.y)
	var y1 := floori(p.y + h - 0.0001)
	var z0 := floori(p.z - hw)
	var z1 := floori(p.z + hw - 0.0001)
	for x in range(x0, x1 + 1):
		for y in range(y0, y1 + 1):
			for z in range(z0, z1 + 1):
				if solid(x, y, z):
					return Vector3i(x, y, z)
	return null


## Height of the ground top under (x, z), searching down from y; -100 if it's a pit.
func ground_below(x: float, z: float, y: float) -> float:
	var cx := floori(x)
	var cz := floori(z)
	for cy in range(mini(floori(y), LY - 1), -1, -1):
		if solid(cx, cy, cz):
			return cy + 1.0
	return -100.0


# ------------------------------------------------------------------ building the level
func generate() -> void:
	rng.seed = 2026
	cells.resize(LX * LY * LZ)
	cells.fill(AIR)
	heights.resize(LX)
	# ground height per column (top of the grass is at y = h)
	for x in LX:
		heights[x] = 5
	_hgt(22, 33, 6); _hgt(36, 43, 6)
	_hgt(62, 73, 8)
	_hgt(112, 117, 7)
	_hgt(122, 123, 6); _hgt(124, 125, 7); _hgt(126, 128, 8); _hgt(129, 130, 7); _hgt(131, 131, 6)
	var pits := [[44, 46], [94, 103], [141, 143]]
	for x in LX:
		var pit := false
		for p in pits:
			if x >= p[0] and x <= p[1]:
				pit = true
		if pit:
			continue
		var h: int = heights[x]
		for z in LZ:
			set_cell(x, 0, z, BEDROCK)
			for y in range(1, h):
				var t := STONE
				if y >= h - 2:
					t = DIRT
				if y == h - 1:
					t = GRASS
				if t == STONE or (x >= 62 and x <= 73 and y >= 3 and y < h - 1):
					t = _ore(x, y)
				set_cell(x, y, z, t)
	# the wall Blocky can mine through (Rocco can double jump over it)
	for y in range(6, 9):
		for z in range(3, LZ):
			set_cell(34, y, z, STONE)
			set_cell(35, y, z, IRON if y == 7 and z == 6 else STONE)
	# ? blocks and bricks to bonk
	for x in [50, 52, 54]:
		_row(x, 8, MYSTERY, 5, 7)
	for x in [51, 53]:
		_row(x, 8, BRICK, 5, 7)
	for x in [134, 136, 138]:
		_row(x, 8, BRICK if x == 136 else MYSTERY, 4, 8)
	_row(14, 8, MYSTERY, 5, 7)
	# floating brick platforms over the big pit
	for x in [96, 97, 100, 101]:
		_row(x, 5, BRICK, 4, 8)
	_row(170, 9, MYSTERY, 5, 7)
	# coins
	for i in 5:
		coin_spots.append(Vector3(16.5 + i * 1.2, 6.5, 6.5))
	for i in 4:
		coin_spots.append(Vector3(43.5 + i * 1.3, 7.3 + sin(i * 1.0) * 0.6, 6.5))
	for i in 6:
		coin_spots.append(Vector3(64.5 + i * 1.6, 9.5, 5.5 + (i % 2) * 2))
	for i in 8:
		coin_spots.append(Vector3(94.5 + i * 1.2, 7.5 + sin(i * 0.8) * 0.8, 6.5))
	for i in 6:
		coin_spots.append(Vector3(124.5 + i, 9.7 + (i % 3) * 0.3, 6.5))
	for i in 5:
		coin_spots.append(Vector3(145.5 + i * 1.3, 6.5, 4.5 + i))
	# who lives where
	for x in [40.0, 57.0, 70.0, 88.0, 106.0, 110.0, 119.0, 133.0, 147.0]:
		spawns.append({"kind": "grumbler", "pos": Vector3(x, heights[int(x)] + 0.01, 5.5 + rng.randf_range(0, 3))})
	for x in [79.0, 115.0, 128.0]:
		spawns.append({"kind": "skelly", "pos": Vector3(x, heights[int(x)] + 0.01, 6.5)})
	spawns.append({"kind": "kragjr", "pos": Vector3(176, 10, 6.5)})
	signs = [
		[Vector3(6.5, 5, 3.6), "Hi team!\nMove with the stick.\nJUMP twice for a\nDOUBLE JUMP!"],
		[Vector3(31.5, 6, 3.6), "Tall wall!\nSWAP to BLOCKY\nand MINE through it!"],
		[Vector3(48.5, 5, 3.6), "Bonk the ? blocks\nfrom below!"],
		[Vector3(61.0, 5, 3.6), "ORE HILL!\nMine iron, gold\nand DIAMONDS!"],
		[Vector3(89.0, 5, 3.6), "CHECKPOINT!"],
		[Vector3(152.0, 5, 3.6), "DANGER!\nKrag Jr. ahead!"],
	]
	_build_mesh_nodes()
	_scenery()
	rebuild()


func _hgt(a: int, b: int, h: int) -> void:
	for x in range(a, b + 1):
		heights[x] = h


func _row(x: int, y: int, t: int, z0: int, z1: int) -> void:
	for z in range(z0, z1 + 1):
		set_cell(x, y, z, t)


func _ore(x: int, y: int) -> int:
	var rich := x >= 62 and x <= 73
	var r := rng.randf()
	if r < (0.05 if rich else 0.012):
		return DIAMOND
	if r < (0.14 if rich else 0.03):
		return GOLD
	if r < (0.3 if rich else 0.07):
		return IRON
	return STONE


# ------------------------------------------------------------------ drawing the blocks
func _cube_mesh(t: int) -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var aw := 72.0 * ATLAS_TILES
	# [normal, 4 corners (counter-clockwise seen from outside), face slot]
	var faces := [
		[Vector3.UP, [Vector3(0, 1, 1), Vector3(1, 1, 1), Vector3(1, 1, 0), Vector3(0, 1, 0)], 0],
		[Vector3.DOWN, [Vector3(0, 0, 0), Vector3(1, 0, 0), Vector3(1, 0, 1), Vector3(0, 0, 1)], 2],
		[Vector3.BACK, [Vector3(0, 0, 1), Vector3(1, 0, 1), Vector3(1, 1, 1), Vector3(0, 1, 1)], 1],
		[Vector3.FORWARD, [Vector3(1, 0, 0), Vector3(0, 0, 0), Vector3(0, 1, 0), Vector3(1, 1, 0)], 1],
		[Vector3.RIGHT, [Vector3(1, 0, 1), Vector3(1, 0, 0), Vector3(1, 1, 0), Vector3(1, 1, 1)], 1],
		[Vector3.LEFT, [Vector3(0, 0, 0), Vector3(0, 0, 1), Vector3(0, 1, 1), Vector3(0, 1, 0)], 1],
	]
	for f in faces:
		var tile: int = FACES[t][f[2]]
		var u0 := (tile * 72 + 4.5) / aw
		var u1 := (tile * 72 + 67.5) / aw
		var v0 := 4.5 / 72.0
		var v1 := 67.5 / 72.0
		# corners go bottom-left, bottom-right, top-right, top-left (top face: front-left first)
		var uvs := [Vector2(u0, v1), Vector2(u1, v1), Vector2(u1, v0), Vector2(u0, v0)]
		var shade := 1.0
		if f[0] == Vector3.DOWN:
			shade = 0.6
		elif f[0] != Vector3.UP:
			shade = 0.86
		var c: Array = f[1]
		for i in [0, 2, 1, 0, 3, 2]:
			st.set_color(Color(shade, shade, shade))
			st.set_normal(f[0])
			st.set_uv(uvs[i])
			st.add_vertex(c[i])
	return st.commit()


func _build_mesh_nodes() -> void:
	var mat := StandardMaterial3D.new()
	mat.albedo_texture = load("res://art/atlas.png")
	mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	mat.vertex_color_use_as_albedo = true
	mat.roughness = 1.0
	mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	for t in ATLAS_TILES:
		var mmi := MultiMeshInstance3D.new()
		var mm := MultiMesh.new()
		mm.transform_format = MultiMesh.TRANSFORM_3D
		if t != AIR:
			mm.mesh = _cube_mesh(t)
		mmi.multimesh = mm
		mmi.material_override = mat
		add_child(mmi)
		mm_nodes.append(mmi)


func _exposed(x: int, y: int, z: int) -> bool:
	if y + 1 >= LY or get_cell(x, y + 1, z) == AIR:
		return true
	if z + 1 >= LZ or get_cell(x, y, z + 1) == AIR:
		return true
	if x == 0 or x == LX - 1:
		return true
	if get_cell(x - 1, y, z) == AIR or get_cell(x + 1, y, z) == AIR:
		return true
	if y > 0 and get_cell(x, y - 1, z) == AIR:
		return true
	if z > 0 and get_cell(x, y, z - 1) == AIR:
		return true
	return false


## Draws every visible block once (at the start). After that, set_cell() only
## touches the few blocks around a change, so mining never freezes the game.
## Each block type's MultiMesh keeps spare room; slots[t] says which block is in each slot.
func rebuild() -> void:
	built = true
	slot_of.clear()
	slots.clear()
	var lists := []
	for t in ATLAS_TILES:
		lists.append([])
		slots.append([])
	for z in LZ:
		for y in LY:
			for x in LX:
				var t: int = cells[idx(x, y, z)]
				if t != AIR and _exposed(x, y, z):
					lists[t].append(Vector3i(x, y, z))
	for t in range(1, ATLAS_TILES):
		var mm := mm_nodes[t].multimesh
		var pts: Array = lists[t]
		mm.instance_count = pts.size() + SPARE
		for i in pts.size():
			mm.set_instance_transform(i, Transform3D(Basis.IDENTITY, Vector3(pts[i])))
			slot_of[pts[i]] = Vector2i(t, i)
		slots[t] = pts
		mm.visible_instance_count = pts.size()


const SPARE := 2000


func _refresh(c: Vector3i) -> void:
	if not inside(c.x, c.y, c.z):
		return
	var t := get_cell(c.x, c.y, c.z)
	var show := t != AIR and _exposed(c.x, c.y, c.z)
	if slot_of.has(c):
		var e: Vector2i = slot_of[c]
		if show and e.x == t:
			return
		_hide(c, e)
	if show:
		_show(c, t)


func _show(c: Vector3i, t: int) -> void:
	var arr: Array = slots[t]
	var mm := mm_nodes[t].multimesh
	if arr.size() >= mm.instance_count:
		rebuild()   # ran out of spare room (lots of digging): redraw once with fresh room
		return
	mm.set_instance_transform(arr.size(), Transform3D(Basis.IDENTITY, Vector3(c)))
	slot_of[c] = Vector2i(t, arr.size())
	arr.append(c)
	mm.visible_instance_count = arr.size()


func _hide(c: Vector3i, e: Vector2i) -> void:
	var arr: Array = slots[e.x]
	var mm := mm_nodes[e.x].multimesh
	var last: Vector3i = arr.back()
	if last != c:   # move the last block into the freed slot
		arr[e.y] = last
		mm.set_instance_transform(e.y, Transform3D(Basis.IDENTITY, Vector3(last)))
		slot_of[last] = Vector2i(e.x, e.y)
	arr.pop_back()
	slot_of.erase(c)
	mm.visible_instance_count = arr.size()


# ------------------------------------------------------------------ scenery
const TOY_PROPS := ["tree_a", "tree_b", "tree_c", "bush", "flower_pink", "flower_blue", "flower_white", "cloud",
	"hill_a", "hill_b", "hill_c", "coin", "heart", "flag", "sign", "spark", "arrow", "spikeball", "dennis"]


## A prop standing with its bottom at pos: a 3D toy in the toy look, else a paper cutout.
func prop(tex_name: String, pos: Vector3, size: float, shadow := false, flip := false) -> Node3D:
	if Look.toy and tex_name in TOY_PROPS:
		var t := Toys.prop(tex_name, size)
		t.position = pos
		if flip:
			t.rotation.y = PI
		add_child(t)
		return t
	return paper(tex_name, pos, size, shadow, flip)


func paper(tex_name: String, pos: Vector3, size: float, shadow := false, flip := false) -> Sprite3D:
	var s := Sprite3D.new()
	s.texture = load("res://art/" + tex_name + ".png")
	s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	s.shaded = false
	s.alpha_cut = SpriteBase3D.ALPHA_CUT_DISCARD if shadow else SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
	s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	s.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON if shadow else GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	s.pixel_size = size / s.texture.get_height()
	s.position = pos + Vector3(0, size * 0.5, 0)
	s.flip_h = flip
	add_child(s)
	return s


func _scenery() -> void:
	var trees := ["tree_a", "tree_b", "tree_c"]
	var x := 2.0
	while x < LX - 2:
		var cx := int(x)
		if solid(cx, heights[cx] - 1, 1):
			prop(trees[rng.randi() % 3], Vector3(x, heights[cx], rng.randf_range(0.8, 2.2)), rng.randf_range(3.2, 4.4), true, rng.randf() < 0.5)
		x += rng.randf_range(3.0, 7.0)
	x = 1.0
	while x < LX - 1:
		var cx := int(x)
		if solid(cx, heights[cx] - 1, 2):
			var what: String = ["bush", "flower_pink", "flower_blue", "flower_white", "flower_pink"][rng.randi() % 5]
			prop(what, Vector3(x, heights[cx], rng.randf_range(2.1, 2.9)), 0.8 if what == "bush" else 0.6)
		x += rng.randf_range(1.2, 3.0)
	# far hills and clouds (they're big and far so they move slowly: parallax for free)
	var hx := -20.0
	while hx < LX + 40:
		var h: String = ["hill_a", "hill_b", "hill_c"][rng.randi() % 3]
		var hs := prop(h, Vector3(hx, -2, rng.randf_range(-34, -22)), rng.randf_range(9, 16))
		if hs is Sprite3D:
			hs.billboard = BaseMaterial3D.BILLBOARD_DISABLED
		hx += rng.randf_range(10, 18)
	for i in 34:
		var c := prop("cloud", Vector3(rng.randf_range(-20, LX + 30), rng.randf_range(13, 20), rng.randf_range(-30, -10)), rng.randf_range(2.5, 4.5))
		if c is Sprite3D:
			c.modulate = Color(1, 1, 1, 0.95)
	for s in signs:
		var board := prop("sign", s[0], 2.3)
		var label := Label3D.new()
		label.text = s[1]
		label.font_size = 34
		label.pixel_size = 0.0058
		label.outline_size = 0
		label.modulate = Color("4a2c14")
		label.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		label.position = board.position + Vector3(0, 0.43, 0.03)
		if Look.toy:
			label.position = s[0] + Vector3(0, 1.36, 0.07)
			label.billboard = BaseMaterial3D.BILLBOARD_DISABLED
		label.width = 320
		label.no_depth_test = false
		add_child(label)
		var lines: int = String(s[1]).count("\n") + 1
		if lines > 3:
			label.font_size = 28
