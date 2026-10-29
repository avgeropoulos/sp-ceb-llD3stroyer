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
		draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.08, 0.15, 0.6))
		txt("PAUSED", Vector2(0, s.y * 0.42), 80, Color(1, 0.86, 0.3), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		txt("Tap to keep playing", Vector2(0, s.y * 0.42 + 60), 32, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x)
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
	draw_texture_rect(tex[who + "_idle"], Rect2(x + 14, 122, 56, 70), false)
	txt(who.to_upper(), Vector2(x + 82, 172), 26, Color(1, 0.95, 0.7))
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
			draw_texture_rect(tex[nxt + "_idle"], Rect2(c - Vector2(rad * 0.62, rad * 0.95), Vector2(rad * 1.24, rad * 1.55)), false)
			txt("SWAP", Vector2(c.x - rad, c.y + rad + 26), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, rad * 2)
		else:
			var n := String(labels[k]).length()
			var fs := int(rad * (0.44 if n <= 4 else (0.36 if n <= 6 else 0.27)))
			txt(labels[k], Vector2(c.x - rad, c.y + fs * 0.36), fs, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, rad * 2, 6)


func update_showing() -> bool:
	return game.updater != null and game.updater.status in ["available", "downloading", "installing", "error"]


func update_rect() -> Rect2:
	return Rect2(size.x - 330, size.y - 150, 300, 104)


func _draw_update() -> void:
	var u = game.updater
	if u == null:
		return
	if u.can_update():
		txt("v0.%d" % u.my_version, Vector2(20, size.y - 20), 22, Color(1, 1, 1, 0.8))
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
	txt("PAPER 3D", Vector2(0, s.y * 0.24 + 150), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
	_draw_update()
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
