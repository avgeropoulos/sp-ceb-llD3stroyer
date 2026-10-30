extends SceneTree
## Renders every decoration's 3D model to art/decor_<id>.png (transparent), for the PAPER look
## and the DECOR palette icons, plus art/decor_<id>_mask.png for paintable ones (paint parts
## white and shaded, the rest black: the paper look colours those pixels).
## Then tools/sticker.py gives them the white sticker border (and crops the masks to match).
## Run (needs a display): xvfb-run godot --path . --script res://tools/render_decor.gd [-- id id ...]
## (names after -- render only those decorations, e.g. -- sofa bed)

var vp: SubViewport
var holder: Node3D
var i := -1
var wait := 0


func _initialize() -> void:
	vp = SubViewport.new()
	vp.size = Vector2i(320, 320)
	vp.transparent_bg = true
	vp.own_world_3d = true
	vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	vp.msaa_3d = Viewport.MSAA_4X
	root.add_child(vp)
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_CLEAR_COLOR
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color("dce8f5")
	e.ambient_light_energy = 0.6
	env.environment = e
	vp.add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-45, 150, 0)
	vp.add_child(sun)
	var cam := Camera3D.new()
	cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	cam.size = 2.4
	vp.add_child(cam)
	cam.look_at_from_position(Vector3(2.2, 2.0, 3.2), Vector3(0, 0.55, 0))
	cam.current = true
	holder = Node3D.new()
	vp.add_child(holder)


## Scenery and the rocket for the PAPER look (rendered from their 3D models too).
const PROPS := ["pine_a", "pine_b", "snowman", "snowpile", "cactus_a", "cactus_b", "palm", "desert_rock", "rocket"]


func _jobs() -> Array:
	var only := OS.get_cmdline_user_args()
	var out := []
	for it in Decor.ITEMS:
		if only.size() > 0 and not it.id in only:
			continue
		out.append(["decor_" + it.id, Decor.model(it.id)])
		if Decor.paintable(it.id):
			out.append(["decor_%s_mask" % it.id, Decor.mask_model(it.id)])
	if only.is_empty():
		for k in PROPS:
			out.append([k, Toys.prop(k, 3.0)])
	return out


var jobs := []


func _process(_dt: float) -> bool:
	if jobs.is_empty() and i < 0:
		jobs = _jobs()
	if wait > 0:
		wait -= 1
		if wait == 0:
			var img := vp.get_texture().get_image()
			img.save_png("res://art/%s.png" % jobs[i][0])
			print("rendered ", jobs[i][0])
		return false
	i += 1
	if i >= jobs.size():
		return true
	for c in holder.get_children():
		c.queue_free()
	var m: Node3D = jobs[i][1]
	holder.add_child(m)
	# make it fit the picture: scale by its height
	var top := 0.0
	for mi in Toys._all_meshes(m):
		var bb: AABB = mi.get_aabb()
		top = maxf(top, (mi.global_transform * bb).end.y)
	m.scale = Vector3.ONE * (1.9 / maxf(top, 0.6) if top > 1.9 else 1.0)
	wait = 4
	return false
