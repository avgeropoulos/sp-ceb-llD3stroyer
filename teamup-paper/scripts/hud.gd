class_name Hud
extends Control
## Everything flat on the screen: hearts, coins, ores, the boss bar, big messages,
## the title / pause / clear screens, and the touch controls (a thumb stick on the
## left, round buttons on the right). Handles several fingers at once.

var game
var font: Font
var tex := {}

# touch state
var stick_id := -1
var stick_base := Vector2.ZERO
var stick := Vector2.ZERO
var held := {}            # finger index -> button name
var pressed_now := {}     # button name -> true (cleared by the game each frame)
var buttons := {}         # name -> [center, radius]
# home: a finger that isn't on the stick or a button turns the camera (drag),
# places a block (quick tap) or breaks one (hold still)
var look_id := -1
var look_last := Vector2.ZERO
var look_start := Vector2.ZERO
var look_t0 := 0
var look_moved := false
var look_delta := Vector2.ZERO          # camera drag this frame (the game takes it)
var world_taps: Array[Vector2] = []     # quick taps on the world (the game takes them)
var hold_pos := Vector2(-1, -1)         # where a finger is held still (breaking)

var message := ""
var message_t := 0.0
var sub_message := ""
var title_t := 0.0


func _ready() -> void:
	set_anchors_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	font = ThemeDB.fallback_font
	var cf := ConfigFile.new()
	if cf.load("user://ui.cfg") == OK:
		haptics = cf.get_value("ui", "haptics", true)
	for n in ["heart", "heart_empty", "coin", "rocco_idle", "blocky_idle", "sparky_idle", "t_stone", "t_iron", "t_gold", "t_diamond", "kragjr_1"]:
		tex[n] = load("res://art/" + n + ".png")


## Any picture from art/, loaded once.
func img(name: String) -> Texture2D:
	if not tex.has(name):
		tex[name] = load("res://art/" + name + ".png")
	return tex[name]


## Draws a picture inside a box, keeping its shape (critters are wider than tall).
func fit(t: Texture2D, r: Rect2) -> void:
	var k := minf(r.size.x / t.get_width(), r.size.y / t.get_height())
	var sz := Vector2(t.get_width(), t.get_height()) * k
	draw_texture_rect(t, Rect2(r.position + (r.size - sz) * 0.5, sz), false)


func say(text: String, sub: String = "", time: float = 2.2) -> void:
	message = text
	sub_message = sub
	message_t = time
	message_total = maxf(time, 0.01)


func _layout() -> void:
	var s := size
	var u := minf(s.y / 720.0, s.x / 1280.0) * 1.0
	u = clampf(u, 0.75, 1.6)
	var jump := [Vector2(s.x - 125 * u, s.y - 125 * u), 82 * u]
	var act := [Vector2(s.x - 300 * u, s.y - 92 * u), 70 * u]
	var sw := [Vector2(s.x - 145 * u, s.y - 315 * u), 56 * u]
	var sup := [Vector2(s.x - 300 * u, s.y - 255 * u), 56 * u]
	var pz := [Vector2(s.x - 52 * u, 52 * u), 34 * u]
	if game and game.state == "kart":
		buttons = {"jump": jump, "action": act, "pause": pz}
		if game.kart and game.kart.phase == "podium":
			buttons = {"pause": pz}
	elif game and game.state == "minigame":
		buttons = {"jump": jump, "pause": pz}
	elif game and game.state == "arcade":
		buttons = {}
	elif game and game.state == "map":
		buttons = {"land": [Vector2(s.x - 125 * u, s.y - 215 * u), 82 * u], "pause": pz}
	elif game and game.state == "home" and game.home:
		var fly := [Vector2(s.x - 52 * u, 252 * u), 42 * u]
		if game.mecha and game.mecha.active:   # MECHA KRAG attacks: JUMP, ACTION (grab/throw), SWAP
			buttons = {"jump": jump, "action": act, "swap": sw, "pause": pz, "fly": fly}
		elif game.home.mode == "":   # walking around the home: BUILD, DECOR, PARTNER, FLY
			buttons = {"jump": jump, "build": act, "swap": sw, "decor": sup, "pause": pz,
				"partner": [Vector2(s.x - 52 * u, 150 * u), 46 * u], "fly": fly}
			if game.near_tv():   # standing by a TV: watch the MOVIES
				buttons["movies"] = [Vector2(s.x - 445 * u, s.y - 175 * u), 52 * u]
			elif game.near_launcher():   # the FIREWORKS launcher
				buttons["fireworks"] = [Vector2(s.x - 445 * u, s.y - 175 * u), 52 * u]
			if game.daynight and game.daynight.is_night():   # SLEEP till morning
				buttons["sleep"] = [Vector2(s.x - 52 * u, 350 * u), 42 * u]
		else:                      # building: PLACE, REMOVE, DONE (and TURN for decorations)
			buttons = {"jump": jump, "place": act, "done": sw, "remove": sup, "pause": pz, "fly": fly}
			if game.home.mode == "decor":
				buttons["rotate"] = [Vector2(s.x - 445 * u, s.y - 175 * u), 46 * u]
				buttons["paint"] = [Vector2(s.x - 445 * u, s.y - 290 * u), 46 * u]
		if game.flying:
			buttons["down"] = [Vector2(s.x - 445 * u, s.y - 60 * u), 50 * u]   # clear of MOVIES / TURN above it
	else:
		buttons = {"jump": jump, "action": act, "swap": sw, "super": sup, "pause": pz}
		if game and game.mega and game.mega.ready():   # MEGA EVOLUTION is ready
			buttons["mega"] = [Vector2(s.x - 445 * u, s.y - 255 * u), 54 * u]
		if game and game.world_rocket and game.world_rocket.near:   # standing on the rocket pad
			buttons["blastoff"] = [Vector2(s.x * 0.5, s.y - 120 * u), 78 * u]


func button_at(p: Vector2) -> String:
	for k in buttons:
		var b: Array = buttons[k]
		if p.distance_to(b[0]) < b[1] * 1.2:
			return k
	return ""


func is_held(name: String) -> bool:
	return held.values().has(name)


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		var e := event as InputEventScreenTouch
		if e.pressed:
			if game.state == "home" and game.home and game.home.mode != "" and _palette_tap(e.position):
				return
			if game.state == "map" and game.sky:   # island buttons, or tap an island itself
				var ib := island_button_at(e.position)
				if ib != "":
					game.sky.fly_to(ib)
					return
				if button_at(e.position) == "" and game.sky.tap_screen(e.position, game.cam):
					return
			if game.state == "minigame" and game.minigames and game.minigames.phase == "result" and button_at(e.position) == "":
				game.jump_pressed = true   # tap anywhere for OK
			if game.state == "kart" and game.kart and game.kart.can_leave() and button_at(e.position) == "":
				game.kart_done()   # tap anywhere after the podium
				return
			if game.state == "mecha_ask":
				_mecha_ask_tap(e.position)
				return
			if game.state != "play" and game.state != "home" and game.state != "map" and game.state != "minigame" and game.state != "kart":
				if game.state == "arcade":
					_arcade_tap(e.position)
					return
				if game.state == "title" and update_showing() and update_rect().grow(20).has_point(e.position):
					game.updater.download()
					return
				if game.state == "title" and look_rect().grow(16).has_point(e.position):
					game.toggle_look()
					return
				if game.state == "title" and buzz_rect().grow(12).has_point(e.position):
					set_haptics(not haptics)
					return
				if game.state == "slots":
					_slots_tap(e.position)
					return
				if game.state == "partner":
					_partner_tap(e.position)
					return
				if game.state == "team":
					_team_tap(e.position)
					return
				if game.state == "scene":
					game.scene_tap()
					return
				if game.state == "movies":
					_movies_tap(e.position)
					return
				if game.state == "paused":
					for i in game.pause_count():
						if pause_rect(i).has_point(e.position):
							game.pause_choice(i)
					return
				if game.state == "starselect":
					_select_tap(e.position)
					return
				if game.state == "stargot":
					if game.open_play.cel_t > 0.8:
						game.open_play.end_celebrate()
					return
				game.on_tap()
				return
			var b := button_at(e.position)
			if b != "":
				held[e.index] = b
				pressed_now[b] = true
				press_t[b] = 0.0
				buzz(15)
			elif e.position.x < size.x * 0.5 and stick_id == -1:
				stick_id = e.index
				stick_base = e.position
				stick = Vector2.ZERO
			elif (game.state == "home" or (game.state == "play" and game.level.open)) and look_id == -1:
				look_id = e.index
				look_last = e.position
				look_start = e.position
				look_t0 = Time.get_ticks_msec()
				look_moved = false
		else:
			held.erase(e.index)
			if e.index == stick_id:
				stick_id = -1
				stick = Vector2.ZERO
			if e.index == look_id:
				if not look_moved and hold_pos.x < 0 and Time.get_ticks_msec() - look_t0 < 350:
					world_taps.append(e.position)
				look_id = -1
				hold_pos = Vector2(-1, -1)
	elif event is InputEventScreenDrag:
		var e := event as InputEventScreenDrag
		if e.index == look_id:
			if hold_pos.x >= 0:   # breaking: slide the finger to break the next block
				hold_pos = e.position
			else:
				if e.position.distance_to(look_start) > 22:
					look_moved = true
				if look_moved:
					look_delta += e.position - look_last
			look_last = e.position
		if e.index == stick_id:
			var r := 70.0 * clampf(size.y / 720.0, 0.75, 1.6)
			var d := e.position - stick_base
			if d.length() > r * 1.5:   # drag the base along so the thumb never "runs out"
				stick_base = e.position - d.normalized() * r * 1.5
				d = e.position - stick_base
			stick = (d / r).limit_length(1.0)


func _process(dt: float) -> void:
	message_t = maxf(0, message_t - dt)
	if look_id >= 0 and not look_moved and hold_pos.x < 0 and Time.get_ticks_msec() - look_t0 > 420 \
			and game and game.home and game.home.mode != "":
		hold_pos = look_last   # held still on a block: start breaking it
	if game and game.state != "home" and not (game.state == "play" and game.level.open):
		look_id = -1
		hold_pos = Vector2(-1, -1)
	title_t += dt
	size = get_viewport_rect().size
	_layout()
	for k in press_t.keys():
		press_t[k] += dt
	var alab := ""
	if game and game.state in ["play", "home", "kart"] and buttons.has("action"):
		alab = game.action_label()
	for k in buttons:   # buttons that just appeared (or changed what they do) pulse for a moment
		var lab := alab if k == "action" else ""
		if not seen_t.has(k) or _btn_last.get(k, "") != lab:
			seen_t[k] = 0.0 if (seen_t.has(k) or title_t > 1.5) else 9.0
			_btn_last[k] = lab
		seen_t[k] += dt
	for k in seen_t.keys():
		if not buttons.has(k):
			seen_t.erase(k)
			_btn_last.erase(k)
	_juice_step(dt)
	queue_redraw()


# ------------------------------------------------------------------ drawing
func txt(s: String, pos: Vector2, fs: int, color: Color = Color.WHITE, align := HORIZONTAL_ALIGNMENT_LEFT, width := -1.0, outline := 8) -> void:
	draw_string_outline(font, pos, s, align, width, fs, outline, Color(0.13, 0.09, 0.18))
	draw_string(font, pos, s, align, width, fs, color)


func _draw() -> void:
	_draw_screen()
	if game != null and (closing >= 0 or wipe >= 0) and not _wiped:
		_draw_wipe()


var _wiped := false


func _draw_screen() -> void:
	_wiped = false
	var s := size
	if game == null:
		return
	if game.state == "loading":
		_draw_loading()
		return
	if game.state == "title":
		_draw_title()
		return
	if game.state == "slots":
		_draw_slots()
		return
	if game.state == "scene":
		_draw_scene()
		return
	if game.state == "movies":
		_draw_movies()
		return
	if game.state == "partner":
		_draw_partner()
		return
	if game.state == "team":
		_draw_team()
		return
	if game.state == "arcade":
		_draw_arcade()
		return
	if game.state == "minigame":
		if game.minigames:
			game.minigames.draw(self)
		_draw_controls()
		return
	if game.state == "kart":
		if game.kart:
			game.kart.draw(self)
			if game.kart.phase != "podium":
				_draw_controls()
		return
	if game.state == "mecha_ask":
		_draw_mecha_ask()
		return
	_draw_hud()
	if game.mecha and game.mecha.active and game.state == "home":
		game.mecha.draw(self)
	if game.state == "play" or game.state == "home" or game.state == "map":
		_draw_controls()
	if game.state == "map" and game.sky:
		_draw_map_info()
	if game.state == "home" and game.home and game.home.mode != "":
		_draw_palette()
		_draw_crosshair()
	if message_t > 0 and game.state != "paused":
		var a := clampf(message_t * 3, 0, 1)
		# The pop-in grows the picture, never the font size: a new font size every frame makes
		# the tablet redraw every letter from scratch (that was the lag at the start, on SUPERs
		# and when Krag Jr. shows up). The banner slides in from the right and out to the left.
		var age := message_total - message_t
		var pop := 1.0 + maxf(0, (0.3 - age)) * 1.5
		var slide := 0.0
		if age < 0.22:
			var e := 1.0 - age / 0.22
			slide = s.x * 0.6 * e * e
		elif message_t < 0.28:
			var e := 1.0 - message_t / 0.28
			slide = -s.x * 0.5 * e * e
		var mid := Vector2(s.x * 0.5 + slide, s.y * 0.36 - 22)
		draw_set_transform(Vector2(slide, 0) + (mid - Vector2(slide, 0)) * (1.0 - pop), 0.0, Vector2(pop, pop))
		txt(message, Vector2(0, s.y * 0.36), 64, Color(1, 0.86, 0.3, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
		if sub_message != "":
			draw_set_transform(Vector2(slide * 1.15, 0), 0.0, Vector2.ONE)
			txt(sub_message, Vector2(0, s.y * 0.36 + 52), 30, Color(1, 1, 1, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
			draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	if game.state == "paused":
		_draw_pause()
	if game.state == "starselect":
		_draw_select()
	if game.state == "stargot":
		_draw_stargot()
	if game.state == "clear":
		_draw_clear()
	if game.weather and game.weather.flash > 0.01:   # lightning!
		draw_rect(Rect2(Vector2.ZERO, s), Color(1, 1, 1, game.weather.flash * 0.45))
	_draw_coin_fliers()
	_draw_wipe()
	_wiped = true


# ------------------------------------------------------------------ MECHA KRAG: fight or not now?
func mecha_ask_rect(i: int) -> Rect2:
	var w := 300.0
	var gap := 40.0
	return Rect2(size.x * 0.5 - w - gap * 0.5 + i * (w + gap), size.y * 0.62, w, 110)


func _mecha_ask_tap(p: Vector2) -> void:
	if mecha_ask_rect(0).has_point(p):
		game.start_mecha()
	elif mecha_ask_rect(1).has_point(p):
		game.decline_mecha()


func _draw_mecha_ask() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.12, 0.02, 0.04, 0.72))
	var flash := 0.5 + 0.5 * sin(title_t * 8)
	txt("WARNING!", Vector2(0, s.y * 0.2), 44, Color(1, 0.3, 0.3, 0.6 + 0.4 * flash), HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
	txt("MECHA KRAG IS COMING!", Vector2(0, s.y * 0.33), 76, Color("ffe14a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 16)
	txt("A giant robot King Krag wants to stomp your home!", Vector2(0, s.y * 0.33 + 52), 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	var labels := ["FIGHT!", "NOT NOW"]
	var cols := [Color("e8413b"), Color(0.25, 0.3, 0.45)]
	for i in 2:
		var r := mecha_ask_rect(i)
		card(r, cols[i])
		txt(labels[i], Vector2(r.position.x, r.position.y + 72), 48, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 10)


func _draw_hud() -> void:
	var x := 22.0
	for i in game.MAX_HEARTS:
		var hr := Rect2(x + i * 46, 14, 50, 50)
		if heart_shake > 0:   # ouch: the hearts wobble
			hr.position.x += sin(heart_shake * 60.0 + i) * 5.0 * heart_shake * 2.0
		if heart_pop > 0 and i < game.hearts:   # refilled: they bounce, one after another
			var b := maxf(0.0, sin(clampf(0.5 - heart_pop - i * 0.05, 0, 0.3) / 0.3 * PI))
			hr = hr.grow(b * 7.0)
		draw_texture_rect(tex["heart" if i < game.hearts else "heart_empty"], hr, false)
	var cp := coin_pop * coin_pop
	draw_texture_rect(tex["coin"], Rect2(x, 66, 42, 42).grow(cp * 6.0), false)
	var cs := 1.0 + cp * 0.25
	var cpos := Vector2(x + 46, 100)
	draw_set_transform((cpos + Vector2(0, -10)) * (1.0 - cs), 0.0, Vector2(cs, cs))
	txt("x %d" % int(shown_coins if shown_coins >= 0 else game.coins), cpos, 30, Color.WHITE if cp < 0.05 else Color(1, 0.92, 0.5))
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	var ox := x + 150
	for o in ["stone", "iron", "gold", "diamond"]:
		draw_texture_rect(tex["t_" + o], Rect2(ox, 74, 30, 30), false)
		draw_rect(Rect2(ox, 74, 30, 30), Color(0.13, 0.09, 0.18), false, 2.0)
		txt(str(game.ores[o]), Vector2(ox + 36, 100), 26)
		ox += 92
	# who's out
	var who: String = game.leader_name()
	draw_circle(Vector2(x + 40, 162), 36, Color(1, 1, 1, 0.85))
	fit(img(game.face(who)), Rect2(x + 8, 124, 66, 70))
	txt(game.display_name(who), Vector2(x + 82, 172), 26, Color(1, 0.95, 0.7))
	# the partner's level and XP
	var m = game.heroes.mon
	var py := 214.0
	draw_rect(Rect2(x, py, 250, 58), Color(0.08, 0.12, 0.25, 0.55))
	fit(img(game.face("mon")), Rect2(x + 4, py + 2, 60, 54))
	var lv: int = game.mon_level()
	txt("%s  Lv %d" % [Mons.name_of(m.line, m.stage), lv], Vector2(x + 70, py + 26), 22, Color.WHITE)
	var xp: int = game.mon_xp[game.mon_line]
	draw_rect(Rect2(x + 70, py + 36, 170, 12), Color(0.1, 0.1, 0.15))
	draw_rect(Rect2(x + 70, py + 36, 170 * Mons.progress(xp), 12), Color("54c8ff"))
	if game.mega and game.mega.active():   # MEGA time left (a gold bar under the XP)
		draw_rect(Rect2(x + 70, py + 50, 170, 6), Color(0.1, 0.1, 0.15))
		draw_rect(Rect2(x + 70, py + 50, 170 * clampf(game.mega.left / Mega.TIME, 0, 1), 6), Color("ffd23a"))
		txt("MEGA", Vector2(x + 4, py + 74), 20, Color("ffd23a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
	if game.open_play and game.state in ["play", "paused", "clear"]:
		_draw_stars()
	if game.bonus and game.state == "play":   # a painting's course: its goal and progress
		var bx := (size.x - 620.0) * 0.5
		draw_rect(Rect2(bx, 16, 620, 44), Color(0.08, 0.06, 0.2, 0.75))
		draw_rect(Rect2(bx, 16, 620, 44), Color("ffd23a"), false, 2.0)
		txt(game.bonus.progress(), Vector2(bx, 46), 22, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, 620, 6)
	if game.powers and game.state == "play":
		game.powers.draw_hud(self)
	# boss bar
	var boss = game.boss
	if game.boss_on and boss != null and is_instance_valid(boss) and boss.dying < 0:
		var w := minf(560.0, size.x * 0.45)
		var bx := (size.x - w) * 0.5
		var by := 70.0 if game.open_play else 0.0   # under the star row in the open worlds
		txt(boss.boss_name(), Vector2(0, 46 + by), 34, Color("ff8c2a"), HORIZONTAL_ALIGNMENT_CENTER, size.x)
		draw_rect(Rect2(bx - 4, 56 + by, w + 8, 24), Color(0.13, 0.09, 0.18))
		draw_rect(Rect2(bx, 60 + by, w * float(boss.hp) / boss.max_hp, 16), Color("ff4f6d"))
	_draw_grab_info()


## KING BOMBO's health bar (when you're near him) and the tail-swing / grab prompts.
func _draw_grab_info() -> void:
	if game.open_play == null or game.state != "play":
		return
	var kb = game.open_play.bombo_foe()
	if kb and not game.boss_on and kb.state != "sleep" and kb.position.distance_to(game.leader().position) < 16:
		var w := minf(420.0, size.x * 0.35)
		var bx := (size.x - w) * 0.5
		txt("KING BOMBO", Vector2(0, 246), 30, Color("ffb02e"), HORIZONTAL_ALIGNMENT_CENTER, size.x)
		draw_rect(Rect2(bx - 4, 254, w + 8, 22), Color(0.13, 0.09, 0.18))
		for i in kb.max_hp:   # one block per throw still needed
			var cw: float = (w - 8.0 * (kb.max_hp - 1)) / kb.max_hp
			draw_rect(Rect2(bx + i * (cw + 8), 258, cw, 14), Color("ff4f6d") if i < kb.hp else Color(0.3, 0.25, 0.35))
	var g = game.grab
	if g == null:
		return
	if g.tail:
		var k: float = clampf(g.spin_w / 13.0, 0, 1)
		var c := Vector2(size.x * 0.5, size.y * 0.78)
		draw_arc(c, 46, -PI / 2, -PI / 2 + TAU * k, 40, Color("ffcf33"), 10, true)
		txt("SPIN!", Vector2(0, c.y + 12), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, size.x, 7)
		txt("Turn the stick round (or tap ACTION) - JUMP to throw!", Vector2(0, c.y + 74), 22, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, size.x, 6)
	elif game.boss_on and is_instance_valid(game.boss) and g._tail_target(game.leader()) != null:
		txt("GRAB HIS TAIL! (ACTION)", Vector2(0, size.y * 0.78), 30, Color("ffcf33"), HORIZONTAL_ALIGNMENT_CENTER, size.x, 8)


## Open worlds, Mario 64 style: every star of this world at the top (gold = found),
## how many open the gate, then the CURRENT QUEST with its goal and progress, and an
## arrow pointing the way when the quest is off the screen.
func _draw_stars() -> void:
	var op = game.open_play
	var all: Array = op.ids()
	var n: int = op.count()
	var gap := 50.0
	var x0 := (size.x - all.size() * gap) * 0.5
	for i in all.size():
		var c := Vector2(x0 + gap * 0.5 + i * gap, 34)
		var mine: bool = op.got.has(all[i])
		var cur: bool = all[i] == op.current
		if cur:
			draw_circle(c, 24, Color(1, 0.9, 0.4, 0.35 + 0.15 * sin(title_t * 6)))
		_star_shape(c, 22, Color(0.1, 0.08, 0.2, 0.8))
		_star_shape(c, 17, Color("ffd23a") if mine else Color(1, 1, 1, 0.25))
	var gate_txt := "GATE OPEN!" if game.gate_open else "%d more for the gate" % maxi(0, OpenPlay.STARS_NEEDED - n)
	txt("%d / %d   %s" % [n, all.size(), gate_txt], Vector2(0, 76), 20, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER, size.x, 6)
	if game.boss_on or game.boss_done or game.state != "play":
		return
	# the quest panel
	var title: String = op.quest_title(op.current)
	var w := minf(760.0, size.x - 640.0)
	var r := Rect2((size.x - w) * 0.5, 118, w, 70)
	draw_rect(r, Color(0.08, 0.06, 0.2, 0.62))
	draw_rect(r, Color(1, 0.86, 0.3, 0.9), false, 3.0)
	_star_shape(r.position + Vector2(30, 35), 18, Color("ffd23a"))
	txt(title, Vector2(r.position.x + 52, r.position.y + 30), 26, Color("ffe14a"), HORIZONTAL_ALIGNMENT_LEFT, r.size.x - 60, 7)
	txt(op.progress(), Vector2(r.position.x + 52, r.position.y + 58), 20, Color.WHITE, HORIZONTAL_ALIGNMENT_LEFT, r.size.x - 60, 6)
	_draw_quest_arrow()


## Where the quest is on the screen: a bouncing arrow over it, or an arrow at the
## edge of the screen pointing the way (with how many blocks away it is).
func _draw_quest_arrow() -> void:
	var op = game.open_play
	var cam: Camera3D = game.cam
	var tp: Vector3 = op.target_pos()
	var dist := Vector2(tp.x - game.leader().position.x, tp.z - game.leader().position.z).length()
	var p := cam.unproject_position(tp + Vector3(0, 1.4, 0))
	var behind := cam.is_position_behind(tp + Vector3(0, 1.4, 0))
	var margin := 70.0
	var area := Rect2(Vector2(margin, 210), size - Vector2(margin * 2, 210 + margin))
	var col := Color("ffe14a")
	if not behind and area.has_point(p):
		if dist < 2.5:
			return
		var b := sin(title_t * 7) * 8
		var tip := p + Vector2(0, b)
		draw_colored_polygon(PackedVector2Array([tip, tip + Vector2(-20, -30), tip + Vector2(20, -30)]), Color(0.1, 0.06, 0.15, 0.8))
		draw_colored_polygon(PackedVector2Array([tip + Vector2(0, -5), tip + Vector2(-14, -27), tip + Vector2(14, -27)]), col)
		txt("%d" % int(dist), Vector2(tip.x - 40, tip.y - 38), 22, col, HORIZONTAL_ALIGNMENT_CENTER, 80, 6)
		return
	var c := size * 0.5
	var d := p - c
	if behind:
		d = -d
	if d.length() < 1:
		d = Vector2(0, 1)
	d = d.normalized()
	# push out to the edge of the area
	var k := INF
	if absf(d.x) > 0.001:
		k = minf(k, ((area.end.x if d.x > 0 else area.position.x) - c.x) / d.x)
	if absf(d.y) > 0.001:
		k = minf(k, ((area.end.y if d.y > 0 else area.position.y) - c.y) / d.y)
	var at := c + d * k
	var side := Vector2(-d.y, d.x)
	var pulse := 1.0 + 0.12 * sin(title_t * 8)
	var tip := at + d * 26 * pulse
	draw_colored_polygon(PackedVector2Array([tip + d * 6, at - d * 14 + side * 30, at - d * 14 - side * 30]), Color(0.1, 0.06, 0.15, 0.85))
	draw_colored_polygon(PackedVector2Array([tip, at - d * 8 + side * 22, at - d * 8 - side * 22]), col)
	txt("%d" % int(dist), Vector2(at.x - 50 - d.x * 30, at.y - d.y * 34 + 8), 22, col, HORIZONTAL_ALIGNMENT_CENTER, 100, 6)
	arrow_at = at   # for tests


var arrow_at := Vector2(-1, -1)


# ------------------------------------------------------------------ STAR SELECT (Mario 64 style)
func select_rect(i: int, n: int) -> Rect2:
	var per := mini(n, 4)
	var rows := int(ceil(n / float(per)))
	var gap := 18.0
	var w := minf(270.0, (size.x - 80 - gap * (per - 1)) / per)
	var h := minf(190.0, (size.y - 300) / rows - gap)
	var row := i / per
	var in_row := mini(per, n - row * per)
	var x0 := (size.x - (w * in_row + gap * (in_row - 1))) * 0.5
	return Rect2(x0 + (i % per) * (w + gap), 150 + row * (h + gap), w, h)


func select_go_rect() -> Rect2:
	return Rect2(size.x * 0.5 - 170, size.y - 96, 340, 76)


func _select_tap(p: Vector2) -> void:
	var op = game.open_play
	var n: int = op.ids().size()
	for i in n:
		if select_rect(i, n).has_point(p):
			op.select_tap(i)
			return
	if select_go_rect().has_point(p):
		op.choose(op.sel)


func _draw_select() -> void:
	var op = game.open_play
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.04, 0.05, 0.14, 0.82))
	var wi: Dictionary = game.world_info()
	txt(String(wi.name), Vector2(0, 74), 56, Color(1, 0.86, 0.3), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	txt("PICK A STAR TO GO FOR   (%d / %d found, 5 open Krag Jr.'s gate)" % [op.count(), op.ids().size()], Vector2(0, 118), 22, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	var all: Array = op.ids()
	for i in all.size():
		var r := select_rect(i, all.size())
		var o: Dictionary = op.objective(all[i])
		var mine: bool = op.got.has(all[i])
		var sel: bool = op.sel == i
		paper_card(r, Color.WHITE, 0, sel, Color(0.98, 0.95, 0.86) if not mine else Color(1.0, 0.93, 0.6))
		_star_shape(r.position + Vector2(r.size.x * 0.5, 44), 30, Color(0.15, 0.1, 0.2))
		_star_shape(r.position + Vector2(r.size.x * 0.5, 44), 24, Color("ffd23a") if mine else Color(0.82, 0.82, 0.86))
		txt("STAR %d" % (i + 1), Vector2(r.position.x, r.position.y + 98), 20, Color(0.35, 0.3, 0.45), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		txt(String(o.name), Vector2(r.position.x + 6, r.position.y + 126), 24, Color(0.15, 0.1, 0.2), HORIZONTAL_ALIGNMENT_CENTER, r.size.x - 12, 0)
		if mine:
			txt("GOT IT!", Vector2(r.position.x, r.position.y + 156), 20, Color("c07a00"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	var cur: Dictionary = op.objective(all[op.sel]) if all.size() > 0 else {}
	if not cur.is_empty():
		txt(String(cur.get("hint", "")), Vector2(0, s.y - 118), 26, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
	var g := select_go_rect()
	draw_rect(g, Color("3fc06a"))
	draw_rect(g, Color.WHITE, false, 4.0)
	txt("GO!", Vector2(g.position.x, g.position.y + 54), 44, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, g.size.x, 9)


## "YOU GOT A STAR!"
func _draw_stargot() -> void:
	var op = game.open_play
	var s := size
	var k := clampf(op.cel_t / 0.35, 0, 1)
	draw_rect(Rect2(0, 0, s.x, 70 * k), Color(0, 0, 0, 0.85))
	draw_rect(Rect2(0, s.y - 90 * k, s.x, 90 * k), Color(0, 0, 0, 0.85))
	var pop := 1.0 + maxf(0.0, 0.4 - op.cel_t) * 1.5
	var mid := Vector2(s.x * 0.5, s.y * 0.22)
	draw_set_transform(mid * (1.0 - pop), 0.0, Vector2(pop, pop))
	txt("YOU GOT A STAR!", Vector2(0, s.y * 0.22 + 22), 64, Color(1, 0.86, 0.3), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	txt(String(op.objective(op.cel_id).get("name", "")), Vector2(0, s.y * 0.22 + 66), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	txt("★ %d / %d" % [op.count(), op.ids().size()], Vector2(0, s.y - 30), 38, Color("ffe14a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 9)


func _star_shape(c: Vector2, r: float, col: Color) -> void:
	var pts := PackedVector2Array()
	for i in 10:
		var a := -PI / 2 + TAU * i / 10.0
		pts.append(c + Vector2(cos(a), sin(a)) * (r if i % 2 == 0 else r * 0.45))
	draw_colored_polygon(pts, col)


func _draw_controls() -> void:
	if game.state == "minigame" and game.minigames and not game.minigames.uses_stick():
		_draw_buttons_only()
		return
	_draw_stick_and_buttons()


func _draw_buttons_only() -> void:
	_draw_stick_and_buttons(false)


func _draw_stick_and_buttons(with_stick := true) -> void:
	if with_stick:
		_draw_stick()
	var labels := {"jump": "JUMP", "action": game.action_label(), "swap": "SWAP", "super": "SUPER", "pause": "",
		"build": "BUILD", "decor": "DECOR", "partner": "TEAM", "place": "PLACE", "remove": "REMOVE", "done": "DONE", "rotate": "TURN", "land": "LAND", "blastoff": "BLAST OFF!", "paint": "PAINT",
		"fly": "LAND" if game.flying else "FLY", "down": "DOWN", "movies": "MOVIES", "mega": "MEGA!",
		"fireworks": "BOOM!", "sleep": "SLEEP"}
	if game.flying:
		labels["jump"] = "UP"
	if game.state == "minigame" and game.minigames:
		labels["jump"] = game.minigames.button_label()
	if game.state == "kart":
		labels["jump"] = "HOP"
	for k in buttons:
		var c: Vector2 = buttons[k][0]
		var rad: float = buttons[k][1]
		var col: Color = button_color(k)
		if k == "land" and (game.sky == null or game.sky.near_info().is_empty() or not game.sky.near_info().ok):
			col = Color(0.42, 0.45, 0.52)   # nothing to land on here
		var lab: String = String(labels.get(k, k.to_upper()))
		_draw_button(k, c, rad, col, lab, _icon_for(k, lab), is_held(k))


# ------------------------------------------------------------------ the touch buttons (look)
var press_t := {}            # button -> seconds since it was pressed (squash + ripple)
var seen_t := {}             # button -> seconds since it appeared (a glow pulse for new actions)
var _btn_last := {}          # button -> its last label (a changed label pulses too)
const GLOW_NEW := ["action", "blastoff", "mega", "land", "movies", "fireworks", "sleep", "paint", "down", "rotate"]


## The colour language: green = move (jump, land, place), orange = action, blue = team / swap,
## gold = super, red = big / danger (blast off, remove), pink = decorate.
func button_color(k: String) -> Color:
	match k:
		"jump", "place", "land":
			return Color("33b860")
		"action", "build":
			return Color("ff8a1f")
		"swap", "partner":
			return Color("5d6cf2")
		"super":
			return Color("ffc21a")
		"pause":
			return Color(0.2, 0.25, 0.42)
		"decor":
			return Color("ff5fa2")
		"remove", "blastoff", "movies":
			return Color("e8413b")
		"done", "down":
			return Color("3f7fd9")
		"rotate":
			return Color("8a5bc4")
		"paint":
			return Decor.PAINTS[game.home.paint] if game.home and game.home.paint > 0 else Color("ff6fa8")
		"fly":
			return Color("ffb020") if game.flying else Color("3cc4f0")
		"mega":
			return Color.from_hsv(fmod(title_t * 0.5, 1.0), 0.65, 1.0)
		"fireworks":
			return Color("ff6fd8")
		"sleep":
			return Color("3b4fa0")
	return Color("8a8e96")


## Which little picture goes on a button.
func _icon_for(k: String, lab: String) -> String:
	match k:
		"jump":
			return "down" if lab == "WAIT" else ("check" if lab == "OK" else "up")
		"action":
			match lab:
				"LONG JUMP":
					return "long"
				"BACKFLIP":
					return "backflip"
				"POUND":
					return "pound"
				"DIVE":
					return "dive"
				"GRAB", "GRAB TAIL", "WIGGLE":
					return "hand"
				"THROW":
					return "throw"
				"SPIN!":
					return "rotate"
				"MINE":
					return "pick"
				"ITEM":
					return "box"
				"TONGUE":
					return "tongue"
			return "burst"
		"super":
			return "star"
		"mega":
			return "gem"
		"pause":
			return "pause"
		"build":
			return "hammer"
		"decor":
			return "flower"
		"partner":
			return "team"
		"fly":
			return "down" if game.flying else "wing"
		"land", "blastoff":
			return "rocket"
		"place":
			return "plus"
		"remove":
			return "minus"
		"done":
			return "check"
		"rotate":
			return "rotate"
		"paint":
			return "drop"
		"down":
			return "down"
		"movies":
			return "tv"
		"fireworks":
			return "burst"
		"sleep":
			return "moon"
	return ""


## A glossy, chunky toy button: a dark side under it (it sinks when pressed), a shine on top,
## a picture and a small label. New actions pulse; a tap sends a ripple out.
func _draw_button(k: String, c: Vector2, rad: float, col: Color, lab: String, icon: String, down: bool) -> void:
	var pt: float = press_t.get(k, 9.0)
	var st: float = seen_t.get(k, 9.0)
	var depth := rad * 0.11
	var sink := depth * (0.85 if down else (0.6 if pt < 0.08 else 0.0))
	var sq := 0.93 if down else 1.0 + maxf(0.0, 0.12 - pt) * 0.6   # squash in, then a tiny bounce out
	# glow for an action that just appeared (or whose label just changed)
	if k in GLOW_NEW and st < 1.6:
		var g := 1.0 - st / 1.6
		draw_circle(c, rad * (1.12 + 0.12 * sin(st * 12.0)), Color(1, 0.95, 0.6, 0.35 * g))
	if k == "mega":
		draw_circle(c, rad + 8 + sin(title_t * 8) * 4, Color(1, 0.95, 0.5, 0.35))
	# shadow + the button's side
	draw_circle(c + Vector2(0, depth + rad * 0.08), rad * 1.02, Color(0, 0, 0, 0.25))
	draw_circle(c + Vector2(0, depth), rad, col.darkened(0.45))
	var f := c + Vector2(0, sink)
	draw_set_transform(f * (1.0 - sq), 0.0, Vector2(sq, sq))
	var face := col.darkened(0.18) if down else col
	if k == "super" and game.state == "play" and game.super_charge < 1.0:
		face = Color(0.16, 0.17, 0.28)
	draw_circle(f, rad, face)
	draw_circle(f + Vector2(0, -rad * 0.1), rad * 0.84, face.lightened(0.12))
	_ellipse(f + Vector2(0, -rad * 0.48), rad * 0.6, rad * 0.26, Color(1, 1, 1, 0.3))
	draw_arc(f, rad - 1.5, 0, TAU, 48, Color(1, 1, 1, 0.9), 3.0, true)
	# SUPER charges round its rim
	if k == "super" and game.state == "play":
		var fc: float = game.super_charge
		if fc < 1.0:
			draw_arc(f, rad - 6, -PI / 2, -PI / 2 + TAU * fc, 48, Color("ffc21a"), 7, true)
		else:
			draw_arc(f, rad + 5 + 3 * sin(title_t * 8), 0, TAU, 48, Color(1, 0.92, 0.4, 0.8), 4, true)
	var has_label := lab != "" and k != "pause"
	if k == "swap":   # the face of who you'll swap to, with little swap arrows
		var nxt: String = game.next_swap_name()
		fit(img(game.face(nxt)), Rect2(f - Vector2(rad * 0.62, rad * 0.78), Vector2(rad * 1.24, rad * 1.2)))
		_icon("swap", f, rad * 0.95, Color.WHITE)
	elif icon != "":
		_icon(icon, f + Vector2(0, -rad * (0.17 if has_label else 0.0)), rad * (0.38 if has_label else 0.48), Color.WHITE)
	if has_label:
		var fs := clampi(int(rad * (0.27 if lab.length() <= 6 else 0.21)), 12, 26)
		var y := f.y + rad * 0.55
		if k == "swap":
			y = c.y + rad + 26
			fs = 22
		txt(lab, Vector2(f.x - rad, y), fs, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, rad * 2, 5)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	# tap ripple
	if pt < 0.4:
		var a := 1.0 - pt / 0.4
		draw_arc(c, rad * (1.0 + pt * 1.3), 0, TAU, 40, Color(1, 1, 1, 0.7 * a), 4.0 * a + 1.0, true)


func _ellipse(c: Vector2, rx: float, ry: float, col: Color) -> void:
	var pts := PackedVector2Array()
	for i in 20:
		var a := TAU * i / 20.0
		pts.append(c + Vector2(cos(a) * rx, sin(a) * ry))
	draw_colored_polygon(pts, col)


## Little white pictures for the buttons, drawn with lines (a dark edge first so they read on any colour).
func _icon(id: String, c: Vector2, r: float, col: Color) -> void:
	var dark := Color(0.1, 0.07, 0.16, 0.55)
	for pass_i in 2:
		var cc := dark if pass_i == 0 else col
		var w := r * (0.34 if pass_i == 0 else 0.2)
		var o := Vector2(0, r * 0.06) if pass_i == 0 else Vector2.ZERO
		var p := c + o
		match id:
			"up":
				_arrow(p, Vector2(0, -1), r, w, cc)
			"down":
				_arrow(p, Vector2(0, 1), r, w, cc)
			"long":
				_arrow(p + Vector2(r * 0.1, 0), Vector2(1, 0), r, w, cc)
				draw_line(p + Vector2(-r * 0.95, -r * 0.45), p + Vector2(-r * 0.45, -r * 0.45), cc, w * 0.6, true)
				draw_line(p + Vector2(-r * 0.95, r * 0.45), p + Vector2(-r * 0.45, r * 0.45), cc, w * 0.6, true)
			"backflip":
				draw_arc(p + Vector2(0, r * 0.1), r * 0.7, PI * 0.15, PI * 1.6, 20, cc, w, true)
				_head(p + Vector2(cos(PI * 0.15), sin(PI * 0.15)) * r * 0.7 + Vector2(0, r * 0.1), Vector2(-0.3, 1).normalized(), r * 0.45, cc)
			"pound":
				_arrow(p + Vector2(0, -r * 0.15), Vector2(0, 1), r * 0.85, w, cc)
				draw_line(p + Vector2(-r, r * 0.85), p + Vector2(r, r * 0.85), cc, w * 0.8, true)
			"dive":
				_arrow(p, Vector2(1, 0.75).normalized(), r, w, cc)
			"hand", "throw":
				draw_circle(p + Vector2(0, r * 0.25), r * 0.48, cc)
				for i in 4:
					var fx := -r * 0.42 + i * r * 0.28
					draw_line(p + Vector2(fx, r * 0.05), p + Vector2(fx, -r * 0.6 + absf(i - 1.5) * r * 0.12), cc, w * 0.75, true)
				draw_line(p + Vector2(-r * 0.45, r * 0.3), p + Vector2(-r * 0.8, -r * 0.05), cc, w * 0.75, true)
				if id == "throw":
					draw_arc(p + Vector2(r * 0.3, -r * 0.1), r * 0.85, -PI * 0.9, -PI * 0.25, 12, cc, w * 0.6, true)
			"pick":
				draw_line(p + Vector2(-r * 0.6, r * 0.8), p + Vector2(r * 0.3, -r * 0.3), cc, w, true)
				draw_arc(p + Vector2(r * 0.55, r * 0.05), r * 0.75, -PI * 0.95, -PI * 0.25, 14, cc, w * 1.1, true)
			"burst":
				for i in 8:
					var a := TAU * i / 8.0 + 0.2
					draw_line(p + Vector2(cos(a), sin(a)) * r * 0.3, p + Vector2(cos(a), sin(a)) * r * (0.95 if i % 2 == 0 else 0.7), cc, w * 0.8, true)
				draw_circle(p, r * 0.28, cc)
			"star":
				_star_shape(p, r * 1.0, cc)
			"gem":
				draw_colored_polygon(PackedVector2Array([p + Vector2(0, -r), p + Vector2(r * 0.8, -r * 0.2), p + Vector2(0, r), p + Vector2(-r * 0.8, -r * 0.2)]), cc)
			"pause":
				draw_line(p + Vector2(-r * 0.32, -r * 0.6), p + Vector2(-r * 0.32, r * 0.6), cc, w * 1.2, true)
				draw_line(p + Vector2(r * 0.32, -r * 0.6), p + Vector2(r * 0.32, r * 0.6), cc, w * 1.2, true)
			"hammer":
				draw_line(p + Vector2(-r * 0.55, r * 0.85), p + Vector2(r * 0.25, -r * 0.2), cc, w, true)
				var hp := PackedVector2Array()
				for v in [Vector2(-0.1, -0.95), Vector2(0.95, 0.1), Vector2(0.6, 0.45), Vector2(-0.45, -0.6)]:
					hp.append(p + v * r)
				draw_colored_polygon(hp, cc)
			"flower":
				for i in 5:
					var a := TAU * i / 5.0
					draw_circle(p + Vector2(cos(a), sin(a)) * r * 0.48, r * 0.34, cc)
				draw_circle(p, r * 0.28, Color("ffd23a") if pass_i == 1 else cc)
			"team":
				draw_circle(p + Vector2(-r * 0.4, -r * 0.35), r * 0.3, cc)
				draw_circle(p + Vector2(r * 0.4, -r * 0.35), r * 0.3, cc)
				_ellipse(p + Vector2(-r * 0.4, r * 0.45), r * 0.42, r * 0.38, cc)
				_ellipse(p + Vector2(r * 0.4, r * 0.45), r * 0.42, r * 0.38, cc)
			"wing":
				for i in 3:
					draw_arc(p + Vector2(r * 0.4, r * 0.2), r * (0.45 + i * 0.28), PI * 0.95, PI * 1.5, 10, cc, w * 0.8, true)
			"rocket":
				draw_colored_polygon(PackedVector2Array([p + Vector2(0, -r), p + Vector2(r * 0.38, -r * 0.35), p + Vector2(r * 0.38, r * 0.5),
					p + Vector2(-r * 0.38, r * 0.5), p + Vector2(-r * 0.38, -r * 0.35)]), cc)
				draw_colored_polygon(PackedVector2Array([p + Vector2(r * 0.38, r * 0.1), p + Vector2(r * 0.72, r * 0.7), p + Vector2(r * 0.38, r * 0.5)]), cc)
				draw_colored_polygon(PackedVector2Array([p + Vector2(-r * 0.38, r * 0.1), p + Vector2(-r * 0.72, r * 0.7), p + Vector2(-r * 0.38, r * 0.5)]), cc)
				if pass_i == 1:
					draw_circle(p + Vector2(0, -r * 0.2), r * 0.16, Color(0.3, 0.6, 0.95))
					draw_colored_polygon(PackedVector2Array([p + Vector2(-r * 0.22, r * 0.55), p + Vector2(r * 0.22, r * 0.55), p + Vector2(0, r * (0.95 + 0.1 * sin(title_t * 30)))]), Color("ffb020"))
			"plus":
				draw_line(p + Vector2(-r * 0.7, 0), p + Vector2(r * 0.7, 0), cc, w * 1.2, true)
				draw_line(p + Vector2(0, -r * 0.7), p + Vector2(0, r * 0.7), cc, w * 1.2, true)
			"minus":
				draw_line(p + Vector2(-r * 0.7, 0), p + Vector2(r * 0.7, 0), cc, w * 1.2, true)
			"check":
				draw_polyline(PackedVector2Array([p + Vector2(-r * 0.7, 0), p + Vector2(-r * 0.15, r * 0.55), p + Vector2(r * 0.75, -r * 0.55)]), cc, w * 1.1, true)
			"rotate":
				draw_arc(p, r * 0.7, -PI * 0.3, PI * 1.3, 20, cc, w, true)
				_head(p + Vector2(cos(-PI * 0.3), sin(-PI * 0.3)) * r * 0.7, Vector2(0.5, 1).normalized(), r * 0.45, cc)
			"swap":
				draw_arc(c, r, -PI * 0.85, -PI * 0.55, 8, cc, w * 0.6, true)
				draw_arc(c, r, PI * 0.15, PI * 0.45, 8, cc, w * 0.6, true)
			"drop":
				draw_circle(p + Vector2(0, r * 0.25), r * 0.55, cc)
				draw_colored_polygon(PackedVector2Array([p + Vector2(0, -r), p + Vector2(r * 0.5, r * 0.05), p + Vector2(-r * 0.5, r * 0.05)]), cc)
			"tv":
				draw_rect(Rect2(p - Vector2(r * 0.85, r * 0.6), Vector2(r * 1.7, r * 1.15)), cc, false, w * 0.8)
				draw_line(p + Vector2(-r * 0.35, -r * 0.6), p + Vector2(-r * 0.6, -r), cc, w * 0.6, true)
				draw_line(p + Vector2(r * 0.35, -r * 0.6), p + Vector2(r * 0.6, -r), cc, w * 0.6, true)
			"moon":
				draw_arc(p, r * 0.7, PI * 0.35, PI * 1.65, 18, cc, w * 1.4, true)
			"box":
				draw_rect(Rect2(p - Vector2(r * 0.7, r * 0.7), Vector2(r * 1.4, r * 1.4)), cc, false, w)
				draw_circle(p + Vector2(0, r * 0.35), r * 0.1, cc)
				draw_arc(p + Vector2(0, -r * 0.15), r * 0.25, PI, TAU + 0.6, 10, cc, w * 0.5, true)
			"tongue":
				draw_circle(p + Vector2(-r * 0.45, 0), r * 0.42, cc)
				draw_line(p + Vector2(-r * 0.1, 0), p + Vector2(r * 0.8, 0), cc, w, true)
				draw_circle(p + Vector2(r * 0.85, 0), r * 0.2, cc)


func _arrow(c: Vector2, d: Vector2, r: float, w: float, col: Color) -> void:
	draw_line(c - d * r * 0.75, c + d * r * 0.2, col, w, true)
	_head(c + d * r * 0.85, d, r * 0.7, col)


func _head(tip: Vector2, d: Vector2, s: float, col: Color) -> void:
	var n := Vector2(-d.y, d.x)
	draw_colored_polygon(PackedVector2Array([tip, tip - d * s + n * s * 0.6, tip - d * s - n * s * 0.6]), col)


## The thumb stick: a soft ring with a glow on the side you push, and a shiny knob.
func _draw_stick() -> void:
	var r := 70.0 * clampf(size.y / 720.0, 0.75, 1.6)
	var base := stick_base if stick_id >= 0 else Vector2(r * 2.1, size.y - r * 2.1)
	var on := stick_id >= 0
	draw_circle(base + Vector2(0, 6), r * 1.22, Color(0, 0, 0, 0.18))
	draw_circle(base, r * 1.22, Color(0.08, 0.12, 0.28, 0.32 if on else 0.22))
	draw_arc(base, r * 1.22, 0, TAU, 56, Color(1, 1, 1, 0.55 if on else 0.35), 4, true)
	draw_arc(base, r * 0.7, 0, TAU, 40, Color(1, 1, 1, 0.12), 2, true)
	if stick.length() > 0.15:   # a glowing arc on the side you're pushing
		var a := stick.angle()
		var glow := Color("ff8a1f") if game.sprinting else Color("8fe3ff")
		draw_arc(base, r * 1.22, a - 0.6, a + 0.6, 16, Color(glow, 0.9), 8, true)
	if game.sprinting:
		txt("SPRINT!", Vector2(base.x - r * 1.2, base.y - r * 1.4), 24, Color("ffb35a"), HORIZONTAL_ALIGNMENT_CENTER, r * 2.4, 6)
	var k := base + stick * r
	draw_circle(k + Vector2(0, 5), r * 0.56, Color(0, 0, 0, 0.25))
	var kc := Color("ffd6a8") if game.sprinting else Color(0.95, 0.96, 1.0, 0.92)
	draw_circle(k, r * 0.55, kc)
	draw_circle(k + Vector2(0, -r * 0.06), r * 0.44, kc.lightened(0.15))
	_ellipse(k + Vector2(0, -r * 0.26), r * 0.3, r * 0.13, Color(1, 1, 1, 0.6))
	draw_arc(k, r * 0.55, 0, TAU, 32, Color(0.2, 0.25, 0.45, 0.5), 2.5, true)


func _draw_crosshair() -> void:
	var c := size * 0.5
	for w in [[6.0, Color(0.05, 0.05, 0.1, 0.7)], [3.0, Color.WHITE]]:
		draw_line(c - Vector2(16, 0), c + Vector2(16, 0), w[1], w[0])
		draw_line(c - Vector2(0, 16), c + Vector2(0, 16), w[1], w[0])
	if hold_pos.x >= 0:   # breaking ring under the finger
		draw_arc(hold_pos, 34, 0, TAU, 32, Color("ff8a6a"), 5, true)


# ------------------------------------------------------------------ BUILD / DECOR palette
const PAL_BOX := 54.0
const PAL_GAP := 6.0


func _pal_items() -> Array:
	var h = game.home
	var out := []
	if h.mode == "build":
		for b in HomeBase.BLOCKS:
			var t: int = b.t
			out.append({"icon": img("t_" + _tex_name(t)), "name": Level.NAMES[t], "cost": b.cost})
	elif h.mode == "decor":
		for it in Decor.ITEMS:
			var locked: bool = Decor.locked(it, game)
			out.append({"icon": img("decor_" + it.id), "name": it.name, "cost": {"coins": it.coins}, "locked": locked})
	return out


static func _tex_name(t: int) -> String:
	return ["", "grass_top", "dirt", "stone", "iron", "gold", "diamond", "bedrock", "brick", "mystery", "used",
		"planks", "wool_red", "wool_blue", "wool_yellow", "wool_white", "window", "iron_block", "gold_block", "diamond_block", "stone_brick",
		"snow_top", "ice", "sand", "sandstone", "log_side", "leaves", "wool_green", "wool_black", "road",
		"swamp_top", "mud", "moon_top", "moon_rock",
		"lava", "basalt", "frosting_top", "cake", "candy_cane", "water", "cloud", "lantern"][t]


func _pal_rect(i: int, n: int) -> Rect2:
	var x0 := 560.0
	var room := size.x - 110 - x0
	var per := maxi(1, int(room / (PAL_BOX + PAL_GAP)))
	per = maxi(per, int(ceil(n / 3.0)))   # never more than 3 rows: the boxes get a bit smaller instead
	var bx := minf(PAL_BOX, room / per - PAL_GAP)
	var row := i / per
	var col := i % per
	return Rect2(x0 + col * (bx + PAL_GAP), 12 + row * (bx + PAL_GAP), bx, bx)


## DECOR mode: the paint pots under the decorations (0 = the item's own colours).
const SWATCH := 34.0


func _swatch_rect(i: int) -> Rect2:
	var n := _pal_items().size()
	var y := _pal_rect(n - 1, n).end.y + 12
	return Rect2(560 + i * (SWATCH + 10), y, SWATCH, SWATCH)


func _draw_swatches() -> void:
	var h = game.home
	for i in Decor.PAINTS.size():
		var r := _swatch_rect(i)
		var c := r.get_center()
		if h.paint == i:
			draw_circle(c, SWATCH * 0.5 + 6, Color("ffd23a"))
		draw_circle(c, SWATCH * 0.5 + 2, Color(0.1, 0.12, 0.25, 0.8))
		if i == 0:   # its own colours: a little rainbow
			var cols := [Color("e8413b"), Color("ffd23a"), Color("4cb048"), Color("3f7fd9")]
			for k in 4:
				draw_arc(c, SWATCH * 0.3, k * TAU / 4, (k + 1) * TAU / 4, 10, cols[k], SWATCH * 0.36)
		else:
			draw_circle(c, SWATCH * 0.5, Decor.PAINTS[i])


func _palette_tap(p: Vector2) -> bool:
	var items := _pal_items()
	if game.home.mode == "decor":
		for i in Decor.PAINTS.size():
			if _swatch_rect(i).grow(4).has_point(p):
				game.home.paint = i
				game.sfx("bonk", -10)
				return true
	for i in items.size():
		if _pal_rect(i, items.size()).grow(4).has_point(p):
			game.home.sel[game.home.mode] = i
			game.sfx("bonk", -10)
			return true
	return false


func _cost_text(cost: Dictionary) -> String:
	var parts := []
	for k in cost:
		var n := int(cost[k])
		if n == 0:
			parts.append("FREE")
		else:
			parts.append("%d %s" % [n, "coin" + ("s" if n > 1 else "") if k == "coins" else k.to_upper()])
	return ", ".join(parts) if parts.size() > 0 else "FREE"


func _draw_palette() -> void:
	var h = game.home
	var items := _pal_items()
	var last := Rect2()
	for i in items.size():
		var r := _pal_rect(i, items.size())
		last = r
		var sel: bool = h.sel[h.mode] == i
		draw_rect(r.grow(5) if sel else r.grow(1), Color("ffd23a") if sel else Color(0.1, 0.12, 0.25, 0.7))
		draw_rect(r, Color(1, 1, 1, 0.92))
		fit(items[i].icon, r.grow(-6))
		if items[i].get("locked", false):
			draw_rect(r, Color(0.1, 0.1, 0.2, 0.55))
			txt("?", Vector2(r.position.x, r.position.y + r.size.y * 0.7), 36, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 6)
	# the chosen one: name and price, and why it can't go there
	var it: Dictionary = items[h.sel[h.mode]]
	var y := last.end.y + 34
	var name: String = it.name
	if h.mode == "decor":
		_draw_swatches()
		y = _swatch_rect(0).end.y + 30
		var id: String = Decor.ITEMS[h.sel.decor].id
		if h.paint > 0 and Decor.paintable(id):
			name = "%s %s" % [Decor.PAINT_NAMES[h.paint], name]
	txt("%s  -  %s" % [name, _cost_text(it.cost)], Vector2(560, y), 24, Color("fff2a8"), HORIZONTAL_ALIGNMENT_LEFT, -1, 7)
	txt("TAP to place   HOLD to break   DRAG to look around", Vector2(560, y + 28), 20, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
	if not h.can_place and h.why != "" and h.has_under:
		txt(h.why, Vector2(560, y + 56), 22, Color("ffb0a8"), HORIZONTAL_ALIGNMENT_LEFT, -1, 6)


func _draw_map_info() -> void:
	var sky = game.sky
	var ni: Dictionary = sky.near_info()
	var s := size
	if sky.auto_to != "":
		txt("Flying to " + sky.short_name(sky.auto_to) + "...", Vector2(0, 120), 36, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 9)
	elif ni.is_empty():
		txt("Tap an island to fly there!", Vector2(0, 120), 32, Color(1, 1, 1, 0.9), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		txt("or steer with the stick (all the way = BOOST)", Vector2(0, 156), 22, Color(1, 1, 1, 0.65), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	else:
		txt(ni.name, Vector2(0, 120), 44, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
		if ni.ok:
			txt("Tap LAND!", Vector2(0, 160), 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		else:
			txt("LOCKED - " + ni.why, Vector2(0, 160), 28, Color("ffb0a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	# a button for every island along the bottom: tap one and the rocket flies there by itself
	var n: int = sky.order.size()
	for i in n:
		var id: String = sky.order[i]
		var r := island_button_rect(i, n)
		var ok: bool = sky.can_land(id)
		var col := Color("3fc06a") if id == "home" else (Color("3f7fd9") if ok else Color(0.35, 0.38, 0.45))
		var picked: bool = sky.auto_to == id or sky.near_id == id
		if picked:
			card(r.grow(5), Color("ffd23a"))
		card(r, col, picked)
		var label: String = sky.short_name(id) if ok else "LOCKED"
		txt(label, Vector2(r.position.x, r.position.y + r.size.y * 0.5 + 8), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 6)


## The island buttons sit in a row along the bottom middle (between the stick and LAND).
## The island buttons along the bottom: one row if they fit between the stick and LAND,
## else two rows (there are lots of worlds now).
func island_button_rect(i: int, n: int) -> Rect2:
	var room := size.x - 560.0
	var per := maxi(1, int((room + 10.0) / 120.0))
	var rows := 1 if n <= per else 2
	var in_row := int(ceil(float(n) / rows))
	var w := clampf((room + 10.0) / in_row - 10.0, 100.0, 190.0)
	var row := i / in_row
	var col := i % in_row
	var count := in_row if row < rows - 1 else n - in_row * (rows - 1)
	var total := count * (w + 10.0) - 10.0
	var x0 := (size.x - total) * 0.5
	var h := 64.0 if rows == 1 else 52.0
	var y := size.y - 86.0 if rows == 1 else size.y - 132.0 + row * 62.0
	return Rect2(x0 + col * (w + 10.0), y, w, h)


func island_button_at(p: Vector2) -> String:
	if game.sky == null:
		return ""
	var n: int = game.sky.order.size()
	for i in n:
		if island_button_rect(i, n).grow(6).has_point(p):
			return game.sky.order[i]
	return ""


func update_showing() -> bool:
	return game.updater != null and game.updater.status in ["available", "downloading", "installing", "error"]


func look_rect() -> Rect2:
	return Rect2(24, size.y - 150, 330, 96)


func _draw_look() -> void:
	var r := look_rect()
	txt("LOOK", Vector2(r.position.x, r.position.y - 10), 24, Color(1, 1, 1, 0.9))
	var half := Rect2(r.position, Vector2(r.size.x * 0.5, r.size.y))
	for i in 2:
		var on: bool = (i == 1) == Look.toy
		var box := Rect2(r.position + Vector2(i * r.size.x * 0.5, 0), half.size)
		card(box.grow(-3), Color("ff8a1f") if on else Color(0.1, 0.15, 0.3, 0.75), on)
		txt("PAPER" if i == 0 else "3D TOYS", Vector2(box.position.x, box.position.y + 60), 30 if on else 26,
			Color.WHITE if on else Color(1, 1, 1, 0.7), HORIZONTAL_ALIGNMENT_CENTER, box.size.x, 7)


func update_rect() -> Rect2:
	return Rect2(size.x - 330, size.y - 150, 300, 104)


func _draw_update() -> void:
	var u = game.updater
	if u == null:
		return
	if u.can_update():
		txt("v0.%d" % u.my_version, Vector2(24, size.y - 22), 22, Color(1, 1, 1, 0.8))
	if u.message != "" and u.status != "checking":
		txt(u.message, Vector2(0, size.y - 170 if update_showing() else size.y - 22), 26, Color("fff2a8") if u.status == "available" else Color.WHITE, HORIZONTAL_ALIGNMENT_RIGHT, size.x - 30, 8)
	if not update_showing():
		return
	var r := update_rect()
	var glow := 0.5 + 0.5 * sin(title_t * 6)
	var col := Color("3fc06a") if u.status != "downloading" else Color("6a7cff")
	if u.status == "available":
		draw_rect(r.grow(6 + glow * 6), Color(1, 0.95, 0.4, 0.35))
	card(r, col, u.status == "available")
	if u.status == "downloading":
		draw_rect(Rect2(r.position + Vector2(10, r.size.y - 26), Vector2((r.size.x - 20) * u.progress, 14)), Color("ffd23a"))
		txt("%d%%" % int(u.progress * 100), Vector2(r.position.x, r.position.y + 58), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 8)
	else:
		txt("UPDATE!", Vector2(r.position.x, r.position.y + 66), 52, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 10)


## Every text size the game uses later, drawn invisibly on the title screen (one per frame),
## so the letters are ready before play and nothing has to be prepared mid-game.
var warm_sizes := [12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32, 33, 34, 35, 36, 38, 40, 44, 46, 52, 54, 56, 64, 76, 80, 84, 88, 92]
const WARM_TEXT := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 %!?&.,:;'/+-→()"


## The first few frames: a plain screen while every text size and effect gets ready.
## Freezing here is invisible, so the title and the game run smoothly afterwards.
func _draw_loading() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color("2a6fb8"))
	txt("LOADING...", Vector2(0, s.y * 0.5), 48, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
	# pictures and shapes the game screen uses (so starting a game doesn't hitch)
	for l in Mons.ORDER:
		for st in Mons.LINES[l].names.size():
			draw_texture_rect(img(Mons.face(l, st)), Rect2(-500, -500, 40, 40), false)
	for n in ["heart", "heart_empty", "coin", "kragjr_1"]:
		draw_texture_rect(img(n), Rect2(-500, -500, 40, 40), false)
	for id in Team.ORDER:   # every hero's face (TEAM screen, SWAP button, save cards)
		draw_texture_rect(img(id + "_idle"), Rect2(-500, -500, 40, 40), false)
	for it in Decor.ITEMS:
		draw_texture_rect(img("decor_" + it.id), Rect2(-500, -500, 40, 40), false)
	for k in Powers.KINDS:
		draw_texture_rect(img("pw_" + k), Rect2(-500, -500, 40, 40), false)
	for b in HomeBase.BLOCKS:
		draw_texture_rect(img("t_" + _tex_name(b.t)), Rect2(-500, -500, 40, 40), false)
	draw_circle(Vector2(-300, -300), 40, Color(1, 1, 1, 0.01))
	draw_arc(Vector2(-300, -300), 40, 0, TAU, 48, Color(1, 1, 1, 0.01), 3, true)
	draw_arc(Vector2(-300, -300), 30, -PI / 2, 1.0, 48, Color(1, 1, 1, 0.01), 8, true)
	draw_rect(Rect2(-300, -300, 30, 30), Color(1, 1, 1, 0.01), false, 2.0)
	# the game screen's buttons and panels once, off-screen
	draw_set_transform(Vector2(-6000, -6000), 0.0, Vector2.ONE)
	_draw_hud()
	_draw_controls()
	card(Rect2(0, 0, 200, 80), Color("3fc06a"), true)       # the rounded menu cards
	paper_card(Rect2(0, 100, 200, 160), Color("3f7fd9"), 40, true)
	var keep_w := closing
	closing = 0.1
	_draw_wipe()                                             # and the star wipe
	closing = keep_w
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	for i in 7:
		if warm_sizes.is_empty():
			break
		var sz: int = warm_sizes.pop_back()
		for o in [0, 6, 7, 8, 9, 10, 12, 14, 16, 18]:
			if o <= sz / 3:
				draw_string_outline(font, Vector2(-4000, -4000), WARM_TEXT, HORIZONTAL_ALIGNMENT_LEFT, -1, sz, o, Color(0, 0, 0, 0.01))
		draw_string(font, Vector2(-4000, -4000), WARM_TEXT, HORIZONTAL_ALIGNMENT_LEFT, -1, sz, Color(0, 0, 0, 0.01))


func _draw_title() -> void:
	var s := size
	# the logo drops in and bounces, then every letter rides a little wave
	var drop := 0.0
	if title_t < 1.1:
		var k := title_t / 1.1
		drop = -s.y * 0.5 * (1.0 - k) * absf(cos(k * PI * 2.5))
	_wave_text("ROCCO & BLOCKY", s.y * 0.24 + drop, 92, Color("ffd23a"), 0.0)
	var team_s := 1.0 + 0.06 * maxf(0.0, sin(title_t * 3.0)) if title_t > 1.1 else clampf((title_t - 0.5) * 2.0, 0.01, 1.0)
	var tc := Vector2(s.x * 0.5, s.y * 0.24 + 92 - 30)
	draw_set_transform(tc * (1.0 - team_s), sin(title_t * 2.0) * 0.03, Vector2(team_s, team_s))
	txt("TEAM UP!", Vector2(0, s.y * 0.24 + 92), 84, Color("ff4a3a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 18)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	txt("3D TOYS" if Look.toy else "PAPER 3D", Vector2(0, s.y * 0.24 + 150), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
	_draw_title_parade()
	_draw_update()
	_draw_look()
	_draw_buzz()
	# TAP TO START breathes instead of blinking
	var ps := 1.0 + 0.07 * sin(title_t * 4.0)
	var pc := Vector2(s.x * 0.5, s.y * 0.9 - 16)
	draw_set_transform(pc * (1.0 - ps), 0.0, Vector2(ps, ps))
	txt("TAP TO START", Vector2(0, s.y * 0.9), 46, Color(1, 1, 1, 0.75 + 0.25 * sin(title_t * 4.0)), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


## A line of text whose letters bob up and down one after another (fixed font size).
func _wave_text(t: String, y: float, fs: int, col: Color, phase: float) -> void:
	var w := font.get_string_size(t, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var x := (size.x - w) * 0.5
	for i in t.length():
		var ch := t[i]
		var cw := font.get_string_size(ch, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
		var dy := sin(title_t * 3.2 - i * 0.45 + phase) * 7.0
		txt(ch, Vector2(x, y + dy), fs, col, HORIZONTAL_ALIGNMENT_LEFT, -1, 18)
		x += cw


## The heroes hop across the bottom of the title screen, one after another.
func _draw_title_parade() -> void:
	var ids: Array = Team.ORDER
	var s := size
	var span := s.x + 400.0
	for i in ids.size():
		var x := fposmod(title_t * 150.0 - i * 95.0, span) - 200.0
		var hop := absf(sin(title_t * 9.0 + i * 1.3)) * 22.0
		var r := Rect2(x - 32, s.y * 0.78 - 64 - hop, 64, 64)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
		_ellipse(Vector2(x, s.y * 0.78 + 2), 26.0 - hop * 0.4, 7.0, Color(0, 0, 0, 0.25))
		draw_texture_rect(img(ids[i] + "_idle"), r, false)


func buzz_rect() -> Rect2:
	return Rect2(size.x - 196, 22, 172, 58)


func _draw_buzz() -> void:
	var r := buzz_rect()
	card(r, Color("33b860") if haptics else Color(0.25, 0.27, 0.35, 0.85))
	txt("BUZZ: " + ("ON" if haptics else "OFF"), Vector2(r.position.x, r.position.y + 40), 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 7)


func set_haptics(on: bool) -> void:
	haptics = on
	var cf := ConfigFile.new()
	cf.set_value("ui", "haptics", on)
	cf.save("user://ui.cfg")
	buzz(30)


func _draw_clear() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.55))
	var cx := s.x * 0.5
	txt("LEVEL CLEAR!", Vector2(0, s.y * 0.2), 88, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 16)
	txt("You beat Krag Jr.!", Vector2(0, s.y * 0.2 + 56), 36, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x)
	var y := s.y * 0.2 + 120
	draw_texture_rect(tex["coin"], Rect2(cx - 150, y, 48, 48), false)
	txt("%d coins" % game.coins, Vector2(cx - 92, y + 38), 40)
	y += 64
	var ox := cx - 260
	for o in ["stone", "iron", "gold", "diamond"]:
		draw_texture_rect(tex["t_" + o], Rect2(ox, y, 40, 40), false)
		txt(str(game.ores[o]), Vector2(ox + 48, y + 34), 36)
		ox += 135
	txt("Bad guys beaten: %d" % game.beaten, Vector2(0, y + 100), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x)
	if game.clear_t > 1.5 and int(title_t * 2) % 2 == 0:
		txt("TAP TO PLAY AGAIN", Vector2(0, s.y * 0.9), 44, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)


# ------------------------------------------------------------------ pause menu
const PAUSE_LABELS := ["RESUME", "SAVE", "SAVE & QUIT", "STARS"]
const PAUSE_COLORS := [Color("3fc06a"), Color("3f7fd9"), Color("ff8a1f"), Color("e0a800"), Color("1f6fb8"), Color("e8413b")]


func pause_rect(i: int) -> Rect2:
	var w := minf(460.0, size.x * 0.6)
	var step := 108.0 if game.pause_count() <= 3 else (92.0 if game.pause_count() == 4 else (82.0 if game.pause_count() == 5 else 72.0))
	return Rect2((size.x - w) * 0.5, size.y * 0.27 + i * step, w, step - 18)


func _draw_pause() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.7))
	txt("PAUSED", Vector2(0, s.y * 0.22), 76, Color(1, 0.86, 0.3), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	for i in game.pause_count():
		var r := pause_rect(i)
		var sel: bool = game.pause_sel == i
		var col: Color = PAUSE_COLORS[i]
		if i == 4:   # POSEIDON FLOOD: a deep-sea button with a trident; red when armed, grey when used
			col = Color(0.3, 0.32, 0.4) if game.flood_used else (Color("c0392b") if game.flood_armed_t > 0 else PAUSE_COLORS[4])
		card(r, col, sel)
		if i == 4:
			_trident(Vector2(r.position.x + 34, r.position.y + r.size.y * 0.5), 20)
		txt(game.pause_label(i), Vector2(r.position.x, r.position.y + (60 if r.size.y > 70 else 42)), 44 if i < 4 and r.size.y > 60 else 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 9)
	# lag finder (bottom left): screenshot this if the game stutters
	if game.slow_count > 0:
		var ly: float = s.y - 24.0 - game.slow.size() * 24.0
		txt("Slow moments: %d" % game.slow_count, Vector2(20, ly), 20, Color(1, 1, 1, 0.75), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
		for e in game.slow:
			ly += 24
			txt("%d ms  %s" % [e[0], e[1]], Vector2(20, ly), 18, Color(1, 1, 1, 0.6), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
	else:
		txt("No slow moments", Vector2(20, s.y - 24), 18, Color(1, 1, 1, 0.5), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
	var y := pause_rect(game.pause_count() - 1).end.y + 50
	if game.save_note_t > 0:
		txt(game.save_note, Vector2(0, y), 38, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 9)
	elif game.slot > 0:
		txt("Playing FILE %d" % game.slot, Vector2(0, y), 28, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)


# ------------------------------------------------------------------ save files screen
func slot_rect(i: int) -> Rect2:
	var gap := 28.0
	var w := minf(360.0, (size.x - 80 - gap * 2) / 3.0)
	var h := minf(400.0, size.y * 0.6)
	var x0 := (size.x - (w * 3 + gap * 2)) * 0.5
	return Rect2(x0 + i * (w + gap), size.y * 0.24, w, h)


func slot_erase_rect(i: int) -> Rect2:
	var r := slot_rect(i)
	return Rect2(r.position.x + 20, r.end.y + 16, r.size.x - 40, 58)


func back_rect() -> Rect2:
	return Rect2(24, 24, 170, 70)


func _slots_tap(p: Vector2) -> void:
	_slot_cache_t = -1.0   # show ERASE / UNDO results right away
	if back_rect().has_point(p):
		game.state = "title"
		return
	for i in 3:
		var n := i + 1
		if slot_erase_rect(i).has_point(p):
			if Saves.exists(n):
				game.slot_erase(n)
			elif Saves.can_undo(n):
				game.slot_undo(n)
			return
		if slot_rect(i).has_point(p):
			game.slot_sel = i
			game.slot_pick(n)
			return


var _slot_cache := [{}, {}, {}]
var _slot_cache_t := 0.0


func _draw_slots() -> void:
	var s := size
	if title_t - _slot_cache_t > 0.3 or _slot_cache_t == 0.0:   # re-read the files a few times a second, not every frame
		_slot_cache_t = title_t
		for i in 3:
			_slot_cache[i] = Saves.read(i + 1)
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.45))
	txt("PICK A SAVE FILE", Vector2(0, s.y * 0.17), 56, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	var b := back_rect()
	draw_rect(b, Color(0.1, 0.15, 0.3, 0.85))
	draw_rect(b, Color.WHITE, false, 3.0)
	txt("BACK", Vector2(b.position.x, b.position.y + 48), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, b.size.x, 7)
	for i in 3:
		var n := i + 1
		var r := slot_rect(i)
		var d: Dictionary = _slot_cache[i]
		var sel: bool = game.slot_sel == i
		paper_card(r, Color("3f7fd9"), 64, sel, Color("fdf4e3") if not d.is_empty() else Color(1, 1, 1, 0.75))
		txt("FILE %d" % n, Vector2(r.position.x, r.position.y + 47), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 8)
		var cx := r.position.x + r.size.x * 0.5
		if d.is_empty():
			draw_circle(Vector2(cx, r.position.y + r.size.y * 0.5), 46, Color("3fc06a"))
			draw_rect(Rect2(cx - 28, r.position.y + r.size.y * 0.5 - 8, 56, 16), Color.WHITE)
			draw_rect(Rect2(cx - 8, r.position.y + r.size.y * 0.5 - 28, 16, 56), Color.WHITE)
			txt("NEW GAME", Vector2(r.position.x, r.end.y - 40), 38, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		else:
			var who: String = d.get("human", "rocco")
			var tm: Array = d.get("team", ["rocco", "blocky"])
			if tm.size() == 2:   # the human slots hold whoever is on the team
				who = String(tm[0] if who == "rocco" else tm[1])
			if not Team.INFO.has(who):
				who = "rocco"
			fit(img(who + "_idle"), Rect2(r.position.x + 16, r.position.y + 78, 90, 112))
			var ml := String(d.get("mon", "spark"))
			if not ml in Mons.ORDER:
				ml = "spark"
			var mxp := int((d.get("mon_xp", {}) as Dictionary).get(ml, 0))
			fit(img(Mons.face(ml, Mons.stage_of(ml, Mons.level_of(mxp)))), Rect2(r.position.x + 84, r.position.y + 112, 84, 82))
			var tx := r.position.x + 170
			# how far this file has got: worlds cleared, and where a level was left off
			var cw: Dictionary = d.get("cleared_w", {"meadow": int(d.get("cleared", 0))} if int(d.get("cleared", 0)) > 0 else {})
			var done := 0
			for w in Worlds.ORDER:
				if int(cw.get(w, 0)) > 0:
					done += 1
			txt("WORLDS", Vector2(tx, r.position.y + 112), 22, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			txt("%d / %d" % [done, Worlds.ORDER.size()], Vector2(tx, r.position.y + 150), 38, Color("ff4a3a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
			if d.get("in_level", false):
				txt("IN " + String(Worlds.info(String(d.get("level", "meadow"))).short), Vector2(tx, r.position.y + 182), 16, Color("3f9a48"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			elif done > 0:
				txt("CLEARED!", Vector2(tx, r.position.y + 182), 22, Color("e0a800"), HORIZONTAL_ALIGNMENT_LEFT, -1, 4)
			var y := r.position.y + 210
			for k in 5:
				draw_texture_rect(tex["heart" if k < int(d.get("hearts", 5)) else "heart_empty"], Rect2(r.position.x + 20 + k * 34, y, 36, 36), false)
			y += 48
			draw_texture_rect(tex["coin"], Rect2(r.position.x + 20, y, 38, 38), false)
			txt("x %d" % int(d.get("coins", 0)), Vector2(r.position.x + 64, y + 30), 30, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			y += 52
			var ox := r.position.x + 20
			var o: Dictionary = d.get("ores", {})
			for k in ["stone", "iron", "gold", "diamond"]:
				draw_texture_rect(tex["t_" + k], Rect2(ox, y, 30, 30), false)
				txt(str(int(o.get(k, 0))), Vector2(ox + 34, y + 25), 24, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
				ox += (r.size.x - 40) / 4.0
			txt("TAP TO PLAY", Vector2(r.position.x, r.end.y - 18), 26, Color("3f7fd9"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		# ERASE (two taps) or UNDO ERASE
		var er := slot_erase_rect(i)
		if n == game.PROTECTED_SLOT and not d.is_empty():   # file 1 is safe: no ERASE
			draw_rect(er, Color(0.15, 0.25, 0.4, 0.85))
			draw_rect(er, Color.WHITE, false, 2.0)
			txt("SAFE FILE", Vector2(er.position.x, er.position.y + 40), 26, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, er.size.x, 6)
		elif not d.is_empty():
			var armed: bool = game.erase_armed == n
			draw_rect(er, Color("e8413b") if armed else Color(0.35, 0.12, 0.15, 0.85))
			draw_rect(er, Color.WHITE, false, 2.0)
			txt("TAP AGAIN TO ERASE" if armed else "ERASE", Vector2(er.position.x, er.position.y + 40), 24 if armed else 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, er.size.x, 6)
		elif Saves.can_undo(n):
			draw_rect(er, Color(0.15, 0.3, 0.2, 0.85))
			draw_rect(er, Color.WHITE, false, 2.0)
			txt("UNDO ERASE", Vector2(er.position.x, er.position.y + 40), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, er.size.x, 6)


# ------------------------------------------------------------------ PICK YOUR PARTNER
const PARTNER_COLS := 6      # PICK YOUR PARTNER: 2 rows of 6 cards
const PARTNER_GAP := 14.0


func _card_size() -> Vector2:
	var w := minf(200.0, (size.x - 60 - PARTNER_GAP * (PARTNER_COLS - 1)) / PARTNER_COLS)
	var h := minf(230.0, (size.y - size.y * 0.17 - 150 - PARTNER_GAP) / 2.0)
	return Vector2(w, h)


func partner_rect(i: int) -> Rect2:
	var c := _card_size()
	var x0 := (size.x - (c.x * PARTNER_COLS + PARTNER_GAP * (PARTNER_COLS - 1))) * 0.5
	var col := i % PARTNER_COLS
	var row := i / PARTNER_COLS
	return Rect2(x0 + col * (c.x + PARTNER_GAP), size.y * 0.17 + row * (c.y + PARTNER_GAP), c.x, c.y)


func go_rect() -> Rect2:
	var c := _card_size()
	return Rect2(size.x * 0.5 - 150, size.y * 0.17 + c.y * 2 + PARTNER_GAP + 58, 300, 70)


func _partner_tap(p: Vector2) -> void:
	if back_rect().has_point(p):
		game.partner_back()
		return
	if go_rect().has_point(p):
		game.choose_partner(Mons.ORDER[game.partner_sel])
		return
	for i in Mons.ORDER.size():
		if partner_rect(i).has_point(p):
			game.partner_tap(i)
			return


func _draw_partner() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.5))
	txt("PICK YOUR PARTNER", Vector2(0, s.y * 0.12), 52, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	var b := back_rect()
	draw_rect(b, Color(0.1, 0.15, 0.3, 0.85))
	draw_rect(b, Color.WHITE, false, 3.0)
	txt("BACK", Vector2(b.position.x, b.position.y + 48), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, b.size.x, 7)
	for i in Mons.ORDER.size():
		var l: String = Mons.ORDER[i]
		var info: Dictionary = Mons.LINES[l]
		var xp: int = game.mon_xp[l]
		var lv := Mons.level_of(xp)
		var st := Mons.stage_of(l, lv)
		var r := partner_rect(i)
		var sel: bool = game.partner_sel == i
		paper_card(r, info.color, 34, sel)
		txt(info.type, Vector2(r.position.x, r.position.y + 26), 20, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 6)
		var bob := sin(title_t * 4 + i) * 4 if sel else 0.0
		var fh := r.size.y - 34 - 74
		fit(img(Mons.face(l, st)), Rect2(r.position.x + 8, r.position.y + 38 + bob, r.size.x - 16, fh))
		var y := r.position.y + 38 + fh + 24
		txt(Mons.name_of(l, st), Vector2(r.position.x, y), 22, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		y += 24
		txt("Lv %d" % lv, Vector2(r.position.x, y), 20, Color("3f7fd9"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		y += 8
		draw_rect(Rect2(r.position.x + 16, y, r.size.x - 32, 9), Color(0.15, 0.15, 0.2))
		draw_rect(Rect2(r.position.x + 16, y, (r.size.x - 32) * Mons.progress(xp), 9), Color("54c8ff"))
	# the picked one: its moves, what it does, and when it evolves
	var ls: String = Mons.ORDER[game.partner_sel]
	var li: Dictionary = Mons.LINES[ls]
	var lvs := Mons.level_of(game.mon_xp[ls])
	var nxt := ""
	for e in li.evolve:
		if lvs < e:
			nxt = "   Evolves at Lv %d" % e
			break
	var iy := partner_rect(PARTNER_COLS).end.y + 30
	txt("%s   SUPER: %s%s" % [li.action, li.super, nxt], Vector2(0, iy), 24, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
	txt(li.about, Vector2(0, iy + 26), 20, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	var g := go_rect()
	var l2: String = Mons.ORDER[game.partner_sel]
	draw_rect(g, Color("3fc06a"))
	draw_rect(g, Color.WHITE, false, 3.0)
	txt("GO WITH %s!" % Mons.name_of(l2, Mons.stage_of(l2, Mons.level_of(game.mon_xp[l2]))), Vector2(g.position.x, g.position.y + 47), 32, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, g.size.x, 8)


# ------------------------------------------------------------------ cutscenes and MOVIES
## Black bars at the top and bottom, the words at the bottom, and "TAP TO SKIP".
func _draw_scene() -> void:
	var cs = game.cutscene
	var bar := size.y * 0.13
	draw_rect(Rect2(0, 0, size.x, bar), Color(0, 0, 0, 0.92))
	draw_rect(Rect2(0, size.y - bar * 1.35, size.x, bar * 1.35), Color(0, 0, 0, 0.92))
	if cs == null:
		return
	if cs.t < 2.5:
		txt(Cutscene.TITLES.get(cs.id, ""), Vector2(0, bar * 0.62), 30, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, size.x, 6)
	if cs.t > 1.0:
		txt("TAP TO SKIP  >>", Vector2(size.x - 260, bar * 0.62), 20, Color(1, 1, 1, 0.7), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	var y := size.y - bar * 1.35 + 44
	if cs.speaker != "":
		txt(cs.speaker, Vector2(0, y), 26, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, size.x, 6)
		y += 36
	if cs.caption != "":
		txt(cs.caption, Vector2(40, y), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, size.x - 80, 6)


func movie_rect(i: int) -> Rect2:
	var w := minf(270.0, (size.x - 120) / 4.0)
	var h := 150.0
	var x0 := (size.x - w * 4 - 30 * 3) * 0.5
	return Rect2(x0 + (i % 4) * (w + 30), 150 + (i / 4) * (h + 30), w, h)


func movies_back_rect() -> Rect2:
	return Rect2(24, 24, 170, 70)


func _movies_tap(p: Vector2) -> void:
	if movies_back_rect().grow(10).has_point(p):
		game.state = "home"
		return
	for i in Cutscene.ORDER.size():
		if movie_rect(i).has_point(p):
			game.movie_sel = i
			game.movie_pick(i)
			return


func _draw_movies() -> void:
	draw_rect(Rect2(Vector2.ZERO, size), Color(0.07, 0.05, 0.14, 0.94))
	txt("MOVIES", Vector2(0, 92), 64, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, size.x, 12)
	var br := movies_back_rect()
	draw_rect(br, Color(0.13, 0.17, 0.33))
	draw_rect(br, Color.WHITE, false, 3.0)
	txt("BACK", Vector2(br.position.x, br.position.y + 48), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, br.size.x, 6)
	for i in Cutscene.ORDER.size():
		var id: String = Cutscene.ORDER[i]
		var r := movie_rect(i)
		var seen: bool = id in game.scenes_seen
		paper_card(r, Color.WHITE, 0, game.movie_sel == i, Color(0.92, 0.88, 0.8) if seen else Color(0.3, 0.3, 0.38))
		draw_rect(Rect2(r.position + Vector2(10, 10), Vector2(r.size.x - 20, r.size.y - 60)), Color(0.12, 0.12, 0.2))
		if seen:
			_star_shape(r.position + Vector2(r.size.x * 0.5, 50), 24, Color("ffd23a"))
			txt(Cutscene.TITLES[id], Vector2(r.position.x + 6, r.end.y - 18), 20, Color(0.15, 0.1, 0.2), HORIZONTAL_ALIGNMENT_CENTER, r.size.x - 12, 0)
		else:
			txt("?", Vector2(r.position.x, r.position.y + 70), 44, Color(1, 1, 1, 0.5), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
			txt("NOT SEEN YET", Vector2(r.position.x, r.end.y - 18), 20, Color(1, 1, 1, 0.6), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	txt("Tap a movie to watch it again!", Vector2(0, size.y - 40), 26, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_CENTER, size.x, 6)


# ------------------------------------------------------------------ the ARCADE menu
func arcade_rect(i: int) -> Rect2:
	var n := MiniGames.GAMES.size()
	var gap := 30.0
	var w := minf(330.0, (size.x - 80 - gap * (n - 1)) / n)
	var h := minf(330.0, size.y * 0.5)
	var x0 := (size.x - (w * n + gap * (n - 1))) * 0.5
	return Rect2(x0 + i * (w + gap), size.y * 0.24, w, h)


func arcade_back_rect() -> Rect2:
	return Rect2(24, 24, 170, 70)


func _arcade_tap(p: Vector2) -> void:
	if arcade_back_rect().grow(10).has_point(p):
		game.close_arcade()
		return
	for i in MiniGames.GAMES.size():
		if arcade_rect(i).has_point(p):
			if game.arcade_sel == i:   # second tap on the same card: play!
				game.start_minigame(MiniGames.GAMES[i].id)
			else:
				game.arcade_sel = i
				game.sfx("swap", -8)
			return


func _draw_arcade() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.1, 0.04, 0.18, 0.95))
	for i in 14:   # flashing arcade lights along the top
		var on := int(title_t * 4 + i) % 3 == 0
		draw_circle(Vector2(s.x * (i + 0.5) / 14.0, 120), 8, Color.from_hsv(i / 14.0, 0.7, 1.0 if on else 0.4))
	txt("ARCADE", Vector2(0, 92), 76, Color("ff6fd8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	var br := arcade_back_rect()
	draw_rect(br, Color(0.13, 0.17, 0.33))
	draw_rect(br, Color.WHITE, false, 3.0)
	txt("BACK", Vector2(br.position.x, br.position.y + 48), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, br.size.x, 6)
	for i in MiniGames.GAMES.size():
		var gm: Dictionary = MiniGames.GAMES[i]
		var r := arcade_rect(i)
		var sel: bool = game.arcade_sel == i
		paper_card(r, gm.color, 64, sel, Color(0.97, 0.94, 0.86))
		txt(gm.name, Vector2(r.position.x, r.position.y + 44), 30, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 7)
		_game_icon(gm.id, r.position + Vector2(r.size.x * 0.5, 140))
		txt(gm.vs, Vector2(r.position.x, r.position.y + 254), 24, Color(0.35, 0.15, 0.2), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		txt("WIN: +%d coins" % MiniGames.PRIZE_WIN, Vector2(r.position.x, r.end.y - 22), 22, Color(0.55, 0.4, 0.05), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	var g: Dictionary = MiniGames.GAMES[game.arcade_sel]
	txt(g.about, Vector2(0, arcade_rect(0).end.y + 56), 28, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
	txt("Tap a game twice to play!", Vector2(0, arcade_rect(0).end.y + 98), 24, Color(1, 1, 1, 0.75), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)


## A little picture for each game's card (drawn, no image files needed).
func _game_icon(id: String, c: Vector2) -> void:
	match id:
		"bowling":
			for r in 3:
				for k in r + 1:
					var p := c + Vector2((k - r * 0.5) * 30, -34 + r * 26)
					draw_circle(p, 11, Color.WHITE)
					draw_arc(p, 11, 0, TAU, 20, Color(0.2, 0.15, 0.25), 2.0, true)
					draw_line(p + Vector2(-9, -2), p + Vector2(9, -2), Color("e8413b"), 3)
			draw_circle(c + Vector2(-60, 46), 24, Color("3a5bd9"))
			draw_circle(c + Vector2(-66, 38), 4, Color(0.1, 0.1, 0.15))
			draw_circle(c + Vector2(-54, 38), 4, Color(0.1, 0.1, 0.15))
		"coins":
			for k in 5:
				var p := c + Vector2(-60 + k * 30, sin(k * 1.7) * 20)
				draw_circle(p, 18, Color("ffd23a"))
				draw_arc(p, 18, 0, TAU, 24, Color("b8860b"), 3.0, true)
				draw_circle(p, 7, Color("ffe98a"))
		"race":
			draw_rect(Rect2(c + Vector2(-90, 20), Vector2(180, 30)), Color("bfe6ff"))
			for k in 6:
				draw_rect(Rect2(c + Vector2(50 + (k % 2) * 10, 20 + (k / 2) * 10), Vector2(10, 10)), Color.BLACK if (k + k / 2) % 2 == 0 else Color.WHITE)
			draw_circle(c + Vector2(-30, 0), 26, Color("1e2a44"))
			draw_circle(c + Vector2(-30, 6), 17, Color.WHITE)
			draw_colored_polygon(PackedVector2Array([c + Vector2(-8, -2), c + Vector2(6, 2), c + Vector2(-8, 6)]), Color("ff9a3c"))
		"kart":
			for k in 7:   # a little rainbow road
				var col := Color.from_hsv(k / 7.0, 0.75, 1.0)
				draw_line(c + Vector2(-90, 50 - k * 6), c + Vector2(90, 20 - k * 6), col, 6)
			draw_rect(Rect2(c + Vector2(-34, -40), Vector2(68, 30)), Color("e8413b"))
			draw_rect(Rect2(c + Vector2(-20, -62), Vector2(40, 24)), Color("e8413b").lightened(0.2))
			for wx in [-30, 30]:
				draw_circle(c + Vector2(wx, -8), 12, Color(0.1, 0.1, 0.15))
				draw_circle(c + Vector2(wx, -8), 5, Color("ffd23a"))


## Poseidon's trident (on the pause button).
func _trident(c: Vector2, r: float) -> void:
	var col := Color("ffd23a")
	draw_line(c + Vector2(0, r), c + Vector2(0, -r), col, 4)
	draw_line(c + Vector2(-r * 0.6, -r * 0.4), c + Vector2(r * 0.6, -r * 0.4), col, 4)
	draw_line(c + Vector2(-r * 0.6, -r * 0.4), c + Vector2(-r * 0.6, -r), col, 4)
	draw_line(c + Vector2(r * 0.6, -r * 0.4), c + Vector2(r * 0.6, -r), col, 4)


# ------------------------------------------------------------------ TEAM: 2 heroes + 1 partner
func _team_card() -> Vector2:
	var n := Team.ORDER.size()
	var w := minf(170.0, (size.x - 60 - 12.0 * (n - 1)) / n)
	return Vector2(w, minf(250.0, size.y * 0.36))


func team_rect(i: int) -> Rect2:
	var c := _team_card()
	var n := Team.ORDER.size()
	var x0 := (size.x - (c.x * n + 12.0 * (n - 1))) * 0.5
	return Rect2(x0 + i * (c.x + 12.0), size.y * 0.17, c.x, c.y)


func team_partner_rect() -> Rect2:
	var y := team_rect(0).end.y + 92
	return Rect2(size.x * 0.5 - 330, y, 300, 110)


func team_go_rect() -> Rect2:
	var y := team_rect(0).end.y + 92
	return Rect2(size.x * 0.5 + 30, y + 20, 300, 76)


func _team_tap(p: Vector2) -> void:
	if game.team_from == "home" and back_rect().has_point(p):
		game.state = "home"
		return
	if team_go_rect().has_point(p):
		game.team_go()
		return
	if team_partner_rect().has_point(p):
		game.team_partner()
		return
	for i in Team.ORDER.size():
		if team_rect(i).has_point(p):
			game.team_cur = i
			game.team_tap(Team.ORDER[i])
			return


func _draw_team() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.55))
	txt("PICK YOUR TEAM", Vector2(0, s.y * 0.12), 52, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	if game.team_from == "home":
		var b := back_rect()
		draw_rect(b, Color(0.1, 0.15, 0.3, 0.85))
		draw_rect(b, Color.WHITE, false, 3.0)
		txt("BACK", Vector2(b.position.x, b.position.y + 48), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, b.size.x, 7)
	var open: Array = Team.unlocked(game.cleared_w)
	for i in Team.ORDER.size():
		var id: String = Team.ORDER[i]
		var info: Dictionary = Team.info(id)
		var r := team_rect(i)
		var got := id in open
		var pick: int = game.team_sel.find(id)
		if game.team_cur == i:
			card(r.grow(9), Color(1, 1, 1, 0.5))
		paper_card(r, info.color if got else Color(0.4, 0.4, 0.46), 30, pick >= 0)
		txt(String(info.action) if got else "LOCKED", Vector2(r.position.x, r.position.y + 23), 17, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 5)
		var fh := r.size.y - 30 - 60
		var bob := sin(title_t * 4 + i) * 4 if pick >= 0 else 0.0
		fit(img(id + "_idle"), Rect2(r.position.x + 10, r.position.y + 36 + bob, r.size.x - 20, fh))
		if not got:
			draw_rect(Rect2(r.position.x + 3, r.position.y + 33, r.size.x - 6, fh + 6), Color(0.08, 0.08, 0.14, 0.82))
			txt("?", Vector2(r.position.x, r.position.y + 36 + fh * 0.6), 64, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 8)
		txt(String(info.name) if got else "???", Vector2(r.position.x, r.end.y - 30), 22, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		if pick >= 0:
			draw_circle(Vector2(r.end.x - 18, r.position.y + 48), 17, Color("3fc06a"))
			txt(str(pick + 1), Vector2(r.end.x - 35, r.position.y + 58), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, 34, 5)
		elif not got:
			txt("Clear %d world%s" % [Team.need(id), "s" if Team.need(id) > 1 else ""], Vector2(r.position.x, r.end.y - 8), 15, Color("8a3a3a"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	# what the highlighted hero does
	var cid: String = Team.ORDER[clampi(game.team_cur, 0, Team.ORDER.size() - 1)]
	var y := team_rect(0).end.y + 34
	if cid in open:
		txt("%s:  %s" % [Team.name_of(cid), Team.info(cid).about], Vector2(0, y), 22, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
	else:
		txt("Clear more worlds to unlock new friends!", Vector2(0, y), 22, Color("fff2a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 7)
	txt("Tap 2 heroes, then pick your partner", Vector2(0, y + 28), 20, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	# the partner
	var pr := team_partner_rect()
	draw_rect(pr, Color("fdf4e3"))
	draw_rect(pr, Color(0.13, 0.09, 0.18), false, 3.0)
	fit(img(game.face("mon")), Rect2(pr.position.x + 8, pr.position.y + 8, 100, pr.size.y - 16))
	txt("PARTNER", Vector2(pr.position.x + 116, pr.position.y + 34), 20, Color("3f7fd9"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	txt(game.display_name("mon"), Vector2(pr.position.x + 116, pr.position.y + 64), 26, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	txt("tap to change", Vector2(pr.position.x + 116, pr.position.y + 92), 17, Color("8a5a35"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	# GO
	var g := team_go_rect()
	var ok: bool = game.team_sel.size() == 2
	draw_rect(g, Color("3fc06a") if ok else Color(0.4, 0.42, 0.48))
	draw_rect(g, Color.WHITE, false, 3.0)
	txt("LET'S GO!" if ok else "PICK 2 HEROES", Vector2(g.position.x, g.position.y + 51), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, g.size.x, 8)


# ------------------------------------------------------------------ juice: coins, hearts, wipes, buzz
static var haptics := true    # little buzzes on button taps / hits (Android); BUZZ on the title switches it
var shown_coins := -1.0       # the coin counter rolls up to game.coins
var coin_pop := 0.0           # the counter bounces when a coin arrives
var coin_fliers: Array = []   # [start screen pos, age] coins flying to the counter
var _last_coins := -1
var _last_hearts := -1
var heart_shake := 0.0        # hearts wobble when you get hurt
var heart_pop := 0.0          # and bounce when they refill
var wipe := -1.0              # >= 0 while the star wipe opens a new screen
var closing := -1.0           # >= 0 while the star wipe closes (main.go waits for it)
var _last_state := ""
var message_total := 2.2      # how long the current banner lasts (for its slide in / out)
const WIPE_IN := 0.55
const WIPE_OUT := 0.32
const COIN_AT := Vector2(43, 87)   # the coin icon on the HUD


func buzz(ms: int) -> void:
	if haptics and OS.has_feature("mobile"):
		Input.vibrate_handheld(ms)


func _juice_step(dt: float) -> void:
	if game == null:
		return
	# the star wipe opens when the LOADING screen ends
	if _last_state == "loading" and game.state != "loading":
		wipe = 0.0
	_last_state = game.state
	if wipe >= 0:
		wipe += dt
		if wipe > WIPE_IN:
			wipe = -1.0
	if closing >= 0:
		closing += dt
	# coins: new ones fly from the hero to the counter, which rolls up and bounces
	var c: int = game.coins
	if _last_coins < 0 or game.state in ["loading", "title", "slots"]:
		_last_coins = c
		shown_coins = c
	elif c > _last_coins:
		var from := _hero_on_screen()
		for i in mini(c - _last_coins, 6):
			coin_fliers.append([from + Vector2(randf_range(-18, 18), randf_range(-18, 18)), -i * 0.05])
		_last_coins = c
	elif c < _last_coins:   # spent: the counter just follows
		_last_coins = c
		shown_coins = c
	for pp in pops:
		pp[2] += dt
	pops = pops.filter(func(pp): return pp[2] < 0.8)
	for fl in coin_fliers:
		fl[1] += dt
	coin_fliers = coin_fliers.filter(func(fl): return fl[1] < 0.55)
	if shown_coins < c:
		var before := int(shown_coins)
		shown_coins = minf(float(c), shown_coins + maxf(12.0, (c - shown_coins) * 5.0) * dt)
		if int(shown_coins) != before:
			coin_pop = 1.0
	coin_pop = maxf(0.0, coin_pop - dt * 5.0)
	# hearts
	var h: int = game.hearts
	if _last_hearts >= 0 and h < _last_hearts:
		heart_shake = 0.5
		buzz(40)
	elif _last_hearts >= 0 and h > _last_hearts:
		heart_pop = 0.5
	_last_hearts = h
	heart_shake = maxf(0.0, heart_shake - dt)
	heart_pop = maxf(0.0, heart_pop - dt)


## Where the hero you steer is on the screen (coins fly from there).
func _hero_on_screen() -> Vector2:
	if game.cam and game.leader():
		var p: Vector3 = game.leader().global_position + Vector3(0, 1.0, 0)
		if not game.cam.is_position_behind(p):
			return game.cam.unproject_position(p) * (size / get_viewport_rect().size)
	return size * 0.5


var pops: Array = []   # [world pos, amount, age]: damage numbers that pop out of hit bad guys


func pop_number(n: int, wpos: Vector3) -> void:
	if pops.size() < 8:
		pops.append([wpos, n, 0.0])


func _draw_pops() -> void:
	if pops.is_empty() or game.cam == null:
		return
	var k := size / get_viewport_rect().size
	for pp in pops:
		var t: float = pp[2]
		var wp: Vector3 = pp[0] + Vector3(0, t * 1.6, 0)
		if game.cam.is_position_behind(wp):
			continue
		var p: Vector2 = game.cam.unproject_position(wp) * k
		var sc := 0.6 + minf(t * 8.0, 1.0) * 0.6 - maxf(0.0, t - 0.5) * 0.8
		var a := clampf((0.8 - t) * 4.0, 0, 1)
		draw_set_transform(p, sin(t * 20.0) * 0.08 * (1.0 - t), Vector2(sc, sc))
		txt("-%d" % pp[1], Vector2(-60, 14), 40, Color(1, 0.85, 0.25, a), HORIZONTAL_ALIGNMENT_CENTER, 120, 10)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _draw_coin_fliers() -> void:
	_draw_pops()
	for fl in coin_fliers:
		var t: float = fl[1]
		if t < 0:
			continue
		var k := clampf(t / 0.55, 0, 1)
		var e := k * k * (3.0 - 2.0 * k)
		var from: Vector2 = fl[0]
		var p := from.lerp(COIN_AT, e) + Vector2(0, -sin(k * PI) * 90.0)
		var sz := lerpf(34.0, 22.0, k)
		draw_texture_rect(tex["coin"], Rect2(p - Vector2(sz, sz) * 0.5, Vector2(sz, sz)), false)


## The star wipe: a star-shaped hole that grows (new screen) or shrinks (leaving).
func _draw_wipe() -> void:
	var k := -1.0
	if closing >= 0:
		k = 1.0 - clampf(closing / WIPE_OUT, 0, 1)
	elif wipe >= 0:
		var t := clampf(wipe / WIPE_IN, 0, 1)
		k = t * t * (3.0 - 2.0 * t)
	if k < 0 or k >= 1.0:
		return
	var s := size
	var c := s * 0.5
	var big := s.length() * 1.6
	var r := big * k
	var col := Color("2a6fb8")   # the LOADING screen's blue, so close -> LOADING -> open is one smooth move
	var inner := PackedVector2Array()
	var outer := PackedVector2Array()
	for i in 10:
		var a := -PI / 2 + TAU * i / 10.0 + k * 0.6
		inner.append(c + Vector2(cos(a), sin(a)) * (r if i % 2 == 0 else r * 0.45))
		outer.append(c + Vector2(cos(a), sin(a)) * big * 2.2)
	for i in 10:
		var j := (i + 1) % 10
		draw_colored_polygon(PackedVector2Array([inner[i], inner[j], outer[j], outer[i]]), col)
	if r < 4:
		draw_rect(Rect2(Vector2.ZERO, s), col)


## Rounded, shiny cards for menus (pause, star select ...). Cached by colour.
var _card_boxes := {}


## A cream "paper" card with a coloured header strip (save files, partners, star select ...).
## The picked one gets a gold frame and bounces.
func paper_card(r: Rect2, header: Color, header_h: float, sel := false, bg := Color("fdf4e3")) -> Rect2:
	var rr := r.grow(3 + 3 * sin(title_t * 7.0)) if sel else r
	var key := "p" + bg.to_html() + ("s" if sel else "")
	if not _card_boxes.has(key):
		var sb := StyleBoxFlat.new()
		sb.bg_color = bg
		sb.set_corner_radius_all(16)
		sb.border_color = Color("ffd23a") if sel else Color(0.13, 0.09, 0.18)
		sb.set_border_width_all(6 if sel else 3)
		sb.shadow_color = Color(0, 0, 0, 0.35)
		sb.shadow_size = 10 if sel else 6
		sb.shadow_offset = Vector2(0, 6)
		sb.anti_aliasing = true
		_card_boxes[key] = sb
	var hk := "h" + header.to_html()
	if header_h > 0 and not _card_boxes.has(hk):
		var hb := StyleBoxFlat.new()
		hb.bg_color = header
		hb.corner_radius_top_left = 13
		hb.corner_radius_top_right = 13
		hb.anti_aliasing = true
		_card_boxes[hk] = hb
	draw_style_box(_card_boxes[key], rr)
	if header_h > 0:
		var inset := 6.0 if sel else 3.0
		draw_style_box(_card_boxes[hk], Rect2(rr.position + Vector2(inset, inset), Vector2(rr.size.x - inset * 2, header_h - inset)))
	return rr


func card(r: Rect2, col: Color, sel := false) -> void:
	var key := col.to_html() + ("s" if sel else "")
	if not _card_boxes.has(key):
		var sb := StyleBoxFlat.new()
		sb.bg_color = col
		sb.set_corner_radius_all(18)
		sb.border_color = Color.WHITE if sel else col.lightened(0.35)
		sb.set_border_width_all(5 if sel else 3)
		sb.shadow_color = Color(0, 0, 0, 0.35)
		sb.shadow_size = 8
		sb.shadow_offset = Vector2(0, 6)
		sb.anti_aliasing = true
		_card_boxes[key] = sb
		var gloss := StyleBoxFlat.new()
		gloss.bg_color = Color(1, 1, 1, 0.18)
		gloss.corner_radius_top_left = 14
		gloss.corner_radius_top_right = 14
		gloss.corner_radius_bottom_left = 40
		gloss.corner_radius_bottom_right = 40
		_card_boxes["gloss"] = gloss
	var rr := r
	if sel:   # the picked card bounces a little
		rr = r.grow(4 + 3 * sin(title_t * 7.0))
	draw_style_box(_card_boxes[key], rr)
	draw_style_box(_card_boxes["gloss"], Rect2(rr.position + Vector2(6, 5), Vector2(rr.size.x - 12, rr.size.y * 0.42)))
