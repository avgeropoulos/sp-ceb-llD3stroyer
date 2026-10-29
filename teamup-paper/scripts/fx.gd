class_name Fx
extends Node3D
## Bits of juice: little cube bursts, floating words, lightning bolts and the screen shake.

var shake := 0.0
var _cube: BoxMesh
var _mats := {}


func _ready() -> void:
	_cube = BoxMesh.new()
	_cube.size = Vector3(0.16, 0.16, 0.16)


func _mat(c: Color) -> StandardMaterial3D:
	var k := c.to_html()
	if not _mats.has(k):
		var m := StandardMaterial3D.new()
		m.albedo_color = c
		m.roughness = 1.0
		_mats[k] = m
	return _mats[k]


## A puff of little cubes (block crumbs, sparkles, dust).
func burst(pos: Vector3, color: Color, n: int = 10, speed: float = 4.0, gravity: float = 18.0, size: float = 1.0) -> void:
	var p := CPUParticles3D.new()
	p.mesh = _cube
	p.material_override = _mat(color)
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = n
	p.lifetime = 0.7
	p.direction = Vector3.UP
	p.spread = 80
	p.initial_velocity_min = speed * 0.5
	p.initial_velocity_max = speed
	p.gravity = Vector3(0, -gravity, 0)
	p.angular_velocity_min = -400
	p.angular_velocity_max = 400
	p.scale_amount_min = 0.6 * size
	p.scale_amount_max = 1.3 * size
	p.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	p.position = pos
	add_child(p)
	p.emitting = true
	get_tree().create_timer(1.2).timeout.connect(p.queue_free)


## Words that pop up and float away ("+1 IRON", "WOOF!").
func text(pos: Vector3, s: String, color: Color = Color.WHITE, size: int = 64, life: float = 0.9) -> void:
	var l := Label3D.new()
	l.text = s
	l.font_size = size
	l.outline_size = maxi(10, size / 5)
	l.outline_modulate = Color(0.13, 0.09, 0.18)
	l.modulate = color
	l.pixel_size = 0.006
	l.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	l.no_depth_test = true
	l.render_priority = 5
	l.position = pos
	add_child(l)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(l, "position", pos + Vector3(0, 1.1, 0), life).set_ease(Tween.EASE_OUT).set_trans(Tween.TRANS_CUBIC)
	tw.tween_property(l, "modulate:a", 0.0, life * 0.4).set_delay(life * 0.6)
	tw.chain().tween_callback(l.queue_free)


## A thunderbolt crashing down onto a spot (Sparky's SUPER).
func bolt(pos: Vector3) -> void:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(0.6, 0.9, 1.0)
	var root := Node3D.new()
	root.position = pos
	add_child(root)
	var y := 12.0
	var x := 0.0
	while y > 0:
		var seg := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.22, 1.6, 0.22)
		seg.mesh = bm
		seg.material_override = m
		var nx := x + randf_range(-0.7, 0.7)
		seg.position = Vector3((x + nx) * 0.5, y - 0.8, 0)
		seg.rotation.z = atan2(nx - x, 1.6)
		root.add_child(seg)
		x = nx
		y -= 1.5
	# (no real light here: a new kind of light makes phones rebuild every material = a freeze)
	burst(pos + Vector3(0, 0.3, 0), Color(0.55, 0.85, 1.0), 12, 6)
	var tw := create_tween()
	tw.tween_property(root, "scale", Vector3(1.6, 1, 1.6), 0.08)
	tw.tween_property(root, "scale", Vector3(0.1, 1, 0.1), 0.25)
	tw.tween_callback(root.queue_free)


func _glow(c: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = c
	return m


## HYDRO WAVE: two big waves roll out both ways from the partner.
func wave(at: Vector3, _lv) -> void:
	for dir in [-1.0, 1.0]:
		var w := Node3D.new()
		w.position = at
		add_child(w)
		# a rounded wall of water with a curling white crest on top, leaning forward
		var body := MeshInstance3D.new()
		var cm := CapsuleMesh.new()
		cm.radius = 0.55
		cm.height = 2.6
		body.mesh = cm
		body.material_override = _glow(Color("4aa8f5"))
		body.scale = Vector3(1, 1, 3.0)
		body.position = Vector3(0, 0.9, 0)
		body.rotation.z = dir * 0.25
		w.add_child(body)
		var crest := MeshInstance3D.new()
		var cc := CylinderMesh.new()
		cc.top_radius = 0.42
		cc.bottom_radius = 0.42
		cc.height = 6.6
		crest.mesh = cc
		crest.material_override = _glow(Color("dff3ff"))
		crest.rotation.x = PI / 2
		crest.position = Vector3(dir * 0.45, 2.1, 0)
		w.add_child(crest)
		var foam := MeshInstance3D.new()
		var fc := CylinderMesh.new()
		fc.top_radius = 0.3
		fc.bottom_radius = 0.3
		fc.height = 6.4
		foam.mesh = fc
		foam.material_override = _glow(Color("8fd0ff"))
		foam.rotation.x = PI / 2
		foam.position = Vector3(dir * 0.85, 1.75, 0)
		w.add_child(foam)
		var tw := create_tween()
		tw.tween_property(w, "position:x", at.x + dir * 14.0, 0.9).set_ease(Tween.EASE_OUT)
		tw.parallel().tween_property(w, "scale", Vector3(1, 0.4, 1), 0.9)
		tw.tween_callback(w.queue_free)
	burst(at + Vector3(0, 0.5, 0), Color("7fd0ff"), 16, 6, 10)


## SOLAR BEAM: a huge beam shoots out in front.
func beam(from: Vector3, dir: float, length: float) -> void:
	var b := Node3D.new()
	b.position = from
	add_child(b)
	for k in 2:
		var mi := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.55 - k * 0.3
		cm.bottom_radius = cm.top_radius
		cm.height = 1.0
		mi.mesh = cm
		mi.material_override = _glow(Color("b8ff6a") if k == 0 else Color("ffffd0"))
		mi.rotation.z = PI / 2
		mi.position.x = dir * 0.5
		b.add_child(mi)
	b.scale = Vector3(0.01, 1, 1)
	var tw := create_tween()
	tw.tween_property(b, "scale:x", length, 0.25)
	tw.parallel().tween_property(b, "position:x", from.x + dir * 0.0, 0.25)
	tw.tween_interval(0.5)
	tw.tween_property(b, "scale", Vector3(length, 0.05, 0.05), 0.3)
	tw.tween_callback(b.queue_free)
	if dir < 0:
		b.rotation.y = PI
	burst(from, Color("d8ff8a"), 14, 5, 2)


## FIRE SPIN: a ring of fire grows all around.
func ring(at: Vector3, radius: float) -> void:
	var r := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 0.8
	tm.outer_radius = 1.0
	r.mesh = tm
	r.material_override = _glow(Color("ff8a2a"))
	r.position = at + Vector3(0, 0.5, 0)
	add_child(r)
	r.scale = Vector3(0.3, 1.5, 0.3)
	var tw := create_tween()
	tw.tween_property(r, "scale", Vector3(radius, 3.0, radius), 0.5).set_ease(Tween.EASE_OUT)
	tw.tween_property(r, "scale", Vector3(radius * 1.1, 0.1, radius * 1.1), 0.3)
	tw.tween_callback(r.queue_free)
	for i in 10:
		var a := i * TAU / 10
		burst(at + Vector3(cos(a) * radius * 0.6, 0.5, sin(a) * radius * 0.6), Color("ffb02e"), 5, 4, 2)


## VINE WHIP: a green vine lashes out and back.
func vine(from: Vector3, dir: Vector3, reach: float) -> void:
	var v := Node3D.new()
	v.position = from
	add_child(v)
	var n := 6
	for k in n:
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(0.12, 0.12, 0.12)
		mi.mesh = bm
		mi.material_override = _glow(Color("3fae4a") if k < n - 1 else Color("7fe05a"))
		mi.position = dir * (reach * (k + 1) / n) + Vector3(0, sin(k * 1.3) * 0.12, 0)
		v.add_child(mi)
	v.scale = Vector3.ONE * 0.05
	var tw := create_tween()
	tw.tween_property(v, "scale", Vector3.ONE, 0.1)
	tw.tween_interval(0.08)
	tw.tween_property(v, "scale", Vector3.ONE * 0.05, 0.12)
	tw.tween_callback(v.queue_free)


func _process(dt: float) -> void:
	shake = maxf(0, shake - dt * 2.5)
