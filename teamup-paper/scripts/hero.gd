class_name Hero
extends Actor
## Rocco, Blocky or the partner ("mon": Splashy, Seedy, Ember, Sparky and their
## evolutions). The game decides who's being steered; this remembers each hero's
## own move timers, and for the partner which line and stage it is.

var who := "rocco"
var speed := 5.6
var jump_v := 12.6
var coyote := 0.0
var jumps_left := 1
var slam := ""          # "", "wind" (spinning in the air), "fall"
var slam_t := 0.0
var long_t := 0.0
var action_t := 0.0
var zap_t := 0.0
var blink_t := 2.0
# Rocco's Mario-64 moves (see main.gd _rocco_*)
var trick := ""         # "", "long", "jump2", "triple", "backflip", "sideflip", "wallkick", "dive", "slide"
var chain := 0          # jumps in a row for the TRIPLE JUMP
var land_t := 1.0       # time on the ground since landing
var rev_t := 1.0        # time since the stick was pulled back the other way (SIDE FLIP)
var wall_t := 0.0       # > 0 just after touching a wall in the air (WALL KICK)
var wall_n := Vector3.ZERO
var lock_t := 0.0       # no steering for a moment after a wall kick
var line := "spark"     # partner only: water / grass / fire / spark
var stage := 0          # partner only: 0, 1, 2 (evolutions)


func init(lv: Level, w: String, stats: Dictionary) -> void:
	who = w
	if w == "mon":
		setup(lv, _mon_frames("spark", 0), 1.95, 48.0, "sparky")
		set_form("spark", 0)
		return
	var f := {}
	match w:
		"rocco":
			f = {"idle": ["rocco_idle"], "blink": ["rocco_blink"], "run": ["rocco_run1", "rocco_idle", "rocco_run2", "rocco_idle"],
				"jump": ["rocco_jump"], "fall": ["rocco_fall"], "slam": ["rocco_slam"], "hurt": ["rocco_hurt"], "win": ["rocco_win"],
				"long": ["rocco_long"], "dive": ["rocco_dive"], "slide": ["rocco_slide"], "wallkick": ["rocco_kick"], "sideflip": ["rocco_jump"],
				"flip": ["rocco_tuck0", "rocco_tuck1", "rocco_tuck2", "rocco_tuck3", "rocco_tuck4", "rocco_tuck5", "rocco_tuck6", "rocco_tuck7"],
				"backflip": ["rocco_tuck0", "rocco_tuck7", "rocco_tuck6", "rocco_tuck5", "rocco_tuck4", "rocco_tuck3", "rocco_tuck2", "rocco_tuck1"]}
		"blocky":
			f = {"idle": ["blocky_idle"], "run": ["blocky_run1", "blocky_idle", "blocky_run2", "blocky_idle"],
				"jump": ["blocky_jump"], "fall": ["blocky_jump"], "mine": ["blocky_mine1", "blocky_mine2"], "hurt": ["blocky_hurt"], "win": ["blocky_mine1"]}
	setup(lv, f, stats.size, stats.feet, w)
	speed = stats.speed
	jump_v = stats.jump
	hw = stats.hw
	height = stats.h
	play("idle")


static func _mon_frames(l: String, st: int) -> Dictionary:
	if l == "spark" and st == 0:
		return {"idle": ["sparky_idle"], "run": ["sparky_run1", "sparky_run2"], "jump": ["sparky_jump"], "fall": ["sparky_jump"],
			"zap": ["sparky_zap"], "hurt": ["sparky_zap"], "win": ["sparky_jump"]}
	var b := "mon_%s%d_" % [l, st]
	return {"idle": [b + "idle"], "run": [b + "run1", b + "run2"], "jump": [b + "jump"], "fall": [b + "jump"],
		"zap": [b + "act"], "hurt": [b + "act"], "win": [b + "jump"]}


## Turns the partner into this line and stage (new look, size and speed).
func set_form(l: String, st: int) -> void:
	line = l
	stage = st
	var dims: Array = Mons.STAGE_SIZE[st]
	var feet := 48.0 if l == "spark" else 40.0
	var size: float = dims[0] * (1.3 if l == "spark" and st == 1 else 1.0)
	if l == "spark":
		size = 1.95 * (1.3 if st == 1 else 1.0)
	var kind := ("sparky" if st == 0 else "sparky:1") if l == "spark" else "mon:%s:%d" % [l, st]
	reskin(_mon_frames(l, st), size, feet, kind)
	var info: Dictionary = Mons.LINES[l]
	speed = info.speed[st]
	jump_v = info.jump[st]
	hw = dims[1]
	height = dims[2]
	play("idle")
