class_name Decor
## Decorations for the home base: what they're called, what they cost, and their 3D models.
## Placing one pays its price; picking it up gives the coins back.

const ITEMS := [
	{"id": "flowers", "name": "FLOWER POT", "coins": 3},
	{"id": "bush", "name": "BUSH", "coins": 3},
	{"id": "fence", "name": "FENCE", "coins": 2},
	{"id": "tree", "name": "TREE", "coins": 8},
	{"id": "pumpkin", "name": "PUMPKIN", "coins": 4},
	{"id": "lamp", "name": "LAMP", "coins": 8},
	{"id": "chair", "name": "CHAIR", "coins": 5},
	{"id": "table", "name": "TABLE", "coins": 8},
	{"id": "bench", "name": "BENCH", "coins": 10},
	{"id": "bed", "name": "BED", "coins": 15},
	{"id": "campfire", "name": "CAMPFIRE", "coins": 8},
	{"id": "mailbox", "name": "MAILBOX", "coins": 5},
	{"id": "fountain", "name": "FOUNTAIN", "coins": 25},
	{"id": "plush_water", "name": "SPLASHY PLUSH", "coins": 12},
	{"id": "plush_grass", "name": "SEEDY PLUSH", "coins": 12},
	{"id": "plush_fire", "name": "EMBER PLUSH", "coins": 12},
	{"id": "plush_spark", "name": "SPARKY PLUSH", "coins": 12},
	{"id": "trophy", "name": "KRAG JR. TROPHY", "coins": 0, "need_clear": true},
]


static func find(id: String) -> Dictionary:
	for it in ITEMS:
		if it.id == id:
			return it
	return {}


## The 3D model, feet at y = 0, about one block wide, facing +Z.
static func model(id: String) -> Node3D:
	var r := Node3D.new()
	var P := Toys.part
	match id:
		"flowers":
			P.call(r, Toys.cyl(0.22, 0.16, 0.3, 12), Vector3(0, 0.15, 0), Color("c8643c"))
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
				P.call(r, Toys.box(Vector3(0.12, 0.7, 0.1)), Vector3(x, 0.35, 0), Color("f2efe6"))
				P.call(r, Toys.cyl(0.0, 0.085, 0.12, 4), Vector3(x, 0.76, 0), Color("f2efe6"), Vector3.ONE, Vector3(0, PI / 4, 0))
			for y in [0.25, 0.52]:
				P.call(r, Toys.box(Vector3(1.0, 0.09, 0.06)), Vector3(0, y, 0.05), Color("f2efe6"))
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
			P.call(r, Toys.box(Vector3(0.3, 0.32, 0.3)), Vector3(0, 1.75, 0), Color("fff2a8"), Vector3.ONE, Vector3.ZERO, true, 1.6)
			P.call(r, Toys.cyl(0.0, 0.26, 0.16, 4), Vector3(0, 1.98, 0), Color("3a3f4f"), Vector3.ONE, Vector3(0, PI / 4, 0))
		"chair":
			P.call(r, Toys.box(Vector3(0.5, 0.08, 0.5)), Vector3(0, 0.42, 0), Color("c4925a"))
			P.call(r, Toys.box(Vector3(0.5, 0.5, 0.08)), Vector3(0, 0.7, -0.21), Color("c4925a"))
			for x in [-0.2, 0.2]:
				for z in [-0.2, 0.2]:
					P.call(r, Toys.box(Vector3(0.07, 0.42, 0.07)), Vector3(x, 0.21, z), Color("8a5a35"))
		"table":
			P.call(r, Toys.box(Vector3(0.9, 0.08, 0.7)), Vector3(0, 0.62, 0), Color("c4925a"))
			for x in [-0.38, 0.38]:
				for z in [-0.28, 0.28]:
					P.call(r, Toys.box(Vector3(0.08, 0.6, 0.08)), Vector3(x, 0.3, z), Color("8a5a35"))
			P.call(r, Toys.cyl(0.1, 0.12, 0.04, 12), Vector3(0.15, 0.68, 0.05), Color.WHITE)
			P.call(r, Toys.sph(0.07), Vector3(0.15, 0.73, 0.05), Color("ff4f6d"))
		"bench":
			P.call(r, Toys.box(Vector3(0.95, 0.08, 0.4)), Vector3(0, 0.42, 0), Color("c4925a"))
			P.call(r, Toys.box(Vector3(0.95, 0.3, 0.06)), Vector3(0, 0.7, -0.18), Color("c4925a"))
			for x in [-0.4, 0.4]:
				P.call(r, Toys.box(Vector3(0.08, 0.42, 0.36)), Vector3(x, 0.21, 0), Color("3a3f4f"))
		"bed":
			P.call(r, Toys.box(Vector3(0.9, 0.3, 1.6)), Vector3(0, 0.25, 0.3), Color("8a5a35"))
			P.call(r, Toys.box(Vector3(0.84, 0.16, 1.5)), Vector3(0, 0.46, 0.3), Color("f2f2f6"))
			P.call(r, Toys.box(Vector3(0.86, 0.12, 1.0)), Vector3(0, 0.5, 0.55), Color("3f7fd9"))
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
			P.call(r, Toys.box(Vector3(0.28, 0.26, 0.42)), Vector3(0, 0.98, 0), Color("3f7fd9"))
			P.call(r, Toys.cyl(0.14, 0.14, 0.42, 12, ), Vector3(0, 1.11, 0), Color("3f7fd9"), Vector3.ONE, Vector3(PI / 2, 0, 0))
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
	for mi in Toys._all_meshes(r):
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	return r
