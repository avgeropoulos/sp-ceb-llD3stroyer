class_name Bonus
extends Node3D
## A giant painting's BONUS COURSE (Mario 64 style). Four kinds (Level.bonus_kind):
## * SKY CLIMB: jump from platform to platform up to the star on the gold platform.
## * SECRET SLIDE: race down a long twisting ice slide; reach the bottom in SLIDE_TIME
##   seconds and the star is yours (too slow: back to the top to try again).
## * RED COIN MAZE: find the 8 red coins in a hedge maze; the star appears at the start.
## * BIG BRAWL: beat the BIG GRUMBLER on a floating arena; the star appears in the middle.
## Fall off and you start again from the last place you stood; fall 3 times and the
## painting spits you back out into the world. Getting the star does too (with the
## star saved). See Level.generate_bonus and main.gd enter_painting / leave_bonus.

const MAX_FALLS := 3
const SLIDE_TIME := 45.0
const SLIDE_SPEED := 10.5     # the slide pushes you along this fast

var game
var level: Level
var kind := "sky"
var star: Node3D
var star_label: Label3D
var falls := 0
var done_t := -1.0
var got := false
var t := 0.0
var race_t := -1.0            # SECRET SLIDE: time since leaving the start pad (-1 = not yet)
var reds: Array = []          # RED COIN MAZE: the red coins still out
var red_got := 0
var star_out := false


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	kind = lv.bonus_kind
	if kind == "sky" or kind == "slide":
		_show_star()
	for p in level.red_bonus:
		var c := level.prop("coin", p - Vector3(0, 0.35, 0), 0.9)
		_redden(c)
		reds.append(c)


func _redden(c: Node3D) -> void:
	if c is Sprite3D:
		c.modulate = Color(1.0, 0.35, 0.3)
		return
	for ch in c.get_children():
		if ch is MeshInstance3D:
			ch.material_override = Toys.mat(Color("e8413b"), true, 0.6)


func _show_star() -> void:
	if star_out:
		return
	star_out = true
	star = OpenPlay.star_model()
	star.position = level.bonus_star
	star.scale = Vector3.ONE * 1.4
	add_child(star)
	star_label = Label3D.new()
	star_label.text = "★ " + Level.PAINT_NAMES.get(kind, "PAINTING") + " STAR"
	star_label.font_size = 64
	star_label.outline_size = 16
	star_label.fixed_size = true
	star_label.pixel_size = 0.0009
	star_label.no_depth_test = true
	star_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	star_label.modulate = Color("ffe14a")
	star_label.outline_modulate = Color(0.25, 0.12, 0.02)
	star_label.position = level.bonus_star + Vector3(0, 2.0, 0)
	add_child(star_label)


func begin() -> void:
	var what: String = {"sky": "Jump up to the gold platform!",
		"slide": "Race down the slide in %d seconds!" % int(SLIDE_TIME),
		"maze": "Find the 8 red coins in the maze!",
		"boss": "Beat the BIG GRUMBLER!"}.get(kind, "")
	game.hud.say(Level.PAINT_NAMES.get(kind, "PAINTING STAR") + "!", what + "  Falls left: %d" % (MAX_FALLS - falls), 3.2)


## The goal and how far along you are (top of the screen, see hud.gd).
func progress() -> String:
	match kind:
		"slide":
			if race_t < 0:
				return "SECRET SLIDE: slide down to the gold pad in %d seconds!" % int(SLIDE_TIME)
			return "SECRET SLIDE   Time: %.1f / %d" % [race_t, int(SLIDE_TIME)]
		"maze":
			return "RED COIN MAZE   Red coins: %d / 8" % red_got if not star_out else "The star is at the start: go get it!"
		"boss":
			var b := brawler()
			return "BIG BRAWL   Big Grumbler health: %d" % (b.hp if b else 0) if not star_out else "The star is out: grab it!"
	return "SKY CLIMB: jump to the gold platform at the top!"


## The BIG GRUMBLER in the BIG BRAWL (null once he's beaten).
func brawler() -> Foe:
	for f in game.foes:
		if is_instance_valid(f) and f.kind == "biggrumbler" and f.dying < 0:
			return f
	return null


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
	if done_t < -50:
		return
	var l: Hero = game.leader()
	match kind:
		"slide":
			_slide_step(dt, l)
		"maze":
			_maze_step(dt, l)
		"boss":
			if not star_out and brawler() == null and t > 0.5:
				_show_star()
				game.sfx("item")
				game.hud.say("A STAR APPEARED!", "You beat the BIG GRUMBLER!", 2.2)
				game.fx.burst(level.bonus_star, Color("ffe14a"), 18, 5, 6)
	var can_take := kind != "slide" or race_t == -100.0   # the slide star: only after a fast enough run
	if star and can_take and star.position.distance_to(l.position + Vector3(0, l.height * 0.5, 0)) < 1.6:
		_collect()
		return
	if l.position.y < level.bonus_floor - 9.0:
		fell()


## SECRET SLIDE: on the ice the slide carries you along (steer a little with the stick);
## the clock starts when you leave the start pad and stops on the gold pad.
func _slide_step(dt: float, l: Hero) -> void:
	var path: Array = level.slide_path
	if path.size() < 2:
		return
	var i := _nearest_seg(l.position)
	var a: Vector3 = path[i]
	var b: Vector3 = path[i + 1]
	var dir := Vector3(b.x - a.x, 0, b.z - a.z).normalized()
	var on_slide := l.on_floor and level.get_cell(floori(l.position.x), floori(l.position.y - 0.1), floori(l.position.z)) in [Level.ICE, Level.STONE_BRICK] \
		and _dist_to_seg(l.position, a, b) < 2.6
	if on_slide:
		if race_t < 0:
			race_t = 0.0
		# pull toward the middle of the slide and push along it
		var rel := Vector3(l.position.x - a.x, 0, l.position.z - a.z)
		var off := rel - dir * rel.dot(dir)
		var want := dir * SLIDE_SPEED - off * 2.0
		l.vel.x = lerpf(l.vel.x, want.x + game.move_vec.x * 2.0, 1 - exp(-dt * 3.0))
		l.vel.z = lerpf(l.vel.z, want.z + game.move_vec.y * 2.0, 1 - exp(-dt * 3.0))
		l.facing = dir
		l.play("slide" if l.frames.has("slide") else "run")
	if race_t >= 0 and not got:
		race_t += dt
		var fin: Vector3 = level.bonus_tops[1]
		if Vector2(l.position.x - fin.x, l.position.z - fin.z).length() < 4.5 and absf(l.position.y - fin.y) < 1.5:
			if race_t <= SLIDE_TIME:
				game.hud.say("%.1f SECONDS!" % race_t, "Fast enough! Grab the star!", 2.0)
				race_t = -100.0   # done racing (the star is right there)
			else:
				game.hud.say("TOO SLOW!", "%.1f seconds. Back to the top - try again!" % race_t, 2.4)
				race_t = -1.0
				_back_to(level.start_pos)


func _nearest_seg(p: Vector3) -> int:
	var path: Array = level.slide_path
	var best := 0
	var bd := 1e9
	for i in path.size() - 1:
		var a: Vector3 = path[i]
		var b: Vector3 = path[i + 1]
		var d := _dist_to_seg(p, a, b) + absf(p.y - lerpf(a.y, b.y, 0.5)) * 0.02
		var ab := Vector2(b.x - a.x, b.z - a.z)
		if Vector2(p.x - a.x, p.z - a.z).dot(ab) >= ab.length_squared() - 0.5:
			d += 0.8   # already past the end of this part: the next part is the one
		if d < bd:
			bd = d
			best = i
	return best


func _dist_to_seg(p: Vector3, a: Vector3, b: Vector3) -> float:
	var ab := Vector2(b.x - a.x, b.z - a.z)
	var ap := Vector2(p.x - a.x, p.z - a.z)
	var k := clampf(ap.dot(ab) / maxf(ab.length_squared(), 0.001), 0, 1)
	return (ap - ab * k).length()


## RED COIN MAZE: pick up the red coins; the 8th makes the star appear at the start.
func _maze_step(_dt: float, l: Hero) -> void:
	for i in range(reds.size() - 1, -1, -1):
		var c: Node3D = reds[i]
		c.rotation.y = t * 3.0 + i
		if c.position.distance_to(l.position + Vector3(0, 0.7, 0)) < 1.3:
			red_got += 1
			game.coins += 2
			game.sfx("coin", -1)
			game.fx.burst(c.position, Color("ff5a4a"), 8, 3, 6)
			game.fx.text(c.position + Vector3(0, 0.8, 0), "%d" % red_got, Color("ff6a5a"), 64)
			c.queue_free()
			reds.remove_at(i)
			if red_got >= 8:
				_show_star()
				game.sfx("item")
				game.hud.say("ALL 8 RED COINS!", "The star is back at the start!", 2.4)


func _back_to(p: Vector3) -> void:
	var l: Hero = game.leader()
	l.position = p + Vector3(0, 0.3, 0)
	l.vel = Vector3.ZERO
	game.follower().position = p + Vector3(-0.6, 0.6, 0.4)
	game.follower().vel = Vector3.ZERO
	game.safe_pos = p


## Fell off the course: start again from the last safe place, or out after 3 falls.
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
	var p: Vector3 = game.safe_pos
	if kind == "slide":   # slides start again at the top
		p = level.start_pos
		race_t = -1.0
	_back_to(p)
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
	game.hud.say("YOU GOT A STAR!", "The %s STAR!" % Level.PAINT_NAMES.get(kind, "PAINTING"), 2.4)
	star.queue_free()
	star = null
	if star_label:
		star_label.queue_free()
		star_label = null
	done_t = 0.0
