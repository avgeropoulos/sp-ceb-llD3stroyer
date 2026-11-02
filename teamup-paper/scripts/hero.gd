class_name Hero
extends Actor
## A human hero (Rocco, Blocky, or a friend you unlocked: see team.gd) or the partner ("mon": Splashy, Seedy, Ember, Sparky and their
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
var float_t := 0.0      # PETAL's float / LUCKY's flutter time used this jump
var dash_t := 0.0       # SPOTTY's DASH / BRAWLO's BASH
var carry := false      # holding something over your head (grab.gd)
var swing := false      # swinging a boss by the tail (grab.gd)
var line := "spark"     # partner only: water / grass / fire / spark
var stage := 0          # partner only: 0, 1, 2 (evolutions)
var mega := false       # partner only: the MEGA form is out (mega.gd)


func init(lv: Level, w: String, stats: Dictionary) -> void:
	who = w
	if w == "mon":
		setup(lv, _mon_frames("spark", 0), 1.95, 40.0, "sparky")
		set_form("spark", 0)
		return
	var f := Team.frames(w)
	setup(lv, f, stats.size, stats.feet, w)
	speed = stats.speed
	jump_v = stats.jump
	hw = stats.hw
	height = stats.h
	play("idle")


## Turns a human slot into another hero (TEAM screen): new look, size and speed.
func become(w: String) -> void:
	var st: Dictionary = Team.info(w)
	who = w
	reskin(Team.frames(w), float(st.size), float(st.feet), w)
	speed = float(st.speed)
	jump_v = float(st.jump)
	hw = float(st.hw)
	height = float(st.h)
	trick = ""
	slam = ""
	play("idle")


## The partner's paper pictures (rendered from its 3D model by tools/render_mons.gd).
static func _mon_frames(l: String, st: int, is_mega := false) -> Dictionary:
	var b := "mon_%s%s_" % [l, "M" if is_mega else str(st)]
	return {"idle": [b + "idle"], "run": [b + "run1", b + "run2"], "jump": [b + "jump"], "fall": [b + "jump"],
		"zap": [b + "act"], "hurt": [b + "act"], "win": [b + "jump"]}


## Turns the partner into this line and stage (new look, size and speed).
func set_form(l: String, st: int, is_mega := false) -> void:
	line = l
	stage = st
	mega = is_mega
	var dims: Array = Mons.STAGE_SIZE[st]
	var size := Mega.picture_size(l, st, is_mega)
	var kind := ("sparky" if st == 0 else "sparky:1") if l == "spark" else "mon:%s:%d" % [l, st]
	if is_mega:
		kind = "sparky:2" if l == "spark" else kind + ":mega"
	reskin(_mon_frames(l, st, is_mega), size, 40.0, kind)
	var info: Dictionary = Mons.LINES[l]
	speed = info.speed[st]
	jump_v = info.jump[st]
	hw = dims[1]
	height = dims[2]
	play("idle")
