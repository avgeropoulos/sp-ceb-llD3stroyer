class_name Decor
## Decorations for the home base: what they're called, what they cost, and their 3D models.
## Placing one pays its price; picking it up gives the coins back.
## Items with "paint" can be coloured: the parts made with paint() take the colour
## (blankets, cushions, seats, frames), the rest (wood legs, metal, books) stays.
## "h" is how tall the paper picture stands (default 1.0).

## Paint colours: 0 = the item's own colours, then the paint pots.
const PAINTS := [Color(0, 0, 0, 0), Color("e8413b"), Color("ff8a1f"), Color("ffd23a"), Color("4cb048"), Color("2cbec8"),
	Color("3f7fd9"), Color("8a5bc4"), Color("ff6fa8"), Color("f2f2f6"), Color("34343e")]
const PAINT_NAMES := ["ITS OWN COLOURS", "RED", "ORANGE", "YELLOW", "GREEN", "TEAL", "BLUE", "PURPLE", "PINK", "WHITE", "BLACK"]

const ITEMS := [
	{"id": "flowers", "name": "FLOWER POT", "coins": 3, "paint": true},
	{"id": "bush", "name": "BUSH", "coins": 3},
	{"id": "fence", "name": "FENCE", "coins": 2, "paint": true},
	{"id": "tree", "name": "TREE", "coins": 8, "h": 2.6},
	{"id": "pumpkin", "name": "PUMPKIN", "coins": 4},
	{"id": "lamp", "name": "LAMP", "coins": 8, "paint": true, "h": 2.6},
	{"id": "chair", "name": "CHAIR", "coins": 5, "paint": true},
	{"id": "table", "name": "TABLE", "coins": 8, "paint": true},
	{"id": "bench", "name": "BENCH", "coins": 10, "paint": true},
	{"id": "bed", "name": "BED", "coins": 15, "paint": true},
	{"id": "campfire", "name": "CAMPFIRE", "coins": 8},
	{"id": "mailbox", "name": "MAILBOX", "coins": 5, "paint": true, "h": 1.4},
	{"id": "fountain", "name": "FOUNTAIN", "coins": 25, "h": 1.4},
	{"id": "plush_water", "name": "SPLASHY PLUSH", "coins": 12},
	{"id": "plush_grass", "name": "SEEDY PLUSH", "coins": 12},
	{"id": "plush_fire", "name": "EMBER PLUSH", "coins": 12},
	{"id": "plush_spark", "name": "SPARKY PLUSH", "coins": 12},
	{"id": "trophy", "name": "KRAG JR. TROPHY", "coins": 0, "need_clear": true, "h": 1.4},
	# furniture (new items always go at the end)
	{"id": "bed_bunk", "name": "BUNK BED", "coins": 25, "paint": true, "h": 2.0},
	{"id": "bed_canopy", "name": "CANOPY BED", "coins": 30, "paint": true, "h": 2.1},
	{"id": "bed_car", "name": "RACE CAR BED", "coins": 30, "paint": true, "h": 1.1},
	{"id": "sleeping_bag", "name": "SLEEPING BAG", "coins": 6, "paint": true, "h": 0.8},
	{"id": "hammock", "name": "HAMMOCK", "coins": 12, "paint": true, "h": 1.3},
	{"id": "sofa", "name": "SOFA", "coins": 20, "paint": true, "h": 1.1},
	{"id": "armchair", "name": "ARMCHAIR", "coins": 12, "paint": true, "h": 1.0},
	{"id": "beanbag", "name": "BEANBAG", "coins": 8, "paint": true, "h": 0.8},
	{"id": "bookshelf", "name": "BOOKSHELF", "coins": 15, "paint": true, "h": 1.9},
	{"id": "tv", "name": "TV", "coins": 20, "paint": true, "h": 1.3},
	{"id": "rug", "name": "RUG", "coins": 6, "paint": true, "h": 0.7},
	{"id": "desk", "name": "DESK", "coins": 12, "paint": true, "h": 1.1},
	{"id": "wardrobe", "name": "WARDROBE", "coins": 18, "paint": true, "h": 1.9},
	{"id": "plant", "name": "PLANT POT", "coins": 5, "paint": true, "h": 1.3},
	{"id": "toybox", "name": "TOY BOX", "coins": 10, "paint": true, "h": 0.9},
]


static func find(id: String) -> Dictionary:
	for it in ITEMS:
		if it.id == id:
			return it
	return {}


static func paintable(id: String) -> bool:
	return find(id).get("paint", false)


## A part that takes the paint colour (shade 1.0 = the main colour, lower = a darker trim).
static func paint(parent: Node3D, mesh: Mesh, pos: Vector3, c: Color, scl := Vector3.ONE, rot := Vector3.ZERO, shade := 1.0, glow := 0.0) -> MeshInstance3D:
	var mi := Toys.part(parent, mesh, pos, c, scl, rot, true, glow)
	mi.set_meta("paint", shade)
	return mi


## Colours a model's paint parts (colour 0 = leave its own colours).
static func recolour(n: Node3D, col: int) -> void:
	if col <= 0 or col >= PAINTS.size():
		return
	for mi in Toys._all_meshes(n):
		if mi.has_meta("paint"):
			var m: StandardMaterial3D = mi.material_override
			var c: Color = PAINTS[col]
			var sh: float = mi.get_meta("paint")
			c = Color(c.r * sh, c.g * sh, c.b * sh)
			mi.material_override = Toys.mat(c, m.next_pass != null, m.emission_energy_multiplier if m.emission_enabled else 0.0)


## The model with paint parts in plain white and everything else black (for tools/render_decor.gd:
## the paper look uses this picture to know which pixels to colour, and how shaded they are).
static func mask_model(id: String) -> Node3D:
	var n := model(id)
	var black := StandardMaterial3D.new()
	black.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	black.albedo_color = Color.BLACK
	for mi in Toys._all_meshes(n):
		if mi.has_meta("paint"):
			var sh: float = mi.get_meta("paint")
			var w := StandardMaterial3D.new()
			w.albedo_color = Color(sh, sh, sh)
			w.roughness = 0.8
			w.metallic_specular = 0.25
			w.diffuse_mode = BaseMaterial3D.DIFFUSE_LAMBERT_WRAP
			mi.material_override = w
		else:
			mi.material_override = black
	return n


## The 3D model, feet at y = 0, about one block wide, facing +Z.
static func model(id: String) -> Node3D:
	var r := Node3D.new()
	var P := Toys.part
	match id:
		"flowers":
			paint(r, Toys.cyl(0.22, 0.16, 0.3, 12), Vector3(0, 0.15, 0), Color("c8643c"))
			P.call(r, Toys.cyl(0.2, 0.2, 0.04, 12), Vector3(0, 0.3, 0), Color("6b4226"), Vector3.ONE, Vector3.ZERO, false)
			for k in 5:
				var a := k * TAU / 5
				var col: Color = [Color("ff6fa8"), Color("ffd23a"), Color("7fb8ff"), Color("ff7a4a"), Color("ffffff")][k]
				P.call(r, Toys.cyl(0.012, 0.012, 0.3, 5), Vector3(cos(a) * 0.09, 0.45, sin(a) * 0.09), Color("3f9a48"), Vector3.ONE, Vector3.ZERO, false)
				P.call(r, Toys.sph(0.08), Vector3(cos(a) * 0.1, 0.62, sin(a) * 0.1), col)
		"bush":
			for c in [[-0.18, 0.22, 0.24], [0.16, 0.24, 0.26], [0.0, 0.38, 0.26]]:
				P.call(r, Toys.sph(c[2]), Vector3(c[0], c[1], 0), Color("53b85c"))
			P.call(r, Toys.sph(0.05), Vector3(0.1, 0.5, 0.18), Color("ff6a7a"))
		"fence":
			for x in [-0.4, 0.0, 0.4]:
				paint(r, Toys.box(Vector3(0.12, 0.7, 0.1)), Vector3(x, 0.35, 0), Color("f2efe6"))
				paint(r, Toys.cyl(0.0, 0.085, 0.12, 4), Vector3(x, 0.76, 0), Color("f2efe6"), Vector3.ONE, Vector3(0, PI / 4, 0))
			for y in [0.25, 0.52]:
				paint(r, Toys.box(Vector3(1.0, 0.09, 0.06)), Vector3(0, y, 0.05), Color("f2efe6"))
		"tree":
			var t := Toys.prop("tree_a", 3.2)
			r.add_child(t)
		"pumpkin":
			for k in 6:
				var a := k * TAU / 6
				P.call(r, Toys.sph(0.2), Vector3(cos(a) * 0.12, 0.24, sin(a) * 0.12), Color("ff8a1f"), Vector3(0.8, 1.0, 0.8))
			P.call(r, Toys.sph(0.22), Vector3(0, 0.25, 0), Color("ff8a1f"))
			P.call(r, Toys.cyl(0.03, 0.04, 0.14, 6), Vector3(0, 0.5, 0), Color("4f7a2a"))
			# a happy carved face that glows
			for sx in [-0.09, 0.09]:
				P.call(r, Toys.cyl(0.0, 0.05, 0.05, 3), Vector3(sx, 0.3, 0.3), Color("ffe36a"), Vector3.ONE, Vector3(PI / 2, 0, 0), false, 1.5)
			P.call(r, Toys.box(Vector3(0.2, 0.04, 0.04)), Vector3(0, 0.18, 0.31), Color("ffe36a"), Vector3.ONE, Vector3.ZERO, false, 1.5)
		"lamp":
			P.call(r, Toys.cyl(0.14, 0.18, 0.1, 10), Vector3(0, 0.05, 0), Color("3a3f4f"))
			P.call(r, Toys.cyl(0.04, 0.04, 1.6, 8), Vector3(0, 0.85, 0), Color("3a3f4f"))
			paint(r, Toys.box(Vector3(0.3, 0.32, 0.3)), Vector3(0, 1.75, 0), Color("fff2a8"), Vector3.ONE, Vector3.ZERO, 1.0, 1.6)
			P.call(r, Toys.cyl(0.0, 0.26, 0.16, 4), Vector3(0, 1.98, 0), Color("3a3f4f"), Vector3.ONE, Vector3(0, PI / 4, 0))
		"chair":
			paint(r, Toys.box(Vector3(0.5, 0.08, 0.5)), Vector3(0, 0.42, 0), Color("c4925a"))
			paint(r, Toys.box(Vector3(0.5, 0.5, 0.08)), Vector3(0, 0.7, -0.21), Color("c4925a"))
			for x in [-0.2, 0.2]:
				for z in [-0.2, 0.2]:
					P.call(r, Toys.box(Vector3(0.07, 0.42, 0.07)), Vector3(x, 0.21, z), Color("8a5a35"))
		"table":
			paint(r, Toys.box(Vector3(0.9, 0.08, 0.7)), Vector3(0, 0.62, 0), Color("c4925a"))
			for x in [-0.38, 0.38]:
				for z in [-0.28, 0.28]:
					P.call(r, Toys.box(Vector3(0.08, 0.6, 0.08)), Vector3(x, 0.3, z), Color("8a5a35"))
			P.call(r, Toys.cyl(0.1, 0.12, 0.04, 12), Vector3(0.15, 0.68, 0.05), Color.WHITE)
			P.call(r, Toys.sph(0.07), Vector3(0.15, 0.73, 0.05), Color("ff4f6d"))
		"bench":
			paint(r, Toys.box(Vector3(0.95, 0.08, 0.4)), Vector3(0, 0.42, 0), Color("c4925a"))
			paint(r, Toys.box(Vector3(0.95, 0.3, 0.06)), Vector3(0, 0.7, -0.18), Color("c4925a"))
			for x in [-0.4, 0.4]:
				P.call(r, Toys.box(Vector3(0.08, 0.42, 0.36)), Vector3(x, 0.21, 0), Color("3a3f4f"))
		"bed":
			P.call(r, Toys.box(Vector3(0.9, 0.3, 1.6)), Vector3(0, 0.25, 0.3), Color("8a5a35"))
			P.call(r, Toys.box(Vector3(0.84, 0.16, 1.5)), Vector3(0, 0.46, 0.3), Color("f2f2f6"))
			paint(r, Toys.box(Vector3(0.86, 0.12, 1.0)), Vector3(0, 0.5, 0.55), Color("3f7fd9"))
			P.call(r, Toys.box(Vector3(0.5, 0.12, 0.3)), Vector3(0, 0.58, -0.3), Color.WHITE)
			P.call(r, Toys.box(Vector3(0.9, 0.7, 0.1)), Vector3(0, 0.45, -0.5), Color("8a5a35"))
		"campfire":
			for k in 5:
				var a := k * TAU / 5
				P.call(r, Toys.sph(0.09), Vector3(cos(a) * 0.32, 0.07, sin(a) * 0.32), Color("8a8e96"))
			for k in 3:
				P.call(r, Toys.cyl(0.05, 0.05, 0.6, 6), Vector3(0, 0.08, 0), Color("6b4226"), Vector3.ONE, Vector3(PI / 2, k * PI / 3, 0))
			Toys._flame(r, Vector3(0, 0.1, 0), 0.42)
		"mailbox":
			P.call(r, Toys.box(Vector3(0.08, 0.9, 0.08)), Vector3(0, 0.45, 0), Color("8a5a35"))
			paint(r, Toys.box(Vector3(0.28, 0.26, 0.42)), Vector3(0, 0.98, 0), Color("3f7fd9"))
			paint(r, Toys.cyl(0.14, 0.14, 0.42, 12), Vector3(0, 1.11, 0), Color("3f7fd9"), Vector3.ONE, Vector3(PI / 2, 0, 0))
			P.call(r, Toys.box(Vector3(0.03, 0.18, 0.06)), Vector3(0.17, 1.12, 0.1), Color("e8413b"))
		"fountain":
			P.call(r, Toys.cyl(0.48, 0.5, 0.26, 20), Vector3(0, 0.13, 0), Color("b8bcc4"))
			P.call(r, Toys.cyl(0.4, 0.4, 0.04, 20), Vector3(0, 0.25, 0), Color("5ab4ff"), Vector3.ONE, Vector3.ZERO, false, 0.4)
			P.call(r, Toys.cyl(0.08, 0.1, 0.6, 10), Vector3(0, 0.5, 0), Color("b8bcc4"))
			P.call(r, Toys.cyl(0.22, 0.16, 0.1, 14), Vector3(0, 0.82, 0), Color("b8bcc4"))
			for k in 6:
				var a := k * TAU / 6
				P.call(r, Toys.sph(0.05), Vector3(cos(a) * 0.2, 0.92 + (k % 2) * 0.06, sin(a) * 0.2), Color("8fd0ff"), Vector3.ONE, Vector3.ZERO, false, 0.6)
			P.call(r, Toys.sph(0.07), Vector3(0, 1.0, 0), Color("cdeeff"), Vector3.ONE, Vector3.ZERO, false, 0.6)
		"plush_water", "plush_grass", "plush_fire":
			var body := Node3D.new()
			body.scale = Vector3.ONE * 0.7
			r.add_child(body)
			Toys._critter(body, {}, id.substr(6), 0)
			for n in body.get_children():
				n.rotation.y = 0.0
		"plush_spark":
			var body := Node3D.new()
			body.scale = Vector3.ONE * 0.75
			r.add_child(body)
			Toys._sparky(body, {}, 0)
		"trophy":
			P.call(r, Toys.box(Vector3(0.5, 0.18, 0.5)), Vector3(0, 0.09, 0), Color("6b4226"))
			P.call(r, Toys.cyl(0.08, 0.14, 0.3, 10), Vector3(0, 0.33, 0), Color("ffcf33"))
			P.call(r, Toys.cyl(0.3, 0.12, 0.38, 14), Vector3(0, 0.66, 0), Color("ffcf33"))
			for sx in [-1, 1]:
				P.call(r, Toys.cyl(0.03, 0.03, 0.22, 6), Vector3(sx * 0.32, 0.68, 0), Color("ffcf33"), Vector3.ONE, Vector3(0, 0, PI / 2))
			Toys.star_badge(r, Vector3(0, 0.66, 0.24), 0.1)
			# a tiny Krag Jr. hover-pot on top
			P.call(r, Toys.hemi(0.13), Vector3(0, 0.98, 0), Color("8a5bc4"), Vector3.ONE, Vector3(PI, 0, 0))
			P.call(r, Toys.sph(0.08), Vector3(0, 1.04, 0), Color("59c25a"))
		_:
			_furniture(r, id)
	for mi in Toys._all_meshes(r):
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	return r


const WOOD := Color("c4925a")
const DWOOD := Color("8a5a35")
const METAL := Color("3a3f4f")


## The newer furniture (beds, sofa, shelves...).
static func _furniture(r: Node3D, id: String) -> void:
	var P := Toys.part
	match id:
		"bed_bunk":
			for x in [-0.45, 0.45]:
				for z in [-0.48, 1.08]:
					P.call(r, Toys.box(Vector3(0.1, 1.85, 0.1)), Vector3(x, 0.92, z), DWOOD)
			for lv in [[0.22, 0.9], [1.12, 1.0]]:
				var y: float = lv[0]
				P.call(r, Toys.box(Vector3(0.9, 0.14, 1.6)), Vector3(0, y, 0.3), DWOOD)
				P.call(r, Toys.box(Vector3(0.84, 0.14, 1.5)), Vector3(0, y + 0.13, 0.3), Color("f2f2f6"))
				paint(r, Toys.box(Vector3(0.86, 0.1, 1.0)), Vector3(0, y + 0.17, 0.55), Color("4cb048"), Vector3.ONE, Vector3.ZERO, lv[1])
				P.call(r, Toys.box(Vector3(0.48, 0.1, 0.28)), Vector3(0, y + 0.24, -0.28), Color.WHITE)
			P.call(r, Toys.box(Vector3(0.06, 0.12, 1.5)), Vector3(-0.45, 1.5, 0.3), DWOOD)   # rail on top
			for k in 4:   # ladder at the foot
				P.call(r, Toys.box(Vector3(0.5, 0.05, 0.05)), Vector3(0.1, 0.45 + k * 0.28, 1.14), WOOD)
		"bed_canopy":
			P.call(r, Toys.box(Vector3(0.95, 0.3, 1.6)), Vector3(0, 0.25, 0.3), Color("f2efe6"))
			P.call(r, Toys.box(Vector3(0.88, 0.16, 1.5)), Vector3(0, 0.46, 0.3), Color("f2f2f6"))
			paint(r, Toys.box(Vector3(0.9, 0.12, 1.0)), Vector3(0, 0.5, 0.55), Color("ff6fa8"))
			P.call(r, Toys.box(Vector3(0.5, 0.12, 0.3)), Vector3(0, 0.58, -0.3), Color.WHITE)
			P.call(r, Toys.box(Vector3(0.95, 0.8, 0.1)), Vector3(0, 0.5, -0.5), Color("f2efe6"))
			for x in [-0.46, 0.46]:
				for z in [-0.5, 1.1]:
					P.call(r, Toys.cyl(0.035, 0.035, 2.0, 8), Vector3(x, 1.0, z), Color("ffd23a"))
			paint(r, Toys.box(Vector3(1.02, 0.08, 1.7)), Vector3(0, 2.0, 0.3), Color("ff6fa8"), Vector3.ONE, Vector3.ZERO, 0.9)
			for x in [-0.5, 0.5]:   # curtains tied at the posts
				paint(r, Toys.cyl(0.06, 0.12, 1.4, 10), Vector3(x, 1.27, -0.45), Color("ff6fa8"), Vector3.ONE, Vector3.ZERO, 0.8)
				paint(r, Toys.cyl(0.06, 0.12, 1.4, 10), Vector3(x, 1.27, 1.05), Color("ff6fa8"), Vector3.ONE, Vector3.ZERO, 0.8)
			Toys.star_badge(r, Vector3(0, 0.75, -0.44), 0.09)
		"bed_car":
			paint(r, Toys.box(Vector3(1.0, 0.42, 1.8)), Vector3(0, 0.33, 0.3), Color("e8413b"))
			paint(r, Toys.box(Vector3(1.0, 0.24, 0.4)), Vector3(0, 0.66, -0.4), Color("e8413b"), Vector3.ONE, Vector3.ZERO, 0.85)
			paint(r, Toys.box(Vector3(1.1, 0.06, 0.3)), Vector3(0, 0.98, -0.55), Color("e8413b"), Vector3.ONE, Vector3.ZERO, 0.75)
			for x in [-0.4, 0.4]:
				P.call(r, Toys.box(Vector3(0.06, 0.2, 0.06)), Vector3(x, 0.86, -0.55), METAL)
			P.call(r, Toys.box(Vector3(0.82, 0.12, 1.2)), Vector3(0, 0.58, 0.45), Color("f2f2f6"))
			P.call(r, Toys.box(Vector3(0.84, 0.1, 0.7)), Vector3(0, 0.62, 0.75), Color("3a3f4f"))
			P.call(r, Toys.box(Vector3(0.24, 0.43, 0.08)), Vector3(0, 0.34, 1.2), Color.WHITE)
			for x in [-0.33, 0.33]:
				P.call(r, Toys.sph(0.07), Vector3(x, 0.42, 1.2), Color("fff2a8"), Vector3.ONE, Vector3.ZERO, true, 1.2)
			for x in [-0.53, 0.53]:
				for z in [-0.3, 0.9]:
					P.call(r, Toys.cyl(0.2, 0.2, 0.12, 14), Vector3(x, 0.2, z), Color("23232b"), Vector3.ONE, Vector3(0, 0, PI / 2))
					P.call(r, Toys.cyl(0.09, 0.09, 0.13, 10), Vector3(x * 1.02, 0.2, z), Color("b8bcc4"), Vector3.ONE, Vector3(0, 0, PI / 2), false)
		"sleeping_bag":
			paint(r, Toys.cap(0.2, 1.5), Vector3(0, 0.12, 0.3), Color("4cb048"), Vector3(1.6, 1.0, 0.55), Vector3(PI / 2, 0, 0))
			paint(r, Toys.box(Vector3(0.5, 0.05, 0.7)), Vector3(0, 0.23, 0.55), Color("4cb048"), Vector3.ONE, Vector3.ZERO, 0.8)
			P.call(r, Toys.cap(0.11, 0.5), Vector3(0, 0.12, -0.42), Color.WHITE, Vector3(1, 1, 0.6), Vector3(0, 0, PI / 2))
		"hammock":
			for x in [-0.75, 0.75]:
				P.call(r, Toys.box(Vector3(0.1, 1.25, 0.1)), Vector3(x, 0.62, 0), DWOOD)
				P.call(r, Toys.box(Vector3(0.3, 0.06, 0.3)), Vector3(x, 0.03, 0), DWOOD)
			paint(r, Toys.sph(0.5), Vector3(0, 0.62, 0), Color("ff8a1f"), Vector3(1.35, 0.32, 0.6))
			paint(r, Toys.box(Vector3(1.0, 0.04, 0.4)), Vector3(0, 0.74, 0), Color("ff8a1f"), Vector3.ONE, Vector3.ZERO, 0.85)
			for x in [-0.68, 0.68]:
				P.call(r, Toys.cyl(0.015, 0.015, 0.5, 4), Vector3(x * 0.92, 0.95, 0), Color("e9dcc0"), Vector3.ONE, Vector3(0, 0, -signf(x) * 0.7))
		"sofa":
			paint(r, Toys.box(Vector3(1.15, 0.3, 0.62)), Vector3(0, 0.28, 0), Color("3f7fd9"), Vector3.ONE, Vector3.ZERO, 0.85)
			for x in [-0.27, 0.27]:
				paint(r, Toys.box(Vector3(0.5, 0.14, 0.5)), Vector3(x, 0.5, 0.04), Color("3f7fd9"))
			paint(r, Toys.box(Vector3(1.15, 0.5, 0.18)), Vector3(0, 0.66, -0.23), Color("3f7fd9"), Vector3.ONE, Vector3.ZERO, 0.85)
			for x in [-0.57, 0.57]:
				paint(r, Toys.box(Vector3(0.16, 0.42, 0.62)), Vector3(x, 0.44, 0), Color("3f7fd9"), Vector3.ONE, Vector3.ZERO, 0.8)
				for z in [-0.24, 0.24]:
					P.call(r, Toys.box(Vector3(0.08, 0.13, 0.08)), Vector3(x * 0.9, 0.06, z), DWOOD)
			P.call(r, Toys.box(Vector3(0.28, 0.24, 0.1)), Vector3(-0.3, 0.68, -0.08), Color("ffd23a"), Vector3.ONE, Vector3(0.2, 0.3, 0.1))
		"armchair":
			paint(r, Toys.box(Vector3(0.7, 0.3, 0.62)), Vector3(0, 0.28, 0), Color("8a5bc4"), Vector3.ONE, Vector3.ZERO, 0.85)
			paint(r, Toys.box(Vector3(0.48, 0.14, 0.5)), Vector3(0, 0.5, 0.04), Color("8a5bc4"))
			paint(r, Toys.box(Vector3(0.7, 0.6, 0.18)), Vector3(0, 0.7, -0.23), Color("8a5bc4"), Vector3.ONE, Vector3.ZERO, 0.85)
			for x in [-0.33, 0.33]:
				paint(r, Toys.box(Vector3(0.14, 0.42, 0.62)), Vector3(x, 0.44, 0), Color("8a5bc4"), Vector3.ONE, Vector3.ZERO, 0.8)
				for z in [-0.24, 0.24]:
					P.call(r, Toys.box(Vector3(0.08, 0.13, 0.08)), Vector3(x * 0.9, 0.06, z), DWOOD)
		"beanbag":
			paint(r, Toys.sph(0.42), Vector3(0, 0.28, 0), Color("ff8a1f"), Vector3(1.1, 0.68, 1.1))
			paint(r, Toys.sph(0.3), Vector3(0, 0.44, -0.16), Color("ff8a1f"), Vector3(1.1, 0.9, 0.7), Vector3.ZERO, 0.9)
		"bookshelf":
			for x in [-0.43, 0.43]:
				paint(r, Toys.box(Vector3(0.08, 1.75, 0.4)), Vector3(x, 0.875, 0), WOOD)
			for y in [0.04, 0.47, 0.9, 1.33, 1.72]:
				paint(r, Toys.box(Vector3(0.94, 0.06, 0.4)), Vector3(0, y, 0), WOOD)
			paint(r, Toys.box(Vector3(0.86, 1.72, 0.04)), Vector3(0, 0.87, -0.18), WOOD, Vector3.ONE, Vector3.ZERO, 0.75)
			var cols := [Color("e8413b"), Color("3f7fd9"), Color("ffd23a"), Color("4cb048"), Color("8a5bc4"), Color("ff8a1f"), Color("2cbec8")]
			for row in 3:
				var x := -0.38
				var k := row * 3
				while x < 0.3:
					var w := 0.07 + float((k * 7) % 3) * 0.02
					var hb := 0.26 + float((k * 5) % 4) * 0.03
					P.call(r, Toys.box(Vector3(w, hb, 0.28)), Vector3(x + w * 0.5, 0.07 + row * 0.43 + hb * 0.5, 0.02), cols[k % cols.size()], Vector3.ONE, Vector3.ZERO, false)
					x += w + 0.015
					k += 1
		"tv":
			paint(r, Toys.box(Vector3(1.0, 0.42, 0.45)), Vector3(0, 0.21, 0), WOOD)
			P.call(r, Toys.box(Vector3(0.44, 0.3, 0.02)), Vector3(-0.24, 0.21, 0.23), DWOOD, Vector3.ONE, Vector3.ZERO, false)
			P.call(r, Toys.box(Vector3(0.44, 0.3, 0.02)), Vector3(0.24, 0.21, 0.23), DWOOD, Vector3.ONE, Vector3.ZERO, false)
			P.call(r, Toys.box(Vector3(0.2, 0.08, 0.16)), Vector3(0, 0.46, 0), METAL)
			P.call(r, Toys.box(Vector3(0.95, 0.58, 0.08)), Vector3(0, 0.82, 0), Color("23232b"))
			P.call(r, Toys.box(Vector3(0.85, 0.48, 0.02)), Vector3(0, 0.82, 0.045), Color("54c8ff"), Vector3.ONE, Vector3.ZERO, false, 0.9)
			P.call(r, Toys.sph(0.09), Vector3(-0.1, 0.8, 0.06), Color("ffd83b"), Vector3(1, 1, 0.3), Vector3.ZERO, false, 0.6)
		"rug":
			paint(r, Toys.cyl(0.62, 0.62, 0.03, 28), Vector3(0, 0.015, 0), Color("e8413b"), Vector3(1, 1, 0.8), Vector3.ZERO, 0.75)
			paint(r, Toys.cyl(0.48, 0.48, 0.035, 28), Vector3(0, 0.02, 0), Color("e8413b"), Vector3(1, 1, 0.8))
			P.call(r, Toys.cyl(0.2, 0.2, 0.04, 20), Vector3(0, 0.025, 0), Color("ffd23a"), Vector3(1, 1, 0.8), Vector3.ZERO, false)
		"desk":
			paint(r, Toys.box(Vector3(1.0, 0.07, 0.55)), Vector3(0, 0.72, 0), WOOD)
			paint(r, Toys.box(Vector3(0.36, 0.66, 0.5)), Vector3(0.3, 0.34, 0), WOOD, Vector3.ONE, Vector3.ZERO, 0.85)
			for y in [0.18, 0.5]:
				P.call(r, Toys.sph(0.03), Vector3(0.3, y, 0.26), Color("ffcf33"))
			for z in [-0.22, 0.22]:
				P.call(r, Toys.box(Vector3(0.06, 0.7, 0.06)), Vector3(-0.45, 0.35, z), METAL)
			P.call(r, Toys.box(Vector3(0.42, 0.03, 0.28)), Vector3(-0.15, 0.77, 0.05), Color("b8bcc4"))
			P.call(r, Toys.box(Vector3(0.42, 0.28, 0.03)), Vector3(-0.15, 0.92, -0.09), Color("b8bcc4"), Vector3.ONE, Vector3(-0.25, 0, 0))
			P.call(r, Toys.box(Vector3(0.36, 0.22, 0.01)), Vector3(-0.15, 0.92, -0.07), Color("8fd0ff"), Vector3.ONE, Vector3(-0.25, 0, 0), false, 0.8)
		"wardrobe":
			paint(r, Toys.box(Vector3(0.95, 1.8, 0.55)), Vector3(0, 0.95, 0), WOOD, Vector3.ONE, Vector3.ZERO, 0.85)
			for x in [-0.235, 0.235]:
				paint(r, Toys.box(Vector3(0.44, 1.6, 0.03)), Vector3(x, 0.98, 0.285), WOOD)
				P.call(r, Toys.sph(0.035), Vector3(x * 0.25, 1.0, 0.31), Color("ffcf33"))
			paint(r, Toys.box(Vector3(1.02, 0.08, 0.6)), Vector3(0, 1.88, 0), WOOD, Vector3.ONE, Vector3.ZERO, 0.7)
			for x in [-0.4, 0.4]:
				P.call(r, Toys.box(Vector3(0.08, 0.06, 0.08)), Vector3(x, 0.03, 0), DWOOD)
		"plant":
			paint(r, Toys.cyl(0.21, 0.15, 0.36, 14), Vector3(0, 0.18, 0), Color("c8643c"))
			paint(r, Toys.cyl(0.23, 0.23, 0.06, 14), Vector3(0, 0.36, 0), Color("c8643c"), Vector3.ONE, Vector3.ZERO, 0.85)
			P.call(r, Toys.cyl(0.19, 0.19, 0.03, 14), Vector3(0, 0.38, 0), Color("6b4226"), Vector3.ONE, Vector3.ZERO, false)
			P.call(r, Toys.cyl(0.02, 0.025, 0.6, 6), Vector3(0, 0.68, 0), Color("3f8a3a"))
			for k in 6:
				var a := k * TAU / 6 + 0.3
				var y := 0.6 + (k % 3) * 0.2
				P.call(r, Toys.sph(0.16), Vector3(cos(a) * 0.17, y, sin(a) * 0.17), Color("3fae4a") if k % 2 else Color("57c45a"), Vector3(1.3, 0.35, 0.7), Vector3(0, -a, 0.5))
		"toybox":
			paint(r, Toys.box(Vector3(0.82, 0.48, 0.52)), Vector3(0, 0.24, 0), Color("3f7fd9"))
			paint(r, Toys.box(Vector3(0.86, 0.06, 0.56)), Vector3(0, 0.62, -0.38), Color("3f7fd9"), Vector3.ONE, Vector3(-1.2, 0, 0), 0.85)
			P.call(r, Toys.box(Vector3(0.84, 0.06, 0.06)), Vector3(0, 0.36, 0.27), Color("ffd23a"), Vector3.ONE, Vector3.ZERO, false)
			Toys.star_badge(r, Vector3(0, 0.22, 0.27), 0.08)
			P.call(r, Toys.sph(0.13), Vector3(-0.2, 0.55, 0.05), Color("e8413b"))
			P.call(r, Toys.box(Vector3(0.18, 0.18, 0.18)), Vector3(0.18, 0.54, 0.02), Color("ffd23a"), Vector3.ONE, Vector3(0.3, 0.5, 0.2))
			P.call(r, Toys.sph(0.1), Vector3(0.02, 0.6, -0.08), Color("a8743f"))
			for x in [-0.07, 0.11]:
				P.call(r, Toys.sph(0.04), Vector3(x, 0.69, -0.08), Color("a8743f"))

