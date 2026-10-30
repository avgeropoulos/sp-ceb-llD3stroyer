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
	var jump := [Vector2(s.x - 125 * u, s.y - 125 * u), 82 * u]
	var act := [Vector2(s.x - 300 * u, s.y - 92 * u), 70 * u]
	var sw := [Vector2(s.x - 145 * u, s.y - 315 * u), 56 * u]
	var sup := [Vector2(s.x - 300 * u, s.y - 255 * u), 56 * u]
	var pz := [Vector2(s.x - 52 * u, 52 * u), 34 * u]
	if game and game.state == "map":
		buttons = {"land": jump, "pause": pz}
	elif game and game.state == "home" and game.home:
		var fly := [Vector2(s.x - 52 * u, 252 * u), 42 * u]
		if game.home.mode == "":   # walking around the home: BUILD, DECOR, PARTNER, FLY
			buttons = {"jump": jump, "build": act, "swap": sw, "decor": sup, "pause": pz,
				"partner": [Vector2(s.x - 52 * u, 150 * u), 46 * u], "fly": fly}
		else:                      # building: PLACE, REMOVE, DONE (and TURN for decorations)
			buttons = {"jump": jump, "place": act, "done": sw, "remove": sup, "pause": pz, "fly": fly}
			if game.home.mode == "decor":
				buttons["rotate"] = [Vector2(s.x - 445 * u, s.y - 175 * u), 46 * u]
				buttons["paint"] = [Vector2(s.x - 445 * u, s.y - 290 * u), 46 * u]
		if game.flying:
			buttons["down"] = [Vector2(s.x - 445 * u, s.y - 70 * u), 52 * u]
	else:
		buttons = {"jump": jump, "action": act, "swap": sw, "super": sup, "pause": pz}


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
			if game.state == "home" and game.home and game.home.mode != "" and _palette_tap(e.position):
				return
			if game.state != "play" and game.state != "home" and game.state != "map":
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
			elif game.state == "home" and look_id == -1:
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
	if game and game.state != "home":
		look_id = -1
		hold_pos = Vector2(-1, -1)
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
	if game.state == "loading":
		_draw_loading()
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
		# and when Krag Jr. shows up).
		var pop := 1.0 + maxf(0, (message_t - 1.9)) * 1.5
		var mid := Vector2(s.x * 0.5, s.y * 0.36 - 22)
		draw_set_transform(mid * (1.0 - pop), 0.0, Vector2(pop, pop))
		txt(message, Vector2(0, s.y * 0.36), 64, Color(1, 0.86, 0.3, a), HORIZONTAL_ALIGNMENT_CENTER, s.x, 14)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
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
	if game.sprinting:   # the stick glows orange while sprinting
		draw_arc(base, r * 1.2, 0, TAU, 48, Color("ff8a1f"), 8, true)
		txt("SPRINT!", Vector2(base.x - r * 1.2, base.y - r * 1.35), 24, Color("ffb35a"), HORIZONTAL_ALIGNMENT_CENTER, r * 2.4, 6)
	draw_circle(base + stick * r, r * 0.55, Color("ffd6a8") if game.sprinting else Color(1, 1, 1, 0.75))
	# buttons
	var labels := {"jump": "JUMP", "action": game.action_label(), "swap": "SWAP", "super": "SUPER", "pause": "II",
		"build": "BUILD", "decor": "DECOR", "partner": "PARTNER", "place": "PLACE", "remove": "REMOVE", "done": "DONE", "rotate": "TURN", "land": "LAND", "paint": "PAINT",
		"fly": "LAND" if game.flying else "FLY", "down": "DOWN"}
	if game.flying:
		labels["jump"] = "UP"
	var colors := {"jump": Color("3fc06a"), "action": Color("ff8a1f"), "swap": Color("6a7cff"), "super": Color("ffcf33"), "pause": Color(0.2, 0.25, 0.4),
		"build": Color("ff8a1f"), "decor": Color("ff6fa8"), "partner": Color("2cbec8"), "place": Color("3fc06a"), "remove": Color("e8413b"),
		"done": Color("3f7fd9"), "rotate": Color("8a5bc4"), "land": Color("3fc06a"),
		"paint": Decor.PAINTS[game.home.paint] if game.home and game.home.paint > 0 else Color("ff6fa8"),
		"fly": Color("ffb020") if game.flying else Color("54c8ff"), "down": Color("3f7fd9")}
	for k in buttons:
		var c: Vector2 = buttons[k][0]
		var rad: float = buttons[k][1]
		var col: Color = colors[k]
		if k == "land" and (game.sky == null or game.sky.near_info().is_empty() or not game.sky.near_info().ok):
			col = Color(0.35, 0.38, 0.45)   # nothing to land on here
		var down := is_held(k)
		if k == "super" and game.state == "play":
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
			var locked: bool = it.get("need_clear", false) and game.cleared <= 0
			out.append({"icon": img("decor_" + it.id), "name": it.name, "cost": {"coins": it.coins}, "locked": locked})
	return out


static func _tex_name(t: int) -> String:
	return ["", "grass_top", "dirt", "stone", "iron", "gold", "diamond", "bedrock", "brick", "mystery", "used",
		"planks", "wool_red", "wool_blue", "wool_yellow", "wool_white", "window", "iron_block", "gold_block", "diamond_block", "stone_brick",
		"snow_top", "ice", "sand", "sandstone", "log_side", "leaves", "wool_green", "wool_black", "road"][t]


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
	var ni: Dictionary = game.sky.near_info()
	var s := size
	if ni.is_empty():
		txt("Fly over an island to land", Vector2(0, 120), 30, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		txt("Push the stick all the way for a BOOST!", Vector2(0, 156), 22, Color(1, 1, 1, 0.65), HORIZONTAL_ALIGNMENT_CENTER, s.x, 6)
	else:
		txt(ni.name, Vector2(0, 120), 44, Color("ffd23a"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 10)
		if ni.ok:
			txt("Tap LAND!", Vector2(0, 160), 28, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)
		else:
			txt("LOCKED - " + ni.why, Vector2(0, 160), 28, Color("ffb0a8"), HORIZONTAL_ALIGNMENT_CENTER, s.x, 8)


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
	for n in ["rocco_idle", "blocky_idle", "heart", "heart_empty", "coin", "kragjr_1"]:
		draw_texture_rect(img(n), Rect2(-500, -500, 40, 40), false)
	for it in Decor.ITEMS:
		draw_texture_rect(img("decor_" + it.id), Rect2(-500, -500, 40, 40), false)
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
	# lag finder (bottom left): screenshot this if the game stutters
	if game.slow_count > 0:
		var ly: float = s.y - 24.0 - game.slow.size() * 24.0
		txt("Slow moments: %d" % game.slow_count, Vector2(20, ly), 20, Color(1, 1, 1, 0.75), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
		for e in game.slow:
			ly += 24
			txt("%d ms  %s" % [e[0], e[1]], Vector2(20, ly), 18, Color(1, 1, 1, 0.6), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
	else:
		txt("No slow moments", Vector2(20, s.y - 24), 18, Color(1, 1, 1, 0.5), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
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
