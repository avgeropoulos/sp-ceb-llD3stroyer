class_name Actor
extends Node3D
## Anything that walks around the block world: a paper cutout (Sprite3D) with a
## box for bumping into blocks and a soft round shadow on the ground.

const GRAVITY := 30.0

var level: Level
var vel := Vector3.ZERO
var hw := 0.3          # half width of the collision box
var height := 1.4
var on_floor := false
var facing := Vector3.RIGHT
var sprite: Sprite3D
var shadow: MeshInstance3D
var frames := {}
var anim := ""
var anim_t := 0.0
var hp := 1
var max_hp := 1
var alive := true
var flash_t := 0.0
var bar: Node3D
var bar_fill: MeshInstance3D
var sprite_h := 1.6
var walls_hit := false
var ceiling_hit = null
var landed := false
var squash := Vector2.ONE   # stretch/squash of the cutout (x, y)
var turn := 1.0             # -1..1: the paper flips around when you change direction
var turn_to := 1.0
var spin_t := 0.0
var model: Node3D          # the 3D toy (when Look.toy), else null

static var _shadow_mesh: QuadMesh
static var _shadow_mat: StandardMaterial3D


func setup(lv: Level, tex_names: Dictionary, size: float, feet_px: float = 18.0, toy_kind: String = "") -> void:
	level = lv
	sprite_h = size
	for k in tex_names:
		var list: Array[Texture2D] = []
		for n in tex_names[k]:
			list.append(load("res://art/" + n + ".png"))
		frames[k] = list
	sprite = Sprite3D.new()
	sprite.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	sprite.shaded = false
	sprite.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
	sprite.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	sprite.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	var first: Texture2D = frames.values()[0][0]
	sprite.texture = first
	sprite.pixel_size = size / first.get_height()
	sprite.position.y = (first.get_height() * 0.5 - feet_px) * sprite.pixel_size
	add_child(sprite)
	if Look.toy and toy_kind != "":
		model = Toys.build(toy_kind)
		add_child(model)
		sprite.visible = false
	if _shadow_mesh == null:
		_shadow_mesh = QuadMesh.new()
		_shadow_mesh.size = Vector2(1, 1)
		_shadow_mesh.orientation = PlaneMesh.FACE_Y
		_shadow_mat = StandardMaterial3D.new()
		_shadow_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_shadow_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		_shadow_mat.albedo_texture = _round_shadow_tex()
		_shadow_mat.albedo_color = Color(0.1, 0.12, 0.2, 0.45)
	shadow = MeshInstance3D.new()
	shadow.mesh = _shadow_mesh
	shadow.material_override = _shadow_mat
	shadow.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	lv.add_child(shadow)


static func _round_shadow_tex() -> Texture2D:
	var g := Gradient.new()
	g.set_color(0, Color(1, 1, 1, 1))
	g.set_color(1, Color(1, 1, 1, 0))
	g.add_point(0.55, Color(1, 1, 1, 0.85))
	var t := GradientTexture2D.new()
	t.gradient = g
	t.fill = GradientTexture2D.FILL_RADIAL
	t.fill_from = Vector2(0.5, 0.5)
	t.fill_to = Vector2(1.0, 0.5)
	t.width = 64
	t.height = 64
	return t


## A full paper twirl (double jump, butt slam).
func spin() -> void:
	spin_t = 0.35


func play(name: String) -> void:
	if anim != name:
		anim = name
		anim_t = 0.0


func animate(dt: float, fps: float = 8.0) -> void:
	anim_t += dt
	if model:
		if absf(facing.x) > 0.2:
			turn_to = -1.0 if facing.x < 0 else 1.0
		if spin_t > 0:
			spin_t -= dt
		if flash_t > 0:
			flash_t -= dt
		Toys.pose(self, dt)
		return
	if not frames.has(anim):
		return
	var list: Array = frames[anim]
	sprite.texture = list[int(anim_t * fps) % list.size()]
	if absf(facing.x) > 0.2:
		turn_to = -1.0 if facing.x < 0 else 1.0
	if spin_t > 0:
		spin_t -= dt
		turn = cos((0.35 - spin_t) / 0.35 * TAU) * turn_to
	else:
		turn = move_toward(turn, turn_to, dt * 9.0)
	var tx := turn if absf(turn) > 0.08 else 0.08 * signf(turn_to)
	sprite.scale = Vector3(tx * squash.x, squash.y, 1)
	if flash_t > 0:
		flash_t -= dt
		sprite.modulate = Color(1, 0.55, 0.55) if int(flash_t * 20) % 2 == 0 else Color(1, 1, 1, 0.75)
	else:
		sprite.modulate = Color.WHITE


## Moves through the block grid one axis at a time, stopping at blocks.
func move(dt: float) -> void:
	walls_hit = false
	ceiling_hit = null
	landed = false
	var p := position
	p.x += vel.x * dt
	var hit = level.box_hit(p, hw, height)
	if hit != null:
		p.x = (hit.x - hw - 0.001) if vel.x > 0 else (hit.x + 1 + hw + 0.001)
		vel.x = 0
		walls_hit = true
	p.z += vel.z * dt
	p.z = clampf(p.z, Level.WALK_Z0, Level.WALK_Z1)
	hit = level.box_hit(p, hw, height)
	if hit != null:
		p.z = (hit.z - hw - 0.001) if vel.z > 0 else (hit.z + 1 + hw + 0.001)
		vel.z = 0
		walls_hit = true
	p.y += vel.y * dt
	hit = level.box_hit(p, hw, height)
	if hit != null:
		if vel.y > 0:
			p.y = hit.y - height - 0.001
			ceiling_hit = hit
		else:
			p.y = hit.y + 1.0
			landed = true
		vel.y = 0
	position = p
	on_floor = landed or (vel.y <= 0 and level.box_hit(p - Vector3(0, 0.05, 0), hw * 0.9, 0.1) != null)


func update_shadow() -> void:
	var g := level.ground_below(position.x, position.z, position.y + 0.2)
	if g < -50 or not visible:
		shadow.visible = false
		return
	shadow.visible = true
	var d := clampf(position.y - g, 0, 6)
	var s := (hw * 2.6 + 0.2) * (1.0 - d * 0.1)
	shadow.position = Vector3(position.x, g + 0.03, position.z)
	shadow.scale = Vector3(s, 1, s * 0.75)
	shadow.transparency = clampf(d * 0.12, 0, 0.8)


func show_health() -> void:
	if bar == null:
		bar = Node3D.new()
		var mk := func(color: Color, w: float, z: float) -> MeshInstance3D:
			var q := QuadMesh.new()
			q.size = Vector2(w, 0.14)
			var m := StandardMaterial3D.new()
			m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
			m.albedo_color = color
			m.billboard_mode = BaseMaterial3D.BILLBOARD_FIXED_Y
			m.no_depth_test = true
			m.render_priority = 3 if z > 0 else 2
			var mi := MeshInstance3D.new()
			mi.mesh = q
			mi.material_override = m
			mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			bar.add_child(mi)
			return mi
		mk.call(Color(0.15, 0.1, 0.2), 0.9, 0.0)
		bar_fill = mk.call(Color("ff4f6d"), 0.84, 0.01)
		bar.position.y = sprite_h + 0.15
		add_child(bar)
	var f := clampf(float(hp) / max_hp, 0, 1)
	bar_fill.scale.x = maxf(f, 0.001)
	bar_fill.position.x = -0.42 * (1 - f)


func overlaps(other: Actor, pad: float = 0.0) -> bool:
	return absf(position.x - other.position.x) < hw + other.hw + pad \
		and absf(position.z - other.position.z) < hw + other.hw + pad + 0.25 \
		and position.y < other.position.y + other.height and other.position.y < position.y + height


func remove() -> void:
	alive = false
	if shadow:
		shadow.queue_free()
	queue_free()
