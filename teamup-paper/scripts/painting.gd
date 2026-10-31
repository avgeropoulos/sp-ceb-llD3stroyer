class_name Painting
extends Node3D
## A world's SECRET PAINTING (Mario 64 style): a picture of the world in a gold frame
## on the castle's west wall. It ripples like water when you come close; JUMP into it
## and you're pulled into its BONUS COURSE (Bonus, Level.generate_bonus).

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
	float wave = sin(d * 38.0 - t * 7.0) * (0.006 + near * 0.012 + splash * 0.05);
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


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	info = lv.painting
	position = info.pos
	var w: float = info.w
	var h: float = info.h
	var q := MeshInstance3D.new()
	var qm := QuadMesh.new()
	qm.size = Vector2(w, h)
	q.mesh = qm
	q.rotation.y = -PI / 2   # faces west (-x), out from the castle wall
	mat = ShaderMaterial.new()
	var sh := Shader.new()
	sh.code = RIPPLE
	mat.shader = sh
	mat.set_shader_parameter("pic", picture(lv.world))
	q.material_override = mat
	q.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(q)
	# a chunky gold frame
	var gold := Toys.mat(Color("e8b030"), true, 0.4)
	var fr := 0.18
	for part in [[Vector3(0, h * 0.5 + fr * 0.5, 0), Vector3(fr, fr, w + fr * 2)], [Vector3(0, -h * 0.5 - fr * 0.5, 0), Vector3(fr, fr, w + fr * 2)],
			[Vector3(0, 0, w * 0.5 + fr * 0.5), Vector3(fr, h, fr)], [Vector3(0, 0, -w * 0.5 - fr * 0.5), Vector3(fr, h, fr)]]:
		var b := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = part[1]
		b.mesh = bm
		b.material_override = gold
		b.position = part[0] + Vector3(-0.06, 0, 0)
		add_child(b)


## The painting's picture, drawn from the world's own colours: its sky, hills in the
## world's ground colour, a sun, and a big star in the middle.
static func picture(w: String) -> ImageTexture:
	var wi: Dictionary = Worlds.info(w)
	var W := 96
	var H := 84
	var img := Image.create(W, H, false, Image.FORMAT_RGB8)
	var sky_top := Color(wi.sky[0])
	var sky_low := Color(wi.sky[1])
	var ground := Color(wi.get("cloud", "ffffff")) if w == "cloud" else Color(wi.get("fog", "9ad06a"))
	var g2 := {"meadow": Color("5fb44a"), "frost": Color("eef4fc"), "dunes": Color("e8c47e"), "swamp": Color("4a5a3a"),
		"moon": Color("b8bcc8"), "lava": Color("5a2a22"), "candy": Color("ff9ad0"), "beach": Color("ecce8c"),
		"jungle": Color("2e8a3a"), "cloud": Color("ffffff")}.get(w, Color("5fb44a")) as Color
	for y in H:
		for x in W:
			var k := float(y) / H
			var c := sky_top.lerp(sky_low, k)
			var hill := H * 0.62 + sin(x * 0.09) * 6 + sin(x * 0.23 + 1.0) * 3
			var hill2 := H * 0.74 + sin(x * 0.14 + 2.0) * 4
			if y > hill:
				c = g2.darkened(0.18).lerp(ground, 0.15)
			if y > hill2:
				c = g2
			img.set_pixel(x, y, c)
	# a sun
	for y in range(6, 26):
		for x in range(70, 90):
			if Vector2(x - 80, y - 16).length() < 8:
				img.set_pixel(x, y, Color("fff3a0"))
	# the star
	var pts := PackedVector2Array()
	for i in 10:
		var a := -PI / 2 + TAU * i / 10.0
		var r := 22.0 if i % 2 == 0 else 9.5
		pts.append(Vector2(48 + cos(a) * r, 40 + sin(a) * r))
	for y in H:
		for x in W:
			if Geometry2D.is_point_in_polygon(Vector2(x, y), pts):
				var edge := false
				for o in [Vector2(2, 0), Vector2(-2, 0), Vector2(0, 2), Vector2(0, -2)]:
					if not Geometry2D.is_point_in_polygon(Vector2(x, y) + o, pts):
						edge = true
				img.set_pixel(x, y, Color("3a2408") if edge else Color("ffd23a"))
	return ImageTexture.create_from_image(img)


## True while the hero's body is touching the picture in the air (the way in).
func touching(h: Actor) -> bool:
	if h.on_floor:
		return false
	var x0: float = info.x
	var zc: float = info.z
	return h.position.x > x0 - h.hw - 0.3 and h.position.x < x0 + 0.1 \
		and absf(h.position.z - zc) < info.w * 0.5 + 0.1 \
		and h.position.y + h.height > info.bottom + 0.25 and h.position.y < info.bottom + info.h


func step(dt: float) -> void:
	t += dt
	var l: Hero = game.leader()
	var d := Vector2(l.position.x - info.x, l.position.z - info.z).length()
	near = lerpf(near, 1.0 if d < 5.0 else 0.0, 1 - exp(-dt * 4))
	splash = maxf(0.0, splash - dt * 1.2)
	mat.set_shader_parameter("t", t)
	mat.set_shader_parameter("near", near)
	mat.set_shader_parameter("splash", splash)
	if entering >= 0:
		entering += dt
		l.vel = Vector3.ZERO
		var into := Vector3(info.x + 0.6, info.pos.y - 0.6, info.z)
		l.position = l.position.lerp(into, 1 - exp(-dt * 6))
		l.scale = l.scale.lerp(Vector3.ONE * 0.2, 1 - exp(-dt * 5))
		game.hurt_t = 1.0
		if entering > 0.8:
			entering = -100.0
			game.enter_painting()
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
	game.fx.burst(Vector3(info.x - 0.2, info.pos.y, info.z), Color("bfeeff"), 24, 5, 6)
	game.hud.say("WHOOSH!", "Into the painting!", 1.2)
