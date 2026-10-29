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


func _process(dt: float) -> void:
	shake = maxf(0, shake - dt * 2.5)
