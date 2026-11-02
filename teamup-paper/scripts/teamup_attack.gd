class_name TeamUpAttack
extends Node
## TEAM UP!: when the TEAM meter is full (identity.gd), all three teammates jump out together,
## the camera swings round, the screen freezes on a COMIC POSTER with their faces and the
## combo's name, then their combined super hits every bad guy nearby (and a boss hard).
## The game state is "teamup" while it plays (everything else waits).

const APPEAR := 0.75          # heroes jump out, camera swings
const POSTER := 2.35          # the comic freeze-frame ends
const BLAST := 3.1            # the shockwave ends
const RANGE := 16.0
const HERO_WORD := {"rocco": "JUMP", "blocky": "BLOCK", "lucky": "LUCKY", "alix": "ARROW", "petal": "PETAL",
	"spotty": "SPOTTY", "brawlo": "BRAWL"}
const LINE_WORD := {"fire": "BLAZE", "water": "TSUNAMI", "grass": "BLOOM", "spark": "THUNDER", "rock": "QUAKE", "ghost": "SPOOK",
	"bird": "TORNADO", "mind": "MIND-BLAST", "ice": "BLIZZARD", "bug": "SWARM", "dragon": "DRAGON-ROAR", "fairy": "SPARKLE"}
const SPECIAL := {"rocco+blocky+fire": "BLOCK-BUSTER BLAZE", "rocco+blocky+water": "SUPER SPLASH SQUAD",
	"rocco+blocky+spark": "THUNDER TEAM SLAM", "rocco+blocky+grass": "BLOOMING BLOCK BASH",
	"rocco+blocky+dragon": "DRAGON DOUBLE DASH"}
const LINE_COLOR := {"fire": Color("ff6a2a"), "water": Color("3f9fe0"), "grass": Color("3cb878"), "spark": Color("ffd23a"),
	"rock": Color("b08a60"), "ghost": Color("8a5bd8"), "bird": Color("7ad0ff"), "mind": Color("ff6fd8"), "ice": Color("9ff2ff"),
	"bug": Color("9ccc3a"), "dragon": Color("6a5bff"), "fairy": Color("ffa8e0")}

var game
var id_: Identity
var t := -1.0
var back_to := "play"
var cam_yaw0 := 0.0
var center := Vector3.ZERO
var hidden: Hero = null       # the human who wasn't out (shown just for the attack)
var hidden_pos := Vector3.ZERO
var combo := ""
var hits := 0
var boss_hit := 0


func setup(g, ident: Identity) -> void:
	game = g
	id_ = ident


func busy() -> bool:
	return t >= 0


func can_start() -> bool:
	return t < 0 and game.state == "play" and game.level != null and game.level.open and not game.bonus


## The combo's name, from the three teammates.
static func combo_name(a: String, b: String, line: String) -> String:
	var key := "%s+%s+%s" % [a, b, line]
	if SPECIAL.has(key):
		return SPECIAL[key]
	key = "%s+%s+%s" % [b, a, line]
	if SPECIAL.has(key):
		return SPECIAL[key]
	return "%s-%s %s" % [HERO_WORD.get(a, a.to_upper()), HERO_WORD.get(b, b.to_upper()), LINE_WORD.get(line, line.to_upper())]


func start() -> bool:
	if id_.meter < 1.0 or not can_start():
		return false
	id_.meter = 0.0
	t = 0.0
	hits = 0
	boss_hit = 0
	back_to = game.state
	game.state = "teamup"
	cam_yaw0 = game.cam_yaw
	var l: Hero = game.leader()
	center = l.position
	combo = combo_name(game.heroes.rocco.who, game.heroes.blocky.who, game.heroes.mon.line)
	game.note("TEAM UP " + combo)
	# everybody out: the two humans side by side, the partner in front
	var side: Vector3 = Actor.view_right
	hidden = game.heroes.blocky if game.human == "rocco" else game.heroes.rocco
	hidden_pos = hidden.position
	hidden.visible = true
	if hidden.shadow:
		hidden.shadow.visible = true
	var hr: Hero = game.heroes[game.human]
	hr.visible = true
	game.heroes.mon.visible = true
	hr.position = center - side * 0.9
	hidden.position = center + side * 0.9
	game.heroes.mon.position = center + Actor.view_back * 1.2
	for h in [hr, hidden, game.heroes.mon]:
		h.vel = Vector3(0, 9.0, 0)
		h.facing = Actor.view_back
	game.hud.say("TEAM UP!", "", 0.7)
	game.speak("Team up!", game.heroes.rocco.who)
	game.sfx("alert")
	game.fx.shake = 0.4
	return true


func step(dt: float) -> void:
	if t < 0:
		return
	var was := t
	t += dt
	var c := center + Vector3(0, 1.4, 0)
	# camera: a swing round the team, then holds for the poster, then pulls back for the blast
	var yaw := cam_yaw0 + clampf(t / APPEAR, 0, 1) * 0.9
	var dist := 6.0 if t < POSTER else 6.0 + (t - POSTER) * 6.0
	game.cam.position = c + Vector3(sin(yaw) * dist, 1.6 + maxf(0, t - POSTER) * 3.0, cos(yaw) * dist)
	game.cam.look_at(c, Vector3.UP)
	if t < APPEAR:   # jumping out with a little hang in the air
		for h: Hero in [game.heroes.rocco, game.heroes.blocky, game.heroes.mon]:
			h.vel.y -= 24.0 * dt
			h.position.y = maxf(center.y, h.position.y + h.vel.y * dt)
			h.play("jump")
			h.animate(dt)
	if was < APPEAR and t >= APPEAR:
		game.sfx("boss_hit")
		game.fx.shake = 0.6
	if was < POSTER and t >= POSTER:
		_blast()
	if t >= POSTER and t < BLAST and int(t * 20) != int(was * 20):
		var col: Color = LINE_COLOR.get(game.heroes.mon.line, Color.WHITE)
		var a := randf() * TAU
		var r := (t - POSTER) / (BLAST - POSTER) * RANGE
		game.fx.burst(center + Vector3(cos(a) * r, 0.6, sin(a) * r), col, 6, 4, 4)
	if t >= BLAST:
		_end()


## The combined super: every bad guy nearby takes a big hit, a boss takes a quarter of its health.
func _blast() -> void:
	game.sfx("boss_down")
	game.sfx("thunder", -4)
	game.fx.shake = 1.2
	var col: Color = LINE_COLOR.get(game.heroes.mon.line, Color.WHITE)
	game.fx.ring(center, RANGE * 0.6)
	for i in 10:
		game.fx.burst(center + Vector3(randf_range(-3, 3), randf_range(0.4, 2.5), randf_range(-3, 3)), col if i % 2 else Color("ffe36a"), 14, 7, 6)
	for f in game.foes:
		if not is_instance_valid(f) or f.dying >= 0:
			continue
		if f.position.distance_to(center) > RANGE:
			continue
		if f.is_boss:
			if not game.boss_on:
				continue
			var n := maxi(4, int(f.max_hp * 0.25))
			f.safe_t = 0
			f.hurt(n, game)
			boss_hit += n
		else:
			f.safe_t = 0
			f.hurt(99, game)
			if f.kind == "bomber" and f.dying < 0:   # bombs just light: pop them too
				f.die(game)
		hits += 1
	game.hud.say(combo + "!", "%d bad guys hit!" % hits if hits > 0 else "What a team!", 2.0)


func _end() -> void:
	t = -1.0
	if hidden and is_instance_valid(hidden):
		hidden.visible = false
		if hidden.shadow:
			hidden.shadow.visible = false
		hidden.position = hidden_pos
	var hr: Hero = game.heroes[game.human]
	hr.position = center
	game.heroes.mon.position = center + Actor.view_back * 1.2 - Actor.view_right * 1.0
	if game.control == "mon":
		game.heroes.mon.position = center
		hr.position = center - Actor.view_right * 1.0
	for h: Hero in [game.heroes.rocco, game.heroes.blocky, game.heroes.mon]:
		h.vel = Vector3.ZERO
	game.state = back_to
	id_.progress("teamup")


# ------------------------------------------------------------------ the comic poster (HUD)
func draw(hud) -> void:
	if t < 0:
		return
	var s: Vector2 = hud.size
	if t < APPEAR - 0.1 or t > POSTER + 0.15:
		if t > POSTER and t < POSTER + 0.25:   # a white flash as the blast goes off
			hud.draw_rect(Rect2(Vector2.ZERO, s), Color(1, 1, 1, 1.0 - (t - POSTER) / 0.25))
		return
	var k := clampf((t - (APPEAR - 0.1)) / 0.18, 0, 1)
	var col: Color = LINE_COLOR.get(game.heroes.mon.line, Color("ff5fa8"))
	# dark backdrop + speed rays
	hud.draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.03, 0.12, 0.6 * k))
	var mid := s * 0.5
	for i in 24:
		var a := TAU * i / 24.0 + t * 0.4
		var a2 := a + TAU / 48.0
		var r := s.length()
		hud.draw_colored_polygon(PackedVector2Array([mid, mid + Vector2(cos(a), sin(a)) * r, mid + Vector2(cos(a2), sin(a2)) * r]),
			Color(col, 0.35 * k) if i % 2 == 0 else Color(1, 0.95, 0.6, 0.25 * k))
	# three slanted comic panels slide in, one per teammate
	var faces := [game.face(game.heroes.rocco.who), game.face(game.heroes.blocky.who), game.face("mon")]
	var names := [game.display_name(game.heroes.rocco.who), game.display_name(game.heroes.blocky.who), game.display_name("mon")]
	var pw := minf(s.x * 0.28, 300.0)
	var ph := minf(s.y * 0.56, 330.0)
	for i in 3:
		var ki := clampf((t - (APPEAR - 0.1) - i * 0.1) / 0.2, 0, 1)
		var ease := 1.0 - pow(1.0 - ki, 3.0)
		var cx := s.x * 0.5 + (i - 1) * (pw + 16)
		var cy := s.y * 0.44 + (1.0 - ease) * s.y
		var sl := 26.0
		var pts := PackedVector2Array([Vector2(cx - pw * 0.5 + sl, cy - ph * 0.5), Vector2(cx + pw * 0.5 + sl, cy - ph * 0.5),
			Vector2(cx + pw * 0.5 - sl, cy + ph * 0.5), Vector2(cx - pw * 0.5 - sl, cy + ph * 0.5)])
		var shadow := PackedVector2Array()
		for p in pts:
			shadow.append(p + Vector2(10, 12))
		hud.draw_colored_polygon(shadow, Color(0, 0, 0, 0.45))
		hud.draw_colored_polygon(pts, [Color("ffe36a"), Color("7ad0ff"), col.lightened(0.35)][i])
		# halftone dots
		for dx in range(-3, 4):
			for dy in range(-4, 5):
				hud.draw_circle(Vector2(cx + dx * pw / 7.0 - dy * 6, cy + dy * ph / 9.0), 4.0, Color(1, 1, 1, 0.35))
		var tex: Texture2D = hud.img(faces[i])
		if tex:
			hud.fit(tex, Rect2(cx - pw * 0.42, cy - ph * 0.42, pw * 0.84, ph * 0.7))
		var outline := pts.duplicate()
		outline.append(pts[0])
		hud.draw_polyline(outline, Color(0.1, 0.06, 0.14), 6.0)
		hud.txt(names[i], Vector2(cx - pw * 0.5, cy + ph * 0.44), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, pw, 8)
	# the combo's name on a burst banner
	var kb := clampf((t - APPEAR - 0.25) / 0.2, 0, 1)
	if kb > 0:
		var pop := 1.0 + (1.0 - kb) * 0.8
		var by := s.y * 0.86
		hud.draw_set_transform(Vector2(mid.x, by) * (1.0 - pop), 0.0, Vector2(pop, pop))
		var bw := minf(s.x * 0.86, 900.0)
		hud.draw_colored_polygon(PackedVector2Array([Vector2(mid.x - bw * 0.5, by - 50), Vector2(mid.x + bw * 0.5 + 20, by - 62),
			Vector2(mid.x + bw * 0.5, by + 30), Vector2(mid.x - bw * 0.5 - 20, by + 40)]), Color("e8413b"))
		hud.txt(combo + "!", Vector2(mid.x - bw * 0.5, by + 4), 54, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, bw, 12)
		hud.draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	hud.txt("TEAM UP!", Vector2(0, s.y * 0.1), 64, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
