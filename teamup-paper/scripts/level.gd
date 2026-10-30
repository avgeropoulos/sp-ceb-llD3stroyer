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

enum { AIR, GRASS, DIRT, STONE, IRON, GOLD, DIAMOND, BEDROCK, BRICK, MYSTERY, USED,
	PLANKS, WOOL_RED, WOOL_BLUE, WOOL_YELLOW, WOOL_WHITE, WINDOW, IRON_BLOCK, GOLD_BLOCK, DIAMOND_BLOCK, STONE_BRICK,
	SNOW, ICE, SAND, SANDSTONE, LOG, LEAVES, WOOL_GREEN, WOOL_BLACK, ROAD, SWAMP_GRASS, MUD, MOON_DUST, MOON_ROCK }
const NAMES := ["", "GRASS", "DIRT", "STONE", "IRON", "GOLD", "DIAMOND", "BEDROCK", "BRICK", "?", "BLOCK",
	"PLANKS", "RED", "BLUE", "YELLOW", "WHITE", "WINDOW", "IRON", "GOLD", "DIAMOND", "STONE BRICK",
	"SNOW", "ICE", "SAND", "SANDSTONE", "LOG", "LEAVES", "GREEN", "BLACK", "ROAD", "SWAMP GRASS", "MUD", "MOON DUST", "MOON ROCK"]
## How many pickaxe hits each block takes (999 = can't be mined).
const HITS := [0, 1, 1, 2, 3, 3, 4, 999, 2, 999, 999, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 2, 1, 2, 1, 1, 1, 1, 2, 1, 1, 1, 2]
## Atlas tile for the top, sides and bottom of each block type.
const FACES := [[0, 0, 0], [0, 1, 2], [2, 2, 2], [3, 3, 3], [4, 4, 4], [5, 5, 5], [6, 6, 6], [7, 7, 7], [8, 8, 8], [9, 9, 9], [10, 10, 10],
	[11, 11, 11], [12, 12, 12], [13, 13, 13], [14, 14, 14], [15, 15, 15], [16, 16, 16], [17, 17, 17], [18, 18, 18], [19, 19, 19], [20, 20, 20],
	[21, 22, 2], [23, 23, 23], [24, 24, 24], [25, 25, 25], [26, 27, 26], [28, 28, 28], [29, 29, 29], [30, 30, 30], [31, 31, 31],
	[32, 33, 34], [34, 34, 34], [35, 36, 36], [36, 36, 36]]
const COLORS := [Color.WHITE, Color("60be50"), Color("96663f"), Color("8a8e96"), Color("dfbaa0"), Color("ffd23c"), Color("5ae6e6"), Color("3c3c46"), Color("c45c3a"), Color("2cbec8"), Color("967860"),
	Color("c4925a"), Color("de423c"), Color("3e6edc"), Color("face3c"), Color("ececf0"), Color("96d2f5"), Color("dcdce2"), Color("ffce3a"), Color("60e8e6"), Color("96989e"),
	Color("eef4fc"), Color("96d2f0"), Color("ecce8c"), Color("d6aa6a"), Color("70502e"), Color("3e9a44"), Color("4cb048"), Color("34343e"), Color("4a4c52"),
	Color("4a6046"), Color("543e3a"), Color("c4c6ce"), Color("96989e")]
const ATLAS_TILES := 37       # pictures in art/atlas.png
const BLOCK_TYPES := 34       # block kinds (AIR .. MOON_ROCK)
const HOME_W := 44            # the OLD home plot was 44 x 10 (old saves are moved into the middle of the new one)
const PORTAL_X := 40.5
## The home world: a big square of land to build a whole city on.
const HOME_SX := 96
const HOME_SY := 40
const HOME_SZ := 96
const HOME_OX := 26           # where the old 44 x 10 plot sits inside the new one
const HOME_OZ := 44

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
var width := LX               # walls stop you at x = 0 and x = width
var sx := LX                  # size of the block grid (the home world is bigger)
var sy := LY
var sz := LZ
var is_home := false
var world := "meadow"         # which world this level is (see worlds.gd)
var open := false             # a big open world (home or an open level): turning camera, walk anywhere
var hmap := PackedInt32Array() # open worlds: ground height for each (x, z)
var start_pos := Vector3(4, 5, 6.8)
var arena := Rect2()          # open worlds: Krag Jr.'s castle (x, z)
var arena_y := 6              # its floor
var flag_pos := Vector3.ZERO
var gate: Array[Vector3i] = []   # the gold gate that 5 stars open
var locked := {}              # Vector3i -> true: blocks that can't be mined (castle walls)
var objectives: Array = []    # the 5 star challenges: {id, name, hint, pos}
var keep_out: Array = []      # [center, radius]: the challenges' space
var step_tops: Array = []     # SKY STEPS: where you stand on each step (for the test robot)
var tower_tops: Array = []    # TALL TOWER: the same for the stairs
var top_block := GRASS        # what the ground is made of here
var sub_block := DIRT


func idx(x: int, y: int, z: int) -> int:
	return x + sx * (y + sy * z)


func inside(x: int, y: int, z: int) -> bool:
	return x >= 0 and x < sx and y >= 0 and y < sy and z >= 0 and z < sz


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
	if x < 0 or x >= width:
		return y < sy + 6   # invisible walls at both ends of the level
	if open and (z < 0 or z >= sz):
		return y < sy + 6   # and all round an open world
	if y < 0 or y >= sy or z < 0 or z >= sz:
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
	for cy in range(mini(floori(y), sy - 1), -1, -1):
		if solid(cx, cy, cz):
			return cy + 1.0
	return -100.0


# ------------------------------------------------------------------ building the level
func generate(w: String = "meadow") -> void:
	world = w
	if w in OPEN_WORLDS:
		generate_open(w)
		return
	if w != "meadow":
		_generate_world(w)
		return
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


# ------------------------------------------------------------------ the other worlds
## Builds a world from pieces that are known to be fun and beatable. The start, the
## checkpoint (x 86-96) and the Krag Jr. arena (x 150-200) are always flat, like 1-1.
func _generate_world(w: String) -> void:
	rng.seed = {"frost": 4242, "dunes": 9001}.get(w, 7)
	top_block = SNOW if w == "frost" else SAND
	sub_block = DIRT if w == "frost" else SANDSTONE
	var foe2 := "penguin" if w == "frost" else "scorpion"
	cells.resize(LX * LY * LZ)
	cells.fill(AIR)
	heights.resize(LX)
	heights.fill(5)
	var pits := []
	var tops := {}        # x -> special top block (ice)
	var extra := []       # [x, y, z0, z1, block] rows to add after the ground
	var foes := []        # [kind, x]
	var pieces := ["hill", "pit", "qblocks", "hill2", "special", "orehill", "pit", "wall", "special", "bigpit", "qblocks", "hill", "special", "pit", "hill2"]
	var x := 15
	var used_big := false
	for piece in pieces:
		if x > 140:
			break
		if x >= 73 and x < 98:   # keep the checkpoint flat
			x = 98
			continue
		var wdt := 0
		match piece:
			"hill":
				wdt = rng.randi_range(6, 9)
				_hgt(x, x + wdt - 1, 6)
				for i in 4:
					coin_spots.append(Vector3(x + 1.5 + i * 1.2, 7.6, 6.5))
				foes.append(["grumbler", x + wdt - 2])
			"hill2":
				wdt = 8
				_hgt(x, x + 1, 6)
				_hgt(x + 2, x + 5, 7)
				_hgt(x + 6, x + 7, 6)
				foes.append([foe2, x + 4])
			"pit":
				wdt = 3 + rng.randi_range(0, 1) * 2
				pits.append([x + 1, x + wdt - 2 if wdt > 3 else x + 2])
				for i in 3:
					coin_spots.append(Vector3(x + 0.8 + i * 1.0, 7.6 + sin(i * 1.4) * 0.5, 6.5))
			"bigpit":
				if used_big or x + 12 > 150:
					continue
				used_big = true
				wdt = 12
				pits.append([x + 1, x + 10])
				var plat := ICE if w == "frost" else SANDSTONE
				for px in [x + 3, x + 4, x + 7, x + 8]:
					extra.append([px, 5, 4, 8, plat])
				for i in 8:
					coin_spots.append(Vector3(x + 1.5 + i * 1.2, 7.5 + sin(i * 0.8) * 0.8, 6.5))
			"qblocks":
				wdt = 8
				for i in 3:
					extra.append([x + 2 + i * 2, 8, 5, 7, MYSTERY])
				extra.append([x + 3, 8, 5, 7, BRICK])
				foes.append([foe2, x + 6])
			"orehill":
				wdt = 10
				_hgt(x, x + 1, 6)
				_hgt(x + 2, x + 7, 8)
				_hgt(x + 8, x + 9, 6)
				for i in 4:
					coin_spots.append(Vector3(x + 2.5 + i * 1.5, 9.5, 5.5 + (i % 2) * 2))
				foes.append(["skelly", x + 5])
			"wall":
				wdt = 6
				for y in range(6, 9):
					extra.append([x + 3, y, 3, LZ - 1, STONE])
				foes.append(["grumbler", x + 1])
			"special":
				wdt = 10
				if w == "frost":   # a slippery ice field
					for i in range(x + 1, x + 9):
						tops[i] = ICE
					for i in 5:
						coin_spots.append(Vector3(x + 1.5 + i * 1.6, 6.5, 4.5 + i))
				else:              # sandstone pillars to hop over
					for px in [x + 2, x + 5, x + 8]:
						for y in [5, 6]:
							extra.append([px, y, 3, LZ - 1, SANDSTONE])
						coin_spots.append(Vector3(px + 0.5, 8.6, 6.5))
				foes.append([foe2, x + 6])
		x += wdt + rng.randi_range(1, 3)
	# the ground
	for cx in LX:
		var pit := false
		for p in pits:
			if cx >= p[0] and cx <= p[1]:
				pit = true
		if pit:
			continue
		var h: int = heights[cx]
		for z in LZ:
			set_cell(cx, 0, z, BEDROCK)
			for y in range(1, h):
				var t := STONE
				if y >= h - 2:
					t = sub_block
				if y == h - 1:
					t = tops.get(cx, top_block)
				if t == STONE:
					t = _ore(cx, y)
				set_cell(cx, y, z, t)
	for e in extra:
		for z in range(e[2], e[3] + 1):
			set_cell(e[0], e[1], z, e[4])
	_row(170, 9, MYSTERY, 5, 7)
	for i in 5:
		coin_spots.append(Vector3(145.5 + i * 1.3, 6.5, 4.5 + i))
	# a few more along the way
	for i in 5:
		foes.append([[foe2, "grumbler", foe2, "skelly", foe2][i], [27, 58, 104, 120, 137][i]])
	# who lives where (never on top of a pit or inside a block)
	for f in foes:
		var fx: int = clampi(f[1], 20, 148)
		if heights[fx] > 0 and get_cell(fx, heights[fx] - 1, 6) != AIR and get_cell(fx, heights[fx], 6) == AIR:
			spawns.append({"kind": f[0], "pos": Vector3(fx + 0.5, heights[fx] + 0.01, 5.5 + rng.randf_range(0, 2))})
	spawns.append({"kind": "kragjr", "pos": Vector3(176, 10, 6.5)})
	var hello: String = {"frost": "FROSTY PEAKS\nThe ice is slippery!\nWatch the penguins!",
		"dunes": "SUNSET DUNES\nHop the pillars!\nScorpions lunge!"}.get(w, "")
	signs = [[Vector3(6.5, 5, 3.6), hello], [Vector3(89.0, 5, 3.6), "CHECKPOINT!"],
		[Vector3(152.0, 5, 3.6), "DANGER!\nKrag Jr. ahead!"]]
	_build_mesh_nodes()
	_scenery()
	rebuild()


## The sky map has no ground of its own (the islands are separate).
func generate_empty() -> void:
	world = "map"
	cells.resize(LX * LY * LZ)
	cells.fill(AIR)
	heights.resize(LX)
	heights.fill(0)
	_build_mesh_nodes()
	rebuild()


## The home world: a big flat square of grass to build on, some block trees to
## chop (or keep), and the rocket pad. The old 44 x 10 plot sits in the middle.
func generate_home() -> void:
	is_home = true
	open = true
	sx = HOME_SX
	sy = HOME_SY
	sz = HOME_SZ
	width = HOME_SX
	rng.seed = 77
	cells.resize(sx * sy * sz)
	cells.fill(AIR)
	heights.resize(sx)
	heights.fill(5)
	for z in sz:
		for x in sx:
			cells[idx(x, 0, z)] = BEDROCK
			cells[idx(x, 1, z)] = STONE
			cells[idx(x, 2, z)] = STONE
			cells[idx(x, 3, z)] = DIRT
			cells[idx(x, 4, z)] = GRASS
	# block trees, away from the middle where you start building
	var placed := 0
	var tries := 0
	while placed < 26 and tries < 400:
		tries += 1
		var tx := rng.randi_range(4, sx - 5)
		var tz := rng.randi_range(4, sz - 5)
		if tx > HOME_OX - 6 and tx < HOME_OX + HOME_W + 6 and tz > HOME_OZ - 8 and tz < HOME_OZ + 18:
			continue
		if get_cell(tx, 5, tz) != AIR or get_cell(tx + 2, 6, tz) != AIR or get_cell(tx - 2, 6, tz) != AIR \
				or get_cell(tx, 6, tz + 2) != AIR or get_cell(tx, 6, tz - 2) != AIR:
			continue
		_block_tree(tx, tz)
		placed += 1
	signs = [[Vector3(HOME_OX + 3.5, 5, HOME_OZ + 3.6), "HOME SWEET HOME\nBUILD and DECOR\nto make it yours!"],
		[Vector3(HOME_OX + 37.5, 5, HOME_OZ + 3.6), "ROCKET\nFly to\nthe worlds!"]]
	_build_mesh_nodes()
	_home_scenery()
	rebuild()


## A Minecraft-style tree: a log trunk with a blob of leaves.
func _block_tree(x: int, z: int, base := 5, snowy := false) -> void:
	var h := rng.randi_range(4, 6)
	for y in range(base, base + h):
		cells[idx(x, y, z)] = LOG
	var top := base + h
	for dy in range(-2, 2):
		var r := 2 if dy < 0 else 1
		for dx in range(-r, r + 1):
			for dz in range(-r, r + 1):
				if absi(dx) == r and absi(dz) == r and (dy == 1 or rng.randf() < 0.5):
					continue
				if inside(x + dx, top + dy, z + dz) and cells[idx(x + dx, top + dy, z + dz)] == AIR:
					cells[idx(x + dx, top + dy, z + dz)] = SNOW if snowy and dy == 1 else LEAVES


## Around the home: grass going off to the hills, trees, far hills and clouds.
func _home_scenery(ground_tex := "grass_top", trees: Array = ["tree_a", "tree_b", "tree_c"], hills: Array = ["hill_a", "hill_b", "hill_c"]) -> void:
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://art/t_%s.png" % ground_tex)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	m.uv1_triplanar = true
	m.uv1_scale = Vector3(1, 1, 1)
	m.roughness = 1.0
	var far := 160.0
	# four flat strips around the plot (not under it, so digging never shows them)
	for r in [Rect2(-far, -far, sx + far * 2, far), Rect2(-far, sz, sx + far * 2, far), Rect2(-far, 0, far, sz), Rect2(sx, 0, far, sz)]:
		var q := MeshInstance3D.new()
		var pm := PlaneMesh.new()
		pm.size = r.size
		q.mesh = pm
		q.material_override = m
		q.position = Vector3(r.position.x + r.size.x * 0.5, 5.0, r.position.y + r.size.y * 0.5)
		add_child(q)
	# a ring of trees just outside the edge, so the edge looks like a forest
	var a := 0.0
	for side in 4:
		var t := -2.0
		while t < float(sx) + 2:
			var p: Vector3
			var out := rng.randf_range(1.6, 4.5)
			match side:
				0: p = Vector3(t, 5, -out)
				1: p = Vector3(t, 5, sz + out)
				2: p = Vector3(-out, 5, t)
				_: p = Vector3(sx + out, 5, t)
			prop(trees[rng.randi() % trees.size()], p, rng.randf_range(3.6, 5.0), false, rng.randf() < 0.5)
			t += rng.randf_range(3.2, 5.5)
	var c := Vector3(sx * 0.5, 0, sz * 0.5)
	while a < TAU:
		var d := rng.randf_range(100, 130)
		var hs := prop(hills[rng.randi() % hills.size()], c + Vector3(cos(a) * d, 2.5, sin(a) * d), rng.randf_range(16, 26))
		if hs is Sprite3D:
			hs.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		a += rng.randf_range(0.18, 0.3)
	for i in 30:
		var cl := prop("cloud", c + Vector3(rng.randf_range(-110, 110), rng.randf_range(24, 34), rng.randf_range(-110, 110)), rng.randf_range(4, 8))
		if cl is Sprite3D:
			cl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	for sg in signs:
		_sign(sg)


# ------------------------------------------------------------------ open worlds
const OPEN_WORLDS := ["meadow", "frost", "dunes", "swamp", "moon"]
const OPEN_S := 96
const STAR_COINS := 40        # coins to collect for the COIN HUNT star


func ground_at(x: int, z: int) -> int:
	if x < 0 or z < 0 or x >= sx or z >= sz:
		return 0
	return hmap[x + z * sx]


## A big open world: rolling hills, trees, five star challenges and Krag Jr.'s castle.
## Frosty Peaks is a mirror image of the layout so it feels like a new place.
func generate_open(w: String) -> void:
	world = w
	open = true
	sx = OPEN_S
	sy = 40
	sz = OPEN_S
	width = sx
	rng.seed = {"meadow": 2026, "frost": 4242, "dunes": 9001, "swamp": 6666, "moon": 1969}[w]
	top_block = {"meadow": GRASS, "frost": SNOW, "dunes": SAND, "swamp": SWAMP_GRASS, "moon": MOON_DUST}[w]
	sub_block = {"meadow": DIRT, "frost": DIRT, "dunes": SANDSTONE, "swamp": MUD, "moon": MOON_ROCK}[w]
	var theme: int = {"meadow": PLANKS, "frost": SNOW, "dunes": SANDSTONE, "swamp": LOG, "moon": IRON_BLOCK}[w]
	var foe2: String = {"meadow": "grumbler", "frost": "penguin", "dunes": "scorpion", "swamp": "skelly", "moon": "grumbler"}[w]
	var mirror := w == "frost" or w == "moon"
	var P := func(x: int, z: int) -> Vector2i: return Vector2i(sx - 1 - x if mirror else x, z)
	var start: Vector2i = P.call(48, 86)
	var camp: Vector2i = P.call(18, 64)
	var tower: Vector2i = P.call(77, 62)
	var cave: Vector2i = P.call(16, 32)
	var steps: Vector2i = P.call(78, 36)
	arena = Rect2(30, 3, 36, 23)
	arena_y = 6
	cells.resize(sx * sy * sz)
	cells.fill(AIR)
	heights.resize(sx)
	heights.fill(6)
	hmap.resize(sx * sz)
	var craters := []
	if w == "moon":
		for i in 9:
			craters.append([Vector2(rng.randf_range(8, sx - 8), rng.randf_range(30, sz - 8)), rng.randf_range(3.5, 7.0), rng.randf_range(2.5, 4.0)])
	var n := FastNoiseLite.new()
	n.seed = rng.seed
	n.frequency = 0.03
	n.fractal_octaves = 3
	# flat places: [center, radius, height]; nothing random (trees, rocks, bad guys) grows there
	var flats := [[start, 9, 6], [camp, 9, 6], [tower, 6, 6], [steps, 10, 6], [cave, 12, 6]]
	keep_out = []
	for fl in flats:
		keep_out.append([Vector2(fl[0]), float(fl[1]) + 3.0])
	for z in sz:
		for x in sx:
			var h := 6.0 + n.get_noise_2d(x, z) * 6.0
			if w == "frost":
				h += 1.0 + absf(n.get_noise_2d(x * 0.5 + 300, z * 0.5)) * 4.0   # taller, peaky
			elif w == "swamp":
				h = 5.0 + n.get_noise_2d(x, z) * 4.0   # low and boggy
			elif w == "moon":   # flat dusty plains with round craters
				h = 7.0 + n.get_noise_2d(x * 0.6, z * 0.6) * 2.5
				for cr in craters:
					var cd := Vector2(x, z).distance_to(cr[0])
					if cd < cr[1]:
						h -= cos(cd / cr[1] * PI * 0.5) * cr[2]
					elif cd < cr[1] + 1.5:
						h += 1.0   # the rim
			for f in flats:
				var d := Vector2(x - f[0].x, z - f[0].y).length()
				if d < f[1]:
					h = f[2]
				elif d < f[1] + 6:
					h = lerpf(f[2], h, (d - f[1]) / 6.0)
			# the castle sits on flat ground too
			var ad := maxf(maxf(arena.position.x - 4 - x, x - arena.end.x - 4), maxf(arena.position.y - 4 - z, z - arena.end.y - 4))
			if ad < 0:
				h = arena_y
			elif ad < 6:
				h = lerpf(arena_y, h, ad / 6.0)
			hmap[x + z * sx] = clampi(int(round(h)), 3, 14)
	# the secret cave is inside a big hill
	for z in sz:
		for x in sx:
			var d := Vector2(x - cave.x, z - cave.y).length()
			if d < 9:
				hmap[x + z * sx] = maxi(hmap[x + z * sx], 6 + int(round(cos(d / 9.0 * PI * 0.5) * 9)))
	# fill the ground
	for z in sz:
		for x in sx:
			var h := hmap[x + z * sx]
			cells[idx(x, 0, z)] = BEDROCK
			for y in range(1, h):
				var t := STONE
				if y >= h - 2:
					t = sub_block
				if y == h - 1:
					t = top_block
					if w == "frost" and h <= 4:
						t = ICE   # frozen ponds in the low spots
					elif w == "swamp" and h <= 4:
						t = MUD   # muddy bog in the low spots
				if t == STONE:
					t = _ore(x, y)
				cells[idx(x, y, z)] = t
	_castle(theme)
	_camp(camp, foe2)
	_tower(tower, theme)
	_cave(cave)
	_sky_steps(steps, theme)
	# coins: trails from the start to every challenge, and the COIN HUNT star at the start
	for to in [camp, tower, cave, steps, Vector2i(int(arena.get_center().x), int(arena.end.y) + 3)]:
		var a := Vector2(start)
		var b := Vector2(to)
		var k := 3.0
		while k < a.distance_to(b) - 4:
			var q := a.lerp(b, k / a.distance_to(b))
			var g := ground_at(int(q.x), int(q.y))
			if get_cell(int(q.x), g, int(q.y)) == AIR and get_cell(int(q.x), g + 1, int(q.y)) == AIR:
				coin_spots.append(Vector3(int(q.x) + 0.5, g + 1.0, int(q.y) + 0.5))
			k += 3.2
	objectives.append({"id": "coins", "name": "COIN HUNT", "hint": "Collect %d coins in this world!" % STAR_COINS,
		"pos": Vector3(start.x + 3.5, 7.0, start.y - 2.5)})
	# ? blocks to bonk near the start
	for i in 4:
		set_cell(start.x - 3 + i * 2, 9, start.y - 6, MYSTERY)
	# ore rocks to mine
	for i in 7:
		var o := _free_spot(10)
		if o.x < 0:
			continue
		var g := ground_at(o.x, o.y)
		for dx in 2:
			for dz in 2:
				for dy in 2:
					set_cell(o.x + dx, g + dy, o.y + dz, [IRON, GOLD, STONE, DIAMOND, IRON][rng.randi() % 5] if rng.randf() < 0.6 else STONE)
	# trees
	for i in {"meadow": 34, "frost": 26, "dunes": 0, "swamp": 0, "moon": 0}[w]:
		var o := _free_spot(4)
		if o.x < 3 or o.y < 3 or o.x > sx - 4 or o.y > sz - 4:
			continue
		var g := ground_at(o.x, o.y)
		if get_cell(o.x, g, o.y) == AIR and get_cell(o.x, g - 1, o.y) != ICE:
			_block_tree(o.x, o.y, g, w == "frost")
	if w == "dunes":   # sandstone pillars and cacti
		for i in 14:
			var o := _free_spot(3)
			if o.x < 0:
				continue
			var g := ground_at(o.x, o.y)
			for y in rng.randi_range(2, 4):
				set_cell(o.x, g + y, o.y, SANDSTONE)
		for i in 26:
			var o := _free_spot(2)
			if o.x >= 0:
				prop(["cactus_a", "cactus_b", "palm", "desert_rock"][i % 4], Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), [2.4, 1.4, 3.6, 0.8][i % 4], true)
	if w == "frost":
		for i in 10:
			var o := _free_spot(2)
			if o.x >= 0:
				prop(["snowman", "snowpile"][i % 2], Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), [1.6, 0.8][i % 2], true)
	if w == "swamp":   # dead trees, pumpkins and gravestones
		for i in 30:
			var o := _free_spot(4)
			if o.x < 3 or o.y < 3 or o.x > sx - 4 or o.y > sz - 4:
				continue
			var g := ground_at(o.x, o.y)
			if get_cell(o.x, g, o.y) == AIR:
				_dead_tree(o.x, o.y, g)
		for i in 22:
			var o := _free_spot(2)
			if o.x < 0:
				continue
			var g := ground_at(o.x, o.y)
			if i % 2 == 0:
				_decor_prop("pumpkin", Vector3(o.x + 0.5, g, o.y + 0.5), rng.randi() % 4)
			else:   # a little gravestone
				set_cell(o.x, g, o.y, STONE_BRICK)
				set_cell(o.x, g + 1, o.y, STONE_BRICK)
	if w == "moon":   # moon rocks and shiny ore
		for i in 18:
			var o := _free_spot(2)
			if o.x >= 0:
				prop("desert_rock", Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), rng.randf_range(0.7, 1.6), true)
		for i in 10:
			var o := _free_spot(3)
			if o.x < 0:
				continue
			var g := ground_at(o.x, o.y)
			for y in rng.randi_range(1, 3):
				set_cell(o.x, g + y - 1, o.y, [MOON_ROCK, DIAMOND, IRON][rng.randi() % 3])
	if w == "meadow":
		for i in 30:
			var o := _free_spot(1)
			if o.x >= 0:
				prop(["bush", "flower_pink", "flower_blue", "flower_white"][i % 4], Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), 0.8 if i % 4 == 0 else 0.6)
	# bad guys wandering about
	var kinds := ["grumbler", foe2, "grumbler", foe2, "skelly", foe2]
	for i in 16:
		var o := _free_spot(6)
		if o.x < 0 or Vector2(o).distance_to(Vector2(start)) < 14:
			continue
		var g := ground_at(o.x, o.y)
		if get_cell(o.x, g, o.y) == AIR and get_cell(o.x, g + 1, o.y) == AIR:
			spawns.append({"kind": kinds[i % kinds.size()], "pos": Vector3(o.x + 0.5, g + 0.01, o.y + 0.5)})
	start_pos = Vector3(start.x + 0.5, ground_at(start.x, start.y) + 0.01, start.y + 0.5)
	var hello: String = {"meadow": "MEADOW MINE", "frost": "FROSTY PEAKS", "dunes": "SUNSET DUNES", "swamp": "SPOOKY SWAMP", "moon": "MOON BASE"}[w]
	signs = [[start_pos + Vector3(-3, 0, -2), hello + "\nFind 5 STARS to open\nKrag Jr.'s castle!"]]
	_build_mesh_nodes()
	var tex: String = {"meadow": "grass_top", "frost": "snow_top", "dunes": "sand", "swamp": "swamp_top", "moon": "moon_top"}[w]
	var trees: Array = {"meadow": ["tree_a", "tree_b", "tree_c"], "frost": ["pine_a", "pine_b"], "dunes": ["palm", "cactus_a"],
		"swamp": ["pine_b", "tree_c"], "moon": ["desert_rock"]}[w]
	var hills: Array = {"meadow": ["hill_a", "hill_b", "hill_c"], "frost": ["hill_snow", "hill_snow", "hill_c"], "dunes": ["hill_sand"],
		"swamp": ["hill_b", "hill_c"], "moon": ["hill_snow"]}[w]
	_home_scenery(tex, trees, hills)
	rebuild()


## A spooky dead tree: a bare trunk with crooked branches.
func _dead_tree(x: int, z: int, base: int) -> void:
	var h := rng.randi_range(4, 6)
	for y in range(base, base + h):
		cells[idx(x, y, z)] = LOG
	for d in [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
		if rng.randf() < 0.6:
			var y := base + h - rng.randi_range(1, 2)
			for k in range(1, 3):
				var c := Vector3i(x + d.x * k, y + k - 1, z + d.y * k)
				if inside(c.x, c.y, c.z) and cells[idx(c.x, c.y, c.z)] == AIR:
					cells[idx(c.x, c.y, c.z)] = LOG


## A decoration standing in the world (a 3D toy, or its paper picture).
func _decor_prop(id: String, pos: Vector3, r := 0) -> Node3D:
	if Look.toy:
		var m := Decor.model(id)
		m.position = pos
		m.rotation.y = r * PI / 2
		add_child(m)
		return m
	return paper("decor_" + id, pos, 1.0)


## A random spot on open ground away from the challenges and the castle (or -1, -1).
func _free_spot(keep: float) -> Vector2i:
	for tries in 60:
		var x := rng.randi_range(3, sx - 4)
		var z := rng.randi_range(3, sz - 4)
		if Rect2(arena.position - Vector2(5, 5), arena.size + Vector2(10, 10)).has_point(Vector2(x, z)):
			continue
		var ok := true
		for k in keep_out:
			if Vector2(x, z).distance_to(k[0]) < k[1] + keep * 0.3:
				ok = false
		if ok and get_cell(x, ground_at(x, z), z) == AIR:
			return Vector2i(x, z)
	return Vector2i(-1, -1)


## Krag Jr.'s castle: tall stone walls (can't be mined), a gold gate in the front.
func _castle(theme: int) -> void:
	var x0 := int(arena.position.x)
	var x1 := int(arena.end.x)
	var z0 := int(arena.position.y)
	var z1 := int(arena.end.y)
	var top := arena_y + 6
	var mid := (x0 + x1) / 2
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			var wall := x == x0 or x == x1 or z == z0 or z == z1
			for y in range(1, top + 1 if wall else arena_y):
				var c := Vector3i(x, y, z)
				if wall:
					var t := STONE_BRICK
					if z == z1 and absi(x - mid) <= 1 and y >= arena_y and y < arena_y + 4:
						t = GOLD_BLOCK
						gate.append(c)
					elif y == top and (x + z) % 2 == 0:
						t = AIR   # battlements
					if t != AIR:
						cells[idx(x, y, z)] = t
						locked[c] = true
				elif y == arena_y - 1:
					cells[idx(x, y, z)] = STONE_BRICK if (x + z) % 2 == 0 else theme
					locked[c] = true
	flag_pos = Vector3(mid + 0.5, arena_y, z0 + 4.5)
	spawns.append({"kind": "kragjr", "pos": Vector3(mid + 0.5, arena_y + 4.5, (z0 + z1) * 0.5)})
	signs.append([Vector3(mid + 3.5, arena_y, z1 + 1.6), "KRAG JR.'S CASTLE\n5 STARS open\nthe gold gate!"])


func _camp(c: Vector2i, foe2: String) -> void:
	var g := ground_at(c.x, c.y)
	for i in 5:
		var a := TAU * i / 5.0
		var p := Vector3(c.x + 0.5 + cos(a) * 4.0, g + 0.01, c.y + 0.5 + sin(a) * 4.0)
		spawns.append({"kind": ["grumbler", foe2, "grumbler", "skelly", foe2][i], "pos": p, "camp": true})
	objectives.append({"id": "camp", "name": "BAD GUY CAMP", "hint": "Beat all 5 bad guys at the camp!",
		"pos": Vector3(c.x + 0.5, g + 0.9, c.y + 0.5)})


## A tower with stairs going round it; the star is on the top.
func _tower(c: Vector2i, theme: int) -> void:
	var g := ground_at(c.x, c.y)
	var ring := []
	for i in 4:
		ring.append(Vector2i(-2 + i, -2))
	for i in 4:
		ring.append(Vector2i(2, -2 + i))
	for i in 4:
		ring.append(Vector2i(2 - i, 2))
	for i in 4:
		ring.append(Vector2i(-2, 2 - i))
	var height := ring.size() + 1
	for dx in range(-1, 2):
		for dz in range(-1, 2):
			for y in range(g, g + height):
				set_cell(c.x + dx, y, c.y + dz, STONE_BRICK if (y - g) % 4 else theme)
	for i in ring.size():
		var r: Vector2i = ring[i]
		set_cell(c.x + r.x, g + i, c.y + r.y, theme if i % 2 else STONE_BRICK)
		tower_tops.append(Vector3(c.x + r.x + 0.5, g + i + 1, c.y + r.y + 0.5))
	tower_tops.append(Vector3(c.x + 0.5, g + height, c.y + 0.5))
	set_cell(c.x, g + height + 3, c.y, MYSTERY)
	objectives.append({"id": "tower", "name": "TALL TOWER", "hint": "Climb the stairs round the tower!",
		"pos": Vector3(c.x + 0.5, g + height + 0.9, c.y + 0.5)})


## A hill with a tunnel; the end is blocked with rock: dig through with Blocky!
func _cave(c: Vector2i) -> void:
	var g := 6
	for z in range(c.y - 2, c.y + 12):
		for x in range(c.x, c.x + 2):
			for y in range(g, g + 3):
				set_cell(x, y, z, AIR)
	for z in range(c.y - 4, c.y + 1):
		for x in range(c.x - 2, c.x + 4):
			for y in range(g, g + 4):
				set_cell(x, y, z, AIR)
	for x in range(c.x, c.x + 2):   # the rock plug
		for y in range(g, g + 3):
			set_cell(x, y, c.y + 2, [STONE, IRON, STONE][y - g])
			set_cell(x, y, c.y + 3, [STONE, GOLD, STONE][y - g])
	for p in [Vector3i(c.x - 3, g + 1, c.y - 2), Vector3i(c.x + 4, g + 1, c.y - 3), Vector3i(c.x, g + 4, c.y - 2)]:
		set_cell(p.x, p.y, p.z, DIAMOND)
	objectives.append({"id": "cave", "name": "SECRET CAVE", "hint": "Dig through the rock with Blocky!",
		"pos": Vector3(c.x + 1.0, g + 0.9, c.y - 2.0)})


## Floating platforms going up and up to a little island in the sky.
func _sky_steps(c: Vector2i, theme: int) -> void:
	var g := ground_at(c.x, c.y)
	var p := Vector2i(c.x - 2, c.y + 6)
	var y := g + 1
	# each step is 2 blocks higher with a 1-block gap: an easy jump for Rocco
	var hops := [Vector2i(3, 0), Vector2i(0, -3), Vector2i(-3, 0), Vector2i(0, -3), Vector2i(3, 0), Vector2i(0, -3), Vector2i(-3, 0)]
	for hop in hops:
		for dx in 2:
			for dz in 2:
				set_cell(p.x + dx, y, p.y + dz, theme)
		step_tops.append(Vector3(p.x + 1.0, y + 1, p.y + 1.0))
		p += hop
		y += 2
	for dx in range(-2, 2):   # the island (kept out from over the last steps, so no head bumps)
		for dz in range(-1, 3):
			set_cell(p.x + dx, y, p.y + dz, theme)
	step_tops.append(Vector3(p.x, y + 1, p.y + 1.0))
	objectives.append({"id": "sky", "name": "SKY STEPS", "hint": "Jump up the floating steps! Rocco's double jump helps!",
		"pos": Vector3(p.x, y + 1.9, p.y + 1.0)})


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
	for t in BLOCK_TYPES:
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
	if y + 1 >= sy or get_cell(x, y + 1, z) == AIR:
		return true
	if z + 1 >= sz or get_cell(x, y, z + 1) == AIR:
		return true
	if x == 0 or x == sx - 1:
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
	for t in BLOCK_TYPES:
		lists.append([])
		slots.append([])
	for z in sz:
		for y in sy:
			var base := idx(0, y, z)
			if cells.slice(base, base + sx).count(AIR) == sx:
				continue   # a whole row of sky: nothing to draw
			var up := sx
			var back := sx * sy
			for x in sx:
				var i := base + x
				var t: int = cells[i]
				if t == AIR:
					continue
				# same test as _exposed(), written out so the big home draws quickly
				if y + 1 >= sy or cells[i + up] == AIR or z + 1 >= sz or cells[i + back] == AIR \
						or x == 0 or x == sx - 1 or cells[i - 1] == AIR or cells[i + 1] == AIR \
						or (y > 0 and cells[i - up] == AIR) or (z > 0 and cells[i - back] == AIR):
					lists[t].append(Vector3i(x, y, z))
	for t in range(1, BLOCK_TYPES):
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
		_grow(t)   # ran out of spare room (lots of building): make room for this block type only
	mm.set_instance_transform(arr.size(), Transform3D(Basis.IDENTITY, Vector3(c)))
	slot_of[c] = Vector2i(t, arr.size())
	arr.append(c)
	mm.visible_instance_count = arr.size()


func _grow(t: int) -> void:
	var arr: Array = slots[t]
	var mm := mm_nodes[t].multimesh
	mm.instance_count = arr.size() + SPARE
	for i in arr.size():
		mm.set_instance_transform(i, Transform3D(Basis.IDENTITY, Vector3(arr[i])))
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
	"hill_a", "hill_b", "hill_c", "coin", "heart", "flag", "sign", "spark", "arrow", "spikeball", "dennis", "shot_water", "shot_fire",
	"pine_a", "pine_b", "snowman", "snowpile", "cactus_a", "cactus_b", "palm", "desert_rock", "hill_snow", "hill_sand", "rocket"]


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
	var trees: Array = {"frost": ["pine_a", "pine_b", "pine_a", "snowman"], "dunes": ["cactus_a", "palm", "cactus_b", "palm"]}.get(world, ["tree_a", "tree_b", "tree_c"])
	var smalls: Array = {"frost": ["snowpile", "snowpile", "desert_rock"], "dunes": ["desert_rock", "cactus_b", "desert_rock"]}.get(world, ["bush", "flower_pink", "flower_blue", "flower_white", "flower_pink"])
	var hills: Array = {"frost": ["hill_snow", "hill_snow", "hill_c"], "dunes": ["hill_sand", "hill_sand", "hill_sand"]}.get(world, ["hill_a", "hill_b", "hill_c"])
	var x := 2.0
	while x < width - 2:
		var cx := int(x)
		if solid(cx, heights[cx] - 1, 1):
			var tk: String = trees[rng.randi() % trees.size()]
			prop(tk, Vector3(x, heights[cx], rng.randf_range(0.8, 2.2)), rng.randf_range(3.2, 4.4) * (0.5 if tk == "snowman" else 1.0), true, rng.randf() < 0.5)
		x += rng.randf_range(3.0, 7.0)
	x = 1.0
	while x < width - 1:
		var cx := int(x)
		if solid(cx, heights[cx] - 1, 2):
			var what: String = smalls[rng.randi() % smalls.size()]
			prop(what, Vector3(x, heights[cx], rng.randf_range(2.1, 2.9)), 0.8 if what in ["bush", "snowpile", "desert_rock"] else (1.2 if what == "cactus_b" else 0.6))
		x += rng.randf_range(1.2, 3.0)
	# far hills and clouds (they're big and far so they move slowly: parallax for free)
	var hx := -20.0
	while hx < width + 40:
		var h: String = hills[rng.randi() % hills.size()]
		var hs := prop(h, Vector3(hx, -2, rng.randf_range(-34, -22)), rng.randf_range(9, 16))
		if hs is Sprite3D:
			hs.billboard = BaseMaterial3D.BILLBOARD_DISABLED
		hx += rng.randf_range(10, 18)
	for i in 34:
		var c := prop("cloud", Vector3(rng.randf_range(-20, width + 30), rng.randf_range(13, 20), rng.randf_range(-30, -10)), rng.randf_range(2.5, 4.5))
		if c is Sprite3D:
			c.modulate = Color(1, 1, 1, 0.95)
	for s in signs:
		_sign(s)


func _sign(s: Array) -> void:
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
