class_name Toys
## The chunky 3D toy look: every character and prop is built from simple shapes
## (spheres, capsules, boxes) with cartoon shading and a dark outline, and moved
## by hand-written animations (legs swing, arms pump, Blocky swings his pickaxe).
## Models face +Z and stand with their feet at y = 0. Our own designs.

const INK := Color(0.12, 0.08, 0.16)

static var _mats := {}
static var _meshes := {}
static var _outline: StandardMaterial3D


static func outline_mat() -> StandardMaterial3D:
	if _outline == null:
		_outline = StandardMaterial3D.new()
		_outline.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_outline.albedo_color = INK
		_outline.cull_mode = BaseMaterial3D.CULL_FRONT
		_outline.grow = true
		_outline.grow_amount = 0.022
	return _outline


static func mat(c: Color, outline := true, glow := 0.0) -> StandardMaterial3D:
	var k := "%s|%s|%s" % [c.to_html(), outline, glow]
	if not _mats.has(k):
		var m := StandardMaterial3D.new()
		m.albedo_color = c
		m.roughness = 0.8
		m.metallic_specular = 0.25
		m.diffuse_mode = BaseMaterial3D.DIFFUSE_LAMBERT_WRAP
		if glow > 0:
			m.emission_enabled = true
			m.emission = c
			m.emission_energy_multiplier = glow
		if outline:
			m.next_pass = outline_mat()
		_mats[k] = m
	return _mats[k]


# ------------------------------------------------------------------ shapes
static func sph(r: float) -> Mesh:
	var k := "s%.3f" % r
	if not _meshes.has(k):
		var m := SphereMesh.new()
		m.radius = r
		m.height = r * 2
		m.radial_segments = 18
		m.rings = 9
		_meshes[k] = m
	return _meshes[k]


static func hemi(r: float) -> Mesh:
	var k := "h%.3f" % r
	if not _meshes.has(k):
		var m := SphereMesh.new()
		m.radius = r
		m.height = r
		m.is_hemisphere = true
		m.radial_segments = 20
		m.rings = 6
		_meshes[k] = m
	return _meshes[k]


static func cap(r: float, h: float) -> Mesh:
	var k := "c%.3f_%.3f" % [r, h]
	if not _meshes.has(k):
		var m := CapsuleMesh.new()
		m.radius = r
		m.height = maxf(h, r * 2.01)
		m.radial_segments = 14
		m.rings = 4
		_meshes[k] = m
	return _meshes[k]


static func box(s: Vector3) -> Mesh:
	var k := "b%s" % s
	if not _meshes.has(k):
		var m := BoxMesh.new()
		m.size = s
		_meshes[k] = m
	return _meshes[k]


static func cyl(top: float, bottom: float, h: float, seg := 16) -> Mesh:
	var k := "y%.3f_%.3f_%.3f_%d" % [top, bottom, h, seg]
	if not _meshes.has(k):
		var m := CylinderMesh.new()
		m.top_radius = top
		m.bottom_radius = bottom
		m.height = h
		m.radial_segments = seg
		m.rings = 1
		_meshes[k] = m
	return _meshes[k]


static func part(parent: Node3D, mesh: Mesh, pos: Vector3, c: Color, scl := Vector3.ONE, rot := Vector3.ZERO, outline := true, glow := 0.0) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat(c, outline, glow)
	mi.position = pos
	mi.scale = scl
	mi.rotation = rot
	parent.add_child(mi)
	return mi


static func piv(parent: Node3D, pos: Vector3) -> Node3D:
	var n := Node3D.new()
	n.position = pos
	parent.add_child(n)
	return n


static func eyes(head: Node3D, y: float, z: float, gap: float, r: float, angry := false) -> void:
	for s in [-1, 1]:
		part(head, sph(r), Vector3(s * gap, y, z), Color.WHITE, Vector3(1, 1.25, 0.6), Vector3.ZERO, false)
		part(head, sph(r * 0.55), Vector3(s * gap, y - r * 0.1, z + r * 0.35), Color("1e2a44"), Vector3(1, 1.2, 0.6), Vector3.ZERO, false)
		part(head, sph(r * 0.18), Vector3(s * gap + r * 0.2, y + r * 0.35, z + r * 0.6), Color.WHITE, Vector3.ONE, Vector3.ZERO, false)
		if angry:
			part(head, box(Vector3(r * 2.2, r * 0.45, r * 0.4)), Vector3(s * gap, y + r * 1.45, z + r * 0.2), INK, Vector3.ONE, Vector3(0, 0, -s * 0.45), false)


static func star_badge(parent: Node3D, pos: Vector3, r: float) -> void:
	part(parent, cyl(r, r, r * 0.35, 5), pos, Color("ffcf33"), Vector3.ONE, Vector3(PI / 2, 0, 0), false)


# ------------------------------------------------------------------ characters
## Builds the model for a character. Returns a Node3D whose meta "p" holds its moving parts.
static func build(kind: String) -> Node3D:
	var root := Node3D.new()
	root.name = "Toy"
	var body := piv(root, Vector3.ZERO)
	var p := {"body": body}
	match kind:
		"rocco":
			_rocco(body, p)
		"blocky":
			_blocky(body, p)
		"sparky":
			_sparky(body, p)
		"sparky:1":
			var inner := piv(body, Vector3.ZERO)
			inner.scale = Vector3.ONE * 1.3
			_sparky(inner, p, 1)
		"grumbler":
			_grumbler(body, p)
		"penguin":
			_penguin(body, p)
		"scorpion":
			_scorpion(body, p)
		"skelly":
			_skelly(body, p)
		"kragjr":
			_kragjr(body, p)
	if kind.begins_with("mon:"):
		var bits := kind.split(":")
		_critter(body, p, bits[1], int(bits[2]))
		kind = "mon"
	root.set_meta("p", p)
	root.set_meta("kind", kind)
	for mi in _all_meshes(root):
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	return root


static func _all_meshes(n: Node) -> Array:
	var out := []
	for c in n.get_children():
		if c is MeshInstance3D:
			out.append(c)
		out += _all_meshes(c)
	return out


static func _rocco(b: Node3D, p: Dictionary) -> void:
	var RED := Color("e8413b")
	var DRED := Color("a92a2f")
	var SKIN := Color("f7c69c")
	var JEAN := Color("3d74db")
	for s in [-1, 1]:
		var leg := piv(b, Vector3(s * 0.12, 0.5, 0))
		part(leg, cap(0.085, 0.4), Vector3(0, -0.2, 0), JEAN)
		part(leg, sph(0.12), Vector3(0, -0.44, 0.05), Color.WHITE, Vector3(1, 0.6, 1.45))
		p["legL" if s < 0 else "legR"] = leg
	part(b, cyl(0.2, 0.21, 0.16), Vector3(0, 0.56, 0), JEAN)
	part(b, cap(0.22, 0.5), Vector3(0, 0.8, 0), RED, Vector3(1, 1, 0.85))
	star_badge(b, Vector3(0.06, 0.86, 0.19), 0.06)
	for s in [-1, 1]:
		var arm := piv(b, Vector3(s * 0.27, 0.98, 0))
		part(arm, cap(0.07, 0.36), Vector3(0, -0.15, 0), RED)
		part(arm, sph(0.085), Vector3(0, -0.35, 0), SKIN)
		p["armL" if s < 0 else "armR"] = arm
	var head := piv(b, Vector3(0, 1.1, 0))
	p["head"] = head
	part(head, sph(0.3), Vector3(0, 0.2, 0), SKIN)
	part(head, sph(0.25), Vector3(0, 0.24, -0.13), Color("6b3d1f"), Vector3(1.12, 0.92, 0.8))
	for s in [-1, 1]:
		part(head, sph(0.07), Vector3(s * 0.3, 0.18, -0.02), SKIN)
	eyes(head, 0.24, 0.27, 0.1, 0.08)
	part(head, sph(0.055), Vector3(0, 0.15, 0.31), Color("e09d74"))
	part(head, box(Vector3(0.12, 0.025, 0.02)), Vector3(0, 0.06, 0.28), Color("7a1f2b"), Vector3.ONE, Vector3.ZERO, false)
	part(head, hemi(0.315), Vector3(0, 0.27, 0), RED, Vector3(1, 0.85, 1))
	part(head, cyl(0.2, 0.2, 0.035), Vector3(0, 0.29, 0.25), DRED, Vector3(1, 1, 1.25))
	star_badge(head, Vector3(0, 0.42, 0.24), 0.07)


static func _blocky(b: Node3D, p: Dictionary) -> void:
	var SHIRT := Color("3f7fd9")
	var VEST := Color("ff8a1f")
	var PANTS := Color("2f4a9c")
	var SKIN := Color("e2a77c")
	for s in [-1, 1]:
		var leg := piv(b, Vector3(s * 0.12, 0.52, 0))
		part(leg, box(Vector3(0.18, 0.42, 0.2)), Vector3(0, -0.2, 0), PANTS)
		part(leg, box(Vector3(0.21, 0.13, 0.3)), Vector3(0, -0.44, 0.04), Color("4a3424"))
		p["legL" if s < 0 else "legR"] = leg
	part(b, box(Vector3(0.5, 0.55, 0.3)), Vector3(0, 0.82, 0), SHIRT)
	for s in [-1, 1]:
		part(b, box(Vector3(0.17, 0.5, 0.32)), Vector3(s * 0.165, 0.84, 0), VEST, Vector3.ONE, Vector3.ZERO, false)
		part(b, box(Vector3(0.17, 0.05, 0.33)), Vector3(s * 0.165, 0.78, 0), Color("e9f3f5"), Vector3.ONE, Vector3.ZERO, false)
	for s in [-1, 1]:
		var arm := piv(b, Vector3(s * 0.33, 1.03, 0))
		part(arm, box(Vector3(0.14, 0.42, 0.16)), Vector3(0, -0.17, 0), SHIRT)
		part(arm, box(Vector3(0.14, 0.12, 0.16)), Vector3(0, -0.42, 0), SKIN)
		p["armL" if s < 0 else "armR"] = arm
	# pickaxe in the right hand, pointing forward
	var pick := piv(p.armR, Vector3(0, -0.42, 0.02))
	part(pick, cyl(0.028, 0.028, 0.75, 8), Vector3(0, 0, 0.22), Color("a8743f"), Vector3.ONE, Vector3(PI / 2, 0, 0))
	part(pick, box(Vector3(0.06, 0.5, 0.07)), Vector3(0, 0, 0.58), Color("b8c4cf"), Vector3.ONE, Vector3(0, 0, 0))
	p["pick"] = pick
	var head := piv(b, Vector3(0, 1.12, 0))
	p["head"] = head
	part(head, box(Vector3(0.44, 0.42, 0.42)), Vector3(0, 0.21, 0), SKIN)
	part(head, box(Vector3(0.45, 0.14, 0.43)), Vector3(0, 0.38, -0.02), Color("3b2a20"), Vector3.ONE, Vector3.ZERO, false)
	part(head, box(Vector3(0.44, 0.12, 0.08)), Vector3(0, 0.05, 0.2), Color("5a3e2c"), Vector3.ONE, Vector3.ZERO, false)
	for s in [-1, 1]:
		part(head, box(Vector3(0.09, 0.08, 0.02)), Vector3(s * 0.1, 0.24, 0.215), Color.WHITE, Vector3.ONE, Vector3.ZERO, false)
		part(head, box(Vector3(0.05, 0.07, 0.02)), Vector3(s * 0.1 + 0.02, 0.235, 0.225), Color("1e2a44"), Vector3.ONE, Vector3.ZERO, false)
		part(head, box(Vector3(0.11, 0.025, 0.02)), Vector3(s * 0.1, 0.31, 0.215), Color("3b2a20"), Vector3.ONE, Vector3.ZERO, false)
	part(head, hemi(0.29), Vector3(0, 0.42, 0), Color("ffd23a"), Vector3(1, 0.8, 1))
	part(head, cyl(0.33, 0.33, 0.035), Vector3(0, 0.43, 0), Color("ffd23a"))
	part(head, cyl(0.07, 0.07, 0.05), Vector3(0, 0.55, 0.25), Color("fff6b0"), Vector3.ONE, Vector3(PI / 2 - 0.4, 0, 0), true, 1.5)


static func _sparky(b: Node3D, p: Dictionary, stage := 0) -> void:
	var Y := Color("ffd83b") if stage == 0 else Color("f5c518")
	var BLUE := Color("54c8ff")
	for s in [-1, 1]:
		var foot := piv(b, Vector3(s * 0.15, 0.06, 0.06))
		part(foot, sph(0.1), Vector3.ZERO, Color("ff9a3c"), Vector3(1, 0.6, 1.4))
		p["legL" if s < 0 else "legR"] = foot
	var core := piv(b, Vector3(0, 0.42, 0))
	p["core"] = core
	part(core, sph(0.38), Vector3.ZERO, Y)
	part(core, sph(0.24), Vector3(0, -0.07, 0.2), Color("fff1a8"), Vector3(1, 1, 0.6), Vector3.ZERO, false)
	for i in 4:
		part(core, box(Vector3(0.1, 0.035, 0.03)), Vector3(-0.15 + i * 0.1, -0.07, 0.355), BLUE, Vector3.ONE, Vector3(0, 0, 0.7 if i % 2 == 0 else -0.7), false)
	eyes(core, 0.08, 0.3, 0.13, 0.1)
	for s in [-1, 1]:
		var ant := piv(core, Vector3(s * 0.12, 0.32, 0))
		part(ant, cyl(0.02, 0.02, 0.3, 6), Vector3(s * 0.04, 0.14, 0), INK, Vector3.ONE, Vector3(0, 0, -s * 0.3), false)
		if stage == 0:
			part(ant, sph(0.075), Vector3(s * 0.09, 0.3, 0), BLUE, Vector3.ONE, Vector3.ZERO, true, 0.8)
		else:   # lightning-bolt tips
			part(ant, box(Vector3(0.05, 0.16, 0.05)), Vector3(s * 0.08, 0.3, 0), BLUE, Vector3.ONE, Vector3(0, 0, 0.5), true, 0.8)
			part(ant, box(Vector3(0.05, 0.16, 0.05)), Vector3(s * 0.11, 0.42, 0), BLUE, Vector3.ONE, Vector3(0, 0, -0.5), true, 0.8)
		p["antL" if s < 0 else "antR"] = ant
	var tail := piv(core, Vector3(0, 0, -0.36))
	part(tail, box(Vector3(0.08, 0.22, 0.06)), Vector3(0, 0.06, -0.05), Color("e8aa1c"), Vector3.ONE, Vector3(0.6, 0, 0))
	part(tail, box(Vector3(0.08, 0.2, 0.06)), Vector3(0, 0.18, -0.14), Color("e8aa1c"), Vector3.ONE, Vector3(-0.6, 0, 0))
	p["tail"] = tail


static func _grumbler(b: Node3D, p: Dictionary) -> void:
	var PURPLE := Color("8a5bc4")
	for s in [-1, 1]:
		var foot := piv(b, Vector3(s * 0.2, 0.07, 0.05))
		part(foot, sph(0.13), Vector3.ZERO, Color("4a2f66"), Vector3(1, 0.6, 1.3))
		p["legL" if s < 0 else "legR"] = foot
	var core := piv(b, Vector3(0, 0.47, 0))
	p["core"] = core
	part(core, sph(0.42), Vector3.ZERO, PURPLE)
	part(core, sph(0.1), Vector3(-0.17, 0.2, 0.3), Color("a77ddb"), Vector3(1.4, 0.8, 0.5), Vector3.ZERO, false)
	part(core, cyl(0.0, 0.1, 0.3, 10), Vector3(0, 0.5, 0), Color("f2e7d0"))
	eyes(core, 0.06, 0.34, 0.14, 0.09, true)
	part(core, cyl(0.0, 0.035, 0.08, 6), Vector3(0.06, -0.14, 0.38), Color.WHITE, Vector3.ONE, Vector3(PI, 0, 0), false)


static func _skelly(b: Node3D, p: Dictionary) -> void:
	var BONE := Color("f1eee4")
	for s in [-1, 1]:
		var leg := piv(b, Vector3(s * 0.1, 0.62, 0))
		part(leg, cap(0.045, 0.58), Vector3(0, -0.29, 0), BONE)
		part(leg, sph(0.07), Vector3(0, -0.58, 0.05), BONE, Vector3(1, 0.6, 1.5))
		p["legL" if s < 0 else "legR"] = leg
	part(b, cap(0.04, 0.5), Vector3(0, 0.92, -0.03), BONE)
	for i in 3:
		part(b, cap(0.03, 0.34 - i * 0.04), Vector3(0, 1.05 - i * 0.1, 0.02), BONE, Vector3.ONE, Vector3(0, 0, PI / 2))
	part(b, cyl(0.15, 0.12, 0.08), Vector3(0, 0.66, 0), BONE)
	for s in [-1, 1]:
		var arm := piv(b, Vector3(s * 0.2, 1.15, 0))
		part(arm, cap(0.04, 0.48), Vector3(0, -0.22, 0), BONE)
		p["armL" if s < 0 else "armR"] = arm
	# bow in the left hand
	var bow := piv(p.armL, Vector3(0, -0.45, 0.05))
	for i in 5:
		var a := -0.9 + i * 0.45
		part(bow, box(Vector3(0.05, 0.2, 0.05)), Vector3(0, sin(a) * 0.38, cos(a) * 0.14), Color("8a5a2b"), Vector3.ONE, Vector3(-a * 0.6, 0, 0))
	part(bow, cyl(0.006, 0.006, 0.76, 4), Vector3(0, 0, 0.0), Color("dddddd"), Vector3.ONE, Vector3.ZERO, false)
	p["bow"] = bow
	var head := piv(b, Vector3(0, 1.22, 0))
	p["head"] = head
	part(head, sph(0.2), Vector3(0, 0.16, 0), BONE)
	part(head, box(Vector3(0.22, 0.09, 0.18)), Vector3(0, 0.0, 0.06), BONE)
	for s in [-1, 1]:
		part(head, sph(0.055), Vector3(s * 0.08, 0.17, 0.17), INK, Vector3(1, 1.2, 0.5), Vector3.ZERO, false)
	part(head, sph(0.022), Vector3(0.08, 0.18, 0.2), Color("ff5a4a"), Vector3.ONE, Vector3.ZERO, false, 2.0)


static func _kragjr(b: Node3D, p: Dictionary) -> void:
	var GREEN := Color("59c25a")
	var POT := Color("8a5bc4")
	# the hover-pot (open bowl) with a yellow band and a propeller underneath
	part(b, hemi(0.82), Vector3(0, 0.85, 0), POT, Vector3(1, 0.85, 1), Vector3(PI, 0, 0))
	part(b, cyl(0.84, 0.84, 0.14, 24), Vector3(0, 0.84, 0), Color("ffcf33"))
	for i in 6:
		var a := i * TAU / 6
		part(b, sph(0.06), Vector3(sin(a) * 0.82, 0.84, cos(a) * 0.82), Color("ffcf33"))
	part(b, cyl(0.05, 0.05, 0.2, 8), Vector3(0, 0.1, 0), Color("7c8794"))
	var prop := piv(b, Vector3(0, 0.02, 0))
	for s in [-1, 1]:
		part(prop, box(Vector3(0.75, 0.03, 0.14)), Vector3(s * 0.38, 0, 0), Color("c9d3dc"), Vector3.ONE, Vector3(0.25 * s, 0, 0))
	p["prop"] = prop
	# the little dragon in it
	var kid := piv(b, Vector3(0, 0.95, 0))
	p["core"] = kid
	part(kid, sph(0.32), Vector3(0, 0.2, 0), GREEN)
	for s in [-1, 1]:
		var arm := piv(kid, Vector3(s * 0.3, 0.32, 0.05))
		part(arm, cap(0.07, 0.3), Vector3(0, -0.1, 0.06), GREEN, Vector3.ONE, Vector3(0.6, 0, 0))
		p["armL" if s < 0 else "armR"] = arm
	var head := piv(kid, Vector3(0, 0.62, 0))
	p["head"] = head
	part(head, sph(0.36), Vector3.ZERO, GREEN)
	part(head, sph(0.2), Vector3(0, -0.08, 0.3), Color("8be08a"), Vector3(1.2, 0.8, 1))
	for s in [-1, 1]:
		part(head, sph(0.025), Vector3(s * 0.07, -0.04, 0.48), INK, Vector3.ONE, Vector3.ZERO, false)
	eyes(head, 0.1, 0.27, 0.13, 0.085)
	part(head, box(Vector3(0.16, 0.04, 0.04)), Vector3(-0.13, 0.25, 0.28), INK, Vector3.ONE, Vector3(0, 0, 0.4), false)
	part(head, box(Vector3(0.16, 0.04, 0.04)), Vector3(0.13, 0.25, 0.28), INK, Vector3.ONE, Vector3(0, 0, -0.4), false)
	for i in 4:
		var a := -0.5 + i * 0.4
		part(head, cyl(0.0, 0.09, 0.26, 8), Vector3(0, 0.3 + cos(a) * 0.05, -0.05 + sin(a) * 0.3), Color("ff8c2a"), Vector3.ONE, Vector3(-a * 1.2 - 0.3, 0, 0))


# ------------------------------------------------------------------ animation
## Poses the model of an actor for this frame from its animation name and timer.
static func pose(a, dt: float) -> void:
	var toy: Node3D = a.model
	var p: Dictionary = toy.get_meta("p")
	var kind: String = toy.get_meta("kind")
	var t: float = a.anim_t
	var anim: String = a.anim
	var b: Node3D = p.body
	# turn to face where we're going (a little toward the camera so you see faces)
	var f: Vector3 = a.facing
	if f.length() > 0.1:
		var fv: Vector3 = f + Actor.view_back * 0.55
		var want := atan2(fv.x, fv.z)
		toy.rotation.y = lerp_angle(toy.rotation.y, want, 1 - exp(-dt * 14))
	# somersault for double jumps / slams, and squash & stretch
	b.scale = Vector3(a.squash.x, a.squash.y, a.squash.x)
	b.position = Vector3.ZERO
	b.rotation.x = a._ease_spin() * TAU if a.spin_t > 0 else 0.0
	toy.position.x = a.sprite.position.x   # the shake Krag Jr. does before a dive
	toy.visible = not (a.flash_t > 0 and int(a.flash_t * 20) % 2 == 1)
	for k in ["legL", "legR", "armL", "armR", "head"]:
		if p.has(k):
			p[k].rotation = Vector3.ZERO
	match kind:
		"rocco", "blocky", "skelly":
			_pose_person(p, kind, anim, t)
		"sparky", "sparky:1":
			_pose_sparky(p, anim, t)
		"mon":
			_pose_critter(p, anim, t)
		"grumbler":
			var s := sin(t * 10)
			p.legL.position.z = 0.05 + s * 0.1
			p.legR.position.z = 0.05 - s * 0.1
			p.core.rotation.z = s * 0.12
			if anim == "flat":
				b.scale = Vector3(1.5, 0.22, 1.5)
		"penguin":
			var s := sin(t * 9)
			p.core.rotation.z = s * 0.15
			p.legL.position.z = 0.1 + s * 0.08
			p.legR.position.z = 0.1 - s * 0.08
			if anim == "slide":
				b.rotation.x = PI / 2 * 0.95
				b.position.y = 0.3
				b.position.z = -0.1
			elif anim == "flat":
				b.scale = Vector3(1.5, 0.22, 1.5)
		"scorpion":
			var s := sin(t * 14)
			p.legL.rotation.y = s * 0.3
			p.legR.rotation.y = -s * 0.3
			p.tail.rotation.x = -0.2 + sin(t * 3) * 0.1
			if anim == "aim":
				p.tail.rotation.x = 0.7
				p.core.position.z = -0.05
			elif anim == "flat":
				b.scale = Vector3(1.5, 0.22, 1.5)
		"kragjr":
			p.prop.rotation.y += dt * 30
			b.position.y = sin(t * 3) * 0.06
			p.armL.rotation.x = -0.4 + sin(t * 6) * 0.4
			p.armR.rotation.x = -0.4 - sin(t * 6) * 0.4
			if anim == "angry":
				p.head.rotation.z = sin(t * 40) * 0.15
				p.armL.rotation.x = -2.6
				p.armR.rotation.x = -2.6
	if a.spin_t > 0:   # flip around the middle of the body (not around the feet)
		var mid := Vector3(0, a.height * 0.5, 0)
		b.position += mid - b.basis * mid


static func _pose_person(p: Dictionary, kind: String, anim: String, t: float) -> void:
	var b: Node3D = p.body
	match anim:
		"run", "walk":
			var spd := 13.0 if anim == "run" else 7.0
			var s := sin(t * spd)
			p.legL.rotation.x = s * 0.85
			p.legR.rotation.x = -s * 0.85
			p.armL.rotation.x = -s * 0.9
			p.armR.rotation.x = s * 0.9
			b.position.y = absf(cos(t * spd)) * 0.05
			p.head.rotation.x = 0.08
		"jump":
			p.legL.rotation.x = -0.9
			p.legR.rotation.x = 0.4
			p.armR.rotation.x = -2.9
			p.armL.rotation.x = 0.5
			p.armL.rotation.z = -0.4
		"fall":
			p.legL.rotation.x = 0.3
			p.legR.rotation.x = -0.3
			p.armL.rotation.z = -1.3
			p.armR.rotation.z = 1.3
		"slam":
			p.legL.rotation.x = -1.4
			p.legR.rotation.x = -1.4
			p.armL.rotation.x = -1.3
			p.armR.rotation.x = -1.3
			b.position.y = 0.25
		"hurt":
			p.armL.rotation.z = -2.2
			p.armR.rotation.z = 2.2
			p.head.rotation.x = -0.4
			p.legL.rotation.x = 0.4
		"win":
			p.armL.rotation.x = -2.9
			p.armR.rotation.x = -2.9
			b.position.y = absf(sin(t * 6)) * 0.25
		"mine":
			var ph := fmod(t, 0.3) / 0.3
			p.armR.rotation.x = lerpf(-2.8, 0.5, ease(ph, 0.4))
			p.armL.rotation.x = -0.4
			b.rotation.x += lerpf(-0.1, 0.25, ph)
		"aim":
			p.armL.rotation.x = -1.55
			p.armR.rotation.x = -1.5
			p.armR.rotation.y = 0.5
			p.head.rotation.y = 0.2
		_:   # idle / blink
			b.position.y = sin(t * 3.0) * 0.012
			p.armL.rotation.z = -0.12 - sin(t * 3.0) * 0.04
			p.armR.rotation.z = 0.12 + sin(t * 3.0) * 0.04
			p.head.rotation.y = sin(t * 0.9) * 0.15
	if kind == "blocky" and anim != "mine":
		p.armR.rotation.x -= 0.5   # holds the pickaxe up a bit


static func _pose_sparky(p: Dictionary, anim: String, t: float) -> void:
	var b: Node3D = p.body
	match anim:
		"run":
			b.position.y = absf(sin(t * 14)) * 0.16
			p.legL.position.z = 0.06 + sin(t * 14) * 0.12
			p.legR.position.z = 0.06 - sin(t * 14) * 0.12
			p.tail.rotation.x = sin(t * 14) * 0.3
		"jump", "fall":
			b.position.y = 0.05
			p.core.rotation.x = -0.25
			p.legL.position.z = -0.05
			p.legR.position.z = 0.15
		"zap", "hurt":
			var s := 1.0 + sin(t * 50) * 0.06
			p.core.scale = Vector3(s, s, s)
			p.antL.rotation.z = sin(t * 40) * 0.4
			p.antR.rotation.z = -sin(t * 40) * 0.4
			return
		"win":
			b.position.y = absf(sin(t * 7)) * 0.35
		_:
			b.position.y = sin(t * 3) * 0.02
			p.antL.rotation.z = sin(t * 2.5) * 0.15
			p.antR.rotation.z = -sin(t * 2.5 + 0.5) * 0.15
			p.tail.rotation.x = sin(t * 4) * 0.2
	p.core.scale = Vector3.ONE
	p.core.rotation.x = 0.0 if anim != "jump" and anim != "fall" else p.core.rotation.x


# ------------------------------------------------------------------ props
static func prop(kind: String, size: float) -> Node3D:
	var r := Node3D.new()
	match kind:
		"tree_a", "tree_b", "tree_c":
			var greens := {"tree_a": Color("4fb35a"), "tree_b": Color("3b9d52"), "tree_c": Color("7fbf3f")}
			var g: Color = greens[kind]
			var k := size / 4.0
			part(r, cyl(0.13 * k, 0.2 * k, 2.0 * k, 10), Vector3(0, 1.0 * k, 0), Color("8a5a35"))
			part(r, sph(0.95 * k), Vector3(0, 2.55 * k, 0), g)
			part(r, sph(0.7 * k), Vector3(-0.65 * k, 2.15 * k, 0.2 * k), g)
			part(r, sph(0.7 * k), Vector3(0.65 * k, 2.2 * k, -0.1 * k), g)
			part(r, sph(0.5 * k), Vector3(0.1 * k, 3.2 * k, 0.1 * k), g.lightened(0.2))
			for i in 3:
				var a := i * 2.1
				part(r, sph(0.1 * k), Vector3(sin(a) * 0.75 * k, 2.4 * k + i * 0.2 * k, cos(a) * 0.75 * k), Color("ff6a7a"))
		"bush":
			for x in [-0.25, 0.0, 0.25]:
				part(r, sph(0.25 + (0.08 if x == 0.0 else 0.0)), Vector3(x, 0.2, 0), Color("53b85c"))
		"flower_pink", "flower_blue", "flower_white":
			var cols := {"flower_pink": Color("ff8fc0"), "flower_blue": Color("7fb8ff"), "flower_white": Color("ffffff")}
			part(r, cyl(0.015, 0.015, 0.35, 5), Vector3(0, 0.17, 0), Color("3f9a48"), Vector3.ONE, Vector3.ZERO, false)
			part(r, sph(0.09), Vector3(0, 0.38, 0), cols[kind], Vector3(1, 0.6, 1))
			part(r, sph(0.04), Vector3(0, 0.42, 0), Color("ffd23a"), Vector3.ONE, Vector3.ZERO, false)
		"cloud":
			var k := size / 3.0
			for c in [[0.0, 0.0, 1.0], [-1.0, -0.2, 0.75], [1.0, -0.15, 0.8], [0.4, 0.45, 0.65]]:
				part(r, sph(c[2] * k), Vector3(c[0] * k, c[1] * k, 0), Color.WHITE, Vector3.ONE, Vector3.ZERO, false, 0.35)
		"pine_a", "pine_b":
			var k := size / 4.0
			var g := Color("2f7a52") if kind == "pine_a" else Color("3a8a5a")
			part(r, cyl(0.12 * k, 0.16 * k, 1.0 * k, 8), Vector3(0, 0.5 * k, 0), Color("6b4226"))
			for i in 3:
				var w := (1.1 - i * 0.3) * k
				part(r, cyl(0.0, w, 1.2 * k, 10), Vector3(0, (1.3 + i * 0.75) * k, 0), g)
				part(r, cyl(0.0, w * 0.85, 0.35 * k, 10), Vector3(0, (1.55 + i * 0.75) * k, 0), Color("f4f8ff"), Vector3.ONE, Vector3.ZERO, false)
		"snowman":
			var k := size / 2.0
			part(r, sph(0.42 * k), Vector3(0, 0.4 * k, 0), Color("f4f8ff"))
			part(r, sph(0.3 * k), Vector3(0, 1.0 * k, 0), Color("f4f8ff"))
			part(r, sph(0.22 * k), Vector3(0, 1.45 * k, 0), Color("f4f8ff"))
			part(r, cyl(0.0, 0.05 * k, 0.25 * k, 6), Vector3(0, 1.45 * k, 0.28 * k), Color("ff8a1f"), Vector3.ONE, Vector3(PI / 2, 0, 0))
			for sx in [-0.08, 0.08]:
				part(r, sph(0.035 * k), Vector3(sx * k, 1.52 * k, 0.19 * k), INK, Vector3.ONE, Vector3.ZERO, false)
			part(r, cyl(0.16 * k, 0.16 * k, 0.22 * k, 12), Vector3(0, 1.7 * k, 0), Color("2b2233"))
			part(r, cyl(0.26 * k, 0.26 * k, 0.04 * k, 12), Vector3(0, 1.6 * k, 0), Color("2b2233"))
			part(r, cyl(0.24 * k, 0.24 * k, 0.08 * k, 12), Vector3(0, 1.25 * k, 0), Color("e8413b"))
		"snowpile":
			for c in [[-0.2, 0.14, 0.22], [0.18, 0.12, 0.2], [0.0, 0.2, 0.25]]:
				part(r, sph(c[2]), Vector3(c[0], c[1], 0), Color("f4f8ff"), Vector3(1.3, 0.8, 1))
		"cactus_a", "cactus_b":
			var k := size / (4.0 if kind == "cactus_a" else 1.6)
			var g := Color("4fa85a")
			part(r, cap(0.22 * k, 2.2 * k), Vector3(0, 1.1 * k, 0), g)
			if kind == "cactus_a":
				for sx in [-1, 1]:
					part(r, cap(0.13 * k, 0.6 * k), Vector3(sx * 0.42 * k, 1.0 * k + (0.2 * k if sx > 0 else 0.0), 0), g, Vector3.ONE, Vector3(0, 0, sx * PI / 2))
					part(r, cap(0.13 * k, 0.7 * k), Vector3(sx * 0.62 * k, 1.35 * k + (0.2 * k if sx > 0 else 0.0), 0), g)
				part(r, sph(0.12 * k), Vector3(0, 2.25 * k, 0), Color("ff6fa8"))
		"palm":
			var k := size / 4.0
			for i in 5:
				part(r, cyl(0.13 * k, 0.16 * k, 0.65 * k, 8), Vector3(i * 0.06 * k, (0.32 + i * 0.6) * k, 0), Color("a8743f"), Vector3.ONE, Vector3(0, 0, -0.06 * i))
			for i in 6:
				var a := i * TAU / 6
				part(r, sph(0.55 * k), Vector3(0.3 * k + cos(a) * 0.55 * k, 3.05 * k - 0.15 * k, sin(a) * 0.55 * k), Color("3fae4a"), Vector3(1.4, 0.18, 0.5), Vector3(0, -a, -0.35))
			for i in 3:
				part(r, sph(0.12 * k), Vector3(0.3 * k + cos(i * 2.1) * 0.15 * k, 2.85 * k, sin(i * 2.1) * 0.15 * k), Color("8a5a35"))
		"desert_rock":
			part(r, sph(0.3), Vector3(0, 0.18, 0), Color("c4925a"), Vector3(1.3, 0.75, 1))
			part(r, sph(0.18), Vector3(0.25, 0.12, 0.1), Color("b07a48"), Vector3(1.2, 0.8, 1))
		"rocket":
			var k := size / 3.0
			var body := piv(r, Vector3.ZERO)
			body.scale = Vector3.ONE * k
			r.set_meta("body", body)
			part(body, cyl(0.42, 0.48, 1.4, 20), Vector3(0, 1.0, 0), Color("f4f4f8"))
			part(body, cyl(0.0, 0.42, 0.8, 20), Vector3(0, 2.1, 0), Color("e8413b"))
			part(body, cyl(0.48, 0.36, 0.3, 20), Vector3(0, 0.2, 0), Color("e8413b"))
			part(body, cyl(0.2, 0.2, 0.06, 16), Vector3(0, 1.25, 0.44), Color("5ab4ff"), Vector3.ONE, Vector3(PI / 2, 0, 0), true, 0.4)
			star_badge(body, Vector3(0, 0.75, 0.47), 0.12)
			for i in 3:
				var a := i * TAU / 3
				var fin := part(body, box(Vector3(0.06, 0.6, 0.5)), Vector3(sin(a) * 0.5, 0.35, cos(a) * 0.5), Color("e8413b"))
				fin.rotation.y = a
			var flame := piv(body, Vector3(0, -0.05, 0))
			_flame(flame, Vector3(0, -0.55, 0), 0.5)
			flame.rotation.x = PI
			r.set_meta("flame", flame)
		"hill_snow", "hill_sand":
			part(r, hemi(size * 0.6), Vector3.ZERO, Color("eef4fc") if kind == "hill_snow" else Color("e8c47e"), Vector3(1.4, 1.2, 0.6), Vector3.ZERO, false)
		"hill_a", "hill_b", "hill_c":
			var cols := {"hill_a": Color("7cc96a"), "hill_b": Color("5fb46a"), "hill_c": Color("a4d36f")}
			part(r, hemi(size * 0.6), Vector3.ZERO, cols[kind], Vector3(1.4, 1.2, 0.6), Vector3.ZERO, false)
		"coin":
			var c := piv(r, Vector3(0, 0.35, 0))
			part(c, cyl(0.28, 0.28, 0.07, 20), Vector3.ZERO, Color("ffcf33"), Vector3.ONE, Vector3(PI / 2, 0, 0), true, 0.25)
			star_badge(c, Vector3(0, 0, 0.04), 0.13)
		"heart":
			for s in [-1, 1]:
				part(r, sph(0.18), Vector3(s * 0.14, 0.45, 0), Color("ff4f6d"))
			part(r, cyl(0.0, 0.3, 0.38, 4), Vector3(0, 0.27, 0), Color("ff4f6d"), Vector3(1, 1, 0.6), Vector3(PI, PI / 4, 0))
		"flag":
			var k := size / 4.0
			part(r, cyl(0.05 * k, 0.05 * k, 3.8 * k, 8), Vector3(0, 1.9 * k, 0), Color("e6e6ee"))
			part(r, sph(0.13 * k), Vector3(0, 3.85 * k, 0), Color("ffcf33"))
			var pm := PrismMesh.new()
			pm.size = Vector3(1.0 * k, 0.8 * k, 0.05)
			part(r, pm, Vector3(0.52 * k, 3.3 * k, 0), Color("3fc06a"), Vector3.ONE, Vector3(0, 0, -PI / 2))
		"sign":
			part(r, box(Vector3(0.12, 1.0, 0.12)), Vector3(0, 0.5, 0), Color("8a5a35"))
			part(r, box(Vector3(2.0, 1.0, 0.1)), Vector3(0, 1.35, 0), Color("c98d55"))
		"shot_water":
			part(r, sph(0.16), Vector3(0, 0.25, 0), Color("5ab4ff"), Vector3(1, 1, 1.3), Vector3.ZERO, false, 1.0)
			part(r, sph(0.07), Vector3(0.04, 0.29, 0.1), Color.WHITE, Vector3.ONE, Vector3.ZERO, false, 1.0)
		"shot_fire":
			part(r, sph(0.2), Vector3(0, 0.27, 0), Color("ff7a2a"), Vector3.ONE, Vector3.ZERO, false, 1.6)
			part(r, sph(0.12), Vector3(0, 0.27, 0.1), Color("ffe36a"), Vector3.ONE, Vector3.ZERO, false, 2.0)
		"spark":
			part(r, sph(0.17), Vector3(0, 0.25, 0), Color("54c8ff"), Vector3.ONE, Vector3.ZERO, false, 2.0)
			part(r, sph(0.09), Vector3(0, 0.25, 0.1), Color.WHITE, Vector3.ONE, Vector3.ZERO, false, 2.0)
		"arrow":
			var a := piv(r, Vector3(0, 0.15, 0))
			part(a, cyl(0.02, 0.02, 0.6, 6), Vector3.ZERO, Color("7a4a22"), Vector3.ONE, Vector3(PI / 2, 0, 0), false)
			part(a, cyl(0.0, 0.05, 0.12, 6), Vector3(0, 0, 0.35), Color("b8c4cf"), Vector3.ONE, Vector3(PI / 2, 0, 0), false)
			r.set_meta("aim", a)
		"spikeball":
			var c := piv(r, Vector3(0, 0.4, 0))
			part(c, sph(0.28), Vector3.ZERO, Color("4b4f63"))
			for d in [Vector3.UP, Vector3.DOWN, Vector3.LEFT, Vector3.RIGHT, Vector3.FORWARD, Vector3.BACK]:
				var sp := part(c, cyl(0.0, 0.09, 0.22, 6), d * 0.33, Color("e8e8ee"))
				if d != Vector3.UP and d != Vector3.DOWN:
					sp.look_at_from_position(d * 0.33, d * 2.0, Vector3.UP if absf(d.y) < 0.5 else Vector3.RIGHT)
					sp.rotate_object_local(Vector3.RIGHT, -PI / 2)
				elif d == Vector3.DOWN:
					sp.rotation.x = PI
		"dennis":
			var TAN := Color("d9a05b")
			part(r, cap(0.17, 0.7), Vector3(0, 0.45, 0), TAN, Vector3.ONE, Vector3(0, 0, PI / 2))
			for x in [-0.22, 0.22]:
				for z in [-0.09, 0.09]:
					part(r, cap(0.05, 0.3), Vector3(x, 0.17, z), Color("c48a4a"))
			part(r, sph(0.2), Vector3(0.4, 0.68, 0), TAN)
			part(r, sph(0.09), Vector3(0.58, 0.64, 0), TAN)
			part(r, sph(0.04), Vector3(0.66, 0.66, 0), INK, Vector3.ONE, Vector3.ZERO, false)
			for z in [-0.14, 0.14]:
				part(r, sph(0.09), Vector3(0.36, 0.72, z), Color("8a5a35"), Vector3(0.6, 1.6, 0.5))
			part(r, cyl(0.03, 0.03, 0.3, 6), Vector3(-0.42, 0.62, 0), Color("c48a4a"), Vector3.ONE, Vector3(0, 0, 0.8))
	for mi in _all_meshes(r):
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON if kind.begins_with("tree") or kind.begins_with("pine") or kind.begins_with("cactus") or kind in ["flag", "sign", "snowman", "palm", "rocket"] else GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	return r


# ------------------------------------------------------------------ partner creatures
## A four-legged partner (water turtle, grass dino, fire lizard), stage 0-2.
## Built facing +Z; each stage is bigger and adds features.
static func _critter(b: Node3D, p: Dictionary, line: String, stage: int) -> void:
	var BODIES := {"water": ["8fd3ff", "5fb8f5", "3d7fd9"], "grass": ["8fdc6a", "5cc05a", "3a9d5d"], "fire": ["ff8a4a", "e85a3a", "b83a3a"]}
	var BELLY := {"water": "e6f6ff", "grass": "e6f5b0", "fire": "ffd9a0"}
	var C := Color(BODIES[line][stage])
	var BL := Color(BELLY[line])
	var inner := piv(b, Vector3.ZERO)
	inner.scale = Vector3.ONE * [1.0, 1.22, 1.5][stage]
	p["inner"] = inner
	var core := piv(inner, Vector3(0, 0.36, 0))
	p["core"] = core
	part(core, sph(0.3), Vector3.ZERO, C, Vector3(1.0, 0.82, 1.3))
	part(core, sph(0.22), Vector3(0, -0.1, 0.06), BL, Vector3(1.0, 0.6, 1.3), Vector3.ZERO, false)
	# legs: front pair is legL/legR, back pair backL/backR
	for leg in [["legL", -1, 1], ["legR", 1, 1], ["backL", -1, -1], ["backR", 1, -1]]:
		var lp := piv(inner, Vector3(leg[1] * 0.17, 0.26, leg[2] * 0.2))
		part(lp, cap(0.085, 0.24), Vector3(0, -0.13, 0), C)
		p[leg[0]] = lp
	# tail
	var tail := piv(core, Vector3(0, 0.0, -0.36))
	p["tail"] = tail
	if line == "fire":
		part(tail, cyl(0.03, 0.08, 0.4, 8), Vector3(0, 0.12, -0.12), C, Vector3.ONE, Vector3(-0.9, 0, 0))
		if stage >= 1:
			_flame(tail, Vector3(0, 0.3, -0.28), 0.14 + stage * 0.05)
	else:
		part(tail, cyl(0.0, 0.08, 0.22, 8), Vector3(0, 0, -0.08), C, Vector3.ONE, Vector3(-PI / 2 - 0.3, 0, 0))
	# back features
	match line:
		"water":
			var SH := Color(["2fb5a8", "1f8f9c", "1d5f8a"][stage])
			part(core, hemi(0.33), Vector3(0, 0.05, -0.02), SH, Vector3(1.05, 0.95, 1.35))
			for k in 3:
				part(core, sph(0.07), Vector3((k - 1) * 0.14, 0.27, -0.02 + (k % 2) * 0.1), Color("7fe0d0"), Vector3(1, 0.4, 1), Vector3.ZERO, false)
			if stage == 2:
				for sx in [-0.12, 0.12]:
					part(core, cyl(0.05, 0.06, 0.16, 10), Vector3(sx, 0.34, -0.05), Color("9aa6b2"))
					part(core, sph(0.05), Vector3(sx, 0.48, -0.05), Color("7fd0ff"), Vector3.ONE, Vector3.ZERO, false, 0.6)
					part(core, sph(0.035), Vector3(sx, 0.58, -0.07), Color("7fd0ff"), Vector3.ONE, Vector3.ZERO, false, 0.6)
		"grass":
			for k in [0, 3, 5][stage]:
				_leaf(core, Vector3(0, 0.22, -0.2 + k * 0.1), 0.12 + stage * 0.04, -0.3 + k * 0.12)
			for sp in [Vector3(-0.15, 0.12, 0.05), Vector3(0.18, 0.08, -0.1), Vector3(0.05, 0.2, 0.2)]:
				part(core, sph(0.04), sp, Color("4f9d3e"), Vector3(1, 0.5, 1), Vector3.ZERO, false)
		"fire":
			if stage >= 1:
				for k in 1 + stage * 2:
					_flame(core, Vector3(0, 0.25, -0.22 + k * (0.44 / maxi(1, stage * 2))), 0.08 + stage * 0.03)
			if stage == 2:
				for sx in [-1, 1]:
					var pm := PrismMesh.new()
					pm.size = Vector3(0.36, 0.3, 0.03)
					part(core, pm, Vector3(sx * 0.3, 0.05, -0.05), Color("ff9a3c"), Vector3.ONE, Vector3(0, PI / 2, sx * 1.1))
	# head
	var head := piv(inner, Vector3(0, 0.6, 0.34))
	p["head"] = head
	part(head, sph(0.24), Vector3.ZERO, C)
	part(head, sph(0.13), Vector3(0, -0.08, 0.17), BL, Vector3(1.2, 0.8, 0.8), Vector3.ZERO, false)
	eyes(head, 0.04, 0.19, 0.1, 0.07)
	match line:
		"water":
			if stage == 0:
				part(head, cyl(0.0, 0.06, 0.16, 10), Vector3(0, 0.29, 0), Color("5ac8ff"))
			elif stage == 1:
				var pm := PrismMesh.new()
				pm.size = Vector3(0.05, 0.22, 0.28)
				part(head, pm, Vector3(0, 0.3, -0.05), Color("e6f6ff"), Vector3.ONE, Vector3(-0.3, 0, 0))
		"grass":
			if stage == 0:
				part(head, cyl(0.012, 0.012, 0.12, 5), Vector3(0, 0.28, 0), Color("2f7a3a"), Vector3.ONE, Vector3.ZERO, false)
				_leaf(head, Vector3(0, 0.34, 0), 0.12, 0.6)
			elif stage == 1:
				_leaf(head, Vector3(-0.04, 0.24, 0), 0.13, -0.6)
				_leaf(head, Vector3(0.04, 0.24, 0), 0.13, 0.6)
				part(head, sph(0.05), Vector3(0, 0.29, 0), Color("ff8fc0"))
			else:
				for k in 5:
					var a := k * TAU / 5
					part(head, sph(0.075), Vector3(cos(a) * 0.1, 0.3, sin(a) * 0.1), Color("ff7fb5"), Vector3(1, 0.5, 1))
				part(head, sph(0.05), Vector3(0, 0.33, 0), Color("ffd23a"))
		"fire":
			if stage < 2:
				for k in 3:
					_flame(head, Vector3((k - 1) * 0.08, 0.2, -0.02), 0.07 + stage * 0.03 + (0.02 if k == 1 else 0.0))
			else:
				for k in 7:
					var a := PI * (0.0 + k / 6.0)
					_flame(head, Vector3(cos(a) * 0.22, 0.05 + sin(a) * 0.2, -0.08), 0.1)


static func _flame(parent: Node3D, pos: Vector3, h: float) -> void:
	part(parent, cyl(0.0, h * 0.45, h, 8), pos + Vector3(0, h * 0.5, 0), Color("ffb02e"), Vector3.ONE, Vector3.ZERO, true, 0.9)
	part(parent, cyl(0.0, h * 0.25, h * 0.6, 6), pos + Vector3(0, h * 0.4, 0.02), Color("ffe36a"), Vector3.ONE, Vector3.ZERO, false, 1.2)


static func _leaf(parent: Node3D, pos: Vector3, size: float, tilt: float) -> void:
	part(parent, sph(size), pos + Vector3(sin(tilt) * size * 0.6, size * 0.8, 0), Color("4fb35a"), Vector3(0.25, 1.0, 0.55), Vector3(0, 0, -tilt))


static func _pose_critter(p: Dictionary, anim: String, t: float) -> void:
	var b: Node3D = p.body
	for k in ["backL", "backR"]:
		p[k].rotation = Vector3.ZERO
	p.tail.rotation = Vector3.ZERO
	p.head.position.z = 0.34
	match anim:
		"run":
			var s := sin(t * 13)
			p.legL.rotation.x = s * 0.7
			p.backR.rotation.x = s * 0.7
			p.legR.rotation.x = -s * 0.7
			p.backL.rotation.x = -s * 0.7
			b.position.y = absf(cos(t * 13)) * 0.06
			p.tail.rotation.y = sin(t * 13) * 0.3
		"jump", "fall":
			p.legL.rotation.x = -0.7
			p.legR.rotation.x = -0.7
			p.backL.rotation.x = 0.7
			p.backR.rotation.x = 0.7
			p.core.rotation.x = -0.15
			return
		"zap", "hurt":
			p.head.position.z = 0.34 + absf(sin(t * 18)) * 0.08
			p.core.scale = Vector3.ONE * (1.0 + sin(t * 30) * 0.04)
			return
		"win":
			b.position.y = absf(sin(t * 7)) * 0.3
			p.tail.rotation.y = sin(t * 14) * 0.5
		_:
			b.position.y = sin(t * 3) * 0.01
			p.tail.rotation.y = sin(t * 4) * 0.35
			p.head.rotation.y = sin(t * 0.9) * 0.2
	p.core.rotation.x = 0.0
	p.core.scale = Vector3.ONE


static func _penguin(b: Node3D, p: Dictionary) -> void:
	var BLK := Color("2b3550")
	for s in [-1, 1]:
		var foot := piv(b, Vector3(s * 0.14, 0.04, 0.1))
		part(foot, sph(0.09), Vector3.ZERO, Color("ff9a2a"), Vector3(1, 0.5, 1.6))
		p["legL" if s < 0 else "legR"] = foot
	var core := piv(b, Vector3(0, 0.0, 0))
	p["core"] = core
	part(core, sph(0.36), Vector3(0, 0.45, 0), BLK, Vector3(1, 1.15, 0.95))
	part(core, sph(0.27), Vector3(0, 0.42, 0.14), Color("f4f8ff"), Vector3(1, 1.2, 0.8), Vector3.ZERO, false)
	for s in [-1, 1]:
		part(core, sph(0.12), Vector3(s * 0.36, 0.48, 0), BLK, Vector3(0.45, 1.3, 0.8), Vector3(0, 0, s * 0.3))
	part(core, sph(0.24), Vector3(0, 0.92, 0.02), BLK)
	part(core, sph(0.15), Vector3(0, 0.9, 0.14), Color("f4f8ff"), Vector3(1.1, 0.9, 0.7), Vector3.ZERO, false)
	eyes(core, 0.96, 0.2, 0.08, 0.055)
	part(core, cyl(0.0, 0.06, 0.16, 6), Vector3(0, 0.88, 0.28), Color("ff9a2a"), Vector3.ONE, Vector3(PI / 2, 0, 0))
	part(core, hemi(0.2), Vector3(0, 1.08, 0), Color("e8413b"), Vector3(1, 0.9, 1))
	part(core, sph(0.06), Vector3(0, 1.26, 0), Color("f4f8ff"))


static func _scorpion(b: Node3D, p: Dictionary) -> void:
	var BODY := Color("d88a3a")
	var core := piv(b, Vector3(0, 0.2, 0))
	p["core"] = core
	part(core, sph(0.32), Vector3.ZERO, BODY, Vector3(0.9, 0.5, 1.3))
	for i in 3:
		part(core, box(Vector3(0.5, 0.03, 0.04)), Vector3(0, 0.14, -0.2 + i * 0.16), Color("a8602a"), Vector3.ONE, Vector3.ZERO, false)
	eyes(core, 0.12, 0.38, 0.07, 0.05)
	for s in [-1, 1]:   # claws
		part(core, sph(0.1), Vector3(s * 0.2, 0.0, 0.52), BODY, Vector3(0.9, 0.6, 1.4))
		part(core, cyl(0.0, 0.05, 0.14, 5), Vector3(s * 0.2, 0.0, 0.66), Color("a8602a"), Vector3.ONE, Vector3(PI / 2, 0, 0))
	var legs := []
	for s in [-1, 1]:
		var lp := piv(core, Vector3.ZERO)
		for i in 3:
			part(lp, cap(0.03, 0.34), Vector3(s * 0.3, -0.1, -0.15 + i * 0.15), Color("a8602a"), Vector3.ONE, Vector3(0, 0, s * 1.0))
		legs.append(lp)
	p["legL"] = legs[0]
	p["legR"] = legs[1]
	var tail := piv(core, Vector3(0, 0.05, -0.38))
	p["tail"] = tail
	for i in 4:
		var a := i * 0.5
		part(tail, sph(0.09 - i * 0.01), Vector3(0, sin(a) * 0.32 + i * 0.05, -cos(a) * 0.1 * i), BODY)
	part(tail, cyl(0.0, 0.06, 0.18, 6), Vector3(0, 0.5, 0.12), Color("5a2a14"), Vector3.ONE, Vector3(1.6, 0, 0))
