extends SceneTree
## Renders every decoration's 3D model to art/decor_<id>.png (transparent), for the PAPER look
## and the DECOR palette icons. Then tools/sticker.py gives them the white sticker border.
## Run (needs a display): xvfb-run godot --path . --script res://tools/render_decor.gd

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


func _process(_dt: float) -> bool:
	if wait > 0:
		wait -= 1
		if wait == 0:
			var it: Dictionary = Decor.ITEMS[i]
			var img := vp.get_texture().get_image()
			img.save_png("res://art/decor_%s.png" % it.id)
			print("rendered ", it.id)
		return false
	i += 1
	if i >= Decor.ITEMS.size():
		return true
	for c in holder.get_children():
		c.queue_free()
	var it: Dictionary = Decor.ITEMS[i]
	var m := Decor.model(it.id)
	var big := ["tree", "lamp", "bed"]
	m.scale = Vector3.ONE * (0.62 if it.id in big else 1.0)
	if it.id == "tree":
		m.scale = Vector3.ONE * 0.6
	holder.add_child(m)
	wait = 4
	return false
