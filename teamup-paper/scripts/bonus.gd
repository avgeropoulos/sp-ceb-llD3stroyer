class_name Bonus
extends Node3D
## A painting's BONUS COURSE (Mario 64 style): jump from platform to platform up into
## the painting's sky and grab the PAINTING STAR on the gold platform at the top.
## Fall off and you start again from the last platform you stood on; fall 3 times
## and the painting spits you back out into the world. Getting the star does too
## (with the star saved). See Level.generate_bonus and main.gd enter_painting / leave_bonus.

const MAX_FALLS := 3

var game
var level: Level
var star: Node3D
var falls := 0
var done_t := -1.0
var got := false
var t := 0.0


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	star = OpenPlay.star_model()
	star.position = level.bonus_star
	star.scale = Vector3.ONE * 1.4
	add_child(star)
	var lbl := Label3D.new()
	lbl.text = "★ PAINTING STAR"
	lbl.font_size = 64
	lbl.outline_size = 16
	lbl.fixed_size = true
	lbl.pixel_size = 0.0009
	lbl.no_depth_test = true
	lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lbl.modulate = Color("ffe14a")
	lbl.outline_modulate = Color(0.25, 0.12, 0.02)
	lbl.position = level.bonus_star + Vector3(0, 2.0, 0)
	add_child(lbl)


func begin() -> void:
	game.hud.say("PAINTING STAR!", "Jump up to the gold platform! Falls left: %d" % (MAX_FALLS - falls), 3.2)


func step(dt: float) -> void:
	t += dt
	if star:
		star.rotation.y = t * 2.5
		star.position.y = level.bonus_star.y + sin(t * 3.0) * 0.15
	if done_t >= 0:
		done_t += dt
		game.hurt_t = 1.0   # nothing can hurt you now
		if done_t > 2.4:
			done_t = -100.0
			game.leave_bonus(got)
		return
	var l: Hero = game.leader()
	if star and star.position.distance_to(l.position + Vector3(0, l.height * 0.5, 0)) < 1.5:
		_collect()
		return
	if l.position.y < level.bonus_tops[0].y - 9.0:
		fell()


## Fell off the course: start again from the last platform, or out after 3 falls.
func fell() -> void:
	if done_t >= 0 or done_t < -50:
		return
	var l: Hero = game.leader()
	falls += 1
	game.sfx("hurt")
	if falls >= MAX_FALLS:
		game.hud.say("WHOOPS!", "Back out of the painting you go!", 2.0)
		done_t = 0.4
		l.position = game.safe_pos + Vector3(0, 0.3, 0)
		l.vel = Vector3.ZERO
		return
	if true:
		var p: Vector3 = game.safe_pos
		l.position = p + Vector3(0, 0.3, 0)
		l.vel = Vector3.ZERO
		game.follower().position = p + Vector3(-0.6, 0.6, 0.4)
		game.follower().vel = Vector3.ZERO
		l.flash_t = 1.0
		game.hud.say("OOPS!", "Falls left: %d" % (MAX_FALLS - falls), 1.6)


func _collect() -> void:
	got = true
	game.coins += 10
	game.sfx("clear")
	game.play_music("fanfare", false)
	game.fx.burst(star.position, Color("ffe14a"), 30, 6, 8)
	game.fx.text(star.position + Vector3(0, 1.2, 0), "+10", Color("ffd23a"), 48)
	game.speak("We got the painting star!", "rocco")
	game.hud.say("YOU GOT A STAR!", "The PAINTING STAR!", 2.4)
	star.queue_free()
	star = null
	done_t = 0.0
