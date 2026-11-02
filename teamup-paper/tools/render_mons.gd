extends SceneTree
## Renders the partners' 3D toy models into the PAPER look's pictures (a 3/4 view, facing right):
##   art/mon_<line><stage>_{idle,run1,run2,jump,act}.png   (side view, feet 40 px above the bottom)
##   art/mon_<line>M_*.png                                  (the MEGA forms)
## Run (needs a display):  xvfb-run godot --path . --script res://tools/render_mons.gd [-- line line ...]
## then:  python3 tools/sticker_mons.py   (adds the white sticker border, keeps the size so the feet line up)
## With "-- preview <dir>" it renders a 3/4 view of every form into <dir> instead (for checking the looks).

const W := 336
const H := 320
const FEET := 40.0
const POSES := {"idle": ["idle", 0.4, 0.0], "run1": ["run", 0.02, 0.0], "run2": ["run", 0.13, 0.0],
	"jump": ["jump", 0.1, 3.0], "act": ["zap", 0.05, 0.0]}

var vp: SubViewport
var cam: Camera3D
var holder: Node3D
var jobs := []
var i := -1
var wait := 0
var preview := ""


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	var only := []
	var k := 0
	while k < args.size():
		if args[k] == "preview":
			preview = args[k + 1]
			k += 2
			continue
		only.append(args[k])
		k += 1
	vp = SubViewport.new()
	vp.size = Vector2i(W, H) if preview == "" else Vector2i(300, 300)
	vp.transparent_bg = preview == ""
	vp.own_world_3d = true
	vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	vp.msaa_3d = Viewport.MSAA_4X
	root.add_child(vp)
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_CLEAR_COLOR if preview == "" else Environment.BG_COLOR
	e.background_color = Color("9fd3f5")
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color("dce8f5")
	e.ambient_light_energy = 0.65
	env.environment = e
	vp.add_child(env)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-45, -60, 0)
	vp.add_child(sun)
	cam = Camera3D.new()
	vp.add_child(cam)
	cam.current = true
	holder = Node3D.new()
	vp.add_child(holder)
	for line in Mons.ORDER:
		if only.size() > 0 and not line in only:
			continue
		var stages: int = Mons.LINES[line].names.size()
		for st in stages + 1:   # every stage, then the MEGA form
			var mega := st == stages
			var name := "mon_%s%s" % [line, "M" if mega else str(st)]
			var kind := _kind(line, st if not mega else stages - 1, mega)
			var size: float = Mega.picture_size(line, st if not mega else stages - 1, mega)
			if preview != "":
				jobs.append([name, kind, size, "idle"])
			else:
				for pose in POSES:
					jobs.append([name + "_" + pose, kind, size, pose])


static func _kind(line: String, st: int, mega: bool) -> String:
	if line == "spark":
		return "sparky:2" if mega else ("sparky" if st == 0 else "sparky:1")
	return "mon:%s:%d%s" % [line, st, ":mega" if mega else ""]


func _process(_dt: float) -> bool:
	if wait > 0:
		wait -= 1
		if wait == 0:
			var img := vp.get_texture().get_image()
			var out := ("res://art/%s.png" % jobs[i][0]) if preview == "" else "%s/%s.png" % [preview, jobs[i][0]]
			img.save_png(out)
			print("rendered ", jobs[i][0])
		return false
	i += 1
	if i >= jobs.size():
		return true
	for c in holder.get_children():
		c.queue_free()
	var job: Array = jobs[i]
	var toy := Toys.build(job[1])
	var a := Actor.new()
	a.sprite = Sprite3D.new()
	a.model = toy
	a.facing = Vector3(0, 0, 1)
	var ps: Array = POSES[job[3]]
	a.anim = ps[0]
	a.anim_t = ps[1]
	a.vel = Vector3(0, ps[2], 0)
	holder.add_child(a)
	a.add_child(toy)
	Actor.view_back = Vector3.ZERO   # no turning toward the camera
	Toys.pose(a, 0.0)
	toy.rotation.y = 0.0
	var size: float = job[2]
	if preview != "":
		cam.projection = Camera3D.PROJECTION_PERSPECTIVE
		cam.fov = 40
		var h := size * 0.5
		cam.look_at_from_position(Vector3(size * 0.75, h * 0.8 + 0.3, size * 0.95), Vector3(0, h * 0.45, 0))
	else:   # side view, facing right, the picture spans `size` units like the in-game sprite
		cam.projection = Camera3D.PROJECTION_ORTHOGONAL
		cam.size = size
		cam.keep_aspect = Camera3D.KEEP_HEIGHT
		var cy := size * 0.5 - FEET / H * size
		cam.look_at_from_position(Vector3(-4.6, cy + 1.1, 3.9), Vector3(0, cy, 0))   # 3/4 view: both eyes show
	wait = 4
	return false
