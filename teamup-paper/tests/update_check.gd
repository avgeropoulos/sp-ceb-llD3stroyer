extends SceneTree
## Checks the updater against the real GitHub release:
## godot --headless --path . --script res://tests/update_check.gd -- [pretend_version] [download]

var u: Updater
var t := 0.0
var want_download := false
var started := false
var pretend := 1


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	u = Updater.new()
	root.add_child(u)
	pretend = int(args[0]) if args.size() > 0 else 1
	want_download = args.size() > 1 and args[1] == "download"


func _process(dt: float) -> bool:
	if not started:   # wait until the updater is really in the scene
		started = true
		u.my_version = pretend   # after _ready, which reads the real (computer = 0) version
		if want_download:
			u.status = "available"
			u.download()
		else:
			u.check()
		return false
	t += dt
	if int(t * 2) != int((t - dt) * 2):
		print("%.1fs  status=%s  %s" % [t, u.status, u.message])
	var busy := u.status in ["checking", "downloading"]
	if (not busy and t > 0.5) or t > 120:
		print("FINAL status=%s message=%s newest=%d" % [u.status, u.message, u.newest])
		return true
	return false
