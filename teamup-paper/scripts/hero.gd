class_name Hero
extends Actor
## Rocco, Blocky or Sparky. The game decides who's being steered; this just
## remembers each hero's own move timers.

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


func init(lv: Level, w: String, stats: Dictionary) -> void:
	who = w
	var f := {}
	match w:
		"rocco":
			f = {"idle": ["rocco_idle"], "blink": ["rocco_blink"], "run": ["rocco_run1", "rocco_idle", "rocco_run2", "rocco_idle"],
				"jump": ["rocco_jump"], "fall": ["rocco_fall"], "slam": ["rocco_slam"], "hurt": ["rocco_hurt"], "win": ["rocco_win"]}
		"blocky":
			f = {"idle": ["blocky_idle"], "run": ["blocky_run1", "blocky_idle", "blocky_run2", "blocky_idle"],
				"jump": ["blocky_jump"], "fall": ["blocky_jump"], "mine": ["blocky_mine1", "blocky_mine2"], "hurt": ["blocky_hurt"], "win": ["blocky_mine1"]}
		"sparky":
			f = {"idle": ["sparky_idle"], "run": ["sparky_run1", "sparky_run2"], "jump": ["sparky_jump"], "fall": ["sparky_jump"],
				"zap": ["sparky_zap"], "hurt": ["sparky_zap"], "win": ["sparky_jump"]}
	setup(lv, f, stats.size, stats.feet)
	speed = stats.speed
	jump_v = stats.jump
	hw = stats.hw
	height = stats.h
	play("idle")
