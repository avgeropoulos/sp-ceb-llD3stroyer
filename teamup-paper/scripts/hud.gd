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

var message := ""
var message_t := 0.0
var sub_message := ""
var title_t := 0.0


func _ready() -> void:
	set_anchors_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	font = ThemeDB.fallback_font
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


func _layout() -> void:
	var s := size
	var u := minf(s.y / 720.0, s.x / 1280.0) * 1.0
	u = clampf(u, 0.75, 1.6)
	buttons = {
		"jump": [Vector2(s.x - 125 * u, s.y - 125 * u), 82 * u],
		"action": [Vector2(s.x - 300 * u, s.y - 92 * u), 70 * u],
		"swap": [Vector2(s.x - 145 * u, s.y - 315 * u), 56 * u],
		"super": [Vector2(s.x - 300 * u, s.y - 255 * u), 56 * u],
		"pause": [Vector2(s.x - 52 * u, 52 * u), 34 * u],
	}


func button_at(p: Vector2) -> String:
	for k in buttons:
		var b: Array = buttons[k]
		if p.distance_to(b[0]) < b[1] * 1.12:
			return k
	return ""


func is_held(name: String) -> bool:
	return held.values().has(name)


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		var e := event as InputEventScreenTouch
		if e.pressed:
			if game.state != "play":
				if game.state == "title" and update_showing() and update_rect().grow(20).has_point(e.position):
					game.updater.download()
					return
				if game.state == "title" and look_rect().grow(16).has_point(e.position):
					game.toggle_look()
					return
				if game.state == "slots":
					_slots_tap(e.position)
					return
				if game.state == "partner":
					_partner_tap(e.position)
					return
				if game.state == "paused":
					for i in 3:
						if pause_rect(i).has_point(e.position):
							game.pause_choice(i)
					return
				game.on_tap()
				return
			var b := button_at(e.position)
			if b != "":
				held[e.index] = b
				pressed_now[b] = true
			elif e.position.x < size.x * 0.5 and stick_id == -1:
				stick_id = e.index
				stick_base = e.position
				stick = Vector2.ZERO
		else:
			held.erase(e.index)
			if e.index == stick_id:
				stick_id = -1
				stick = Vector2.ZERO
	elif event is InputEventScreenDrag:
		var e := event as InputEventScreenDrag
		if e.index == stick_id:
			var r := 70.0 * clampf(size.y / 720.0, 0.75, 1.6)
			var d := e.position - stick_base
			if d.length() > r * 1.5:   # drag the base along so the thumb never "runs out"
				stick_base = e.position - d.normalized() * r * 1.5
				d = e.position - stick_base
			stick = (d / r).limit_length(1.0)


func _process(dt: float) -> void:
	message_t = maxf(0, message_t - dt)
	title_t += dt
	size = get_viewport_rect().size
	_layout()
	queue_redraw()


# ------------------------------------------------------------------ drawing
func txt(s: String, pos: Vector2, fs: int, color: Color = Color.WHITE, align := HORIZONTAL_ALIGNMENT_LEFT, width := -1.0, outline := 8) -> void:
	draw_string_outline(font, pos, s, align, width, fs, outline, Color(0.13, 0.09, 0.18))
	draw_string(font, pos, s, align, width, fs, color)


func _draw() -> void:
	var s := size
	if game == null:
		return
	if game.state == "title":
		_draw_title()
		return
	if game.state == "slots":
		_draw_slots()
		return
	if game.state == "partner":
		_draw_partner()
		return
	_draw_hud()
	if game.state == "play":
		_draw_controls()
	if message_t > 0:
		var a := clampf(message_t * 3, 0, 1)
		var pop := 1.0 + maxf(0, (message_t - 1.9)) * 1.5
		txt(message, Vector2(0, s.y * 0.36), int(64 * pop), Color(1, 0.86, 0.3, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		if sub_message != "":
			txt(sub_message, Vector2(0, s.y * 0.36 + 52), 30, Color(1, 1, 1, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
	if game.state == "paused":
		_draw_pause()
	if game.state == "clear":
		_draw_clear()


func _draw_hud() -> void:
	var x := 22.0
	for i in game.MAX_HEARTS:
		draw_texture_rect(tex["heart" if i < game.hearts else "heart_empty"], Rect2(x + i * 46, 14, 50, 50), false)
	draw_texture_rect(tex["coin"], Rect2(x, 66, 42, 42), false)
	txt("x %d" % game.coins, Vector2(x + 46, 100), 30)
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
	# boss bar
	var boss = game.boss
	if game.boss_on and boss != null and is_instance_valid(boss) and boss.dying < 0:
		var w := minf(560.0, size.x * 0.45)
		var bx := (size.x - w) * 0.5
		txt("KRAG JR.", Vector2(0, 46), 34, Color("ff8c2a"), HORIZONTAL_ALIGNMENT_CENTER, size.x)
		draw_rect(Rect2(bx - 4, 56, w + 8, 24), Color(0.13, 0.09, 0.18))
		draw_rect(Rect2(bx, 60, w * float(boss.hp) / boss.max_hp, 16), Color("ff4f6d"))


func _draw_controls() -> void:
	# thumb stick
	var r := 70.0 * clampf(size.y / 720.0, 0.75, 1.6)
	var base := stick_base if stick_id >= 0 else Vector2(r * 2.1, size.y - r * 2.1)
	draw_circle(base, r * 1.2, Color(0.1, 0.15, 0.3, 0.28))
	draw_arc(base, r * 1.2, 0, TAU, 48, Color(1, 1, 1, 0.5), 4, true)
	draw_circle(base + stick * r, r * 0.55, Color(1, 1, 1, 0.75))
	# buttons
	var labels := {"jump": "JUMP", "action": game.action_label(), "swap": "SWAP", "super": "SUPER", "pause": "II"}
	var colors := {"jump": Color("3fc06a"), "action": Color("ff8a1f"), "swap": Color("6a7cff"), "super": Color("ffcf33"), "pause": Color(0.2, 0.25, 0.4)}
	for k in buttons:
		var c: Vector2 = buttons[k][0]
		var rad: float = buttons[k][1]
		var col: Color = colors[k]
		var down := is_held(k)
		if k == "super":
			var f: float = game.super_charge
			draw_circle(c, rad, Color(0.15, 0.15, 0.25, 0.55))
			if f < 1.0:
				draw_arc(c, rad - 5, -PI / 2, -PI / 2 + TAU * f, 48, col, 8, true)
			else:
				var glow := 0.5 + 0.5 * sin(title_t * 8)
				draw_circle(c, rad + 6 * glow, Color(1, 0.9, 0.3, 0.35))
				draw_circle(c, rad, col)
		else:
			draw_circle(c + Vector2(0, 5), rad, Color(0, 0, 0, 0.25))
			draw_circle(c + (Vector2(0, 4) if down else Vector2.ZERO), rad, col.darkened(0.2) if down else Color(col, 0.88))
		draw_arc(c, rad, 0, TAU, 48, Color(1, 1, 1, 0.8), 3, true)
		if k == "swap":
			var nxt: String = game.next_swap_name()
			fit(img(game.face(nxt)), Rect2(c - Vector2(rad * 0.8, rad * 0.95), Vector2(rad * 1.6, rad * 1.55)))
			txt("SWAP", Vector2(c.x - rad, c.y + rad + 26), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, rad * 2)
		else:
			var n := String(labels[k]).length()
			var fs := int(rad * (0.44 if n <= 4 else (0.36 if n <= 6 else 0.27)))
			txt(labels[k], Vector2(c.x - rad, c.y + fs * 0.36), fs, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, rad * 2, 6)


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
		draw_rect(box, Color("ff8a1f") if on else Color(0.1, 0.15, 0.3, 0.6))
		draw_rect(box, Color.WHITE, false, 3.0 if on else 1.5)
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
	draw_rect(r, col)
	draw_rect(r, Color.WHITE, false, 4.0)
	if u.status == "downloading":
		draw_rect(Rect2(r.position + Vector2(10, r.size.y - 26), Vector2((r.size.x - 20) * u.progress, 14)), Color("ffd23a"))
		txt("%d%%" % int(u.progress * 100), Vector2(r.position.x, r.position.y + 58), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 8)
	else:
		txt("UPDATE!", Vector2(r.position.x, r.position.y + 66), 52, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 10)


func _draw_title() -> void:
	var s := size
	var bob := sin(title_t * 2.5) * 6
	txt("ROCCO & BLOCKY", Vector2(0, s.y * 0.24 + bob), 92, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 18)
	txt("TEAM UP!", Vector2(0, s.y * 0.24 + 92 + bob), 84, Color("ff4a3a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 18)
	txt("3D TOYS" if Look.toy else "PAPER 3D", Vector2(0, s.y * 0.24 + 150), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
	_draw_update()
	_draw_look()
	if int(title_t * 2) % 2 == 0:
		txt("TAP TO START", Vector2(0, s.y * 0.9), 46, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)


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
const PAUSE_LABELS := ["RESUME", "SAVE", "SAVE & QUIT"]
const PAUSE_COLORS := [Color("3fc06a"), Color("3f7fd9"), Color("ff8a1f")]


func pause_rect(i: int) -> Rect2:
	var w := minf(460.0, size.x * 0.6)
	return Rect2((size.x - w) * 0.5, size.y * 0.3 + i * 108, w, 88)


func _draw_pause() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.7))
	txt("PAUSED", Vector2(0, s.y * 0.22), 76, Color(1, 0.86, 0.3), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
	for i in 3:
		var r := pause_rect(i)
		var sel: bool = game.pause_sel == i
		draw_rect(r.grow(6) if sel else r, Color.WHITE if sel else Color(1, 1, 1, 0.0))
		draw_rect(r, PAUSE_COLORS[i])
		draw_rect(r, Color.WHITE, false, 3.0)
		txt(PAUSE_LABELS[i], Vector2(r.position.x, r.position.y + 60), 44, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 9)
	var y := pause_rect(2).end.y + 50
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
		if sel:
			draw_rect(r.grow(8), Color(1, 0.9, 0.4, 0.9))
		draw_rect(r, Color("fdf4e3") if not d.is_empty() else Color(1, 1, 1, 0.75))
		draw_rect(r, Color(0.13, 0.09, 0.18), false, 4.0)
		draw_rect(Rect2(r.position, Vector2(r.size.x, 64)), Color("3f7fd9"))
		txt("FILE %d" % n, Vector2(r.position.x, r.position.y + 47), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 8)
		var cx := r.position.x + r.size.x * 0.5
		if d.is_empty():
			draw_circle(Vector2(cx, r.position.y + r.size.y * 0.5), 46, Color("3fc06a"))
			draw_rect(Rect2(cx - 28, r.position.y + r.size.y * 0.5 - 8, 56, 16), Color.WHITE)
			draw_rect(Rect2(cx - 8, r.position.y + r.size.y * 0.5 - 28, 16, 56), Color.WHITE)
			txt("NEW GAME", Vector2(r.position.x, r.end.y - 40), 38, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		else:
			var who: String = d.get("human", "rocco")
			draw_texture_rect(tex[who + "_idle"], Rect2(r.position.x + 16, r.position.y + 78, 90, 112), false)
			var ml := String(d.get("mon", "spark"))
			if not ml in Mons.ORDER:
				ml = "spark"
			var mxp := int((d.get("mon_xp", {}) as Dictionary).get(ml, 0))
			fit(img(Mons.face(ml, Mons.stage_of(ml, Mons.level_of(mxp)))), Rect2(r.position.x + 84, r.position.y + 112, 84, 82))
			var tx := r.position.x + 170
			txt("1-1", Vector2(tx, r.position.y + 118), 40, Color("ff4a3a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 6)
			txt("MEADOW MINE", Vector2(tx, r.position.y + 150), 20, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			var cl := int(d.get("cleared", 0))
			if cl > 0:
				txt("CLEARED x%d" % cl, Vector2(tx, r.position.y + 182), 24, Color("e0a800"), HORIZONTAL_ALIGNMENT_LEFT, -1, 4)
			elif d.get("checkpoint_hit", false):
				txt("CHECKPOINT", Vector2(tx, r.position.y + 182), 22, Color("3f9a48"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
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
		if not d.is_empty():
			var armed: bool = game.erase_armed == n
			draw_rect(er, Color("e8413b") if armed else Color(0.35, 0.12, 0.15, 0.85))
			draw_rect(er, Color.WHITE, false, 2.0)
			txt("TAP AGAIN TO ERASE" if armed else "ERASE", Vector2(er.position.x, er.position.y + 40), 24 if armed else 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, er.size.x, 6)
		elif Saves.can_undo(n):
			draw_rect(er, Color(0.15, 0.3, 0.2, 0.85))
			draw_rect(er, Color.WHITE, false, 2.0)
			txt("UNDO ERASE", Vector2(er.position.x, er.position.y + 40), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, er.size.x, 6)


# ------------------------------------------------------------------ PICK YOUR PARTNER
func partner_rect(i: int) -> Rect2:
	var gap := 20.0
	var w := minf(290.0, (size.x - 60 - gap * 3) / 4.0)
	var h := minf(420.0, size.y * 0.62)
	var x0 := (size.x - (w * 4 + gap * 3)) * 0.5
	return Rect2(x0 + i * (w + gap), size.y * 0.2, w, h)


func go_rect() -> Rect2:
	return Rect2(size.x * 0.5 - 150, size.y * 0.2 + minf(420.0, size.y * 0.62) + 18, 300, 76)


func _partner_tap(p: Vector2) -> void:
	if back_rect().has_point(p):
		game.state = "slots"
		return
	if go_rect().has_point(p):
		game.choose_partner(Mons.ORDER[game.partner_sel])
		return
	for i in 4:
		if partner_rect(i).has_point(p):
			game.partner_tap(i)
			return


func _draw_partner() -> void:
	var s := size
	draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.5))
	txt("PICK YOUR PARTNER", Vector2(0, s.y * 0.14), 54, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 12)
	var b := back_rect()
	draw_rect(b, Color(0.1, 0.15, 0.3, 0.85))
	draw_rect(b, Color.WHITE, false, 3.0)
	txt("BACK", Vector2(b.position.x, b.position.y + 48), 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, b.size.x, 7)
	for i in 4:
		var l: String = Mons.ORDER[i]
		var info: Dictionary = Mons.LINES[l]
		var xp: int = game.mon_xp[l]
		var lv := Mons.level_of(xp)
		var st := Mons.stage_of(l, lv)
		var r := partner_rect(i)
		var sel: bool = game.partner_sel == i
		if sel:
			draw_rect(r.grow(8), Color(1, 0.9, 0.4, 0.95))
		draw_rect(r, Color("fdf4e3"))
		draw_rect(Rect2(r.position, Vector2(r.size.x, 56)), info.color)
		draw_rect(r, Color(0.13, 0.09, 0.18), false, 4.0)
		txt(info.type, Vector2(r.position.x, r.position.y + 40), 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 7)
		var bob := sin(title_t * 4 + i) * 4 if sel else 0.0
		fit(img(Mons.face(l, st)), Rect2(r.position.x + 10, r.position.y + 62 + bob, r.size.x - 20, r.size.y * 0.36))
		var y := r.position.y + 62 + r.size.y * 0.36 + 34
		txt(Mons.name_of(l, st), Vector2(r.position.x, y), 34, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		y += 32
		txt("Lv %d" % lv, Vector2(r.position.x, y), 28, Color("3f7fd9"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		y += 12
		draw_rect(Rect2(r.position.x + 20, y, r.size.x - 40, 12), Color(0.15, 0.15, 0.2))
		draw_rect(Rect2(r.position.x + 20, y, (r.size.x - 40) * Mons.progress(xp), 12), Color("54c8ff"))
		y += 38
		txt(info.action, Vector2(r.position.x, y), 20, Color(0.13, 0.09, 0.18), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		y += 26
		txt("SUPER: " + info.super, Vector2(r.position.x, y), 19, Color("c0392b"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
		var nxt := ""
		for e in info.evolve:
			if lv < e:
				nxt = "Evolves at Lv %d" % e
				break
		if nxt != "":
			txt(nxt, Vector2(r.position.x, r.end.y - 12), 18, Color(0.35, 0.3, 0.4), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	var g := go_rect()
	var l2: String = Mons.ORDER[game.partner_sel]
	draw_rect(g, Color("3fc06a"))
	draw_rect(g, Color.WHITE, false, 3.0)
	txt("GO WITH %s!" % Mons.name_of(l2, Mons.stage_of(l2, Mons.level_of(game.mon_xp[l2]))), Vector2(g.position.x, g.position.y + 52), 32, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, g.size.x, 8)
