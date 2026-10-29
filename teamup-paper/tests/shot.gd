extends SceneTree
## Renders a few screenshots: godot --path . --script res://tests/shot.gd -- <out_dir>
## (needs a real display / xvfb, not --headless)

var main
var frame := 0
var out := "user://"
var shots := [
	[40, "title", ""],
	[60, "start", ""],
	[150, "shot", "01_start"],
	[160, "tp", Vector3(30.5, 6, 6.5)],
	[250, "shot", "02_wall"],
	[260, "tp_blocky", Vector3(61.0, 5, 6.5)],
	[350, "shot", "03_blocky_hill"],
	[360, "tp", Vector3(94.0, 5, 6.5)],
	[450, "shot", "04_platforms"],
	[460, "tp", Vector3(108.0, 5, 6.5)],
	[540, "shot", "05_foes"],
	[550, "tp", Vector3(166.0, 5, 6.5)],
	[700, "shot", "06_boss"],
]


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() > 0:
		out = args[0]
	var a := OS.get_cmdline_user_args()
	Look.loaded = true
	Look.toy = a.has("toy")
	print("look: ", "3D toys" if Look.toy else "paper")
	main = load("res://main.tscn").instantiate()
	root.add_child(main)
	main.invincible = true


func _process(_dt: float) -> bool:
	frame += 1
	for s in shots:
		if s[0] == frame:
			match s[1]:
				"start":
					main.start_game()
				"tp":
					_tp(s[2])
				"tp_blocky":
					_tp(s[2])
					main.do_swap()
				"shot":
					var img: Image = root.get_texture().get_image()
					img.save_png(out + "/" + s[2] + ".png")
					print("saved ", s[2])
	return frame > 710


func _tp(p: Vector3) -> void:
	var l = main.leader()
	l.position = p
	main.follower().position = p + Vector3(-1.3, 0.5, -0.8)
	main.cam_focus = p
