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
	SNOW, ICE, SAND, SANDSTONE, LOG, LEAVES, WOOL_GREEN, WOOL_BLACK, ROAD, SWAMP_GRASS, MUD, MOON_DUST, MOON_ROCK,
	LAVA, BASALT, FROSTING, CAKE, CANDY_CANE, WATER, CLOUD, LANTERN }
const NAMES := ["", "GRASS", "DIRT", "STONE", "IRON", "GOLD", "DIAMOND", "BEDROCK", "BRICK", "?", "BLOCK",
	"PLANKS", "RED", "BLUE", "YELLOW", "WHITE", "WINDOW", "IRON", "GOLD", "DIAMOND", "STONE BRICK",
	"SNOW", "ICE", "SAND", "SANDSTONE", "LOG", "LEAVES", "GREEN", "BLACK", "ROAD", "SWAMP GRASS", "MUD", "MOON DUST", "MOON ROCK",
	"LAVA", "BASALT", "FROSTING", "CAKE", "CANDY CANE", "WATER", "CLOUD", "LANTERN"]
## How many pickaxe hits each block takes (999 = can't be mined).
const HITS := [0, 1, 1, 2, 3, 3, 4, 999, 2, 999, 999, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 2, 1, 2, 1, 1, 1, 1, 2, 1, 1, 1, 2, 999, 2, 1, 1, 2, 999, 1, 1]
## Atlas tile for the top, sides and bottom of each block type.
const FACES := [[0, 0, 0], [0, 1, 2], [2, 2, 2], [3, 3, 3], [4, 4, 4], [5, 5, 5], [6, 6, 6], [7, 7, 7], [8, 8, 8], [9, 9, 9], [10, 10, 10],
	[11, 11, 11], [12, 12, 12], [13, 13, 13], [14, 14, 14], [15, 15, 15], [16, 16, 16], [17, 17, 17], [18, 18, 18], [19, 19, 19], [20, 20, 20],
	[21, 22, 2], [23, 23, 23], [24, 24, 24], [25, 25, 25], [26, 27, 26], [28, 28, 28], [29, 29, 29], [30, 30, 30], [31, 31, 31],
	[32, 33, 34], [34, 34, 34], [35, 36, 36], [36, 36, 36],
	[37, 37, 37], [38, 38, 38], [39, 40, 41], [41, 41, 41], [42, 42, 42], [43, 43, 43], [44, 44, 44], [45, 45, 45]]
const COLORS := [Color.WHITE, Color("60be50"), Color("96663f"), Color("8a8e96"), Color("dfbaa0"), Color("ffd23c"), Color("5ae6e6"), Color("3c3c46"), Color("c45c3a"), Color("2cbec8"), Color("967860"),
	Color("c4925a"), Color("de423c"), Color("3e6edc"), Color("face3c"), Color("ececf0"), Color("96d2f5"), Color("dcdce2"), Color("ffce3a"), Color("60e8e6"), Color("96989e"),
	Color("eef4fc"), Color("96d2f0"), Color("ecce8c"), Color("d6aa6a"), Color("70502e"), Color("3e9a44"), Color("4cb048"), Color("34343e"), Color("4a4c52"),
	Color("4a6046"), Color("543e3a"), Color("c4c6ce"), Color("96989e"),
	Color("ff8a2a"), Color("4a424c"), Color("faa8ce"), Color("ecc48c"), Color("e83440"), Color("3a96de"), Color("f4f8ff"), Color("ffd678")]
const ATLAS_TILES := 46       # pictures in art/atlas.png
const BLOCK_TYPES := 42       # block kinds (AIR .. LANTERN)
const HOME_W := 44            # the OLD home plot was 44 x 10 (old saves are moved into the middle of the new one)
const PORTAL_X := 40.5
## The home world: a big land with the flat build plot in the middle, rolling hills,
## a lake, a ring of mountains, caves underground and the painting castle.
const HOME_SX := 192
const HOME_SY := 56
const HOME_SZ := 192
const HOME_GY := 22           # standing height on the home plot (the grass top is at HOME_GY - 1)
## Where the 96 x 96 home (saves before v3, ground at y 5) sits inside this one.
const HOME_OLD := Vector3i(48, HOME_GY - 5, 48)
const HOME_OX := 74           # where the old 44 x 10 plot (the start) sits: HOME_OLD + (26, 44)
const HOME_OZ := 92
## A flat, empty spot kept clear for the ARCADE building (x, z, width, depth; ground at HOME_GY).
const ARCADE_SPOT := Rect2i(148, 88, 14, 14)
## The painting castle (x, z, width, depth) in the north hills, door facing the plot.
const CASTLE := Rect2i(80, 14, 32, 26)
const MINE := Vector3i(44, 0, 104)       # the mine entrance west of the plot (y = ground there)

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
var paintings: Array = []     # home castle: [{pos (centre on the wall), normal, w, h}] in painting order
var summit := Vector3.ZERO    # home: the top of the mountain stairs
var lake := Vector3.ZERO      # home: the middle of the lake
var cave_rooms: Array = []    # home: centres of cave rooms (for tests / lanterns)
# drawing in chunks (the big home): one MultiMesh per (chunk, top-only?, block type)
var chunked := false
const CH := 32                # chunk size (x and z)
const SPARE_CHUNK := 128
var ncx := 1
var ncz := 1
var band_y := 0               # blocks below this are the "deep" band (caves): only drawn when you're near
var edge_solid := false       # outside the grid counts as solid (no faces on the world's sides)
var touched := {}             # Vector3i -> true: cells that differ from the plain height-map ground
var chunk_nodes := {}         # chunk index -> [nodes]
var _meshes: Array = []       # per type: [cube, top-only quad]
var _mats: Array = []         # per type: material
var _vis_t := 0.0
var objectives: Array = []    # the star challenges (Mario 64 style missions): {id, name, hint, pos}
var red_spots: Array = []     # RED COINS: where the 8 red coins float
var dog_spot := Vector3.ZERO  # LOST DOG: where Dennis is lost
var dog_home := Vector3.ZERO  # ... and his doghouse
var bonus := false            # a painting's BONUS COURSE (see generate_bonus)
var painting := {}            # the secret painting in an open world: {pos, w, h, x, z, bottom}
var bonus_tops: Array = []    # BONUS COURSE: where you stand on each platform, start to star
var bonus_star := Vector3.ZERO
var keep_out: Array = []      # [center, radius]: the challenges' space
# Mario 64-style course (see _course_heights / _course_build):
var summit_spot := Vector3.ZERO  # standing spot in the middle of the flat 9x9 top of the big mountain
var mountain := Vector2.ZERO     # its centre (x, z)
var ramp_s := {}                 # Vector2i -> 0..1: cells of the spiral path (0 = bottom, 1 = top)
var cannon_spot := Vector3.ZERO  # walk into the cannon to fly to the floating island
var island_spot := Vector3.ZERO  # standing spot in the middle of the floating island
var chomp_spot := Vector3.ZERO   # the Chompy's fenced corner
var slide := false               # Frosty Peaks: the inside lane of the mountain path is an ice slide
var landmark := Vector3.ZERO     # each world's own building (cabin, mansion, ship, pyramid room)
var _c_mx := 1.0                 # -1 when the world is mirrored
var _c_top := {}                 # Vector2i -> block: the top block of a column (paths, ponds, bridges)
var _c_bridge := {}              # Vector2i -> y: wooden bridge plank at this height (air below)
var _c_mount := {}               # Vector2i -> true: columns that belong to the mountain
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
		if chunked:
			touched[Vector3i(x, y, z)] = true
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


## The home world: the flat build plot in the middle (the 96 x 96 home from before, so old
## builds stay put), rolling hills, a lake, a ring of mountains with a stairway to a summit,
## caves underground (a mine entrance by the plot and holes in the hillsides) full of ores,
## lanterns and a lava pool, and the painting castle in the north hills.
static var prof := {}   # timing of the home's parts (tests)


func _lap(name: String, t0: int) -> int:
	prof[name] = (Time.get_ticks_usec() - t0) / 1000.0
	return Time.get_ticks_usec()


func generate_home() -> void:
	var t0 := Time.get_ticks_usec()
	is_home = true
	open = true
	chunked = true
	edge_solid = true
	sx = HOME_SX
	sy = HOME_SY
	sz = HOME_SZ
	width = HOME_SX
	band_y = HOME_GY - 4
	touched = {}
	heights.resize(sx)
	heights.fill(HOME_GY)
	if _load_home_cache(cache_path):
		_lap("cache", t0)
		return
	var gy := HOME_GY
	var n := FastNoiseLite.new()
	n.seed = 77
	n.frequency = 0.022
	n.fractal_octaves = 3
	var mn := FastNoiseLite.new()
	mn.seed = 78
	mn.frequency = 0.03
	var old := Rect2(HOME_OLD.x, HOME_OLD.z, 96, 96)
	var flats := [[old, 10.0], [Rect2(CASTLE.position.x - 3, CASTLE.position.y - 2, CASTLE.size.x + 6, CASTLE.size.y + 8), 7.0],
		[Rect2(ARCADE_SPOT.position.x, ARCADE_SPOT.position.y, ARCADE_SPOT.size.x, ARCADE_SPOT.size.y), 6.0],
		[Rect2(MINE.x - 6, MINE.z - 6, 10, 12), 5.0]]
	lake = Vector3(64.5, gy - 1, 158.5)
	var stair_z := 128
	var stair_x0 := 146
	# ---- the height of every column
	hmap.resize(sx * sz)
	var tops := PackedByteArray()
	tops.resize(sx * sz)
	for z in sz:
		for x in sx:
			var h := gy + n.get_noise_2d(x, z) * 5.0
			var d := mini(mini(x, z), mini(sx - 1 - x, sz - 1 - z))
			h = lerpf(gy, h, clampf((d - 1) / 6.0, 0.0, 1.0))   # level with the land outside at the very edge
			if d < 34:   # the mountain ring (low again right at the edge)
				var tt := clampf((d - 3) / 31.0, 0.0, 1.0)
				var amp := 30.0 * (0.4 + 0.6 * (mn.get_noise_2d(x, z) + 1.0) * 0.5)
				h += amp * sin(tt * PI) * (1.0 if d >= 3 else 0.0)
			for f in flats:
				var r: Rect2 = f[0]
				var dd := maxf(maxf(r.position.x - x, x - r.end.x + 1), maxf(r.position.y - z, z - r.end.y + 1))
				if dd <= 0:
					h = gy
				elif dd < f[1]:
					h = lerpf(gy, h, dd / f[1])
			var ld := Vector2(x + 0.5 - lake.x, z + 0.5 - lake.z).length()
			var top := GRASS
			if ld < 9.0:
				h = gy - 1
				top = WATER
			elif ld < 11.0:
				h = gy
				top = SAND
			# the mountain stairs: a 3-wide stairway climbing east to the summit
			if z >= stair_z and z < stair_z + 3 and x >= stair_x0 and x < stair_x0 + 36:
				h = gy + (x - stair_x0) / 2
			var hi := clampi(int(round(h)), 6, sy - 4)
			if top == GRASS and hi - 1 >= 42:
				top = SNOW
			elif top == GRASS and hi - 1 >= 34:
				top = STONE
			hmap[x + z * sx] = hi
			tops[x + z * sx] = top
	t0 = _lap("heights", t0)
	summit = Vector3(stair_x0 + 36.5, hmap[stair_x0 + 35 + (stair_z + 1) * sx], stair_z + 1.5)
	# ---- fill the ground: an Image whose rows are the cell rows, filled with rectangles (fast)
	var img := Image.create(sx, sy * sz, false, Image.FORMAT_R8)
	img.fill(_lum(STONE))
	for z in sz:
		img.fill_rect(Rect2i(0, sy * z, sx, 1), _lum(BEDROCK))
		for x in sx:
			var h := hmap[x + z * sx]
			var top: int = tops[x + z * sx]
			img.fill_rect(Rect2i(x, sy * z + h, 1, sy - h), _lum(AIR))
			img.set_pixel(x, sy * z + h - 1, _lum(top))
			var sub := DIRT if top == GRASS else (SAND if top in [WATER, SAND] else STONE)
			if top == SNOW:
				sub = STONE
			img.fill_rect(Rect2i(x, sy * z + h - 3, 1, 2), _lum(sub))
	cells = img.get_data()
	t0 = _lap("fill", t0)
	rng.seed = 77
	_home_old_trees()
	_home_ores()
	t0 = _lap("ores", t0)
	_home_caves()
	t0 = _lap("caves", t0)
	_home_trees(n)
	_castle_home()
	t0 = _lap("trees+castle", t0)
	# the summit: a little platform with a diamond prize
	var sp := Vector3i(int(summit.x), int(summit.y), int(summit.z))
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			_put(sp.x + dx, sp.y - 1, sp.z + dz, STONE_BRICK)
			for y in range(sp.y, sp.y + 4):
				_put(sp.x + dx, y, sp.z + dz, AIR)
	_put(sp.x + 2, sp.y, sp.z + 2, DIAMOND_BLOCK)
	_put(sp.x + 2, sp.y, sp.z - 2, GOLD_BLOCK)
	signs = [[Vector3(HOME_OX + 3.5, gy, HOME_OZ + 3.6), "HOME SWEET HOME\nBUILD and DECOR\nto make it yours!"],
		[Vector3(HOME_OX + 37.5, gy, HOME_OZ + 3.6), "ROCKET\nFly to\nthe worlds!"],
		[Vector3(MINE.x + 2.5, MINE_Y, MINE.z + 3.6), "THE MINE\nCaves and ores!\nDig with BUILD"],
		[Vector3(CASTLE.position.x + CASTLE.size.x * 0.5 + 4.5, gy, CASTLE.end.y + 2.6), "PAINTING CASTLE\nJump into a painting\nto visit a world!"],
		[Vector3(stair_x0 - 1.5, gy, stair_z - 0.6), "MOUNTAIN\nClimb the stairs\nto the summit!"],
		[summit + Vector3(-1.5, 0, -1.5), "THE SUMMIT!\nYou made it!"]]
	_build_mesh_nodes()
	_home_scenery()
	t0 = _lap("scenery", t0)
	rebuild()
	t0 = _lap("rebuild", t0)



# ------------------------------------------------------------------ the baked home
## The home's starting world is the same every time, so the app build bakes it once
## (tools/bake_home.gd, run by CI before export) and the game loads it in a blink instead
## of making it again. Without the file (tests, editor runs) the home is made as normal.
const HOME_CACHE := "res://data/home_cache.bin"
const HOME_CACHE_V := 1
static var cache_path := HOME_CACHE   # tests point this somewhere else


func save_home_cache(path: String) -> bool:
	var lists := {}
	var bufs := {}
	for k in slots.size():
		var arr: Array = slots[k]
		if arr.is_empty() or mm_nodes[k] == null:
			continue
		var ids := PackedInt32Array()
		ids.resize(arr.size())
		for i in arr.size():
			var c: Vector3i = arr[i]
			ids[i] = c.x + sx * (c.y + sy * c.z)
		lists[k] = ids
		bufs[k] = mm_nodes[k].multimesh.buffer
	var tch := PackedInt32Array()
	for c in touched:
		tch.append(c.x + sx * (c.y + sy * c.z))
	var lck := PackedInt32Array()
	for c in locked:
		lck.append(c.x + sx * (c.y + sy * c.z))
	var d := {"v": HOME_CACHE_V, "size": [sx, sy, sz], "cells": cells, "hmap": hmap, "touched": tch, "locked": lck,
		"lists": lists, "buffers": bufs, "paintings": paintings, "summit": summit, "lake": lake,
		"cave_rooms": cave_rooms, "signs": signs, "mine_y": MINE_Y}
	DirAccess.make_dir_recursive_absolute(path.get_base_dir())
	var f := FileAccess.open_compressed(path, FileAccess.WRITE, FileAccess.COMPRESSION_ZSTD)
	if f == null:
		return false
	f.store_var(d)
	f.close()
	return true


## Loads the baked home (true) or says it can't (false: make it instead).
func _load_home_cache(path: String) -> bool:
	if not FileAccess.file_exists(path):
		return false
	var f := FileAccess.open_compressed(path, FileAccess.READ, FileAccess.COMPRESSION_ZSTD)
	if f == null:
		return false
	var d = f.get_var()
	f.close()
	if not (d is Dictionary) or int(d.get("v", 0)) != HOME_CACHE_V or d.get("size", []) != [sx, sy, sz]:
		return false
	cells = d.cells
	hmap = d.hmap
	if cells.size() != sx * sy * sz or hmap.size() != sx * sz:
		return false
	var row := sx * sy
	touched = {}
	for i in (d.touched as PackedInt32Array):
		touched[Vector3i(i % sx, (i / sx) % sy, i / row)] = true
	locked = {}
	for i in (d.locked as PackedInt32Array):
		locked[Vector3i(i % sx, (i / sx) % sy, i / row)] = true
	paintings = d.paintings
	summit = d.summit
	lake = d.lake
	cave_rooms = d.cave_rooms
	signs = d.signs
	MINE_Y = d.mine_y
	_build_mesh_nodes()
	_home_scenery()
	built = true
	slot_of.clear()
	slots.clear()
	slots.resize(mm_nodes.size())
	for k in slots.size():
		slots[k] = []
	var lists: Dictionary = d.lists
	var bufs: Dictionary = d.buffers
	for k in lists:
		var ids: PackedInt32Array = lists[k]
		var pts := []
		pts.resize(ids.size())
		for j in ids.size():
			var i := ids[j]
			var c := Vector3i(i % sx, (i / sx) % sy, i / row)
			pts[j] = c
			slot_of[c] = Vector2i(k, j)
		var mm := _node(k).multimesh
		var b: PackedFloat32Array = bufs[k]
		mm.instance_count = b.size() / 16
		mm.buffer = b
		mm.visible_instance_count = pts.size()
		slots[k] = pts
	return true


## A block type as a colour the Image can store exactly (see generate_home).
static func _lum(t: int) -> Color:
	return Color((t + 0.25) / 255.0, 0, 0)


## Writes a cell during generation and remembers it differs from the plain ground.
func _put(x: int, y: int, z: int, t: int) -> void:
	if x < 0 or y < 1 or z < 0 or x >= sx or y >= sy or z >= sz:
		return
	cells[x + sx * (y + sy * z)] = t
	touched[Vector3i(x, y, z)] = true


## The trees of the old 96 x 96 home, in the same places (old saves chopped some of them).
func _home_old_trees() -> void:
	var o := HOME_OLD
	var placed := 0
	var tries := 0
	while placed < 26 and tries < 400:
		tries += 1
		var tx := rng.randi_range(4, 91)
		var tz := rng.randi_range(4, 91)
		if tx > 26 - 6 and tx < 26 + HOME_W + 6 and tz > 44 - 8 and tz < 44 + 18:
			continue
		if get_cell(o.x + tx, HOME_GY, o.z + tz) != AIR or get_cell(o.x + tx + 2, HOME_GY + 1, o.z + tz) != AIR \
				or get_cell(o.x + tx - 2, HOME_GY + 1, o.z + tz) != AIR or get_cell(o.x + tx, HOME_GY + 1, o.z + tz + 2) != AIR \
				or get_cell(o.x + tx, HOME_GY + 1, o.z + tz - 2) != AIR:
			continue
		_tree_at(o.x + tx, o.z + tz, HOME_GY)
		placed += 1


func _tree_at(x: int, z: int, base: int, extra := 0) -> void:
	var h := rng.randi_range(4, 6) + extra
	for y in range(base, base + h):
		_put(x, y, z, LOG)
	var top := base + h
	for dy in range(-2, 2):
		var r := 2 if dy < 0 else 1
		for dx in range(-r, r + 1):
			for dz in range(-r, r + 1):
				if absi(dx) == r and absi(dz) == r and (dy == 1 or rng.randf() < 0.5):
					continue
				if inside(x + dx, top + dy, z + dz) and get_cell(x + dx, top + dy, z + dz) == AIR:
					_put(x + dx, top + dy, z + dz, LEAVES)


## Forests on the hills around the plot (none on the plot, the castle or the stairs).
func _home_trees(n: FastNoiseLite) -> void:
	var placed := 0
	for i in 900:
		if placed >= 110:
			break
		var x := rng.randi_range(4, sx - 5)
		var z := rng.randi_range(4, sz - 5)
		if _keep_clear(x, z, 3):
			continue
		if n.get_noise_2d(x * 2.0 + 500, z * 2.0) < 0.05:
			continue   # forests grow in patches
		var g := hmap[x + z * sx]
		if g > 40 or get_cell(x, g - 1, z) != GRASS or get_cell(x, g, z) != AIR:
			continue
		var ok := true
		for d in [Vector2i(2, 0), Vector2i(-2, 0), Vector2i(0, 2), Vector2i(0, -2)]:
			if get_cell(x + d.x, g + 2, z + d.y) != AIR:
				ok = false
		if ok:
			_tree_at(x, z, g, 1 if rng.randf() < 0.3 else 0)
			placed += 1


## Places that must stay as they are: the old plot, the castle, the arcade spot, the mine
## entrance, the lake and the mountain stairs.
func _keep_clear(x: int, z: int, m: int) -> bool:
	for r in [Rect2i(HOME_OLD.x, HOME_OLD.z, 96, 96), Rect2i(CASTLE.position - Vector2i(3, 2), CASTLE.size + Vector2i(6, 8)),
			ARCADE_SPOT, Rect2i(MINE.x - 8, MINE.z - 6, 14, 12), Rect2i(144, 125, 44, 9)]:
		if x >= r.position.x - m and x < r.end.x + m and z >= r.position.y - m and z < r.end.y + m:
			return true
	return Vector2(x - lake.x, z - lake.z).length() < 12 + m


## Ore veins in the stone: iron everywhere, gold deeper, diamonds near the bottom.
func _home_ores() -> void:
	for i in 1400:
		var x := rng.randi_range(2, sx - 3)
		var z := rng.randi_range(2, sz - 3)
		var y := rng.randi_range(1, HOME_GY - 5)
		var t := IRON
		var r := rng.randf()
		if y < 8 and r < 0.3:
			t = DIAMOND
		elif y < 14 and r < 0.45:
			t = GOLD
		var c := Vector3i(x, y, z)
		for k in rng.randi_range(2, 5):
			if get_cell(c.x, c.y, c.z) == STONE:
				cells[idx(c.x, c.y, c.z)] = t   # same solidity: no need to remember it
			c += [Vector3i(1, 0, 0), Vector3i(-1, 0, 0), Vector3i(0, 1, 0), Vector3i(0, -1, 0), Vector3i(0, 0, 1), Vector3i(0, 0, -1)][rng.randi() % 6]


## Caves: a mine entrance by the plot sloping down to a big room, tunnels winding off
## from it, more tunnels deep down, and a few that come out in the hillsides.
func _home_caves() -> void:
	cave_rooms = []
	# the mine: a 3-wide tunnel going down to the west, framed with logs and planks
	var mx := MINE.x
	var mz := MINE.z
	var g := hmap[mx + mz * sx]
	MINE_Y = g
	var y := g
	var x := mx
	var step := 0
	while y > 8:
		for dz in range(-1, 2):
			for dy in range(0, 4):
				_carve_cell(x, y + dy, mz + dz)
			_put(x, y - 1, mz + dz, PLANKS)
		if step % 6 == 0:
			_put(x, y + 3, mz - 2, LANTERN)
		x -= 1
		step += 1
		if step % 2 == 0:
			y -= 1
	# the entrance frame
	for dy in range(0, 4):
		_put(mx + 1, g + dy, mz - 2, LOG)
		_put(mx + 1, g + dy, mz + 2, LOG)
	for dz in range(-2, 3):
		_put(mx + 1, g + 4, mz + dz, PLANKS)
	var room := Vector3(x - 3, y + 2, mz)
	_room(room, 5.0)
	_put(int(room.x), int(room.y) + 4, int(room.z), LANTERN)
	# tunnels winding off from the mine room, and others deep down
	for i in 5:
		_worm(room, Vector3.RIGHT.rotated(Vector3.UP, rng.randf() * TAU), 60, 1.4, 2.2, 4, HOME_GY - 7)
	for i in 12:
		var p := Vector3(rng.randf_range(12, sx - 12), rng.randf_range(5, HOME_GY - 8), rng.randf_range(12, sz - 12))
		_worm(p, Vector3.RIGHT.rotated(Vector3.UP, rng.randf() * TAU), 55, 1.3, 2.3, 3, HOME_GY - 7)
	# holes in the hillsides that lead down into the caves
	var made := 0
	for i in 300:
		if made >= 5:
			break
		var hx := rng.randi_range(20, sx - 21)
		var hz := rng.randi_range(20, sz - 21)
		var hg := hmap[hx + hz * sx]
		if hg < HOME_GY + 6 or hg > HOME_GY + 16 or _keep_clear(hx, hz, 6):
			continue
		var to := Vector3(sx * 0.5, 8, sz * 0.5) - Vector3(hx, hg, hz)
		_worm(Vector3(hx, hg - 1, hz), to.normalized(), 40, 1.5, 2.1, 3, sy - 3, true)
		made += 1
	# a lava pool at the bottom of a deep cave room
	var lava_room := Vector3(sx * 0.5 + 30, 6, sz * 0.5 - 40)
	_room(lava_room, 4.0)
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			if get_cell(int(lava_room.x) + dx, int(lava_room.y) - 3, int(lava_room.z) + dz) == AIR:
				_put(int(lava_room.x) + dx, int(lava_room.y) - 3, int(lava_room.z) + dz, LAVA)


var MINE_Y := HOME_GY   # the ground height at the mine entrance (set when the home is made)


func _carve_cell(x: int, y: int, z: int) -> void:
	if x < 1 or z < 1 or x >= sx - 1 or z >= sz - 1 or y < 1 or y >= sy:
		return
	if get_cell(x, y, z) != AIR:
		_put(x, y, z, AIR)


func _room(c: Vector3, r: float) -> void:
	cave_rooms.append(c)
	for dx in range(-ceili(r), ceili(r) + 1):
		for dy in range(-ceili(r * 0.6), ceili(r * 0.6) + 1):
			for dz in range(-ceili(r), ceili(r) + 1):
				if Vector3(dx, dy / 0.6, dz).length() <= r:
					_carve_cell(int(c.x) + dx, int(c.y) + dy, int(c.z) + dz)


## A wiggly tunnel. Under the build plot it stays well below the grass.
func _worm(p: Vector3, dir: Vector3, steps: int, r0: float, r1: float, ymin: int, ymax: int, from_surface := false) -> void:
	for s in steps:
		var r := rng.randf_range(r0, r1)
		var top := ymax
		if not from_surface and _keep_clear(int(p.x), int(p.z), 2):
			top = mini(ymax, HOME_GY - 7)
		var ir := ceili(r)
		for dx in range(-ir, ir + 1):
			for dy in range(-ir, ir + 1):
				for dz in range(-ir, ir + 1):
					if dx * dx + dy * dy + dz * dz <= r * r:
						var cy := int(p.y) + dy
						if cy <= top + ir:
							_carve_cell(int(p.x) + dx, cy, int(p.z) + dz)
		if s % 9 == 4:   # a lantern on the wall now and then
			var side := Vector3.RIGHT.rotated(Vector3.UP, rng.randf() * TAU)
			for k in range(ir, ir + 4):
				var q := p + side * k
				if get_cell(int(q.x), int(p.y), int(q.z)) != AIR:
					_put(int(q.x), int(p.y), int(q.z), LANTERN)
					break
		dir = (dir + Vector3(rng.randf_range(-0.5, 0.5), rng.randf_range(-0.25, 0.25), rng.randf_range(-0.5, 0.5))).normalized()
		if from_surface and s < 12:
			dir.y = -0.45   # go down into the hill first
		p += dir * r * 0.8
		p.x = clampf(p.x, 4, sx - 5)
		p.z = clampf(p.z, 4, sz - 5)
		if p.y < ymin or (p.y > ymax and not from_surface):
			dir.y = -dir.y
			p.y = clampf(p.y, ymin, ymax)


## The painting castle: stone walls, red towers, a carpet down the middle and a painting
## for every world on the walls inside (HomeBase dresses them; see `paintings`).
func _castle_home() -> void:
	var gy := HOME_GY
	var x0 := CASTLE.position.x
	var z0 := CASTLE.position.y
	var x1 := CASTLE.end.x - 1
	var z1 := CASTLE.end.y - 1
	var hgt := 9
	var door := (x0 + x1) / 2
	for x in range(x0, x1 + 1):
		for z in range(z0, z1 + 1):
			var wall := x == x0 or x == x1 or z == z0 or z == z1
			_lock(x, gy - 1, z, STONE_BRICK if not (x == door or x == door - 1 or x == door + 1) or z == z0 else WOOL_RED)
			for y in range(gy, gy + hgt + 1):
				if wall:
					var t := STONE_BRICK
					if z == z1 and absi(x - door) <= 2 and y < gy + 5:
						t = AIR   # the door
					elif z == z1 and absi(x - door) == 3 and y < gy + 6:
						t = GOLD_BLOCK
					elif y == gy + hgt and (x + z) % 2 == 1:
						t = AIR   # battlements
					elif (x == x0 or x == x1) and y == gy + 6 and (z - z0) % 4 == 2:
						t = WINDOW
					if t == AIR:
						_put(x, y, z, AIR)
					else:
						_lock(x, y, z, t)
				else:
					_put(x, y, z, AIR)
			if not (x == x0 or x == x1 or z == z0 or z == z1):
				_lock(x, gy + hgt - 1, z, STONE_BRICK if (x + z) % 3 else WINDOW)   # the ceiling (with skylights)
	# towers at the front corners with red pointy roofs
	for cx in [x0, x1]:
		for dx in range(-2, 3):
			for dz in range(-2, 3):
				for y in range(gy, gy + hgt + 4):
					if absi(dx) == 2 or absi(dz) == 2:
						_lock(cx + dx, y, z1 + dz, STONE_BRICK)
				for k in 4:
					if absi(dx) <= 2 - k / 2 and absi(dz) <= 2 - k / 2:
						_lock(cx + dx, gy + hgt + 4 + k, z1 + dz, WOOL_RED)
	# paintings: on the back wall and both side walls, facing in
	paintings = []
	var slots_b := []
	var xs := x0 + 4
	while xs <= x1 - 4:
		slots_b.append(xs)
		xs += 5
	for xx in slots_b:
		paintings.append({"pos": Vector3(xx + 0.5, gy + 3.2, z0 + 1.02), "normal": Vector3(0, 0, 1)})
	var zz := z0 + 5
	while zz <= z1 - 6:
		paintings.append({"pos": Vector3(x0 + 1.02, gy + 3.2, zz + 0.5), "normal": Vector3(1, 0, 0)})
		paintings.append({"pos": Vector3(x1 - 0.02, gy + 3.2, zz + 0.5), "normal": Vector3(-1, 0, 0)})
		zz += 4


func _lock(x: int, y: int, z: int, t: int) -> void:
	_put(x, y, z, t)
	locked[Vector3i(x, y, z)] = true


## A Minecraft-style tree: a log trunk with a blob of leaves.
func _block_tree(x: int, z: int, base := 5, snowy := false, extra := 0) -> void:
	var h := rng.randi_range(4, 6) + extra
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


## Clouds in this world's colour (Worlds.INFO "cloud"; white if not set).
func _tint_cloud(cl: Node3D) -> void:
	var tc := Color.WHITE if is_home else Color(String(Worlds.info(world).get("cloud", "ffffff")))
	if tc == Color.WHITE:
		return
	if cl is Sprite3D:
		cl.modulate = tc
		return
	if _cloud_mat == null:
		_cloud_mat = Toys.mat(tc)
	for ch in cl.find_children("*", "MeshInstance3D", true, false):
		(ch as MeshInstance3D).material_override = _cloud_mat


var _cloud_mat: Material


## Cloud Castle: a sea of clouds far below and around the floating islands.
func _sky_clouds() -> void:
	var c := Vector3(sx * 0.5, 0, sz * 0.5)
	for i in 60:
		var p := c + Vector3(rng.randf_range(-130, 130), rng.randf_range(-16, -4), rng.randf_range(-130, 130))
		var cl := prop("cloud", p, rng.randf_range(8, 16))
		if cl is Sprite3D:
			cl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		_tint_cloud(cl)
	for i in 20:
		var cl := prop("cloud", c + Vector3(rng.randf_range(-120, 120), rng.randf_range(22, 34), rng.randf_range(-120, 120)), rng.randf_range(4, 8))
		if cl is Sprite3D:
			cl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		_tint_cloud(cl)


## Around the home: grass going off to the hills, trees, far hills and clouds.
func _home_scenery(ground_tex := "grass_top", trees: Array = ["tree_a", "tree_b", "tree_c"], hills: Array = ["hill_a", "hill_b", "hill_c"]) -> void:
	if ground_tex == "":   # up in the sky: no ground around, just clouds below
		_sky_clouds()
		for sg in signs:
			_sign(sg)
		return
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
		q.position = Vector3(r.position.x + r.size.x * 0.5, float(HOME_GY) if is_home else 5.0, r.position.y + r.size.y * 0.5)
		add_child(q)
	# a ring of trees just outside the edge, so the edge looks like a forest
	var a := 0.0
	for side in (4 if trees.size() > 0 else 0):
		var t := -2.0
		while t < float(sx) + 2:
			var p: Vector3
			var out := rng.randf_range(1.6, 4.5)
			var gy := HOME_GY if is_home else 5
			match side:
				0: p = Vector3(t, gy, -out)
				1: p = Vector3(t, gy, sz + out)
				2: p = Vector3(-out, gy, t)
				_: p = Vector3(sx + out, gy, t)
			prop(trees[rng.randi() % trees.size()], p, rng.randf_range(3.6, 5.0), false, rng.randf() < 0.5)
			t += rng.randf_range(3.2, 5.5)
	var c := Vector3(sx * 0.5, 0, sz * 0.5)
	while a < TAU:
		var d := rng.randf_range(100, 130)
		var hs: Node3D
		if is_home:   # big snowy mountains far away all round
			d = rng.randf_range(135, 175)
			hs = prop(["hill_snow", "hill_c", "hill_snow", "hill_b"][rng.randi() % 4], c + Vector3(cos(a) * d, HOME_GY - 4, sin(a) * d), rng.randf_range(34, 52))
		else:
			hs = prop(hills[rng.randi() % hills.size()], c + Vector3(cos(a) * d, 2.5, sin(a) * d), rng.randf_range(16, 26))
		if hs is Sprite3D:
			hs.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		a += rng.randf_range(0.18, 0.3)
	for i in 30:
		var cy := rng.randf_range(24, 34) + (HOME_GY + 22 if is_home else 0)
		var cl := prop("cloud", c + Vector3(rng.randf_range(-110, 110), cy, rng.randf_range(-110, 110)), rng.randf_range(4, 8))
		if cl is Sprite3D:
			cl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		_tint_cloud(cl)
	for sg in signs:
		_sign(sg)


# ------------------------------------------------------------------ open worlds
const OPEN_WORLDS := ["meadow", "frost", "dunes", "swamp", "moon", "lava", "candy", "beach", "jungle", "cloud"]
const OPEN_S := 96
const STAR_COINS := 40        # coins to collect for the COIN HUNT star


## Who wanders about in each open world (picked in turn). New worlds: add a line here.
const FOES_BY_WORLD := {
	"meadow": ["grumbler", "buzzer", "hopper", "grumbler", "bomber", "skelly", "spiky", "buzzer"],
	"frost": ["penguin", "roller", "boohoo", "penguin", "bomber", "skelly", "spiky", "roller"],
	"dunes": ["scorpion", "mummy", "cactusman", "scorpion", "bomber", "skelly", "spiky", "mummy"],
	"lava": ["bomber", "spiky", "skelly", "grumbler", "bomber", "cactusman", "spiky", "buzzer"],
	"candy": ["hopper", "buzzer", "grumbler", "boohoo", "hopper", "spiky", "bomber", "buzzer"],
	"beach": ["scorpion", "hopper", "buzzer", "scorpion", "spiky", "grumbler", "penguin", "hopper"],
	"jungle": ["hopper", "buzzer", "grumbler", "skelly", "hopper", "spiky", "bomber", "buzzer"],
	"cloud": ["buzzer", "boohoo", "skelly", "buzzer", "spiky", "grumbler", "boohoo", "bomber"],
	"default": ["grumbler", "buzzer", "hopper", "bomber", "skelly", "spiky", "boohoo", "mummy"],
}


## The bad guys that live in one special spot: bats under the sky steps, a Chompy on
## its post, and a BIG GRUMBLER guarding a ? block.
func _special_foes(start: Vector2i) -> void:
	for i in [2, 4]:
		if i < step_tops.size():
			var st: Vector3 = step_tops[i]
			spawns.append({"kind": "bat", "pos": Vector3(st.x, st.y - 1.0 - 0.75, st.z)})
	if chomp_spot != Vector3.ZERO:   # in its fenced corner (Mario 64's Chain Chomp)
		spawns.append({"kind": "chompy", "pos": chomp_spot})
	else:
		var c := _free_spot(8)
		if c.x >= 0 and Vector2(c).distance_to(Vector2(start)) > 14:
			spawns.append({"kind": "chompy", "pos": Vector3(c.x + 0.5, ground_at(c.x, c.y) + 0.01, c.y + 0.5)})
	var b := _free_spot(10)
	if b.x >= 0 and Vector2(b).distance_to(Vector2(start)) > 16:
		var g := ground_at(b.x, b.y)
		spawns.append({"kind": "biggrumbler", "pos": Vector3(b.x + 0.5, g + 0.01, b.y + 0.5)})
		set_cell(b.x, g + 3, b.y + 2, MYSTERY)   # the ? block it guards
		keep_out.append([Vector2(b), 5.0])


## The extra Mario 64-style missions: RED COINS, BIG GRUMBLER and LOST DOG.
func _more_quests(start: Vector2i) -> void:
	# RED COINS: 8 of them, some up high (tower, sky steps, little pillars), star on a red circle
	red_spots = []
	if tower_tops.size() > 8:
		red_spots.append(tower_tops[tower_tops.size() - 1] + Vector3(0, 0.6, 0))
		red_spots.append(tower_tops[8] + Vector3(0, 0.6, 0))
	if step_tops.size() > 4:
		red_spots.append(step_tops[3] + Vector3(0, 0.6, 0))
		red_spots.append(step_tops[step_tops.size() - 1] + Vector3(0, 0.6, 0))
	if island_spot != Vector3.ZERO:   # on the floating island (take the cannon!)
		red_spots.append(island_spot + Vector3(-1.5, 0.6, 1.5))
	if not ramp_s.is_empty():   # half way up the mountain path
		var rp := ramp_point(0.55)
		red_spots.append(Vector3(floori(rp.x) + 0.5, ground_at(floori(rp.x), floori(rp.z)) + 0.6, floori(rp.z) + 0.5))
	elif landmark != Vector3.ZERO:   # in the pyramid's treasure room
		red_spots.append(landmark + Vector3(-2, 0.6, -2))
	var tries := 0
	while red_spots.size() < 8 and tries < 40:
		tries += 1
		var o := _free_spot(6)
		if o.x < 0:
			continue
		var g := ground_at(o.x, o.y)
		var hgt := 0 if red_spots.size() % 2 == 0 else 2   # every other one on a little pillar (one jump up)
		for y in hgt:
			set_cell(o.x, g + y, o.y, STONE_BRICK if y < hgt - 1 else GOLD_BLOCK)
		red_spots.append(Vector3(o.x + 0.5, g + hgt + 0.6, o.y + 0.5))
		keep_out.append([Vector2(o), 3.0])
	var rs := _free_spot(6)
	if rs.x >= 0 and red_spots.size() >= 6:
		var g := ground_at(rs.x, rs.y)
		for dx in range(-2, 3):
			for dz in range(-2, 3):
				var d := Vector2(dx, dz).length()
				if d > 1.4 and d < 2.4 and get_cell(rs.x + dx, g - 1, rs.y + dz) != AIR and get_cell(rs.x + dx, g, rs.y + dz) == AIR:
					set_cell(rs.x + dx, g - 1, rs.y + dz, WOOL_RED)
		keep_out.append([Vector2(rs), 4.0])
		objectives.append({"id": "reds", "name": "RED COINS", "hint": "Find the 8 red coins! The star appears in the red circle.",
			"pos": Vector3(rs.x + 0.5, g + 1.2, rs.y + 0.5)})
	# BIG GRUMBLER: the mini-boss guarding a ? block; beat him for a star
	for sp in spawns:
		if sp.kind == "biggrumbler":
			objectives.append({"id": "boss", "name": "BIG GRUMBLER", "hint": "Beat the BIG GRUMBLER!",
				"pos": sp.pos + Vector3(0, 1.4, 0)})
			break
	# LOST DOG: Dennis wandered far away; find him and walk him back to his doghouse
	var far := Vector2i(-1, -1)
	for i in 40:
		var o := _free_spot(4)
		if o.x >= 0 and Vector2(o).distance_to(Vector2(start)) > 34:
			far = o
			break
	if far.x >= 0:
		dog_spot = Vector3(far.x + 0.5, ground_at(far.x, far.y), far.y + 0.5)
		keep_out.append([Vector2(far), 3.0])
		var hx := start.x + 6
		var hz := start.y + 3
		var g := ground_at(hx, hz)
		for dx in 2:   # a little doghouse: planks with a red roof, open at the front
			for dz in 2:
				set_cell(hx + dx, g, hz + dz + 1, PLANKS)
				set_cell(hx + dx, g + 1, hz + dz + 1, WOOL_RED)
		dog_home = Vector3(hx + 1.0, g, hz + 0.3)
		objectives.append({"id": "dog", "name": "LOST DOG", "hint": "Dennis is lost! Find him and bring him to his doghouse.",
			"pos": dog_home + Vector3(0, 1.2, -0.6)})


## The SECRET PAINTING: hung on the outside of the castle's west wall, a little too high
## to walk into, so you have to JUMP in (like Mario 64). It's the last star of the world.
func _painting_spot() -> void:
	var x0 := int(arena.position.x)
	var zc := float(int(arena.position.y + arena.size.y * 0.5)) + 0.5
	var bottom := arena_y + 1.5
	painting = {"x": float(x0), "z": zc, "w": 3.2, "h": 2.7, "bottom": bottom,
		"pos": Vector3(x0 - 0.03, bottom + 1.35, zc)}
	keep_out.append([Vector2(x0 - 3, zc), 4.0])
	objectives.append({"id": "painting", "name": "SECRET PAINTING", "hint": "Find the painting on the castle wall and JUMP into it!",
		"pos": Vector3(x0 - 1.2, arena_y + 0.9, zc)})


## A painting's BONUS COURSE: platforms floating in the sky going up and away, coins on
## them, a couple of bad guys, a ? block, and the PAINTING STAR on the last one. Every
## jump is one Rocco can make (tests/painting_check.gd jumps them all for real).
func generate_bonus(w: String) -> void:
	world = w
	open = true
	bonus = true
	sx = 40
	sy = 48
	sz = 84
	width = sx
	rng.seed = absi(hash(w)) % 100000 + 31
	cells.resize(sx * sy * sz)
	cells.fill(AIR)
	heights.resize(sx)
	heights.fill(0)
	hmap.resize(sx * sz)
	hmap.fill(0)
	var top: int = {"meadow": GRASS, "frost": SNOW, "dunes": SAND, "swamp": LOG, "moon": IRON_BLOCK, "lava": STONE_BRICK,
		"candy": FROSTING, "beach": SAND, "jungle": GRASS, "cloud": CLOUD}.get(w, GRASS)
	var side: int = {"meadow": DIRT, "frost": ICE, "dunes": SANDSTONE, "swamp": PLANKS, "moon": STONE, "lava": BRICK,
		"candy": CAKE, "beach": SANDSTONE, "jungle": DIRT, "cloud": CLOUD}.get(w, DIRT)
	var colors := [WOOL_RED, WOOL_YELLOW, WOOL_BLUE, WOOL_GREEN, WOOL_WHITE]
	var p := Vector3i(18, 10, 74)
	_bonus_pad(p, 2, top, side)   # the start: a 5 x 5 landing pad
	bonus_tops.append(Vector3(p.x + 0.5, p.y + 1, p.z + 0.5))
	start_pos = Vector3(p.x + 0.5, p.y + 1.01, p.z + 0.5)
	var hops := 15
	var prev_r := 2
	for i in hops:
		# forward (-z) with a gap of one or two blocks; up 0, 1 or 2; a little to the side
		var up: int = [1, 2, 0, 1, 2, 1, 0, 2][i % 8]
		var r := 2 if i in [4, 9] else 1   # wider pads with a bad guy on them
		var gap := 1 if up > 0 else 2
		var fwd := mini(prev_r + r + 1 + gap, 5 if up == 0 else 4)   # never too far from the middle of a pad
		var dx: int = [0, 2, -2, 1, -1, 2, -2, 0][rng.randi() % 8]
		if fwd >= 4:
			dx = clampi(dx, -1, 1)
		var nx := clampi(p.x + dx, 6, sx - 7)
		p = Vector3i(nx, p.y + up, p.z - fwd)
		var t: int = colors[i % colors.size()] if i % 3 == 2 else top
		_bonus_pad(p, r, t, side)
		bonus_tops.append(Vector3(p.x + 0.5, p.y + 1, p.z + 0.5))
		coin_spots.append(Vector3(p.x + 0.5, p.y + 2.0, p.z + 0.5))
		if r == 2:
			spawns.append({"kind": "grumbler", "pos": Vector3(p.x + 1.5, p.y + 1.01, p.z - 1.5)})
			coin_spots.append(Vector3(p.x - 1.5, p.y + 2.0, p.z - 1.5))
			coin_spots.append(Vector3(p.x + 1.5, p.y + 2.0, p.z + 1.5))
		if i == 6:
			set_cell(p.x - 1, p.y + 3, p.z + 1, MYSTERY)   # a power-up (over the back corner: not in the way of the next jump)
		prev_r = r
	# the end: a big gold pad with the star
	p = Vector3i(p.x, p.y + 1, p.z - (prev_r + 2 + 2))
	_bonus_pad(p, 2, GOLD_BLOCK, side)
	bonus_tops.append(Vector3(p.x + 0.5, p.y + 1, p.z + 0.5))
	bonus_star = Vector3(p.x + 0.5, p.y + 2.3, p.z - 0.5)
	for i in 5:
		coin_spots.append(Vector3(p.x - 1.5 + i * 0.75, p.y + 2.0, p.z + 1.5))
	signs = [[start_pos + Vector3(-1.5, 0, 1.2), "PAINTING STAR!\nJump to the gold\nplatform at the top!"]]
	_build_mesh_nodes()
	for sg in signs:
		_sign(sg)
	# far clouds below and around (the course floats in the painting's sky)
	for i in 36:   # kept out to the sides and below, so they never hide the jumps
		var cx := rng.randf_range(-36, -10) if i % 2 == 0 else rng.randf_range(sx + 10, sx + 36)
		var c := prop("cloud", Vector3(cx, rng.randf_range(-8, 30), rng.randf_range(-30, sz + 30)), rng.randf_range(4, 7))
		if c is Sprite3D:
			c.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	rebuild()


## A square floating pad (radius r around c) with an under-layer, and a little point below.
func _bonus_pad(c: Vector3i, r: int, top: int, side: int) -> void:
	for dx in range(-r, r + 1):
		for dz in range(-r, r + 1):
			set_cell(c.x + dx, c.y, c.z + dz, top)
			if absi(dx) < r and absi(dz) < r:
				set_cell(c.x + dx, c.y - 1, c.z + dz, side)
	set_cell(c.x, c.y - 2, c.z, side)


func ground_at(x: int, z: int) -> int:
	if x < 0 or z < 0 or x >= sx or z >= sz:
		return 0
	return hmap[x + z * sx]


# ------------------------------------------------------------------ Mario 64-style courses
## The shape of each world's course. H = height of the mountain's flat top, turns = how many
## times the path winds round it, style = cone / pyramid, kc = how steep the cliffs are.
const COURSE := {
	"meadow": {"H": 24, "turns": 0.75, "path": DIRT, "bridges": true, "pond": true, "garden": true},
	"frost": {"H": 27, "turns": 1.5, "path": SNOW, "slide": true, "kc": 2.0, "cabin": true},
	"dunes": {"H": 22, "style": "pyramid", "quicksand": true},
	"swamp": {"H": 18, "turns": 0.75, "path": STONE_BRICK, "mansion": true},
	"moon": {"H": 22, "turns": 0.75, "path": MOON_ROCK, "bridges": true},
	"lava": {"H": 22, "turns": 0.75, "path": STONE_BRICK, "moat": true, "bridges": true},
	"candy": {"H": 22, "turns": 1.0, "path": WOOL_WHITE, "stripes": true},
	"beach": {"H": 18, "turns": 0.75, "path": PLANKS, "ship": true},
	"jungle": {"H": 28, "turns": 1.0, "path": DIRT, "kc": 3.0, "pond": true, "bridges": true},
	"cloud": {"H": 22, "turns": 0.75, "path": PLANKS, "bridges": true},
}
const RAMP_R0 := 15.5   # the path starts this far from the middle of the mountain (south side) ...
const RAMP_R1 := 5.5    # ... and winds in to the flat top
const RAMP_W := 2.2     # half the path's width
const RAMP_A0 := PI / 2.0   # it starts at the south (towards the start)


func course() -> Dictionary:
	return COURSE.get(world, COURSE["meadow"])


## Where the middle of the spiral path is at s (0..1), at its walking height.
func ramp_point(s: float) -> Vector3:
	var c: Dictionary = course()
	var th: float = RAMP_A0 + s * float(c.get("turns", 0.75)) * TAU
	var r := lerpf(RAMP_R0, RAMP_R1, s)
	var h := lerpf(7.0, float(c.H), s)
	return Vector3(mountain.x + 0.5 + cos(th) * r * _c_mx, round(h), mountain.y + 0.5 + sin(th) * r)


## Downhill along the path at s (flat, normalised): the way the slide pushes you.
func ramp_down(s: float) -> Vector3:
	var a := ramp_point(clampf(s - 0.01, 0, 1))
	var b := ramp_point(clampf(s + 0.01, 0, 1))
	var d := Vector3(a.x - b.x, 0, a.z - b.z)
	return d.normalized() if d.length() > 0.001 else Vector3.ZERO


## The height of the big mountain at (x, z), or -1 if it isn't part of the mountain.
## The path winds round it (each path cell goes in `ramp_s`); between two turns of the path
## there's a wall 2 high (a guard rail you can't just step over); outside the last turn the
## side falls away as a cliff; the 9x9 top is flat.
func _mountain_h(x: int, z: int) -> float:
	var c: Dictionary = course()
	var H := float(c.H)
	var kc := float(c.get("kc", 2.2))
	var dx := float(x) - mountain.x
	var dz := float(z) - mountain.y
	if absf(dx) <= 4.0 and absf(dz) <= 4.0:
		return H
	var d := Vector2(dx, dz).length()
	if c.get("style", "cone") == "pyramid":   # Shifting Sand Land: steps all the way up
		var ring := maxi(absi(int(dx)), absi(int(dz)))
		var ph := H - (ring - 4)
		return ph if ph > 6 else -1.0
	if d > RAMP_R0 + RAMP_W + 10:
		return -1.0
	var turns := float(c.get("turns", 0.75))
	var span := turns * TAU
	var phi := fposmod(atan2(dz, dx * _c_mx) - RAMP_A0, TAU)
	var p_in := Vector2(-1, 0)    # (r, h) of the path turn just inside this cell
	var p_out := Vector2(-1, 0)   # ... and just outside
	for k in 3:
		var sk := (phi + TAU * k) / span
		if sk > 1.0:
			break
		var r := lerpf(RAMP_R0, RAMP_R1, sk)
		var h := lerpf(7.0, H, sk)
		if absf(d - r) <= RAMP_W:
			ramp_s[Vector2i(x, z)] = sk
			return round(h)
		if r < d and (p_in.x < 0 or r > p_in.x):
			p_in = Vector2(r, h)
		if r > d and (p_out.x < 0 or r < p_out.x):
			p_out = Vector2(r, h)
	var cone := H - (d - 5.5) * kc
	if p_in.x >= 0 and p_out.x >= 0:
		return round(p_in.y) + 2.0          # the wall between two turns of the path
	if p_in.x >= 0:
		return round(p_in.y) - (d - p_in.x - RAMP_W) * kc   # the cliff outside the last turn
	if p_out.x >= 0:
		return minf(H, maxf(round(p_out.y) + 2.0, cone))   # the core inside the first turn
	return cone


## Builds the Mario 64-style course into the height map: the big mountain with its spiral
## path (bridges, a lava moat, a slide), a pond, flat pads for the cannon and the Chompy.
## `P` maps a design spot to this world (mirrored worlds flip x).
func _course_heights(w: String, P: Callable) -> void:
	var c: Dictionary = course()
	mountain = Vector2(P.call(48, 52))
	_c_mx = -1.0 if P.call(10, 0).x != 10 else 1.0
	slide = c.get("slide", false)
	ramp_s = {}
	_c_top = {}
	_c_bridge = {}
	_c_mount = {}
	var reach := 22 if c.get("style", "cone") == "pyramid" else int(RAMP_R0 + RAMP_W + 10)
	for z in range(int(mountain.y) - reach, int(mountain.y) + reach + 1):
		for x in range(int(mountain.x) - reach, int(mountain.x) + reach + 1):
			if x < 1 or z < 1 or x >= sx - 1 or z >= sz - 1:
				continue
			var mh := _mountain_h(x, z)
			if mh < 0:
				continue
			var i := x + z * sx
			var cell := Vector2i(x, z)
			if ramp_s.has(cell):
				hmap[i] = int(mh)
				var off := Vector2(x - mountain.x, z - mountain.y).length() - lerpf(RAMP_R0, RAMP_R1, ramp_s[cell])
				if slide and absf(off) <= 1.0:
					_c_top[cell] = ICE   # the slide lane (the outer lane is snow, to walk up)
				else:
					_c_top[cell] = int(c.get("path", DIRT))
			elif mh > hmap[i]:
				hmap[i] = clampi(int(round(mh)), 3, sy - 6)
			else:
				continue
			_c_mount[cell] = true
	# bridges: two short pieces of the path become wooden bridges over a gap
	if c.get("bridges", false):
		for cell in ramp_s:
			var sv: float = ramp_s[cell]
			if (sv > 0.3 and sv < 0.36) or (sv > 0.62 and sv < 0.68):
				var i: int = cell.x + cell.y * sx
				_c_bridge[cell] = hmap[i] - 1
				hmap[i] = maxi(hmap[i] - 5, 4)
				_c_top.erase(cell)
	# a lava moat round the mountain, with one stone bridge where the path starts
	if c.get("moat", false):
		for z in sz:
			for x in sx:
				var d := Vector2(x - mountain.x, z - mountain.y).length()
				if d > RAMP_R0 + RAMP_W + 2.0 and d < RAMP_R0 + RAMP_W + 5.5:
					var i := x + z * sx
					if absf(x - mountain.x) < 2.2 and z > mountain.y:
						hmap[i] = 6
						_c_top[Vector2i(x, z)] = STONE_BRICK
					elif hmap[i] <= 9:
						hmap[i] = 4
						_c_top[Vector2i(x, z)] = LAVA
	# a pond with a sandy edge
	if c.get("pond", false):
		var pc: Vector2i = P.call(30, 76)
		for z in range(pc.y - 7, pc.y + 8):
			for x in range(pc.x - 7, pc.x + 8):
				var d := Vector2(x - pc.x, z - pc.y).length()
				if d < 5.0:
					hmap[x + z * sx] = 5
					_c_top[Vector2i(x, z)] = WATER
				elif d < 6.5:
					hmap[x + z * sx] = 6
					_c_top[Vector2i(x, z)] = SAND
		keep_out.append([Vector2(pc), 7.0])
	# flat pads for the cannon and the Chompy's corner
	for spot in [P.call(56, 82), P.call(80, 84)]:
		for z in range(spot.y - 5, spot.y + 6):
			for x in range(spot.x - 5, spot.x + 6):
				hmap[x + z * sx] = 6
				_c_top.erase(Vector2i(x, z))
	keep_out.append([mountain, float(reach) + 1.0])


## Puts up the course's buildings (after the ground is filled): rails on the mountain path,
## bridges, the cannon and its floating island, the Chompy's fenced corner, and each world's
## own landmark (cabin, haunted mansion, pyramid room, pirate ship, garden).
func _course_build(w: String, P: Callable, theme: int) -> void:
	var c: Dictionary = course()
	var H := int(c.H)
	summit_spot = Vector3(mountain.x + 0.5, H, mountain.y + 0.5)
	# the paths' own top blocks (ponds, the slide, the moat bridge...)
	for cell in _c_top:
		var g := ground_at(cell.x, cell.y)
		if g > 0:
			cells[idx(cell.x, g - 1, cell.y)] = _c_top[cell]
	for cell in _c_bridge:
		var y: int = _c_bridge[cell]
		cells[idx(cell.x, y, cell.y)] = PLANKS
	# the mountain's own rock under its top (white snow cliffs, sandstone, basalt...)
	var rock: int = {"frost": CLOUD, "dunes": SANDSTONE, "moon": MOON_ROCK, "lava": BASALT, "beach": SANDSTONE,
		"cloud": CLOUD, "swamp": STONE_BRICK}.get(w, -1)
	if rock >= 0:
		for cell in _c_mount:
			var g := ground_at(cell.x, cell.y)
			for y in range(1, g - 1):
				var t := cells[idx(cell.x, y, cell.y)]
				if t != AIR and not t in [IRON, GOLD, DIAMOND]:
					cells[idx(cell.x, y, cell.y)] = rock
			if w == "frost" and not ramp_s.has(cell) and g > 1:   # snowy cliffs: white all the way
				cells[idx(cell.x, g - 1, cell.y)] = CLOUD
	if c.get("stripes", false):   # Candy Kingdom: the mountain is a big layer cake
		for cell in _c_mount:
			var g := ground_at(cell.x, cell.y)
			for y in range(1, g - 1):
				if cells[idx(cell.x, y, cell.y)] != AIR:
					cells[idx(cell.x, y, cell.y)] = [CAKE, CAKE, WOOL_RED, CAKE, FROSTING][y % 5]
	if c.get("style", "cone") != "pyramid":
		# rail posts on the outside of the path, and coins all the way up
		var arc := 0.0
		var last := ramp_point(0)
		var next_coin := 2.0
		var next_post := 1.0
		var steps := 240
		for k in range(1, steps + 1):
			var sv := float(k) / steps
			var pt := ramp_point(sv)
			arc += Vector2(pt.x - last.x, pt.z - last.z).length()
			last = pt
			if arc >= next_coin and sv < 0.97:
				next_coin += 3.4
				var gq := ground_at(floori(pt.x), floori(pt.z))
				if get_cell(floori(pt.x), gq, floori(pt.z)) == AIR:
					coin_spots.append(Vector3(floori(pt.x) + 0.5, gq + 1.0, floori(pt.z) + 0.5))
			if arc >= next_post and not slide:
				next_post += 3.5
				var out := Vector2(pt.x - (mountain.x + 0.5), pt.z - (mountain.y + 0.5)).normalized() * (RAMP_W - 0.5)
				var q := Vector2i(floori(pt.x + out.x), floori(pt.z + out.y))
				if ramp_s.has(q) and not _c_bridge.has(q):
					set_cell(q.x, ground_at(q.x, q.y), q.y, LOG)
		var foot := ramp_point(0)
		var sg := Vector2i(floori(foot.x + 3.0 * _c_mx), floori(foot.z + 2.0))
		signs.append([Vector3(sg.x + 0.5, ground_at(sg.x, sg.y), sg.y + 0.5), "MOUNTAIN PATH\nUp to the\nSUMMIT!"])
		# a few bad guys on the way up (like the bob-ombs on the battlefield)
		var walkers: Array = FOES_BY_WORLD.get(w, FOES_BY_WORLD["default"])
		for sv in [0.22, 0.5, 0.78]:
			var pt := ramp_point(sv)
			var g := ground_at(floori(pt.x), floori(pt.z))
			spawns.append({"kind": walkers[int(sv * 10) % walkers.size()], "pos": Vector3(floori(pt.x) + 0.5, g + 0.01, floori(pt.z) + 0.5)})
	else:
		_pyramid_room(P)
	for i in 6:   # coins ring the summit
		var a := TAU * i / 6.0
		coin_spots.append(summit_spot + Vector3(cos(a) * 3.0, 1.0, sin(a) * 3.0))
	_cannon_and_island(P)
	_chomp_pen(P)
	if c.get("garden", false):
		_garden(P)
	if c.get("cabin", false):
		_cabin(P)
	if c.get("mansion", false):
		_mansion(P)
	if c.get("ship", false):
		_ship(P)
	if c.get("quicksand", false):
		for i in 7:
			var o := _free_spot(4)
			if o.x < 0:
				continue
			for dz in range(-2, 3):
				for dx in range(-2, 3):
					if dx * dx + dz * dz <= 5:
						var g := ground_at(o.x + dx, o.y + dz)
						if g > 1 and get_cell(o.x + dx, g, o.y + dz) == AIR:
							set_cell(o.x + dx, g - 1, o.y + dz, MUD)
			keep_out.append([Vector2(o), 3.0])


## The cannon by the start, and the floating island it shoots you to.
func _cannon_and_island(P: Callable) -> void:
	var cs: Vector2i = P.call(56, 82)
	var g := ground_at(cs.x, cs.y)
	for dz in range(-1, 2):   # a stone ring round the cannon
		for dx in range(-1, 2):
			if dx != 0 or dz != 0:
				set_cell(cs.x + dx, g - 1, cs.y + dz, STONE_BRICK)
	cannon_spot = Vector3(cs.x + 0.5, g, cs.y + 0.5)
	keep_out.append([Vector2(cs), 3.0])
	signs.append([cannon_spot + Vector3(2.5 * _c_mx, 0, 1.5), "CANNON!\nWalk in, aim,\nJUMP to fire!"])
	var ic: Vector2i = P.call(22, 86)
	var iy := 21
	var top: int = top_block if top_block != LAVA else BASALT
	for dz in range(-5, 6):
		for dx in range(-5, 6):
			var d := Vector2(dx, dz).length()
			if d <= 4.6:
				set_cell(ic.x + dx, iy - 1, ic.y + dz, top)
			if d <= 3.6:
				set_cell(ic.x + dx, iy - 2, ic.y + dz, sub_block if sub_block != top else STONE)
			if d <= 2.4:
				set_cell(ic.x + dx, iy - 3, ic.y + dz, STONE)
			if d <= 1.2:
				set_cell(ic.x + dx, iy - 4, ic.y + dz, STONE)
	island_spot = Vector3(ic.x + 0.5, iy, ic.y + 0.5)
	set_cell(ic.x + 2, iy + 2, ic.y - 2, MYSTERY)   # a power-up up there
	for i in 8:
		var a := TAU * i / 8.0
		coin_spots.append(island_spot + Vector3(cos(a) * 3.2, 1.0, sin(a) * 3.2))
	keep_out.append([Vector2(ic), 6.0])


## The Chompy on its post in a fenced corner, guarding a ? block (like Mario 64's Chain Chomp).
func _chomp_pen(P: Callable) -> void:
	var c: Vector2i = P.call(80, 84)
	var g := ground_at(c.x, c.y)
	var gap_x := -4 if _c_mx > 0 else 4   # the opening faces the start
	for k in range(-4, 5):
		for edge in [Vector2i(k, -4), Vector2i(k, 4), Vector2i(-4, k), Vector2i(4, k)]:
			if edge.x == gap_x and absi(edge.y) <= 1:
				continue
			set_cell(c.x + edge.x, g, c.y + edge.y, PLANKS)
			if (edge.x + edge.y) % 2 == 0:
				set_cell(c.x + edge.x, g + 1, c.y + edge.y, LOG)
	chomp_spot = Vector3(c.x + 0.5, g + 0.01, c.y + 0.5)
	var bx := c.x - gap_x / 2
	set_cell(bx, g, c.y - 3, GOLD_BLOCK)
	set_cell(bx, g + 3, c.y - 3, MYSTERY)
	for i in 5:
		coin_spots.append(Vector3(c.x + 0.5 + (i - 2) * 1.2, g + 1.0, c.y + 2.5))
	keep_out.append([Vector2(c), 7.0])


## A flower garden with a little fence by the start (Bob-omb Battlefield style).
func _garden(P: Callable) -> void:
	var a: Vector2i = P.call(29, 88)
	var b: Vector2i = P.call(36, 94)
	var x0 := mini(a.x, b.x)
	var x1 := maxi(a.x, b.x)
	for x in range(x0, x1 + 1):
		for z in range(a.y, b.y + 1):
			var edge := x == x0 or x == x1 or z == a.y or z == b.y
			var g := ground_at(x, z)
			if edge and not (z == a.y and absi(x - (x0 + x1) / 2) <= 0):
				set_cell(x, g, z, PLANKS)
			elif not edge and (x + z) % 2 == 0:
				prop(["flower_pink", "flower_blue", "flower_white", "flower_pink"][(x * 3 + z) % 4], Vector3(x + 0.5, g, z + 0.5), 0.6)
	keep_out.append([Vector2((x0 + x1) * 0.5, (a.y + b.y) * 0.5), 6.0])


## Frosty Peaks: a log cabin with a snowy roof by the bottom of the slide.
func _cabin(P: Callable) -> void:
	var c: Vector2i = P.call(69, 77)
	var g := ground_at(c.x, c.y)
	_house(c, g, 7, 6, 3, LOG, SNOW, WINDOW)
	landmark = Vector3(c.x + 0.5, g, c.y + 0.5)
	coin_spots.append(landmark + Vector3(0, 1.0, 0))
	keep_out.append([Vector2(c), 7.0])


## A spooky two-floor mansion with ghosts inside.
func _mansion(P: Callable) -> void:
	var c: Vector2i = P.call(68, 76)
	var g := ground_at(c.x, c.y)
	_house(c, g, 11, 9, 7, LOG, STONE_BRICK, WINDOW)
	for dx in range(-4, 5):   # the upstairs floor (with a hole for the stairs)
		for dz in range(-3, 4):
			if not (dx >= 2 and dz <= 0):
				set_cell(c.x + dx, g + 3, c.y + dz, PLANKS)
	for k in 3:   # stairs up
		set_cell(c.x + 3, g + k, c.y - 3 + k, PLANKS)
	landmark = Vector3(c.x + 0.5, g, c.y + 0.5)
	spawns.append({"kind": "boohoo", "pos": landmark + Vector3(-2, 4.01, 0)})
	spawns.append({"kind": "boohoo", "pos": landmark + Vector3(1.5, 0.01, 1.5)})
	for i in 4:
		coin_spots.append(landmark + Vector3(-3 + i * 1.5, 5.0, 1.5))
	keep_out.append([Vector2(c), 9.0])


## A box house: walls of `wall`, a stepped roof of `roof`, windows, a door at the front (+z).
func _house(c: Vector2i, g: int, w: int, d: int, hgt: int, wall: int, roof: int, win: int) -> void:
	var hx := w / 2
	var hz := d / 2
	for y in range(g, g + hgt):
		for dx in range(-hx, hx + 1):
			for dz in range(-hz, hz + 1):
				if absi(dx) != hx and absi(dz) != hz:
					continue
				var t := wall
				if y == g + 1 and dx % 3 == 0 and absi(dx) != hx:
					t = win
				if dz == hz and absi(dx) <= 1 and y < g + 2:
					t = AIR   # the door
				set_cell(c.x + dx, y, c.y + dz, t)
	for k in hz + 2:   # the roof, in steps
		var za := -hz - 1 + k
		var zb := hz + 1 - k
		if za > zb:
			break
		for dx in range(-hx - 1, hx + 2):
			set_cell(c.x + dx, g + hgt + k, c.y + za, roof)
			set_cell(c.x + dx, g + hgt + k, c.y + zb, roof)


## Beach Bay: an old pirate ship sitting in the bay.
func _ship(P: Callable) -> void:
	var a: Vector2i = P.call(7, 46)
	var b: Vector2i = P.call(22, 52)
	var x0 := mini(a.x, b.x)
	var x1 := maxi(a.x, b.x)
	var zc := (a.y + b.y) / 2
	var deck := 6
	for x in range(x0, x1 + 1):
		var t := float(x - x0) / float(x1 - x0)
		var half := int(round(3.0 * sin(t * PI) + 0.4))
		for dz in range(-half, half + 1):
			for y in range(3, deck):
				if absi(dz) == half or y == 3:
					set_cell(x, y, zc + dz, PLANKS)
			set_cell(x, deck - 1, zc + dz, PLANKS)
			if absi(dz) == half:
				set_cell(x, deck, zc + dz, PLANKS)   # the rail
	for mxx in [x0 + (x1 - x0) / 3, x0 + 2 * (x1 - x0) / 3]:
		for y in range(deck, deck + 7):
			set_cell(mxx, y, zc, LOG)
		for y in range(deck + 3, deck + 7):
			for dz in range(-2, 3):
				set_cell(mxx + 1, y, zc + dz, WOOL_WHITE)
	landmark = Vector3((x0 + x1) / 2 + 0.5, deck, zc + 0.5)
	for i in 5:
		coin_spots.append(Vector3(x0 + 2.5 + i * 2.5, deck + 1.0, zc + 0.5))
	spawns.append({"kind": "skelly", "pos": Vector3(x0 + 3.5, deck + 0.01, zc + 0.5)})
	keep_out.append([Vector2((x0 + x1) * 0.5, zc), 10.0])


## Shifting Sand Land: a tunnel into the pyramid and a treasure room inside.
func _pyramid_room(P: Callable) -> void:
	var mxz := Vector2i(int(mountain.x), int(mountain.y))
	for dz in range(5, 23):   # the tunnel from the south side
		for dx in range(-1, 2):
			for y in range(6, 9):
				set_cell(mxz.x + dx, y, mxz.y + dz, AIR)
	for dz in range(-3, 6):   # the treasure room
		for dx in range(-4, 5):
			for y in range(6, 11):
				set_cell(mxz.x + dx, y, mxz.y + dz, AIR)
			set_cell(mxz.x + dx, 5, mxz.y + dz, GOLD_BLOCK if (dx + dz) % 2 == 0 else SANDSTONE)
	set_cell(mxz.x, 9, mxz.y, MYSTERY)
	landmark = Vector3(mxz.x + 0.5, 6, mxz.y + 0.5)
	for i in 6:
		coin_spots.append(landmark + Vector3(-3 + i * 1.2, 1.0, 3.0))
	spawns.append({"kind": "mummy", "pos": landmark + Vector3(2, 0.01, -1)})
	var sgz := mxz.y + 23
	signs.append([Vector3(mxz.x + 2.5, ground_at(mxz.x + 2, sgz), sgz + 0.5), "PYRAMID\nClimb it, or\ngo inside!"])


## A big open world: rolling hills, trees, five star challenges and Krag Jr.'s castle.
## Frosty Peaks is a mirror image of the layout so it feels like a new place.
func generate_open(w: String) -> void:
	world = w
	open = true
	sx = OPEN_S
	sy = 40
	sz = OPEN_S
	width = sx
	rng.seed = {"meadow": 2026, "frost": 4242, "dunes": 9001, "swamp": 6666, "moon": 1969,
		"lava": 6161, "candy": 7777, "beach": 8181, "jungle": 9393, "cloud": 1010}[w]
	top_block = {"meadow": GRASS, "frost": SNOW, "dunes": SAND, "swamp": SWAMP_GRASS, "moon": MOON_DUST,
		"lava": BASALT, "candy": FROSTING, "beach": SAND, "jungle": GRASS, "cloud": CLOUD}[w]
	sub_block = {"meadow": DIRT, "frost": DIRT, "dunes": SANDSTONE, "swamp": MUD, "moon": MOON_ROCK,
		"lava": BASALT, "candy": CAKE, "beach": SANDSTONE, "jungle": DIRT, "cloud": CLOUD}[w]
	var theme: int = {"meadow": PLANKS, "frost": SNOW, "dunes": SANDSTONE, "swamp": LOG, "moon": IRON_BLOCK,
		"lava": STONE_BRICK, "candy": CANDY_CANE, "beach": PLANKS, "jungle": LOG, "cloud": GOLD_BLOCK}[w]
	var foe2: String = {"meadow": "grumbler", "frost": "penguin", "dunes": "scorpion", "swamp": "skelly", "moon": "grumbler",
		"lava": "grumbler", "candy": "hopper", "beach": "scorpion", "jungle": "hopper", "cloud": "grumbler"}[w]
	var mirror := w in ["frost", "moon", "candy", "cloud"]
	var P := func(x: int, z: int) -> Vector2i: return Vector2i(sx - 1 - x if mirror else x, z)
	var start: Vector2i = P.call(48, 86)
	var camp: Vector2i = P.call(18, 64)
	var tower: Vector2i = P.call(77, 62)
	var cave: Vector2i = P.call(16, 32)
	var steps: Vector2i = P.call(78, 36)
	arena = Rect2(30, 3, 36, 23)
	arena_y = 6
	var gate_front := Vector2i(int(arena.get_center().x), int(arena.end.y) + 3)
	# the paths from the start: round the big mountain, not over it (coins show the way)
	var trails := [[start, P.call(30, 72), camp], [start, tower], [start, P.call(28, 72), P.call(24, 44), cave],
		[start, P.call(70, 70), steps], [start, P.call(28, 72), P.call(24, 42), P.call(34, 32), gate_front],
		[start, P.call(48, 70)]]
	var cloud_bridges := {}
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
			# gentle rolling ground: the course's big shapes (mountain, pads, ponds) come after
			var h := 6.0 + n.get_noise_2d(x, z) * 3.0
			if w == "frost":
				h += 0.5 + absf(n.get_noise_2d(x * 0.5 + 300, z * 0.5)) * 2.5   # snowdrifts
			elif w == "swamp":
				h = 5.0 + n.get_noise_2d(x, z) * 3.0   # low and boggy
			elif w == "lava":   # rough rocky ground with low lava pools
				h = 6.0 + n.get_noise_2d(x, z) * 5.0
			elif w == "candy":   # round cake hills
				h = 6.0 + absf(n.get_noise_2d(x * 0.7, z * 0.7)) * 5.0 - 1.0
			elif w == "beach":   # sloping down to the bay in the west
				h = 6.0 + n.get_noise_2d(x, z) * 2.5 - clampf((30 - x) * 0.12, 0, 3.5)
			elif w == "jungle":   # bumpy and lush
				h = 6.5 + n.get_noise_2d(x * 1.3, z * 1.3) * 3.0
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
			if w == "cloud":   # floating cloud islands: holes down to the sky
				var keep := n.get_noise_2d(x * 1.4 + 500, z * 1.4) > -0.12 or ad < 3
				for f in flats:
					if Vector2(x - f[0].x, z - f[0].y).length() < f[1] + 2:
						keep = true
				if Vector2(x, z).distance_to(Vector2(P.call(48, 52))) < RAMP_R0 + RAMP_W + 4:
					keep = true
				for pad in [P.call(56, 82), P.call(80, 84)]:
					if Vector2(x, z).distance_to(Vector2(pad)) < 6.5:
						keep = true
				if not keep:
					for ln in trails:
						for k in ln.size() - 1:
							if _near_line(Vector2(x, z), Vector2(ln[k]), Vector2(ln[k + 1]), 1.6):
								cloud_bridges[Vector2i(x, z)] = true   # a wooden bridge between the cloud islands
				hmap[x + z * sx] = 6 if keep or cloud_bridges.has(Vector2i(x, z)) else 0
	# the Mario 64-style course: the big mountain with its spiral path, ponds, pads
	_course_heights(w, P)
	for cb in cloud_bridges:
		if not _c_mount.has(cb):
			_c_bridge[cb] = 5
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
			if h <= 0:
				continue   # a hole in the clouds
			if w == "cloud":   # thin fluffy islands floating in the sky
				if _c_bridge.has(Vector2i(x, z)):
					continue   # a plank bridge (put in by _course_build)
				for y in range(3, h):
					cells[idx(x, y, z)] = CLOUD
				continue
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
					elif w == "lava" and h <= 4:
						t = LAVA   # lava pools: don't touch!
					elif w == "beach" and h <= 4:
						t = WATER   # the sea and rock pools (shallow: you can wade)
					elif w == "candy" and h >= 11:
						t = WOOL_WHITE   # whipped cream on the tallest hills
				if t == STONE:
					t = _ore(x, y)
				cells[idx(x, y, z)] = t
	_castle(theme)
	_camp(camp, foe2)
	_tower(tower, theme)
	_cave(cave)
	_sky_steps(steps, theme)
	_fortress(tower)
	_course_build(w, P, theme)
	# coins: trails from the start to every challenge (round the mountain), and the COIN HUNT star at the start
	for ln in trails:
		for seg in ln.size() - 1:
			var a := Vector2(ln[seg])
			var b := Vector2(ln[seg + 1])
			var k := 3.0 if seg == 0 else 0.0
			var last_seg: bool = seg == ln.size() - 2
			while k < a.distance_to(b) - (4.0 if last_seg else 0.0):
				var q := a.lerp(b, k / a.distance_to(b))
				var g := ground_at(int(q.x), int(q.y))
				if g > 0 and get_cell(int(q.x), g, int(q.y)) == AIR and get_cell(int(q.x), g + 1, int(q.y)) == AIR:
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
	for i in {"meadow": 34, "frost": 26, "dunes": 0, "swamp": 0, "moon": 0, "jungle": 60, "beach": 0, "lava": 0, "candy": 0, "cloud": 0}[w]:
		var o := _free_spot(4)
		if o.x < 3 or o.y < 3 or o.x > sx - 4 or o.y > sz - 4:
			continue
		var g := ground_at(o.x, o.y)
		if get_cell(o.x, g, o.y) == AIR and get_cell(o.x, g - 1, o.y) != ICE:
			_block_tree(o.x, o.y, g, w == "frost", 4 if w == "jungle" else 0)
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
	_world_extras(w)
	if w == "meadow":
		for i in 30:
			var o := _free_spot(1)
			if o.x >= 0:
				prop(["bush", "flower_pink", "flower_blue", "flower_white"][i % 4], Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), 0.8 if i % 4 == 0 else 0.6)
	# bad guys wandering about (which ones depends on the world: FOES_BY_WORLD)
	var kinds: Array = FOES_BY_WORLD.get(w, FOES_BY_WORLD["default"])
	_special_foes(start)
	_more_quests(start)
	_painting_spot()
	for i in 16:
		var o := _free_spot(6)
		if o.x < 0 or Vector2(o).distance_to(Vector2(start)) < 14:
			continue
		var g := ground_at(o.x, o.y)
		if get_cell(o.x, g, o.y) == AIR and get_cell(o.x, g + 1, o.y) == AIR:
			spawns.append({"kind": kinds[i % kinds.size()], "pos": Vector3(o.x + 0.5, g + 0.01, o.y + 0.5)})
	start_pos = Vector3(start.x + 0.5, ground_at(start.x, start.y) + 0.01, start.y + 0.5)
	var hello: String = Worlds.info(w).short
	signs = [[start_pos + Vector3(-3, 0, -2), hello + "\nFind 5 STARS to open\nKrag Jr.'s castle!"]]
	_build_mesh_nodes()
	var tex: String = {"meadow": "grass_top", "frost": "snow_top", "dunes": "sand", "swamp": "swamp_top", "moon": "moon_top",
		"lava": "basalt", "candy": "frosting_top", "beach": "water", "jungle": "grass_top", "cloud": ""}[w]
	var trees: Array = {"meadow": ["tree_a", "tree_b", "tree_c"], "frost": ["pine_a", "pine_b"], "dunes": ["palm", "cactus_a"],
		"swamp": ["pine_b", "tree_c"], "moon": ["desert_rock"], "lava": ["desert_rock"], "candy": ["tree_c", "tree_b"],
		"beach": ["palm", "palm", "tree_c"], "jungle": ["tree_b", "palm", "tree_a"], "cloud": []}[w]
	var hills: Array = {"meadow": ["hill_a", "hill_b", "hill_c"], "frost": ["hill_snow", "hill_snow", "hill_c"], "dunes": ["hill_sand"],
		"swamp": ["hill_b", "hill_c"], "moon": ["hill_snow"], "lava": ["hill_b"], "candy": ["hill_c", "hill_a"],
		"beach": ["hill_sand", "hill_a"], "jungle": ["hill_b", "hill_a"], "cloud": []}[w]
	_home_scenery(tex, trees, hills)
	rebuild()


func _near_line(p: Vector2, a: Vector2, b: Vector2, r: float) -> bool:
	var ab := b - a
	var t := clampf((p - a).dot(ab) / maxf(ab.length_squared(), 0.001), 0.0, 1.0)
	return p.distance_to(a + ab * t) < r


## Decorations that make each world look like itself (and colourful).
func _world_extras(w: String) -> void:
	match w:
		"meadow":
			for i in 5:
				_block_mushroom(_free_spot(4), [WOOL_RED, WOOL_BLUE, WOOL_YELLOW][i % 3])
			_flower_patches(8, ["flower_pink", "flower_blue", "flower_white", "flower_pink", "bush"])
		"lava":   # basalt spikes, some with gold on top, and rocks
			for i in 22:
				var o := _free_spot(3)
				if o.x < 0:
					continue
				var g := ground_at(o.x, o.y)
				var hgt := rng.randi_range(2, 5)
				for y in hgt:
					set_cell(o.x, g + y, o.y, BASALT)
				if rng.randf() < 0.3:
					set_cell(o.x, g + hgt, o.y, GOLD_BLOCK)
			for i in 14:
				var o := _free_spot(2)
				if o.x >= 0:
					prop("desert_rock", Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), rng.randf_range(0.8, 1.8), true)
		"candy":   # lollipop trees, gumdrops and cotton candy trees
			for i in 26:
				var o := _free_spot(4)
				if o.x < 3 or o.y < 3 or o.x > sx - 4 or o.y > sz - 4:
					continue
				_lollipop(o.x, o.y, ground_at(o.x, o.y))
			for i in 30:
				var o := _free_spot(2)
				if o.x < 0:
					continue
				var g := ground_at(o.x, o.y)
				var col: int = [WOOL_RED, WOOL_YELLOW, WOOL_GREEN, WOOL_BLUE, WOOL_WHITE][i % 5]
				set_cell(o.x, g, o.y, col)
				if rng.randf() < 0.5:
					set_cell(o.x, g + 1, o.y, col)
			for i in 8:
				var o := _free_spot(4)
				if o.x >= 3 and o.y >= 3 and o.x <= sx - 4 and o.y <= sz - 4:
					_block_tree(o.x, o.y, ground_at(o.x, o.y), false, 1)
		"beach":   # palms, sandcastles and beach balls
			for i in 30:
				var o := _free_spot(3)
				if o.x >= 0:
					prop("palm", Vector3(o.x + 0.5, ground_at(o.x, o.y), o.y + 0.5), rng.randf_range(3.2, 4.6), true, rng.randf() < 0.5)
			for i in 8:
				var o := _free_spot(3)
				if o.x < 0:
					continue
				var g := ground_at(o.x, o.y)
				for dx in 2:
					for dz in 2:
						set_cell(o.x + dx, g, o.y + dz, SANDSTONE)
				set_cell(o.x, g + 1, o.y, SANDSTONE)
				set_cell(o.x + 1, g + 1, o.y + 1, SANDSTONE)
			for i in 10:
				var o := _free_spot(2)
				if o.x >= 0:
					set_cell(o.x, ground_at(o.x, o.y), o.y, [WOOL_RED, WOOL_BLUE, WOOL_YELLOW, WOOL_WHITE][i % 4])
			_flower_patches(3, ["flower_pink", "flower_white", "bush"])
		"jungle":   # giant mushrooms, flowers everywhere
			for i in 7:
				_block_mushroom(_free_spot(4), [WOOL_RED, WOOL_YELLOW, WOOL_BLUE][i % 3])
			_flower_patches(14, ["flower_pink", "flower_blue", "flower_white", "bush", "bush"])
		"cloud":   # rainbow arches and gold-and-white pillars
			for i in 5:
				var o := _free_spot(8)
				if o.x < 6 or o.x > sx - 7:
					continue
				var g := ground_at(o.x, o.y)
				var cols := [WOOL_RED, WOOL_YELLOW, WOOL_GREEN, WOOL_BLUE, WOOL_WHITE]
				for k in cols.size():
					var r := 5.0 - k
					for a in 13:
						var ang := PI * a / 12.0
						set_cell(o.x + int(round(cos(ang) * r)), g + int(round(sin(ang) * r)), o.y, cols[k])
			for i in 12:
				var o := _free_spot(3)
				if o.x < 0:
					continue
				var g := ground_at(o.x, o.y)
				for y in rng.randi_range(2, 4):
					set_cell(o.x, g + y, o.y, GOLD_BLOCK if y % 2 else WOOL_WHITE)


## Flower patches: a bunch of colourful flowers close together (Mario 64 meadow style).
func _flower_patches(count: int, kinds: Array) -> void:
	for i in count:
		var c := _free_spot(3)
		if c.x < 0:
			continue
		for k in 7:
			var p := c + Vector2i(rng.randi_range(-2, 2), rng.randi_range(-2, 2))
			var g := ground_at(p.x, p.y)
			if g > 0 and get_cell(p.x, g, p.y) == AIR and not get_cell(p.x, g - 1, p.y) in [LAVA, WATER]:
				var kind: String = kinds[rng.randi() % kinds.size()]
				prop(kind, Vector3(p.x + 0.5, g, p.y + 0.5), 0.8 if kind == "bush" else 0.6)


## A big Minecraft-style mushroom: a white stalk with a coloured cap.
func _block_mushroom(o: Vector2i, cap: int) -> void:
	if o.x < 3 or o.y < 3 or o.x > sx - 4 or o.y > sz - 4:
		return
	var g := ground_at(o.x, o.y)
	var h := rng.randi_range(3, 5)
	for y in h:
		set_cell(o.x, g + y, o.y, WOOL_WHITE)
	for dx in range(-2, 3):
		for dz in range(-2, 3):
			if absi(dx) == 2 and absi(dz) == 2:
				continue
			set_cell(o.x + dx, g + h, o.y + dz, cap)
	set_cell(o.x, g + h + 1, o.y, cap)


## A lollipop tree: a white stick with a big round candy on top.
func _lollipop(x: int, z: int, g: int) -> void:
	var h := rng.randi_range(3, 5)
	for y in h:
		set_cell(x, g + y, z, WOOL_WHITE)
	var col: int = [WOOL_RED, WOOL_YELLOW, WOOL_GREEN, WOOL_BLUE, CANDY_CANE][rng.randi() % 5]
	var top := g + h + 1
	for dx in range(-1, 2):
		for dy in range(-1, 2):
			set_cell(x + dx, top + dy, z, col if (dx + dy) % 2 == 0 else (WOOL_WHITE if col != CANDY_CANE else WOOL_RED))


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
		var g := ground_at(x, z)
		if ok and g > 0 and get_cell(x, g, z) == AIR and get_cell(x, g + 1, z) == AIR and get_cell(x, g + 2, z) == AIR \
				and not get_cell(x, g - 1, z) in [LAVA, WATER]:
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


## The tower stands in a little fortress: a wall with battlements and a gateway facing the start.
func _fortress(c: Vector2i) -> void:
	var g := ground_at(c.x, c.y)
	for k in range(-5, 6):
		for e in [Vector2i(k, -5), Vector2i(k, 5), Vector2i(-5, k), Vector2i(5, k)]:
			if e.y == 5 and absi(e.x) <= 1:
				continue   # the gateway (south, towards the start)
			for y in range(g, g + 3):
				set_cell(c.x + e.x, y, c.y + e.y, STONE_BRICK)
			if (e.x + e.y) % 2 == 0:
				set_cell(c.x + e.x, g + 3, c.y + e.y, STONE_BRICK)


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
func _cube_mesh(t: int, top_only := false) -> ArrayMesh:
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
		if top_only and f[0] != Vector3.UP:
			continue   # flat ground only ever shows its top: 2 triangles instead of 12
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
	_meshes.clear()
	_mats.clear()
	for t in BLOCK_TYPES:
		_meshes.append([_cube_mesh(t), _cube_mesh(t, true)] if t != AIR else [null, null])
		var m: StandardMaterial3D = mat
		if t == LAVA or t == LANTERN:   # lava and lanterns glow
			m = mat.duplicate()
			m.emission_enabled = true
			m.emission_texture = mat.albedo_texture
			m.emission_energy_multiplier = 0.9 if t == LAVA else 1.3
		elif t == WATER:   # water is shiny
			m = mat.duplicate()
			m.roughness = 0.15
			m.metallic_specular = 0.9
		_mats.append(m)
	if chunked:   # nodes are made when a chunk first needs that block type
		ncx = ceili(sx / float(CH))
		ncz = ceili(sz / float(CH))
		mm_nodes.resize(ncx * ncz * 2 * 2 * BLOCK_TYPES)
		return
	for t in BLOCK_TYPES:
		_new_node(t, t, false, false)


## One MultiMesh node for a key (see _key): its block type, top-only or full cubes.
func _new_node(key: int, t: int, top_only: bool, deep: bool) -> MultiMeshInstance3D:
	var mmi := MultiMeshInstance3D.new()
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true   # every block gets its own gentle colour (see block_color)
	if t != AIR:
		mm.mesh = _meshes[t][1 if top_only else 0]
	mmi.multimesh = mm
	mmi.material_override = _mats[t]
	if t == LAVA or t == LANTERN or deep:
		mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(mmi)
	if chunked:
		mm_nodes[key] = mmi
		var ci := key / (BLOCK_TYPES * 2)
		if not chunk_nodes.has(ci):
			chunk_nodes[ci] = []
		chunk_nodes[ci].append(mmi)
	else:
		mm_nodes.append(mmi)
	return mmi


## Which MultiMesh a block goes in. Plain levels: just its type. The big home: also which
## chunk (32 x 32, deep band or not) and whether only its top shows.
func _key(c: Vector3i, t: int, top_only: bool) -> int:
	if not chunked:
		return t
	var ci := ((c.z / CH) * ncx + c.x / CH) * 2 + (0 if c.y < band_y else 1)
	return (ci * 2 + (1 if top_only else 0)) * BLOCK_TYPES + t


func _node(key: int) -> MultiMeshInstance3D:
	var n: MultiMeshInstance3D = mm_nodes[key]
	if n == null:
		var ci := key / (BLOCK_TYPES * 2)
		n = _new_node(key, key % BLOCK_TYPES, (key / BLOCK_TYPES) % 2 == 1, ci % 2 == 0)
	return n


## The big home: deep (cave) chunks are only drawn near you or when you're underground,
## so the tablet doesn't draw every cave wall in the world all the time.
func _process(dt: float) -> void:
	if not chunked or not built:
		return
	_vis_t -= dt
	if _vis_t > 0:
		return
	_vis_t = 0.25
	var cam := get_viewport().get_camera_3d()
	if cam == null:
		return
	var p := cam.global_position
	var under := p.y < band_y + 2
	for ci in chunk_nodes:
		if ci % 2 == 1:
			continue   # the surface band is always drawn
		var cc: int = ci / 2
		var r := Rect2((cc % ncx) * CH, (cc / ncx) * CH, CH, CH)
		var dx := maxf(maxf(r.position.x - p.x, p.x - r.end.x), 0.0)
		var dz := maxf(maxf(r.position.y - p.z, p.z - r.end.y), 0.0)
		var show := under or Vector2(dx, dz).length() < 26.0
		for n in chunk_nodes[ci]:
			n.visible = show


# ------------------------------------------------------------------ colour variation
## Every block gets a slightly different colour: a tiny random brightness change, plus big
## soft colour patches across the world (yellow-green to deep-green grass, autumn trees,
## peach / pink / lavender frosting...). Multiplied into the texture by the block material.
var _tint: FastNoiseLite
var _tint2: FastNoiseLite

## [colour A, colour B] for the big patches, per block type. Instance colours can only
## darken or tint (they're capped at 1), so the textures are bright and these pull them.
const PATCHES := {
	GRASS: [Color(1.0, 0.98, 0.52), Color(0.68, 0.9, 0.96)],
	SWAMP_GRASS: [Color(1.0, 0.94, 0.82), Color(0.82, 0.9, 1.0)],
	SAND: [Color(1.0, 0.97, 0.86), Color(0.96, 0.86, 0.74)],
	SNOW: [Color(1.0, 1.0, 1.0), Color(0.86, 0.92, 1.0)],
	FROSTING: [Color(1.0, 1.0, 0.7), Color(0.82, 0.88, 1.0)],
	CLOUD: [Color(1.0, 0.92, 0.97), Color(0.9, 0.95, 1.0)],
	MOON_DUST: [Color(1.0, 0.98, 0.94), Color(0.9, 0.93, 1.0)],
	BASALT: [Color(1.0, 0.86, 0.84), Color(0.84, 0.84, 0.96)],
	DIRT: [Color(1.0, 0.96, 0.9), Color(0.88, 0.9, 0.92)],
	STONE: [Color(1.0, 0.98, 0.95), Color(0.9, 0.93, 1.0)],
}
## Leaf colours (the leaf texture is light grey; these colour it).
const LEAF_GREEN := [Color(0.28, 0.6, 0.27), Color(0.42, 0.7, 0.24)]
const LEAF_AUTUMN := [Color(0.98, 0.78, 0.22), Color(0.98, 0.52, 0.16), Color(0.86, 0.26, 0.18)]
const LEAF_CANDY := [Color(1.0, 0.6, 0.82), Color(0.78, 0.66, 1.0), Color(1.0, 0.86, 0.5)]


func block_color(c: Vector3i, t: int) -> Color:
	if _tint == null:
		_tint = FastNoiseLite.new()
		_tint.seed = hash(world) & 0xffff
		_tint.frequency = 0.045
		_tint2 = FastNoiseLite.new()
		_tint2.seed = (hash(world) >> 8) & 0xffff
		_tint2.frequency = 0.2
	var hh := (c.x * 73856093) ^ (c.y * 19349663) ^ (c.z * 83492791)
	var j := float(hh & 1023) / 1023.0
	var b := 0.92 + j * 0.08
	if t == LEAVES:
		var v := _tint2.get_noise_2d(c.x * 0.3, c.z * 0.3)   # whole trees share a colour
		var autumn: bool = world in ["meadow", "frost"] or is_home
		if autumn and v > 0.12:   # autumn trees: yellow, orange or red leaves
			return (LEAF_AUTUMN[int((v - 0.12) * 12.0) % 3] as Color) * b
		if world == "candy":   # cotton candy treetops
			return (LEAF_CANDY[int(absf(v) * 9.0) % 3] as Color) * b
		var g: Color = (LEAF_GREEN[0] as Color).lerp(LEAF_GREEN[1], (v + 1.0) * 0.5)
		if world == "jungle":
			g = g.lerp(Color(0.2, 0.55, 0.26), 0.4)
		elif world == "swamp":
			g = g.lerp(Color(0.36, 0.42, 0.3), 0.5)
		return g * b
	if PATCHES.has(t):
		var p := (_tint.get_noise_2d(c.x, c.z) + 1.0) * 0.5
		var pc: Array = PATCHES[t]
		var col: Color = (pc[0] as Color).lerp(pc[1], clampf((p - 0.5) * 2.2 + 0.5, 0.0, 1.0)) * b
		if t == GRASS and world == "jungle":
			col *= Color(0.8, 0.96, 0.8)
		return col
	return Color(b, b, b)


func _exposed(x: int, y: int, z: int) -> bool:
	return _classify(x, y, z) >= 0


## The MultiMesh key for this cell, or -1 when nothing of it shows (air or buried).
func _classify(x: int, y: int, z: int) -> int:
	if not inside(x, y, z):
		return -1
	var i := idx(x, y, z)
	var t: int = cells[i]
	if t == AIR:
		return -1
	var up := sx
	var back := sx * sy
	var up_air := y + 1 >= sy or cells[i + up] == AIR
	var side_air: bool
	if edge_solid:   # outside the grid is solid
		side_air = (x + 1 < sx and cells[i + 1] == AIR) or (x > 0 and cells[i - 1] == AIR) \
			or (z + 1 < sz and cells[i + back] == AIR) or (z > 0 and cells[i - back] == AIR)
	else:
		side_air = x == 0 or x == sx - 1 or cells[i - 1] == AIR or cells[i + 1] == AIR \
			or z + 1 >= sz or cells[i + back] == AIR or (z > 0 and cells[i - back] == AIR)
	var down_air := y > 0 and cells[i - up] == AIR
	if not (up_air or side_air or down_air):
		return -1
	return _key(Vector3i(x, y, z), t, up_air and not side_air and not down_air)


## Draws every visible block once (at the start). After that, set_cell() only
## touches the few blocks around a change, so mining never freezes the game.
## Each MultiMesh keeps spare room; slots[key] says which block is in each slot.
func rebuild() -> void:
	built = true
	slot_of.clear()
	slots.clear()
	slots.resize(mm_nodes.size())
	for k in slots.size():
		slots[k] = []
	var lists := {}
	if chunked and hmap.size() == sx * sz:
		_collect_fast(lists)
	else:
		_collect_all(lists)
	for k in mm_nodes.size():
		var pts: Array = lists.get(k, [])
		if k % BLOCK_TYPES == AIR or (pts.is_empty() and chunked):
			if mm_nodes[k] != null:
				mm_nodes[k].multimesh.visible_instance_count = 0
			continue
		var mm := _node(k).multimesh
		var t := k % BLOCK_TYPES
		mm.instance_count = pts.size() + (SPARE_CHUNK if chunked else SPARE)
		mm.buffer = _buffer(pts, t, mm.instance_count)
		for i in pts.size():
			slot_of[pts[i]] = Vector2i(k, i)
		slots[k] = pts
		mm.visible_instance_count = pts.size()


## All the transforms + colours of a MultiMesh at once (much faster than one by one).
func _buffer(pts: Array, t: int, count: int) -> PackedFloat32Array:
	var b := PackedFloat32Array()
	b.resize(count * 16)
	for i in count:
		var o := i * 16
		b[o] = 1.0
		b[o + 5] = 1.0
		b[o + 10] = 1.0
		if i < pts.size():
			var c: Vector3i = pts[i]
			b[o + 3] = c.x
			b[o + 7] = c.y
			b[o + 11] = c.z
			var col := block_color(c, t)
			b[o + 12] = col.r
			b[o + 13] = col.g
			b[o + 14] = col.b
			b[o + 15] = col.a
		else:
			b[o + 15] = 1.0
	return b


## Every cell, row by row (plain levels).
func _collect_all(lists: Dictionary) -> void:
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
				var k := -1
				if chunked:
					k = _classify(x, y, z)
				# same test as _classify(), written out so plain levels draw quickly
				elif y + 1 >= sy or cells[i + up] == AIR or z + 1 >= sz or cells[i + back] == AIR \
						or x == 0 or x == sx - 1 or cells[i - 1] == AIR or cells[i + 1] == AIR \
						or (y > 0 and cells[i - up] == AIR) or (z > 0 and cells[i - back] == AIR):
					k = t
				if k >= 0:
					if not lists.has(k):
						lists[k] = []
					lists[k].append(Vector3i(x, y, z))


## The big home: only cells that can show. That's the top of each height-map column down
## to its lowest neighbour, plus every cell that was changed (caves, trees, buildings) and
## the cells next to it. Exact, but skips the millions of buried cells.
func _collect_fast(lists: Dictionary) -> void:
	var seen := PackedByteArray()
	seen.resize(cells.size())
	var cand := PackedInt32Array()
	var row := sx * sy
	# 1. the top of each height-map column down to its lowest neighbour
	for z in sz:
		for x in sx:
			var h := hmap[x + z * sx]
			var lo := h - 1
			if x > 0:
				lo = mini(lo, hmap[x - 1 + z * sx])
			if x + 1 < sx:
				lo = mini(lo, hmap[x + 1 + z * sx])
			if z > 0:
				lo = mini(lo, hmap[x + (z - 1) * sx])
			if z + 1 < sz:
				lo = mini(lo, hmap[x + (z + 1) * sx])
			var i := x + sx * (maxi(lo, 0) + sy * z)
			for y in range(maxi(lo, 0), mini(h, sy)):
				if seen[i] == 0:
					seen[i] = 1
					cand.append(i)
				i += sx
	# 2. every changed cell and its 6 neighbours
	for c in touched:
		var x: int = c.x
		var y: int = c.y
		var z: int = c.z
		var i := x + sx * (y + sy * z)
		for d in 7:
			var j := i
			match d:
				1:
					if x + 1 >= sx: continue
					j = i + 1
				2:
					if x < 1: continue
					j = i - 1
				3:
					if y + 1 >= sy: continue
					j = i + sx
				4:
					if y < 1: continue
					j = i - sx
				5:
					if z + 1 >= sz: continue
					j = i + row
				6:
					if z < 1: continue
					j = i - row
			if seen[j] == 0:
				seen[j] = 1
				cand.append(j)
	# 3. which of those show, and in which MultiMesh (same test as _classify, written out)
	for i in cand:
		var t: int = cells[i]
		if t == AIR:
			continue
		var x := i % sx
		var y := (i / sx) % sy
		var z := i / row
		var up_air := y + 1 >= sy or cells[i + sx] == AIR
		var side_air := (x + 1 < sx and cells[i + 1] == AIR) or (x > 0 and cells[i - 1] == AIR) \
			or (z + 1 < sz and cells[i + row] == AIR) or (z > 0 and cells[i - row] == AIR)
		var down_air := y > 0 and cells[i - sx] == AIR
		if not (up_air or side_air or down_air):
			continue
		var ci := ((z / CH) * ncx + x / CH) * 2 + (0 if y < band_y else 1)
		var k := (ci * 2 + (1 if up_air and not side_air and not down_air else 0)) * BLOCK_TYPES + t
		if not lists.has(k):
			lists[k] = []
		lists[k].append(Vector3i(x, y, z))


const SPARE := 2000


func _refresh(c: Vector3i) -> void:
	if not inside(c.x, c.y, c.z):
		return
	var k := _classify(c.x, c.y, c.z)
	if slot_of.has(c):
		var e: Vector2i = slot_of[c]
		if e.x == k:
			return
		_hide(c, e)
	if k >= 0:
		_show(c, k)


func _show(c: Vector3i, key: int) -> void:
	var arr: Array = slots[key]
	var mm := _node(key).multimesh
	if arr.size() >= mm.instance_count:
		_grow(key)   # ran out of spare room (lots of building): make room for this one only
	mm.set_instance_transform(arr.size(), Transform3D(Basis.IDENTITY, Vector3(c)))
	mm.set_instance_color(arr.size(), block_color(c, key % BLOCK_TYPES))
	slot_of[c] = Vector2i(key, arr.size())
	arr.append(c)
	mm.visible_instance_count = arr.size()


func _grow(key: int) -> void:
	var arr: Array = slots[key]
	var mm := _node(key).multimesh
	mm.instance_count = arr.size() + (SPARE_CHUNK * 4 if chunked else SPARE)
	mm.buffer = _buffer(arr, key % BLOCK_TYPES, mm.instance_count)
	mm.visible_instance_count = arr.size()


func _hide(c: Vector3i, e: Vector2i) -> void:
	var arr: Array = slots[e.x]
	var mm: MultiMesh = mm_nodes[e.x].multimesh
	var last: Vector3i = arr.back()
	if last != c:   # move the last block into the freed slot
		arr[e.y] = last
		mm.set_instance_transform(e.y, Transform3D(Basis.IDENTITY, Vector3(last)))
		mm.set_instance_color(e.y, block_color(last, e.x % BLOCK_TYPES))
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
