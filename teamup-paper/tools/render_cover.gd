extends SceneTree
## Renders the app cover pictures from the real 3D toy models (needs a display):
##   xvfb-run godot --path . --script res://tools/render_cover.gd -- <out_dir>
## Writes <out_dir>/cover_scene.png (the whole team in the meadow, 1920x1080),
## <out_dir>/icon_fg.png (Rocco jumping for a star, transparent, 1024x1024) and
## <out_dir>/icon_bg.png (sky and grass, 1024x1024). tools/make_cover.py turns them
## into the app icon, the Android adaptive icon layers and the splash screen.

var out := "."
var vp: SubViewport
var cam: Camera3D
var holder: Node3D
var env: Environment
var jobs := ["cover_scene", "icon_bg", "icon_fg"]
var i := -1
var wait := 0
var t := 0.0
var actors := []


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() > 0:
		out = args[0]
	vp = SubViewport.new()
	vp.own_world_3d = true
	vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	vp.msaa_3d = Viewport.MSAA_4X
	root.add_child(vp)
	var we := WorldEnvironment.new()
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color("3d8fe6")
	sm.sky_horizon_color = Color("bfe6ff")
	sm.ground_horizon_color = Color("bfe6ff")
	sm.ground_bottom_color = Color("5aa64a")
	sky.sky_material = sm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("e4eefa")
	env.ambient_light_energy = 0.7
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.glow_enabled = true
	env.glow_intensity = 0.6
	we.environment = env
	vp.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-40, -35, 0)
	sun.light_energy = 1.25
	sun.shadow_enabled = true
	vp.add_child(sun)
	cam = Camera3D.new()
	vp.add_child(cam)
	cam.current = true
	holder = Node3D.new()
	vp.add_child(holder)


func _clear() -> void:
	for c in holder.get_children():
		c.queue_free()
	actors.clear()


## A toy character standing at `pos`, facing the camera a little, in a pose.
func _toy(kind: String, pos: Vector3, scale: float, anim: String, at: float, turn: float, vy := 0.0) -> void:
	var toy := Toys.build(kind)
	var a := Actor.new()
	a.sprite = Sprite3D.new()
	a.model = toy
	a.facing = Vector3(0, 0, 1)
	a.anim = anim
	a.anim_t = at
	a.vel = Vector3(0, vy, 0)
	holder.add_child(a)
	a.add_child(toy)
	a.position = pos
	a.scale = Vector3.ONE * scale
	Actor.view_back = Vector3.ZERO
	Toys.pose(a, 0.0)
	toy.rotation.y = turn
	actors.append([a, turn])


func _block(pos: Vector3, size: Vector3, col: Color, top: Color) -> void:
	var m := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	m.mesh = bm
	m.material_override = Toys.mat(col)
	m.position = pos
	holder.add_child(m)
	var tm := MeshInstance3D.new()
	var tb := BoxMesh.new()
	tb.size = Vector3(size.x + 0.02, 0.22, size.z + 0.02)
	tm.mesh = tb
	tm.material_override = Toys.mat(top)
	tm.position = pos + Vector3(0, size.y * 0.5 - 0.1, 0)
	holder.add_child(tm)


func _star(pos: Vector3, s: float) -> void:
	var st := OpenPlay.star_model()
	st.position = pos
	st.scale = Vector3.ONE * s
	st.rotation.y = 0.25
	holder.add_child(st)


func _qblock(pos: Vector3) -> void:
	var m := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3.ONE
	m.mesh = bm
	m.material_override = Toys.mat(Color("2cbec8"))
	m.position = pos
	m.rotation.y = 0.5
	holder.add_child(m)
	var l := Label3D.new()
	l.text = "?"
	l.font_size = 96
	l.outline_size = 0
	l.pixel_size = 0.008
	l.position = pos + Vector3(0, -0.05, 0.51).rotated(Vector3.UP, 0.5)
	l.rotation.y = 0.5
	holder.add_child(l)


func _ground(w: float, d: float) -> void:
	for x in range(-int(w), int(w) + 1):
		for z in range(-int(d), 3):
			var h := 0.0 if absi(x) < 4 or z > -3 else float((absi(x * 7 + z * 3) % 3)) * 0.5
			_block(Vector3(x, -0.5 + h, z), Vector3(1, 1, 1), Color("96643f"), Color("60be50").lightened(randf() * 0.12))


func _process(dt: float) -> bool:
	t += dt
	if wait > 0:
		wait -= 1
		if wait == 0:
			var img := vp.get_texture().get_image()
			img.save_png("%s/%s.png" % [out, jobs[i]])
			print("rendered ", jobs[i])
		return false
	i += 1
	if i >= jobs.size():
		return true
	_clear()
	seed(7)
	match jobs[i]:
		"cover_scene":   # the whole team in the meadow, King Krag peeking from behind
			vp.size = Vector2i(1920, 1080)
			vp.transparent_bg = false
			env.background_mode = Environment.BG_SKY
			_ground(14, 10)
			_toy("rocco", Vector3(0, 1.1, 0.4), 1.25, "jump", 0.1, 0.15, 6.0)
			_toy("blocky", Vector3(-2.1, 0, -0.2), 1.15, "win", 0.35, 0.45)
			_toy("mon:fire:2", Vector3(2.4, 0, -0.3), 1.0, "idle", 0.3, -0.5)
			_toy("sparky:1", Vector3(-3.7, 0, 0.6), 1.0, "idle", 0.2, 0.6)
			_toy("mon:water:1", Vector3(3.9, 0, 0.9), 1.0, "idle", 0.5, -0.7)
			_toy("kingkrag", Vector3(-5.5, 0.5, -6.0), 1.0, "idle", 0.4, 0.5)
			_star(Vector3(0.2, 4.2, 0.1), 1.3)
			_qblock(Vector3(-6.2, 3.0, -3.0))
			_qblock(Vector3(6.4, 3.4, -3.0))
			cam.fov = 50
			cam.look_at_from_position(Vector3(0.6, 3.4, 9.6), Vector3(0, 3.6, 0))   # sky on top for the title
		"icon_bg":   # sky and a grass hill for the icon background
			vp.size = Vector2i(1024, 1024)
			vp.transparent_bg = false
			env.background_mode = Environment.BG_SKY
			_ground(10, 8)
			cam.fov = 50
			cam.look_at_from_position(Vector3(0, 2.0, 6.0), Vector3(0, 1.4, 0))
		"icon_fg":   # Rocco jumping for the star (transparent, so the launcher can move it)
			vp.size = Vector2i(1024, 1024)
			vp.transparent_bg = true
			env.background_mode = Environment.BG_CLEAR_COLOR
			env.background_color = Color(0, 0, 0, 0)
			_toy("rocco", Vector3(0, 0, 0), 1.0, "jump", 0.1, 0.25, 6.0)
			_star(Vector3(0.55, 1.95, 0.2), 0.55)
			cam.fov = 30
			cam.look_at_from_position(Vector3(0.5, 1.4, 5.2), Vector3(0.2, 1.05, 0))
	wait = 8
	return false
