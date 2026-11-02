class_name PartnerLooks
## The partners' 3D toy looks (our own designs). Every line has its own body plan, and every
## evolution is clearly bigger and fancier: shell armour, flower capes, wings, crystal spikes,
## glowing eyes and markings. Final forms get a little aura; MEGA forms (mega.gd) add gold
## armour, a crown of light and a bigger aura.
## Moving parts (for Toys._pose_critter): core, head, tail, legL/legR (front legs or arms),
## backL/backR (back legs), optional wingL/wingR, "floaty" (ghost / psychic float),
## "biped" (stands upright: legL/legR are arms), "glow" (parts that pulse), "orbit" (aura).

const GOLD := Color("ffd23a")
const WHITE := Color("ffffff")
const DARK := Color("2b2340")


static func pt(parent: Node3D, mesh: Mesh, pos: Vector3, c: Color, scl := Vector3.ONE, rot := Vector3.ZERO, outline := true, glow := 0.0) -> MeshInstance3D:
	return Toys.part(parent, mesh, pos, c, scl, rot, outline, glow)


## A part that glows and pulses (eyes, flames, crystals).
static func gl(p: Dictionary, parent: Node3D, mesh: Mesh, pos: Vector3, c: Color, scl := Vector3.ONE, rot := Vector3.ZERO, glow := 1.2) -> MeshInstance3D:
	var mi := pt(parent, mesh, pos, c, scl, rot, false, glow)
	mi.set_meta("base", scl)
	p["glow"].append(mi)
	return mi


static func spike(parent: Node3D, pos: Vector3, h: float, r: float, c: Color, rot := Vector3.ZERO, seg := 6) -> MeshInstance3D:
	return pt(parent, Toys.cyl(0.0, r, h, seg), pos, c, Vector3.ONE, rot)


static func wing(p: Dictionary, core: Node3D, at: Vector3, span: float, c: Color, membrane: Color, bat := false) -> void:
	for sx in [-1, 1]:
		var wp := Toys.piv(core, Vector3(sx * at.x, at.y, at.z))
		p["wingL" if sx < 0 else "wingR"] = wp
		var tilt := Toys.piv(wp, Vector3.ZERO)
		tilt.rotation = Vector3(0, 0, -sx * 0.35)   # lean out from the body a little
		if bat:   # dragon-style: an arm bone sweeping up and back, with webbed sails between fingers
			pt(tilt, Toys.cyl(0.028, 0.04, span * 0.9, 6), Vector3(0, span * 0.38, -span * 0.12), c, Vector3.ONE, Vector3(-0.35, 0, 0))
			for k in 3:
				var fz := -span * (0.12 + k * 0.28)
				pt(tilt, Toys.sph(span * 0.42), Vector3(0, span * (0.42 - k * 0.12), fz), membrane, Vector3(0.1, 1.0, 0.62), Vector3(-0.5 - k * 0.25, 0, 0))
				Toys.part(tilt, Toys.cyl(0.0, 0.035, 0.12, 4), Vector3(0, span * (0.8 - k * 0.15), fz - 0.05), c, Vector3.ONE, Vector3(-0.6 - k * 0.3, 0, 0))
		else:   # feathered: layered feathers, folded back along the body (they lift when flapping)
			for k in 4:
				var len := span * (1.0 - k * 0.16)
				pt(tilt, Toys.sph(len * 0.5), Vector3(0, len * 0.18 - k * 0.04, -len * 0.38 + k * 0.03), [c, membrane, c.darkened(0.15), membrane.darkened(0.1)][k],
					Vector3(0.16, 0.55, 1.0), Vector3(-0.45 + k * 0.12, 0, 0))


## An aura: little glowing bits that circle the partner (spun by the pose).
static func aura(p: Dictionary, b: Node3D, y: float, r: float, c: Color, n := 5, size := 0.05) -> void:
	var o := Toys.piv(b, Vector3(0, y, 0))
	p["orbit"].append(o)
	for k in n:
		var a := k * TAU / n
		gl(p, o, Toys.sph(size), Vector3(cos(a) * r, sin(a * 2.0) * 0.08, sin(a) * r), c, Vector3.ONE, Vector3.ZERO, 1.6)


## The front/back legs of a four-legged partner.
static func quad_legs(p: Dictionary, inner: Node3D, c: Color, r := 0.085, len := 0.24, spread := 0.17, depth := 0.2, claw := Color.TRANSPARENT) -> void:
	for leg in [["legL", -1, 1], ["legR", 1, 1], ["backL", -1, -1], ["backR", 1, -1]]:
		var lp := Toys.piv(inner, Vector3(leg[1] * spread, 0.26, leg[2] * depth))
		pt(lp, Toys.cap(r, len), Vector3(0, -0.13, 0), c)
		if claw.a > 0:
			for k in 3:
				pt(lp, Toys.cyl(0.0, 0.018, 0.06, 4), Vector3((k - 1) * r * 0.55, -0.25, r * 0.9), claw, Vector3.ONE, Vector3(PI / 2, 0, 0), false)
		p[leg[0]] = lp


## A two-legged stance: thick legs (backL/backR) and arms (legL/legR) on the shoulders.
static func biped_limbs(p: Dictionary, inner: Node3D, c: Color, hip_y: float, leg_r: float, leg_len: float, sh: Vector3, arm_r: float, arm_len: float, claw := Color.TRANSPARENT, fist := false) -> void:
	p["biped"] = true
	for s in [-1, 1]:
		var lp := Toys.piv(inner, Vector3(s * (sh.x * 0.6), hip_y, -0.02))
		pt(lp, Toys.cap(leg_r, leg_len), Vector3(0, -leg_len * 0.45, 0), c)
		pt(lp, Toys.sph(leg_r * 1.15), Vector3(0, -leg_len * 0.9, leg_r * 0.6), c.darkened(0.12), Vector3(1.0, 0.55, 1.5))
		p["backL" if s < 0 else "backR"] = lp
		var ap := Toys.piv(inner, Vector3(s * sh.x, sh.y, sh.z))
		pt(ap, Toys.cap(arm_r, arm_len), Vector3(0, -arm_len * 0.42, arm_r * 0.4), c)
		if fist:
			pt(ap, Toys.sph(arm_r * 1.6), Vector3(0, -arm_len * 0.85, arm_r * 0.6), c.darkened(0.1))
		if claw.a > 0:
			for k in 3:
				pt(ap, Toys.cyl(0.0, 0.018, 0.07, 4), Vector3((k - 1) * arm_r * 0.6, -arm_len * 0.9, arm_r * 0.9), claw, Vector3.ONE, Vector3(PI / 2 + 0.3, 0, 0), false)
		p["legL" if s < 0 else "legR"] = ap


static func base(b: Node3D, p: Dictionary, scale: float, core_y: float) -> Array:
	p["glow"] = []
	p["orbit"] = []
	var inner := Toys.piv(b, Vector3.ZERO)
	inner.scale = Vector3.ONE * scale
	p["inner"] = inner
	var core := Toys.piv(inner, Vector3(0, core_y, 0))
	p["core"] = core
	return [inner, core]


static func head_at(p: Dictionary, inner: Node3D, pos: Vector3) -> Node3D:
	var head := Toys.piv(inner, pos)
	p["head"] = head
	p["head_z"] = pos.z
	return head


static func tail_at(p: Dictionary, core: Node3D, pos: Vector3) -> Node3D:
	var t := Toys.piv(core, pos)
	p["tail"] = t
	return t


## Glowing eyes for final forms (a coloured shine ring round the pupil).
static func eye_glow(p: Dictionary, head: Node3D, y: float, z: float, gap: float, c: Color) -> void:
	for s in [-1, 1]:
		gl(p, head, Toys.sph(0.03), Vector3(s * gap + s * 0.045, y + 0.06, z + 0.01), c, Vector3(1.4, 0.5, 0.5), Vector3(0, 0, s * 0.4), 1.4)


# ------------------------------------------------------------------ the lines
static func build(b: Node3D, p: Dictionary, line: String, stage: int, mega := false) -> void:
	match line:
		"water":
			_water(b, p, stage)
		"grass":
			_grass(b, p, stage)
		"fire":
			_fire(b, p, stage)
		"rock":
			_rock(b, p, stage)
		"ghost":
			_ghost(b, p, stage)
		"bird":
			_bird(b, p, stage)
		"mind":
			_mind(b, p, stage)
		"ice":
			_ice(b, p, stage)
		"bug":
			_bug(b, p, stage)
		"dragon":
			_dragon(b, p, stage)
		"fairy":
			_fairy(b, p, stage)
	if mega:
		_mega_extras(b, p, line)


## SPLASHY / TIDAL / HYDRO: a turtle that grows a fortress of a shell with water cannons.
static func _water(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["7fcfff", "4fa8f0", "2f6fd0"][st])
	var SH := Color(["2fb5a8", "1f8f9c", "1d4f8a"][st])
	var BL := Color("e6f6ff")
	var ib: Array = base(b, p, [1.0, 1.2, 1.42][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	pt(core, Toys.sph(0.3), Vector3(0, -0.04, 0), C, Vector3(1.0, 0.75, 1.25))
	pt(core, Toys.sph(0.24), Vector3(0, -0.13, 0.04), Color("f2e6b8"), Vector3(1.0, 0.45, 1.25), Vector3.ZERO, false)
	# shell: a dome with plates
	pt(core, Toys.hemi(0.36), Vector3(0, 0.0, -0.02), SH, Vector3(1.05, [0.85, 1.0, 1.1][st], 1.25))
	var plates: int = [3, 5, 7][st]
	for k in plates:
		var a := k * TAU / plates
		pt(core, Toys.cyl(0.08, 0.08, 0.03, 6), Vector3(cos(a) * 0.18, 0.24 + (0.04 if st > 0 else 0.0), sin(a) * 0.22 - 0.02), SH.lightened(0.25), Vector3.ONE, Vector3(0.3 * sin(a), 0, -0.3 * cos(a)), false)
	pt(core, Toys.cyl(0.38, 0.38, 0.04, 20), Vector3(0, -0.02, -0.02), SH.darkened(0.25), Vector3(1.0, 1.0, 1.2))   # shell rim
	if st >= 1:   # spikes round the rim
		for k in 8:
			var a := k * TAU / 8 + 0.2
			spike(core, Vector3(cos(a) * 0.39, 0.0, sin(a) * 0.46 - 0.02), 0.12, 0.04, BL, Vector3(sin(a) * 1.4, 0, -cos(a) * 1.4))
	if st == 2:   # twin water cannons that glow
		for sx in [-1, 1]:
			var cn := Toys.piv(core, Vector3(sx * 0.16, 0.36, -0.02))
			pt(cn, Toys.cyl(0.07, 0.085, 0.3, 12), Vector3(0, 0.0, 0.08), Color("9aa6b2"), Vector3.ONE, Vector3(-1.1, 0, 0))
			pt(cn, Toys.cyl(0.095, 0.095, 0.05, 12), Vector3(0, 0.1, 0.2), Color("6a7480"), Vector3.ONE, Vector3(-1.1, 0, 0))
			gl(p, cn, Toys.sph(0.06), Vector3(0, 0.13, 0.23), Color("7fe4ff"), Vector3(1, 0.4, 1))
		aura(p, b, 0.7, 0.62, Color("7fe4ff"), 6)
	quad_legs(p, inner, C, 0.09 + st * 0.01, 0.24, 0.2, 0.22, BL if st > 0 else Color.TRANSPARENT)
	var tail := tail_at(p, core, Vector3(0, -0.05, -0.42))
	if st == 0:
		pt(tail, Toys.cyl(0.0, 0.07, 0.2, 8), Vector3(0, 0, -0.08), C, Vector3.ONE, Vector3(-PI / 2 - 0.2, 0, 0))
	else:   # a curly fin tail
		for k in 3:
			pt(tail, Toys.sph(0.09 + st * 0.02), Vector3(0, 0.05 + k * 0.07, -0.06 - k * 0.04), [C, C.lightened(0.3), WHITE][k], Vector3(0.5, 1.0, 1.4), Vector3(-0.4 - k * 0.3, 0, 0))
	var head := head_at(p, inner, Vector3(0, 0.5 + st * 0.02, 0.4))
	pt(head, Toys.sph(0.24), Vector3.ZERO, C, Vector3(1.0, 0.92, 1.05))
	pt(head, Toys.sph(0.13), Vector3(0, -0.1, 0.16), BL, Vector3(1.3, 0.7, 0.8), Vector3.ZERO, false)
	Toys.eyes(head, 0.05, 0.18, 0.1, 0.07, st == 2)
	match st:
		0:
			pt(head, Toys.cyl(0.0, 0.06, 0.16, 10), Vector3(0, 0.28, 0), Color("5ac8ff"))
		1:   # swept-back ear fins
			for sx in [-1, 1]:
				pt(head, Toys.sph(0.11), Vector3(sx * 0.2, 0.12, -0.08), BL, Vector3(0.3, 0.9, 1.2), Vector3(0.5, 0, sx * 0.5))
		2:   # a crest helmet
			pt(head, Toys.hemi(0.22), Vector3(0, 0.1, -0.03), SH, Vector3(1.1, 0.9, 1.15))
			spike(head, Vector3(0, 0.3, -0.02), 0.18, 0.06, BL, Vector3(-0.5, 0, 0))
			eye_glow(p, head, 0.05, 0.19, 0.1, Color("7fe4ff"))


## SEEDY / SPROUT / BLOOM: a little plant dino whose back bursts into a giant glowing flower.
static func _grass(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["8fdc6a", "5cc05a", "3a9d5d"][st])
	var BL := Color("e6f5b0")
	var LEAF := Color(["4fb35a", "3f9d4a", "2f8a40"][st])
	var ib: Array = base(b, p, [1.0, 1.2, 1.45][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	pt(core, Toys.sph(0.3), Vector3.ZERO, C, Vector3(1.0, 0.8, 1.3))
	pt(core, Toys.sph(0.22), Vector3(0, -0.1, 0.06), BL, Vector3(1.0, 0.6, 1.3), Vector3.ZERO, false)
	for sp in [Vector3(-0.17, 0.13, 0.05), Vector3(0.18, 0.1, -0.12), Vector3(0.05, 0.22, 0.18)]:
		pt(core, Toys.sph(0.05), sp, LEAF.darkened(0.2), Vector3(1, 0.45, 1), Vector3.ZERO, false)
	match st:
		0:   # a seed bulb
			pt(core, Toys.sph(0.17), Vector3(0, 0.26, -0.05), Color("7cc957"), Vector3(1, 1.1, 1))
			for k in 3:
				_leaf(core, Vector3((k - 1) * 0.08, 0.38, -0.05), 0.08, (k - 1) * 0.7, LEAF)
		1:   # a bud and a leaf frill
			pt(core, Toys.sph(0.17), Vector3(0, 0.3, -0.06), Color("ff8fc0"), Vector3(0.9, 1.3, 0.9))
			for k in 6:
				var a := k * TAU / 6
				_leaf(core, Vector3(cos(a) * 0.14, 0.22, -0.06 + sin(a) * 0.14), 0.14, cos(a) * 0.9, LEAF)
		2:   # a huge flower with a glowing middle, and a cape of vines
			var fl := Toys.piv(core, Vector3(0, 0.36, -0.08))
			p["flower"] = fl
			pt(fl, Toys.cyl(0.05, 0.06, 0.22, 8), Vector3(0, -0.06, 0), LEAF)
			for k in 8:
				var a := k * TAU / 8
				pt(fl, Toys.sph(0.18), Vector3(cos(a) * 0.26, 0.08, sin(a) * 0.26), Color("ff6fae") if k % 2 == 0 else Color("ff9fd0"), Vector3(1.0, 0.25, 0.55), Vector3(0, -a, 0.25))
			gl(p, fl, Toys.sph(0.12), Vector3(0, 0.1, 0), Color("ffe36a"), Vector3(1, 0.6, 1), Vector3.ZERO, 1.4)
			for k in 5:   # vine cape
				var a := PI * (0.6 + k * 0.2)
				pt(core, Toys.cap(0.03, 0.42), Vector3(cos(a) * 0.27, 0.0, -0.1 + sin(a) * -0.1), LEAF, Vector3.ONE, Vector3(0.5, 0, cos(a) * 0.5))
				_leaf(core, Vector3(cos(a) * 0.3, -0.2, -0.18), 0.07, 0.0, LEAF.lightened(0.15))
			aura(p, b, 0.9, 0.7, Color("fff38a"), 6, 0.04)
	quad_legs(p, inner, C, 0.09, 0.25, 0.17, 0.2)
	var tail := tail_at(p, core, Vector3(0, 0.0, -0.36))
	pt(tail, Toys.cyl(0.0, 0.08, 0.24 + st * 0.06, 8), Vector3(0, 0, -0.1), C, Vector3.ONE, Vector3(-PI / 2 - 0.3, 0, 0))
	if st >= 1:
		_leaf(tail, Vector3(0, 0.05, -0.28 - st * 0.04), 0.09 + st * 0.02, 0.0, LEAF)
	var head := head_at(p, inner, Vector3(0, 0.6, 0.34))
	pt(head, Toys.sph(0.24), Vector3.ZERO, C, Vector3(1.05, 0.95, 1.0))
	pt(head, Toys.sph(0.13), Vector3(0, -0.08, 0.17), BL, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)
	Toys.eyes(head, 0.04, 0.19, 0.1, 0.07, st == 2)
	match st:
		0:
			pt(head, Toys.cyl(0.012, 0.012, 0.12, 5), Vector3(0, 0.28, 0), Color("2f7a3a"), Vector3.ONE, Vector3.ZERO, false)
			_leaf(head, Vector3(0, 0.34, 0), 0.12, 0.6, LEAF)
		1:
			_leaf(head, Vector3(-0.06, 0.22, -0.02), 0.13, -0.7, LEAF)
			_leaf(head, Vector3(0.06, 0.22, -0.02), 0.13, 0.7, LEAF)
		2:   # a leafy crown
			for k in 5:
				_leaf(head, Vector3((k - 2) * 0.07, 0.2, -0.04), 0.12, (k - 2) * 0.45, LEAF)
			eye_glow(p, head, 0.04, 0.2, 0.1, Color("c8ff6a"))


## EMBER / SCORCH / BLAZE: a fire lizard that stands up and grows flaming wings.
static func _fire(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["ff8a4a", "f0603a", "c8372e"][st])
	var BL := Color("ffd9a0")
	if st == 0:
		var ib: Array = base(b, p, 1.0, 0.36)
		var inner: Node3D = ib[0]
		var core: Node3D = ib[1]
		pt(core, Toys.sph(0.3), Vector3.ZERO, C, Vector3(1.0, 0.82, 1.3))
		pt(core, Toys.sph(0.22), Vector3(0, -0.1, 0.06), BL, Vector3(1.0, 0.6, 1.3), Vector3.ZERO, false)
		quad_legs(p, inner, C)
		var tail := tail_at(p, core, Vector3(0, 0.0, -0.36))
		pt(tail, Toys.cyl(0.03, 0.08, 0.4, 8), Vector3(0, 0.12, -0.12), C, Vector3.ONE, Vector3(-0.9, 0, 0))
		_flame(p, tail, Vector3(0, 0.28, -0.28), 0.16)
		var head := head_at(p, inner, Vector3(0, 0.6, 0.34))
		pt(head, Toys.sph(0.24), Vector3.ZERO, C)
		pt(head, Toys.sph(0.13), Vector3(0, -0.08, 0.17), BL, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)
		Toys.eyes(head, 0.04, 0.19, 0.1, 0.07)
		_flame(p, head, Vector3(0, 0.18, -0.04), 0.12)
		return
	# standing up: a lizard warrior (stage 1) and a winged fire dragon (stage 2)
	var ib2: Array = base(b, p, [1.0, 1.05, 1.18][st], 0.64)
	var inner2: Node3D = ib2[0]
	var core2: Node3D = ib2[1]
	pt(core2, Toys.sph(0.26), Vector3.ZERO, C, Vector3(1.0, 1.25, 0.9))
	pt(core2, Toys.sph(0.2), Vector3(0, -0.04, 0.1), BL, Vector3(0.95, 1.2, 0.6), Vector3.ZERO, false)
	for k in 3:   # belly stripes
		pt(core2, Toys.box(Vector3(0.2 - k * 0.03, 0.025, 0.02)), Vector3(0, 0.12 - k * 0.1, 0.22), C.darkened(0.2), Vector3.ONE, Vector3.ZERO, false)
	biped_limbs(p, inner2, C, 0.42, 0.1, 0.34, Vector3(0.27, 0.8, 0.05), 0.06, 0.26, BL)
	var tail2 := tail_at(p, core2, Vector3(0, -0.22, -0.2))
	pt(tail2, Toys.cyl(0.03, 0.09, 0.5, 8), Vector3(0, -0.04, -0.2), C, Vector3.ONE, Vector3(-1.3, 0, 0))
	_flame(p, tail2, Vector3(0, 0.0, -0.46), 0.2 + st * 0.06)
	for k in 2 + st * 2:   # back flames
		_flame(p, core2, Vector3(0, 0.22 - k * 0.1, -0.2), 0.1 + st * 0.03)
	if st == 2:
		wing(p, core2, Vector3(0.12, 0.2, -0.16), 0.55, Color("8a2a24"), Color("ff9a3c"), true)
		aura(p, b, 0.95, 0.65, Color("ffb02e"), 6)
	var head2 := head_at(p, inner2, Vector3(0, 1.08, 0.08))
	pt(head2, Toys.sph(0.22), Vector3.ZERO, C, Vector3(1.0, 0.95, 1.1))
	pt(head2, Toys.sph(0.15), Vector3(0, -0.07, 0.17), C, Vector3(1.0, 0.7, 1.2))   # snout
	pt(head2, Toys.sph(0.1), Vector3(0, -0.12, 0.2), BL, Vector3(1.2, 0.5, 1.0), Vector3.ZERO, false)
	Toys.eyes(head2, 0.06, 0.18, 0.09, 0.065, true)
	for sx in [-1, 1]:   # horns
		spike(head2, Vector3(sx * 0.12, 0.18, -0.08), 0.18 + st * 0.06, 0.045, Color("fff2d0"), Vector3(-0.7, 0, -sx * 0.35))
	if st == 2:
		for k in 5:
			_flame(p, head2, Vector3((k - 2) * 0.06, 0.18, -0.12), 0.12)
		eye_glow(p, head2, 0.06, 0.19, 0.09, Color("ffe36a"))


## The newer lines -------------------------------------------------------------------------

## PEBBLIT / BOULDRO / TERRADON: a rock armadillo that becomes a crystal golem.
static func _rock(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["b59a74", "9a7b5a", "6f6560"][st])
	var AC := Color(["6b5a48", "5a4a3a", "3f3a3a"][st])
	var CRY := Color("7fe8ff")
	if st < 2:
		var ib: Array = base(b, p, [1.0, 1.22][st], 0.36)
		var inner: Node3D = ib[0]
		var core: Node3D = ib[1]
		pt(core, Toys.sph(0.3), Vector3.ZERO, C, Vector3(1.0, 0.82, 1.3))
		for k in 3 + st * 2:   # banded armour
			var n := 3 + st * 2
			pt(core, Toys.hemi(0.31 - absf(k - (n - 1) * 0.5) * 0.03), Vector3(0, 0.02, -0.22 + k * 0.44 / (n - 1)), AC, Vector3(1.1, 0.95, 0.36))
		if st == 1:
			for k in 5:
				var cr := gl(p, core, Toys.cyl(0.0, 0.05, 0.18, 5), Vector3(((k % 2) * 2 - 1) * 0.1, 0.32, -0.2 + k * 0.1), CRY, Vector3.ONE, Vector3(0, 0, ((k % 2) * 2 - 1) * -0.3), 1.0)
		quad_legs(p, inner, C, 0.09, 0.22, 0.18, 0.2, Color("e8d8b8"))
		var tail := tail_at(p, core, Vector3(0, 0.0, -0.38))
		pt(tail, Toys.cyl(0.03, 0.09, 0.3, 8), Vector3(0, 0, -0.08), AC, Vector3.ONE, Vector3(-PI / 2 - 0.4, 0, 0))
		if st == 1:
			pt(tail, Toys.sph(0.09), Vector3(0, -0.04, -0.26), AC.darkened(0.2))   # a club tail
		var head := head_at(p, inner, Vector3(0, 0.55, 0.38))
		pt(head, Toys.sph(0.22), Vector3.ZERO, C)
		pt(head, Toys.sph(0.09), Vector3(0, -0.05, 0.2), AC, Vector3(1, 0.8, 1.2))
		Toys.eyes(head, 0.05, 0.18, 0.1, 0.065)
		pt(head, Toys.hemi(0.2), Vector3(0, 0.1, -0.04), AC, Vector3(1.1, 0.75, 1.1))
		for sx in [-1, 1]:
			pt(head, Toys.sph(0.07), Vector3(sx * 0.15, 0.18, -0.04), C.darkened(0.15), Vector3(0.7, 1.2, 0.5))
		return
	# TERRADON: a big stone golem with glowing crystals growing out of it
	var ib2: Array = base(b, p, 1.12, 0.66)
	var inner2: Node3D = ib2[0]
	var core2: Node3D = ib2[1]
	pt(core2, Toys.box(Vector3(0.62, 0.5, 0.42)), Vector3.ZERO, C, Vector3.ONE, Vector3(0.05, 0, 0))
	pt(core2, Toys.box(Vector3(0.4, 0.3, 0.06)), Vector3(0, -0.02, 0.21), AC, Vector3.ONE, Vector3.ZERO, false)
	gl(p, core2, Toys.sph(0.07), Vector3(0, 0.02, 0.25), CRY, Vector3(1, 1, 0.5))   # heart crystal
	for k in 6:
		var x := (k % 3 - 1) * 0.2
		gl(p, core2, Toys.cyl(0.0, 0.07, 0.3 + (k % 2) * 0.1, 5), Vector3(x, 0.28, -0.08 - (k / 3) * 0.1), CRY, Vector3.ONE, Vector3(-0.3, 0, -x * 1.5), 1.1)
	biped_limbs(p, inner2, C, 0.4, 0.13, 0.32, Vector3(0.42, 0.86, 0.02), 0.11, 0.42, Color.TRANSPARENT, true)
	for s in ["legL", "legR"]:   # crystal shoulders
		gl(p, p[s], Toys.cyl(0.0, 0.06, 0.2, 5), Vector3(0, 0.08, -0.02), CRY, Vector3.ONE, Vector3.ZERO, 1.1)
	var tail2 := tail_at(p, core2, Vector3(0, -0.2, -0.2))
	pt(tail2, Toys.box(Vector3(0.12, 0.12, 0.3)), Vector3(0, -0.06, -0.12), AC, Vector3.ONE, Vector3(0.4, 0, 0))
	var head2 := head_at(p, inner2, Vector3(0, 1.05, 0.06))
	pt(head2, Toys.box(Vector3(0.36, 0.3, 0.32)), Vector3.ZERO, C)
	pt(head2, Toys.box(Vector3(0.38, 0.08, 0.34)), Vector3(0, 0.13, 0), AC)
	Toys.eyes(head2, 0.0, 0.17, 0.09, 0.06, true)
	eye_glow(p, head2, 0.0, 0.18, 0.09, CRY)
	gl(p, head2, Toys.cyl(0.0, 0.06, 0.24, 5), Vector3(0, 0.25, -0.02), CRY, Vector3.ONE, Vector3(-0.2, 0, 0), 1.2)
	aura(p, b, 0.5, 0.75, Color("b98a5a"), 7, 0.06)


## BOOLET / SPOOKLE / PHANTOMA: a sheet ghost that becomes a crowned phantom with a torn cape.
static func _ghost(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["eeeaff", "c8b8f5", "8a6fd6"][st])
	var AC := Color("5a3f9e")
	var GLW := Color(["c9a8ff", "b98cff", "9f6bff"][st])
	var ib: Array = base(b, p, [1.0, 1.2, 1.4][st], 0.5)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	p["floaty"] = true
	pt(core, Toys.sph(0.3), Vector3(0, 0.1, 0), C)
	pt(core, Toys.cyl(0.3, 0.34 + st * 0.03, 0.3, 18), Vector3(0, -0.1, 0), C)
	var n := 8 + st * 2
	for k in n:   # the ragged bottom of the sheet
		var a := k * TAU / n
		pt(core, Toys.cyl(0.07, 0.0, 0.14 + (k % 2) * st * 0.08, 6), Vector3(cos(a) * (0.27 + st * 0.03), -0.31 - (k % 2) * st * 0.04, sin(a) * (0.27 + st * 0.03)), C)
	for sx in [-1, 1]:   # floaty hands
		var hp := Toys.piv(core, Vector3(sx * 0.34, 0.0, 0.08))
		pt(hp, Toys.sph(0.08 + st * 0.015), Vector3.ZERO, C, Vector3(0.8, 1.2, 0.8))
		if st == 2:
			gl(p, hp, Toys.sph(0.05), Vector3(0, 0.0, 0.08), GLW)
	quad_legs(p, inner, C, 0.0, 0.0)   # invisible stand-ins (ghosts have no legs)
	for k in ["legL", "legR", "backL", "backR"]:
		for c in p[k].get_children():
			c.queue_free()
	var tail := tail_at(p, core, Vector3(0, -0.15, -0.28))
	if st >= 1:
		pt(tail, Toys.cyl(0.0, 0.09, 0.36, 8), Vector3(0, -0.12, -0.06), C, Vector3.ONE, Vector3(-2.3, 0, 0))
	if st == 2:   # torn purple cape and wisp flames
		for k in 5:
			var x := (k - 2) * 0.12
			pt(core, Toys.box(Vector3(0.11, 0.6 - absf(k - 2) * 0.08, 0.02)), Vector3(x, -0.02, -0.33), AC, Vector3.ONE, Vector3(0.15, 0, x * 0.4))
		_flame_col(p, tail, Vector3(0, -0.28, -0.22), 0.2, GLW)
		aura(p, b, 0.85, 0.6, GLW, 6)
	var head := head_at(p, inner, Vector3(0, 0.82, 0.14))
	if st == 0:
		Toys.eyes(head, -0.02, 0.26, 0.1, 0.08)
	else:   # hollow spooky eyes that glow
		for sx in [-1, 1]:
			pt(head, Toys.sph(0.075), Vector3(sx * 0.1, -0.01, 0.25), DARK, Vector3(1, 1.3, 0.4), Vector3.ZERO, false)
			gl(p, head, Toys.sph(0.03), Vector3(sx * 0.1, -0.01, 0.28), GLW)
	pt(head, Toys.box(Vector3(0.14, 0.035, 0.02)), Vector3(0, -0.14, 0.28), AC, Vector3.ONE, Vector3.ZERO, false)
	if st >= 1:   # spiky top
		for k in 3:
			spike(head, Vector3((k - 1) * 0.11, 0.2, -0.02), 0.16 + (k == 1 as int) * 0.06, 0.05, C.lightened(0.15), Vector3(0, 0, (1 - k) * 0.4))
	if st == 2:   # a ghostly crown
		pt(head, Toys.cyl(0.13, 0.15, 0.08, 8), Vector3(0, 0.33, 0.0), GOLD)
		for k in 5:
			var a := k * TAU / 5
			spike(head, Vector3(cos(a) * 0.12, 0.42, sin(a) * 0.12), 0.1, 0.03, GOLD)
		gl(p, head, Toys.sph(0.035), Vector3(0, 0.34, 0.15), Color("ff4f8a"))


## PIPWING / SWOOPER / SKYHAWK: a round chick, a sleek swallow, a golden-crested hawk.
static func _bird(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["8cc8f0", "4fa8e8", "2f6fc8"][st])
	var BL := Color("fff4d6")
	var AC := Color(["ffb02e", "ffb02e", "ffcf33"][st])
	var ib: Array = base(b, p, [1.0, 1.2, 1.45][st], 0.4)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	if st == 0:
		pt(core, Toys.sph(0.28), Vector3(0, 0.02, 0), C, Vector3(1.0, 0.98, 1.05))
		pt(core, Toys.sph(0.2), Vector3(0, -0.05, 0.12), BL, Vector3(1.0, 0.85, 0.8), Vector3.ZERO, false)
	else:
		pt(core, Toys.sph(0.26), Vector3(0, 0.02, 0), C, Vector3(0.9, 0.95, 1.35), Vector3(-0.25, 0, 0))
		pt(core, Toys.sph(0.18), Vector3(0, -0.06, 0.16), BL, Vector3(0.95, 0.9, 0.8), Vector3.ZERO, false)
	wing(p, core, Vector3(0.2, 0.1, -0.02), [0.32, 0.5, 0.72][st], C, C.lightened(0.35) if st < 2 else AC)
	for leg in [["legL", -1], ["legR", 1]]:
		var lp := Toys.piv(inner, Vector3(leg[1] * 0.1, 0.26, 0.02))
		pt(lp, Toys.cyl(0.025, 0.025, 0.2, 6), Vector3(0, -0.12, 0), AC)
		for k in 3:
			pt(lp, Toys.cyl(0.0, 0.02, 0.08, 4), Vector3((k - 1) * 0.03, -0.22, 0.04), AC, Vector3.ONE, Vector3(PI / 2, 0, (k - 1) * 0.4), false)
		p[leg[0]] = lp
	for k in ["backL", "backR"]:
		p[k] = Toys.piv(inner, Vector3.ZERO)
	var tail := tail_at(p, core, Vector3(0, 0.0, -0.3))
	var nt := 3 + st * 2
	for k in nt:   # tail feathers fan out
		var a := (k - (nt - 1) * 0.5) * 0.28
		pt(tail, Toys.sph(0.08 + st * 0.02), Vector3(sin(a) * 0.05, 0.03, -0.08 - st * 0.05), [C, AC, C.darkened(0.2)][k % 3], Vector3(0.45, 0.2, 1.8 + st * 0.6), Vector3(0.25, a, 0))
	var head := head_at(p, inner, Vector3(0, 0.72 + st * 0.03, 0.22 + st * 0.04))
	pt(head, Toys.sph(0.2), Vector3.ZERO, C)
	pt(head, Toys.sph(0.11), Vector3(0, -0.06, 0.12), BL, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)
	Toys.eyes(head, 0.04, 0.16, 0.09, 0.06, st == 2)
	pt(head, Toys.cyl(0.0, 0.06, 0.14 + st * 0.03, 6), Vector3(0, -0.02, 0.22), AC, Vector3.ONE, Vector3(PI / 2 + (0.3 if st == 2 else 0.0), 0, 0))
	for k in 1 + st * 2:   # crest feathers
		pt(head, Toys.sph(0.06 + st * 0.01), Vector3(0, 0.18 + k * 0.02, -0.04 - k * 0.05), [AC, Color("e8413b"), GOLD][k % 3], Vector3(0.35, 1.3, 0.7), Vector3(-0.55 - k * 0.12, 0, 0))
	if st == 2:
		for sx in [-1, 1]:   # war-paint markings that glow
			gl(p, head, Toys.box(Vector3(0.1, 0.02, 0.02)), Vector3(sx * 0.13, -0.02, 0.13), Color("7fe4ff"), Vector3.ONE, Vector3(0, -sx * 0.6, sx * 0.3))
		aura(p, b, 0.9, 0.7, Color("bff4ff"), 6)


## MYSTIKIT / FOXSEER / ASTRAFOX: a fox kit with a gem that becomes a floating star fox.
static func _mind(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["f7b0d8", "e070c0", "9a5ad8"][st])
	var BL := Color("fff0f8")
	var GEM := Color(["7a3fd0", "ff4fd8", "7fe4ff"][st])
	var ib: Array = base(b, p, [1.0, 1.2, 1.38][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	if st == 2:
		p["floaty"] = true
		core.position.y = 0.5
	pt(core, Toys.sph(0.28), Vector3.ZERO, C, Vector3(0.95, 0.85, 1.3))
	pt(core, Toys.sph(0.2), Vector3(0, -0.08, 0.08), BL, Vector3(1.0, 0.6, 1.2), Vector3.ZERO, false)
	if st >= 1:   # a fluffy collar
		for k in 7:
			var a := k * TAU / 7
			pt(core, Toys.sph(0.075), Vector3(cos(a) * 0.2, 0.16, 0.18 + sin(a) * 0.05), BL, Vector3.ONE, Vector3.ZERO, false)
		gl(p, core, Toys.sph(0.06), Vector3(0, 0.12, 0.27), GEM, Vector3(1, 1.3, 0.6))
	quad_legs(p, inner, C, 0.075, 0.24, 0.15, 0.19)
	if st == 2:   # floats: little cloud paws
		for k in ["legL", "legR", "backL", "backR"]:
			for c in p[k].get_children():
				c.queue_free()
			pt(p[k], Toys.sph(0.07), Vector3(0, -0.02, 0), BL, Vector3(1, 0.7, 1))
	var tail := tail_at(p, core, Vector3(0, 0.05, -0.32))
	var nt: int = [1, 3, 5][st]
	for k in nt:   # one, three, then five fluffy tails
		var a := (k - (nt - 1) * 0.5) * 0.45
		var tp := Toys.piv(tail, Vector3.ZERO)
		tp.rotation = Vector3(-0.8 - absf(a) * 0.3, a, 0)
		pt(tp, Toys.sph(0.12), Vector3(0, 0, -0.18), C, Vector3(0.75, 0.75, 2.0))
		pt(tp, Toys.sph(0.08), Vector3(0, 0, -0.38), BL, Vector3.ONE, Vector3.ZERO, false)
		if st == 2:
			gl(p, tp, Toys.sph(0.04), Vector3(0, 0, -0.45), GEM)
	var head := head_at(p, inner, Vector3(0, 0.58 + (0.14 if st == 2 else 0.0), 0.34))
	pt(head, Toys.sph(0.23), Vector3.ZERO, C)
	pt(head, Toys.sph(0.12), Vector3(0, -0.07, 0.17), BL, Vector3(1.2, 0.75, 0.9), Vector3.ZERO, false)
	pt(head, Toys.cyl(0.0, 0.06, 0.1, 6), Vector3(0, -0.06, 0.28), C, Vector3.ONE, Vector3(PI / 2, 0, 0))
	Toys.eyes(head, 0.04, 0.18, 0.1, 0.07)
	for sx in [-1, 1]:   # tall ears
		var pm := PrismMesh.new()
		pm.size = Vector3(0.16 + st * 0.04, 0.24 + st * 0.08, 0.06)
		pt(head, pm, Vector3(sx * 0.13, 0.27 + st * 0.04, -0.02), C, Vector3.ONE, Vector3(0, 0, -sx * 0.25))
		pt(head, pm, Vector3(sx * 0.13, 0.25 + st * 0.04, 0.02), BL, Vector3(0.6, 0.6, 0.5), Vector3(0, 0, -sx * 0.25), false)
	gl(p, head, Toys.sph(0.05), Vector3(0, 0.15, 0.2), GEM, Vector3(1, 1.2, 0.6))
	if st == 2:   # a halo of floating crystals
		var halo := Toys.piv(head, Vector3(0, 0.32, -0.05))
		p["orbit"].append(halo)
		for k in 6:
			var a := k * TAU / 6
			gl(p, halo, Toys.cyl(0.0, 0.035, 0.12, 4), Vector3(cos(a) * 0.24, 0, sin(a) * 0.24), Color("bff4ff"), Vector3.ONE, Vector3.ZERO, 1.3)
		eye_glow(p, head, 0.04, 0.19, 0.1, Color("bff4ff"))
		aura(p, b, 0.5, 0.65, Color("ff9fe8"), 5)


## SNOWPUP / FROSTEAL / BLIZZARC: a seal pup that becomes a crystal-crowned walrus.
static func _ice(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["e6f6fc", "a8e2f4", "6fb8e0"][st])
	var BL := Color("ffffff")
	var CRY := Color("bff4ff")
	var ib: Array = base(b, p, [1.0, 1.22, 1.45][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	pt(core, Toys.sph(0.3), Vector3(0, -0.04, -0.05), C, Vector3(0.95, 0.78 + st * 0.06, 1.5))
	pt(core, Toys.sph(0.22), Vector3(0, -0.12, 0.02), BL, Vector3(1.0, 0.55, 1.4), Vector3.ZERO, false)
	for k in 4:   # spots
		pt(core, Toys.sph(0.04), Vector3(((k % 2) * 2 - 1) * 0.15, 0.13, -0.25 + k * 0.12), C.darkened(0.18), Vector3(1, 0.4, 1), Vector3.ZERO, false)
	if st >= 1:   # ice spikes down the back
		for k in 3 + st * 2:
			var cr := gl(p, core, Toys.cyl(0.0, 0.05 + st * 0.01, 0.16 + st * 0.06 + (k % 2) * 0.05, 5), Vector3(0, 0.2, -0.32 + k * 0.6 / (3 + st * 2)), CRY, Vector3.ONE, Vector3(-0.2, 0, 0), 0.8)
	for leg in [["legL", -1, 1], ["legR", 1, 1], ["backL", -1, -1], ["backR", 1, -1]]:
		var lp := Toys.piv(inner, Vector3(leg[1] * 0.2, 0.22, leg[2] * 0.22))
		pt(lp, Toys.sph(0.08), Vector3(leg[1] * 0.06, -0.15, leg[2] * 0.04), C.darkened(0.1), Vector3(1.3, 0.35, 1.5), Vector3(0, leg[1] * 0.4, 0))   # flippers
		p[leg[0]] = lp
	var tail := tail_at(p, core, Vector3(0, -0.05, -0.48))
	pt(tail, Toys.sph(0.12), Vector3(0, -0.05, -0.06), C.darkened(0.1), Vector3(1.8, 0.35, 0.9))
	if st == 2:
		aura(p, b, 0.45, 0.8, Color("e8faff"), 8, 0.045)   # swirling snow
	var head := head_at(p, inner, Vector3(0, 0.48 + st * 0.03, 0.44))
	pt(head, Toys.sph(0.24), Vector3.ZERO, C)
	pt(head, Toys.sph(0.14), Vector3(0, -0.08, 0.16), BL, Vector3(1.3, 0.8, 0.8), Vector3.ZERO, false)
	pt(head, Toys.sph(0.045), Vector3(0, 0.0, 0.25), DARK, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)   # nose
	Toys.eyes(head, 0.07, 0.17, 0.1, 0.065, st == 2)
	for sx in [-1, 1]:
		for k in 2:   # whiskers
			pt(head, Toys.box(Vector3(0.14, 0.012, 0.012)), Vector3(sx * 0.15, -0.05 - k * 0.04, 0.22), Color("9aa6b2"), Vector3.ONE, Vector3(0, 0, sx * (0.2 - k * 0.3)), false)
	if st >= 1:   # tusks
		for sx in [-1, 1]:
			gl(p, head, Toys.cyl(0.0, 0.03 + st * 0.008, 0.16 + st * 0.08, 6), Vector3(sx * 0.06, -0.22 - st * 0.03, 0.22), CRY, Vector3.ONE, Vector3(PI + 0.2, 0, 0), 0.6)
	if st == 2:   # an ice crown
		for k in 5:
			gl(p, head, Toys.cyl(0.0, 0.05, 0.2 + (2 - absi(k - 2)) * 0.05, 5), Vector3((k - 2) * 0.08, 0.24, -0.02), CRY, Vector3.ONE, Vector3(0, 0, (2 - k) * 0.25), 1.2)
		eye_glow(p, head, 0.07, 0.18, 0.1, CRY)


## BUZZLET / BEETLEBUD / TITANBUG: a beetle that becomes a giant armoured rhino beetle.
static func _bug(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["a6d65c", "5aa83a", "2f7a6a"][st])
	var AC := Color(["3a3a48", "2a2a38", "1f1f2a"][st])
	var SHINE := Color(["c8f08a", "8ae86a", "6affd0"][st])
	var ib: Array = base(b, p, [1.0, 1.2, 1.42][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	pt(core, Toys.sph(0.3), Vector3(0, 0.0, -0.02), AC, Vector3(1.0, 0.75, 1.25))
	pt(core, Toys.hemi(0.33), Vector3(0, 0.02, -0.04), C, Vector3(1.04, 0.85 + st * 0.1, 1.3))   # wing cases
	pt(core, Toys.box(Vector3(0.025, 0.05, 0.62)), Vector3(0, 0.3 + st * 0.03, -0.04), AC, Vector3.ONE, Vector3.ZERO, false)
	for k in 2 + st:   # shiny spots / glowing marks on the shell
		var sx := ((k % 2) * 2 - 1) * 0.16
		if st == 2:
			gl(p, core, Toys.sph(0.05), Vector3(sx, 0.24, -0.15 + (k / 2) * 0.2), SHINE, Vector3(1, 0.4, 1.4), Vector3.ZERO, 1.0)
		else:
			pt(core, Toys.sph(0.05), Vector3(sx, 0.25, -0.12 + (k / 2) * 0.2), SHINE, Vector3(1, 0.4, 1.3), Vector3.ZERO, false)
	if st >= 1:   # see-through wings peeking out
		wing(p, core, Vector3(0.16, 0.2, -0.1), [0.0, 0.32, 0.5][st], Color("cfe8ff"), Color("e8f6ff"))
	if st == 2:   # armour spikes along the sides
		for k in 6:
			var sx := ((k % 2) * 2 - 1)
			spike(core, Vector3(sx * 0.33, 0.04, -0.25 + (k / 2) * 0.2), 0.14, 0.04, AC, Vector3(0, 0, -sx * 1.3))
	# six legs: the pose moves the front and back pair, the middle pair is just there
	quad_legs(p, inner, AC, 0.05, 0.26, 0.2, 0.2)
	for sx in [-1, 1]:
		pt(inner, Toys.cap(0.045, 0.24), Vector3(sx * 0.24, 0.14, 0.0), AC, Vector3.ONE, Vector3(0, 0, sx * 0.4))
	var tail := tail_at(p, core, Vector3(0, -0.02, -0.38))
	pt(tail, Toys.sph(0.08), Vector3(0, 0, -0.03), AC, Vector3(1.2, 0.6, 0.8))
	var head := head_at(p, inner, Vector3(0, 0.5, 0.38))
	pt(head, Toys.sph(0.2), Vector3.ZERO, AC.lightened(0.15))
	Toys.eyes(head, 0.04, 0.16, 0.1, 0.065, st == 2)
	for sx in [-1, 1]:   # antennae
		pt(head, Toys.cyl(0.01, 0.01, 0.24, 5), Vector3(sx * 0.08, 0.24, 0.06), AC, Vector3.ONE, Vector3(0.45, 0, -sx * 0.35), false)
		pt(head, Toys.sph(0.035), Vector3(sx * 0.13, 0.35, 0.12), SHINE if st < 2 else AC)
		if st == 2:
			gl(p, head, Toys.sph(0.03), Vector3(sx * 0.13, 0.36, 0.13), SHINE)
	if st >= 1:   # the horn (a big curved rhino horn on TITANBUG)
		var hl := 0.2 + st * 0.14
		pt(head, Toys.cyl(0.0, 0.06 + st * 0.02, hl, 8), Vector3(0, 0.08 + hl * 0.35, 0.2), AC, Vector3.ONE, Vector3(0.55, 0, 0))
		if st == 2:
			pt(head, Toys.cyl(0.0, 0.04, 0.18, 6), Vector3(0, 0.42, 0.36), AC, Vector3.ONE, Vector3(-0.4, 0, 0))
			for sx in [-1, 1]:   # mandibles
				pt(head, Toys.cyl(0.0, 0.03, 0.14, 5), Vector3(sx * 0.09, -0.12, 0.2), AC, Vector3.ONE, Vector3(PI / 2, 0, sx * 0.5))
			aura(p, b, 0.7, 0.7, SHINE, 6)


## DRAKLET / WYVERNO / DRAGOROAR: a little dragon, a winged wyvern, a towering two-legged dragon.
static func _dragon(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["8a9cf0", "4f63d6", "2a3aa8"][st])
	var BL := Color("ffe0a0")
	var AC := Color("ffcf33")
	if st < 2:
		var ib: Array = base(b, p, [1.0, 1.25][st], 0.36)
		var inner: Node3D = ib[0]
		var core: Node3D = ib[1]
		pt(core, Toys.sph(0.3), Vector3.ZERO, C, Vector3(1.0, 0.82, 1.3))
		pt(core, Toys.sph(0.22), Vector3(0, -0.1, 0.06), BL, Vector3(1.0, 0.6, 1.3), Vector3.ZERO, false)
		for k in 3 + st:
			spike(core, Vector3(0, 0.24, -0.22 + k * 0.44 / (2 + st)), 0.13, 0.05, AC)
		if st == 1:
			wing(p, core, Vector3(0.2, 0.16, -0.04), 0.48, C.darkened(0.2), C.lightened(0.35), true)
		quad_legs(p, inner, C, 0.085, 0.24, 0.17, 0.2, Color("fff2d0"))
		var tail := tail_at(p, core, Vector3(0, 0.0, -0.36))
		pt(tail, Toys.cyl(0.03, 0.09, 0.45, 8), Vector3(0, 0.1, -0.14), C, Vector3.ONE, Vector3(-1.0, 0, 0))
		pt(tail, Toys.cyl(0.0, 0.08, 0.12, 4), Vector3(0, 0.27, -0.33), AC, Vector3.ONE, Vector3(-1.0, 0, 0))
		var head := head_at(p, inner, Vector3(0, 0.62, 0.34))
		pt(head, Toys.sph(0.23), Vector3.ZERO, C)
		pt(head, Toys.sph(0.13), Vector3(0, -0.06, 0.18), C, Vector3(1.0, 0.7, 1.2))
		pt(head, Toys.sph(0.09), Vector3(0, -0.1, 0.22), BL, Vector3(1.2, 0.5, 1.0), Vector3.ZERO, false)
		Toys.eyes(head, 0.05, 0.18, 0.1, 0.065, st == 1)
		for sx in [-1, 1]:
			spike(head, Vector3(sx * 0.12, 0.2, -0.06), 0.16 + st * 0.06, 0.045, Color("fff2d0"), Vector3(-0.6, 0, -sx * 0.3))
		return
	var ib2: Array = base(b, p, 1.2, 0.66)
	var inner2: Node3D = ib2[0]
	var core2: Node3D = ib2[1]
	pt(core2, Toys.sph(0.28), Vector3.ZERO, C, Vector3(1.05, 1.3, 0.95))
	pt(core2, Toys.sph(0.21), Vector3(0, -0.04, 0.12), BL, Vector3(0.95, 1.2, 0.6), Vector3.ZERO, false)
	for k in 4:   # glowing belly bands
		gl(p, core2, Toys.box(Vector3(0.24 - k * 0.03, 0.025, 0.02)), Vector3(0, 0.16 - k * 0.1, 0.23), Color("ffb02e"), Vector3.ONE, Vector3.ZERO, 0.9)
	for k in 5:
		spike(core2, Vector3(0, 0.32 - k * 0.12, -0.24), 0.16, 0.05, AC, Vector3(-1.2, 0, 0))
	wing(p, core2, Vector3(0.14, 0.22, -0.18), 0.75, C.darkened(0.25), AC.darkened(0.15), true)
	biped_limbs(p, inner2, C, 0.42, 0.11, 0.34, Vector3(0.29, 0.82, 0.06), 0.065, 0.28, Color("fff2d0"))
	var tail2 := tail_at(p, core2, Vector3(0, -0.22, -0.2))
	pt(tail2, Toys.cyl(0.03, 0.1, 0.6, 8), Vector3(0, -0.06, -0.26), C, Vector3.ONE, Vector3(-1.35, 0, 0))
	pt(tail2, Toys.cyl(0.0, 0.1, 0.16, 4), Vector3(0, -0.12, -0.6), AC, Vector3.ONE, Vector3(-1.35, 0, 0))
	var head2 := head_at(p, inner2, Vector3(0, 1.12, 0.1))
	pt(head2, Toys.sph(0.22), Vector3.ZERO, C, Vector3(1.0, 0.95, 1.1))
	pt(head2, Toys.sph(0.16), Vector3(0, -0.06, 0.2), C, Vector3(1.0, 0.7, 1.35))
	pt(head2, Toys.sph(0.11), Vector3(0, -0.12, 0.24), BL, Vector3(1.2, 0.45, 1.1), Vector3.ZERO, false)
	Toys.eyes(head2, 0.06, 0.18, 0.09, 0.06, true)
	eye_glow(p, head2, 0.06, 0.19, 0.09, AC)
	for sx in [-1, 1]:   # big swept horns
		spike(head2, Vector3(sx * 0.13, 0.17, -0.1), 0.34, 0.055, Color("fff2d0"), Vector3(-1.0, 0, -sx * 0.3))
		spike(head2, Vector3(sx * 0.2, 0.04, -0.04), 0.14, 0.035, Color("fff2d0"), Vector3(-0.8, 0, -sx * 1.0))
	aura(p, b, 1.0, 0.8, AC, 6)


## PIXIBUN / FLUFFAIRY / STARABBIT: a bunny that grows fairy wings and a star tiara.
static func _fairy(b: Node3D, p: Dictionary, st: int) -> void:
	var C := Color(["ffe0f0", "ffb8de", "ff8fc8"][st])
	var BL := Color("ffffff")
	var AC := Color("ffd23a")
	var ib: Array = base(b, p, [1.0, 1.18, 1.38][st], 0.36)
	var inner: Node3D = ib[0]
	var core: Node3D = ib[1]
	pt(core, Toys.sph(0.29), Vector3.ZERO, C, Vector3(1.0, 0.92, 1.15))
	pt(core, Toys.sph(0.21), Vector3(0, -0.08, 0.08), BL, Vector3(1.0, 0.75, 1.0), Vector3.ZERO, false)
	if st >= 1:   # a ribbon bow
		for sx in [-1, 1]:
			pt(core, Toys.sph(0.08), Vector3(sx * 0.09, 0.22, 0.2), Color("ff4f8a"), Vector3(1.4, 0.8, 0.5))
		pt(core, Toys.sph(0.045), Vector3(0, 0.22, 0.22), Color("ff4f8a"))
		var wc := Color("e8f6ff") if st == 1 else Color("fff6c8")
		wing(p, core, Vector3(0.16, 0.2, -0.12), [0.0, 0.34, 0.55][st], wc, Color("ffd6f0"))
	quad_legs(p, inner, C, 0.08, 0.22, 0.16, 0.18)
	var tail := tail_at(p, core, Vector3(0, 0.0, -0.32))
	pt(tail, Toys.sph(0.11), Vector3(0, 0.02, 0), BL)
	if st == 2:
		gl(p, tail, Toys.sph(0.05), Vector3(0, 0.02, -0.08), AC)
		aura(p, b, 0.6, 0.65, Color("fff38a"), 7, 0.045)
		aura(p, b, 1.0, 0.45, Color("ff9fe8"), 4, 0.04)
	var head := head_at(p, inner, Vector3(0, 0.6, 0.3))
	pt(head, Toys.sph(0.24), Vector3.ZERO, C)
	pt(head, Toys.sph(0.12), Vector3(0, -0.08, 0.17), BL, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)
	pt(head, Toys.sph(0.03), Vector3(0, -0.02, 0.24), Color("ff4f8a"), Vector3.ONE, Vector3.ZERO, false)
	Toys.eyes(head, 0.05, 0.19, 0.1, 0.075)
	for sx in [-1, 1]:   # long ears
		var el := 0.3 + st * 0.06
		var ep := Toys.piv(head, Vector3(sx * 0.1, 0.18, -0.02))
		ep.rotation = Vector3(-0.15, 0, -sx * (0.18 + st * 0.08))
		pt(ep, Toys.cap(0.065, el), Vector3(0, el * 0.45, 0), C, Vector3(1, 1, 0.6))
		pt(ep, Toys.cap(0.032, el * 0.8), Vector3(0, el * 0.45, 0.03), Color("ff8fc8") if st < 2 else AC, Vector3(1, 1, 0.5), Vector3.ZERO, false)
	if st == 2:   # a star tiara
		pt(head, Toys.cyl(0.18, 0.18, 0.03, 16), Vector3(0, 0.17, 0.0), AC, Vector3(1, 1, 1), Vector3(0.25, 0, 0))
		gl(p, head, Toys.cyl(0.08, 0.08, 0.03, 5), Vector3(0, 0.25, 0.16), AC, Vector3.ONE, Vector3(PI / 2, 0, 0), 1.3)
		eye_glow(p, head, 0.05, 0.2, 0.1, Color("fff38a"))


## MEGA: gold armour, a crown of light, bigger and with a big aura (mega.gd turns it on).
static func _mega_extras(b: Node3D, p: Dictionary, line: String) -> void:
	var col := Mons.LINES[line].color as Color
	p.inner.scale *= 1.2   # bigger
	var head: Node3D = p.head
	# a crown of light floating over the head
	var crown := Toys.piv(head, Vector3(0, 0.42, -0.02))
	p["orbit"].append(crown)
	for k in 6:
		var a := k * TAU / 6
		gl(p, crown, Toys.cyl(0.0, 0.05, 0.18, 4), Vector3(cos(a) * 0.17, 0.0, sin(a) * 0.17), GOLD, Vector3.ONE, Vector3.ZERO, 1.6)
	gl(p, crown, Toys.sph(0.06), Vector3.ZERO, col.lightened(0.4), Vector3.ONE, Vector3.ZERO, 2.0)
	# gold armour plates on the body
	var core: Node3D = p.core
	for sx in [-1, 1]:   # gold shoulder guards with a spike
		var sg := Toys.piv(core, Vector3(sx * 0.24, 0.16, 0.08))
		sg.rotation = Vector3(0, 0, -sx * 0.6)
		pt(sg, Toys.hemi(0.11), Vector3.ZERO, GOLD, Vector3(1.0, 0.7, 1.0))
		spike(sg, Vector3(0, 0.1, 0), 0.12, 0.035, WHITE)
	var gem := Toys.piv(head, Vector3(0, 0.18, 0.16))   # a gem on the forehead
	pt(gem, Toys.cyl(0.05, 0.05, 0.03, 6), Vector3.ZERO, GOLD, Vector3.ONE, Vector3(PI / 2 - 0.3, 0, 0))
	gl(p, gem, Toys.sph(0.035), Vector3(0, 0.0, 0.02), col.lightened(0.5))
	aura(p, b, 0.35, 1.0, col.lightened(0.3), 8, 0.07)
	aura(p, b, 1.2, 0.8, GOLD, 6, 0.05)


# ------------------------------------------------------------------ small pieces
static func _leaf(parent: Node3D, pos: Vector3, size: float, tilt: float, c := Color("4fb35a")) -> void:
	pt(parent, Toys.sph(size), pos + Vector3(sin(tilt) * size * 0.6, size * 0.8, 0), c, Vector3(0.25, 1.0, 0.55), Vector3(0, 0, -tilt))


static func _flame(p: Dictionary, parent: Node3D, pos: Vector3, h: float) -> void:
	gl(p, parent, Toys.cyl(0.0, h * 0.45, h, 8), pos + Vector3(0, h * 0.5, 0), Color("ffb02e"), Vector3.ONE, Vector3.ZERO, 0.9)
	gl(p, parent, Toys.cyl(0.0, h * 0.25, h * 0.6, 6), pos + Vector3(0, h * 0.4, 0.02), Color("ffe36a"), Vector3.ONE, Vector3.ZERO, 1.2)


static func _flame_col(p: Dictionary, parent: Node3D, pos: Vector3, h: float, c: Color) -> void:
	gl(p, parent, Toys.cyl(0.0, h * 0.45, h, 8), pos + Vector3(0, h * 0.5, 0), c, Vector3.ONE, Vector3.ZERO, 1.0)
