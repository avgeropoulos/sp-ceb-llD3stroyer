class_name Painting
extends Node3D
## A GIANT PAINTING in an open world (Mario 64 style): a big picture in a thick gold
## frame. Each world has 3 (Level.world_paintings): one on the castle's west wall and two
## out in the world on their own walls. It ripples like water when you come close; JUMP
## into it and you're pulled into its BONUS COURSE (Bonus, Level.generate_bonus): SKY
## CLIMB, SECRET SLIDE, RED COIN MAZE or BIG BRAWL. Once you have its star it sparkles.

const RIPPLE := """
shader_type spatial;
render_mode unshaded, cull_disabled;
uniform sampler2D pic : source_color, filter_linear;
uniform float near = 0.0;
uniform float splash = 0.0;
uniform float t = 0.0;
void fragment() {
	vec2 uv = UV;
	vec2 c = uv - vec2(0.5);
	float d = length(c);
	float wave = sin(d * 38.0 - t * 7.0) * (0.004 + near * 0.010 + splash * 0.05);
	uv += normalize(c + vec2(0.0001)) * wave;
	vec3 col = texture(pic, uv).rgb;
	col += vec3(0.08) * near * (0.5 + 0.5 * sin(d * 38.0 - t * 7.0));
	ALBEDO = col;
}
"""

var game
var level: Level
var mat: ShaderMaterial
var near := 0.0
var splash := 0.0
var t := 0.0
var entering := -1.0         # >= 0 while you're being pulled in
var info: Dictionary
var label: Label3D
var sparkle_t := 0.0


func setup(g, lv: Level, inf: Dictionary = {}) -> void:
	game = g
	level = lv
	info = inf if not inf.is_empty() else lv.painting
	position = info.pos
	var n: Vector3 = info.get("normal", Vector3(-1, 0, 0))
	rotation.y = atan2(n.x, n.z)   # the picture faces out along n
	var w: float = info.w
	var h: float = info.h
	var q := MeshInstance3D.new()
	var qm := QuadMesh.new()
	qm.size = Vector2(w, h)
	q.mesh = qm
	mat = ShaderMaterial.new()
	var sh := Shader.new()
	sh.code = RIPPLE
	mat.shader = sh
	mat.set_shader_parameter("pic", picture(lv.world, String(info.get("kind", "sky"))))
	q.material_override = mat
	q.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(q)
	# a big, chunky gold frame with corner knobs
	var gold := Toys.mat(Color("e8b030"), true, 0.4)
	var fr := 0.42
	var dz := 0.12
	for part in [[Vector3(0, h * 0.5 + fr * 0.5, dz), Vector3(w + fr * 2, fr, 0.34)], [Vector3(0, -h * 0.5 - fr * 0.5, dz), Vector3(w + fr * 2, fr, 0.34)],
			[Vector3(w * 0.5 + fr * 0.5, 0, dz), Vector3(fr, h, 0.34)], [Vector3(-w * 0.5 - fr * 0.5, 0, dz), Vector3(fr, h, 0.34)]]:
		var b := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = part[1]
		b.mesh = bm
		b.material_override = gold
		b.position = part[0]
		add_child(b)
	for cx in [-1, 1]:
		for cy in [-1, 1]:
			var k := MeshInstance3D.new()
			var sm := SphereMesh.new()
			sm.radius = 0.32
			sm.height = 0.64
			k.mesh = sm
			k.material_override = gold
			k.position = Vector3(cx * (w * 0.5 + fr * 0.5), cy * (h * 0.5 + fr * 0.5), dz + 0.1)
			add_child(k)
	# its name on a gold plaque under the picture
	label = Label3D.new()
	label.text = Level.PAINT_NAMES.get(String(info.get("kind", "sky")), "PAINTING")
	label.font_size = 56
	label.outline_size = 12
	label.pixel_size = 0.007
	label.modulate = Color("fff2a8")
	label.outline_modulate = Color(0.3, 0.16, 0.02)
	label.position = Vector3(0, -h * 0.5 - fr - 0.05, dz + 0.25)
	add_child(label)


## The painting's picture, drawn from the world's own colours, showing its course:
## SKY CLIMB = platforms going up to a star, SECRET SLIDE = a slide zig-zagging down,
## RED COIN MAZE = a hedge maze with red coins, BIG BRAWL = the Big Grumbler.
static func picture(w: String, kind: String = "sky") -> ImageTexture:
	var wi: Dictionary = Worlds.info(w)
	var W := 120
	var H := 88
	var img := Image.create(W, H, false, Image.FORMAT_RGB8)
	var sky_top := Color(wi.sky[0])
	var sky_low := Color(wi.sky[1])
	var g2 := {"meadow": Color("5fb44a"), "frost": Color("eef4fc"), "dunes": Color("e8c47e"), "swamp": Color("4a5a3a"),
		"moon": Color("b8bcc8"), "lava": Color("5a2a22"), "candy": Color("ff9ad0"), "beach": Color("ecce8c"),
		"jungle": Color("2e8a3a"), "cloud": Color("ffffff")}.get(w, Color("5fb44a")) as Color
	for y in H:
		for x in W:
			var c := sky_top.lerp(sky_low, float(y) / H)
			if kind != "maze":
				var hill := H * 0.66 + sin(x * 0.09) * 6 + sin(x * 0.23 + 1.0) * 3
				if y > hill:
					c = g2.darkened(0.15)
				if y > H * 0.8 + sin(x * 0.14 + 2.0) * 3:
					c = g2
			img.set_pixel(x, y, c)
	var gold := Color("ffd23a")
	var ink := Color("3a2408")
	match kind:
		"sky":   # floating platforms stepping up to the star
			for i in 6:
				var px := 10 + i * 17
				var py := 74 - i * 9
				_box(img, px, py, 14, 4, g2.lightened(0.15) if i % 2 else Color("de423c"))
				_box(img, px, py + 4, 14, 3, g2.darkened(0.4))
			_star(img, Vector2(104, 18), 13.0, gold, ink)
		"slide":   # a slide zig-zagging down, with a speedy streak
			var pts := [Vector2(10, 12), Vector2(105, 30), Vector2(18, 50), Vector2(108, 74)]
			for i in pts.size() - 1:
				_line(img, pts[i], pts[i + 1], 7, Color("96d2f0"))
				_line(img, pts[i], pts[i + 1], 2, Color("eaf8ff"))
			_star(img, Vector2(98, 66), 9.0, gold, ink)
			for i in 4:
				_line(img, Vector2(30 + i * 6, 20 + i * 2), Vector2(44 + i * 6, 23 + i * 2), 1, Color.WHITE)
		"maze":  # a hedge maze from above with red coins
			var hedge := Color("2e7a32") if w in ["meadow", "jungle", "beach"] else g2.darkened(0.35)
			for y in range(8, H - 6):
				for x in range(10, W - 10):
					var cx := (x - 10) % 14
					var cy := (y - 8) % 14
					var wall := cx < 3 or cy < 3
					if wall and ((x * 7 + y * 3) / 14) % 3 == 0 and cx >= 3:
						wall = false
					img.set_pixel(x, y, hedge if wall else g2.lightened(0.25))
			for i in 8:
				var cxy := Vector2(24 + (i % 4) * 26, 22 + (i / 4) * 36)
				_disc(img, cxy, 4.5, Color("e8413b"), ink)
			_star(img, Vector2(60, 46), 9.0, gold, ink)
		"boss":  # the Big Grumbler with his crown
			_disc(img, Vector2(60, 52), 26.0, Color("8a5bc4"), ink)
			_disc(img, Vector2(50, 46), 6.0, Color.WHITE, ink)
			_disc(img, Vector2(70, 46), 6.0, Color.WHITE, ink)
			_disc(img, Vector2(51, 47), 2.5, ink, ink)
			_disc(img, Vector2(69, 47), 2.5, ink, ink)
			_line(img, Vector2(44, 38), Vector2(56, 42), 2, ink)
			_line(img, Vector2(76, 38), Vector2(64, 42), 2, ink)
			for i in 3:   # crown
				_line(img, Vector2(46 + i * 14, 28), Vector2(53 + i * 14, 16), 3, gold)
				_line(img, Vector2(53 + i * 14, 16), Vector2(60 + i * 14, 28), 3, gold)
			_box(img, 45, 26, 30, 5, gold)
			_star(img, Vector2(104, 16), 9.0, gold, ink)
	return ImageTexture.create_from_image(img)


static func _box(img: Image, x: int, y: int, w: int, h: int, c: Color) -> void:
	for yy in range(maxi(0, y), mini(img.get_height(), y + h)):
		for xx in range(maxi(0, x), mini(img.get_width(), x + w)):
			img.set_pixel(xx, yy, c)


static func _disc(img: Image, c: Vector2, r: float, col: Color, ink: Color) -> void:
	for y in range(int(c.y - r - 2), int(c.y + r + 3)):
		for x in range(int(c.x - r - 2), int(c.x + r + 3)):
			if x < 0 or y < 0 or x >= img.get_width() or y >= img.get_height():
				continue
			var d := Vector2(x, y).distance_to(c)
			if d <= r:
				img.set_pixel(x, y, col if d < r - 1.2 else ink)


static func _line(img: Image, a: Vector2, b: Vector2, th: int, c: Color) -> void:
	var n := int(a.distance_to(b)) + 1
	for i in n + 1:
		var p := a.lerp(b, float(i) / n)
		_box(img, int(p.x) - th / 2, int(p.y) - th / 2, th, th, c)


static func _star(img: Image, c: Vector2, r: float, col: Color, ink: Color) -> void:
	var pts := PackedVector2Array()
	for i in 10:
		var a := -PI / 2 + TAU * i / 10.0
		var rr := r if i % 2 == 0 else r * 0.43
		pts.append(c + Vector2(cos(a), sin(a)) * rr)
	for y in range(int(c.y - r - 1), int(c.y + r + 2)):
		for x in range(int(c.x - r - 1), int(c.x + r + 2)):
			if x < 0 or y < 0 or x >= img.get_width() or y >= img.get_height():
				continue
			if Geometry2D.is_point_in_polygon(Vector2(x, y), pts):
				var edge := false
				for o in [Vector2(1.5, 0), Vector2(-1.5, 0), Vector2(0, 1.5), Vector2(0, -1.5)]:
					if not Geometry2D.is_point_in_polygon(Vector2(x, y) + o, pts):
						edge = true
				img.set_pixel(x, y, ink if edge else col)


## True while the hero's body is touching the picture in the air (the way in).
func touching(h: Actor) -> bool:
	if h.on_floor:
		return false
	var n: Vector3 = info.get("normal", Vector3(-1, 0, 0))
	var tg: Vector3 = info.get("tangent", Vector3(0, 0, 1))
	var rel := h.position - Vector3(info.pos.x, h.position.y, info.pos.z)
	var front := rel.dot(n)         # how far in front of the picture
	var side := rel.dot(tg)
	return front > -0.1 and front < h.hw + 0.3 and absf(side) < info.w * 0.5 + 0.1 \
		and h.position.y + h.height > info.bottom + 0.25 and h.position.y < info.bottom + info.h


func step(dt: float) -> void:
	t += dt
	var l: Hero = game.leader()
	var d := Vector2(l.position.x - info.pos.x, l.position.z - info.pos.z).length()
	near = lerpf(near, 1.0 if d < 6.0 else 0.0, 1 - exp(-dt * 4))
	splash = maxf(0.0, splash - dt * 1.2)
	mat.set_shader_parameter("t", t)
	mat.set_shader_parameter("near", near)
	mat.set_shader_parameter("splash", splash)
	# its star is found: little sparkles round the frame now and then
	if game.open_play and game.open_play.got.has(String(info.get("id", "painting1"))):
		sparkle_t -= dt
		if sparkle_t <= 0 and d < 30:
			sparkle_t = 0.35
			var tg: Vector3 = info.get("tangent", Vector3(0, 0, 1))
			var p: Vector3 = info.pos + tg * randf_range(-info.w * 0.55, info.w * 0.55) + Vector3(0, randf_range(-info.h * 0.55, info.h * 0.55), 0)
			game.fx.burst(p + info.get("normal", Vector3.ZERO) * 0.3, Color("fff2a8"), 2, 1.2, 2, 0.7)
	if entering >= 0:
		entering += dt
		l.vel = Vector3.ZERO
		var n: Vector3 = info.get("normal", Vector3(-1, 0, 0))
		var into: Vector3 = info.pos - n * 0.6 + Vector3(0, -0.6, 0)
		l.position = l.position.lerp(into, 1 - exp(-dt * 6))
		l.scale = l.scale.lerp(Vector3.ONE * 0.2, 1 - exp(-dt * 5))
		game.hurt_t = 1.0
		if entering > 0.8:
			entering = -100.0
			game.enter_painting(int(info.get("idx", 1)))
		return
	if entering < -50:
		return
	if touching(l):
		jump_in()


func jump_in() -> void:
	entering = 0.0
	splash = 1.0
	game.sfx("item")
	game.sfx("jump2", -4)
	game.fx.burst(info.pos + info.get("normal", Vector3.ZERO) * 0.4, Color("bfeeff"), 24, 5, 6)
	game.hud.say("WHOOSH!", "Into the %s painting!" % Level.PAINT_NAMES.get(String(info.get("kind", "sky")), ""), 1.2)
