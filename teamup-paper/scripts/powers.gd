class_name Powers
extends Node3D
## Power-ups (like the 2D game's flowers, mushrooms and caps). They pop out of ? blocks
## and lie around the open worlds. Whoever you're steering gets the power; it moves to
## the next hero when you SWAP.
##   FIRE FLOWER    ACTION throws bouncing fireballs          (until you get hurt)
##   ICE FLOWER     ACTION throws ice balls: bad guys turn into ice blocks you can stand on
##   SUPER MUSHROOM big! one free hit, head-bumps smash blocks  (until you get hurt)
##   MINI MUSHROOM  tiny, jumps much higher, fits small gaps    (15 s)
##   SUPER STAR     rainbow, can't be hurt, touching bad guys beats them, faster (12 s)
##   WING CAP       hold JUMP to fly up                        (12 s)
##   PROPELLER      JUMP in the air: a big spin up, then float down (15 s)
##   METAL CAP      heavy and shiny: nothing hurts you, stomps shake the ground (12 s)
##   LIFE MUSHROOM  all hearts back (right away)

const KINDS := ["fire", "ice", "mushroom", "mini", "star", "wing", "propeller", "metal", "life"]
const NAMES := {"fire": "FIRE FLOWER", "ice": "ICE FLOWER", "mushroom": "SUPER MUSHROOM", "mini": "MINI MUSHROOM",
	"star": "SUPER STAR", "wing": "WING CAP", "propeller": "PROPELLER", "metal": "METAL CAP", "life": "LIFE MUSHROOM"}
const HINTS := {"fire": "ACTION throws fireballs!", "ice": "ACTION freezes bad guys!", "mushroom": "Big! One free hit. Bump blocks to smash them!",
	"mini": "Tiny! Super high jumps!", "star": "Rainbow power! Run into bad guys!", "wing": "Hold JUMP to fly!",
	"propeller": "JUMP in the air for a big spin!", "metal": "Nothing can hurt you!", "life": "All hearts back!"}
const TIME := {"mini": 15.0, "star": 12.0, "wing": 12.0, "propeller": 15.0, "metal": 12.0}
const COLORS := {"fire": Color("ff5a3a"), "ice": Color("7fd8ff"), "mushroom": Color("e8413b"), "mini": Color("3f7fd9"),
	"star": Color("ffd23a"), "wing": Color("ffffff"), "propeller": Color("ffb020"), "metal": Color("b8c4cf"), "life": Color("3fc06a")}
## Items that slide along the ground (like the mushrooms in the 2D game).
const SLIDERS := ["mushroom", "mini", "life"]
const FREEZE_TIME := 4.5

var game
var level: Level
var power := ""              # the power the team has now ("" = none)
var power_t := 0.0           # time left for timed powers
var worn_by: Hero            # who shows it right now
var items: Array = []        # [{kind, node, vel, sliding}]
var balls: Array = []        # ice balls: {node, vel, life}
var frozen := {}             # Foe -> {t, cells: [Vector3i]}
var prop_ready := true       # PROPELLER: one big spin per jump
var air_t := 0.0             # how long the hero has been in the air
var t := 0.0
var overlay: StandardMaterial3D   # tints the toy models (fire, ice, star, metal)
var extra: Node3D            # wings / propeller hat on the hero
var base := {}               # the hero's own hw / height before MINI / MUSHROOM


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	overlay = StandardMaterial3D.new()
	overlay.roughness = 0.35
	overlay.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	overlay.blend_mode = BaseMaterial3D.BLEND_MODE_MIX
	overlay.albedo_color = Color(1, 1, 1, 0)


# ------------------------------------------------------------------ the items
## A spinning item: a 3D toy in the toy look, a paper picture in the paper look.
static func item_model(kind: String) -> Node3D:
	var n := Node3D.new()
	if not Look.toy:
		var s := Sprite3D.new()
		s.texture = load("res://art/pw_%s.png" % kind)
		s.pixel_size = 1.1 / s.texture.get_height()
		s.position.y = 0.6
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.shaded = false
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		n.add_child(s)
		return n
	var P := Toys
	var W := Color.WHITE
	n.scale = Vector3.ONE * 1.5   # easy to see from far away
	match kind:
		"fire", "ice":
			var petal := Color("ff5a3a") if kind == "fire" else Color("6ec8ff")
			var mid := Color("ffd23a") if kind == "fire" else Color("e9f8ff")
			P.part(n, P.cyl(0.04, 0.05, 0.4, 8), Vector3(0, 0.2, 0), Color("3fae4a"))
			for s in [-1, 1]:
				P.part(n, P.sph(0.1), Vector3(s * 0.12, 0.16, 0), Color("4cc85a"), Vector3(1.2, 0.35, 0.7), Vector3(0, 0, s * 0.4))
			var head := P.piv(n, Vector3(0, 0.55, 0))
			for i in 6:
				var a := TAU * i / 6.0
				P.part(head, P.sph(0.11), Vector3(cos(a) * 0.15, sin(a) * 0.15, 0), petal, Vector3(1, 1, 0.5))
			P.part(head, P.sph(0.12), Vector3(0, 0, 0.03), mid, Vector3(1, 1, 0.7))
			for s in [-1, 1]:
				P.part(head, P.sph(0.025), Vector3(s * 0.04, 0.02, 0.11), Color("1e2a44"), Vector3.ONE, Vector3.ZERO, false)
		"mushroom", "mini", "life":
			var capc: Color = {"mushroom": Color("e8413b"), "mini": Color("3f7fd9"), "life": Color("3fc06a")}[kind]
			var k := 0.7 if kind == "mini" else 1.0
			P.part(n, P.cyl(0.14 * k, 0.17 * k, 0.24 * k), Vector3(0, 0.12 * k, 0), Color("fff3dc"))
			P.part(n, P.hemi(0.3 * k), Vector3(0, 0.22 * k, 0), capc, Vector3(1, 1.1, 1))
			for p in [Vector3(0, 0.5, 0.08), Vector3(0.2, 0.33, 0.15), Vector3(-0.2, 0.33, 0.15), Vector3(0, 0.36, -0.22)]:
				P.part(n, P.sph(0.07 * k), p * k, W, Vector3(1, 0.6, 1), Vector3.ZERO, false)
			for s in [-1, 1]:
				P.part(n, P.box(Vector3(0.035, 0.08, 0.02) * k), Vector3(s * 0.05, 0.14, 0.16) * k, Color("1e2a44"), Vector3.ONE, Vector3.ZERO, false)
		"star":
			var mi := MeshInstance3D.new()
			mi.mesh = OpenPlay._star_mesh()
			mi.material_override = P.mat(Color("ffd23a"), true, 1.2)
			mi.position.y = 0.5
			mi.scale = Vector3.ONE * 0.8
			n.add_child(mi)
			for s in [-1, 1]:
				P.part(n, P.sph(0.045), Vector3(s * 0.09, 0.56, 0.11), Color("1e2a44"), Vector3(1, 1.6, 1), Vector3.ZERO, false)
		"wing":
			P.part(n, P.hemi(0.24), Vector3(0, 0.25, 0), Color("e8413b"), Vector3(1, 0.9, 1))
			P.part(n, P.cyl(0.26, 0.26, 0.04), Vector3(0, 0.26, 0.05), Color("c22f2a"), Vector3(1, 1, 1.2))
			for s in [-1, 1]:
				_wing(n, Vector3(s * 0.24, 0.38, -0.02), s, 0.6)
		"propeller":
			P.part(n, P.hemi(0.22), Vector3(0, 0.2, 0), Color("ffb020"), Vector3(1, 0.9, 1))
			P.part(n, P.cyl(0.03, 0.03, 0.18, 8), Vector3(0, 0.46, 0), Color("8a8e96"))
			var blades := P.piv(n, Vector3(0, 0.56, 0))
			blades.name = "blades"
			for s in [-1, 1]:
				P.part(blades, P.box(Vector3(0.5, 0.03, 0.1)), Vector3(s * 0.25, 0, 0), Color("3f7fd9"), Vector3.ONE, Vector3(0.2 * s, 0, 0))
		"metal":
			P.part(n, P.hemi(0.25), Vector3(0, 0.22, 0), Color("c8d0d8"), Vector3(1, 0.9, 1), Vector3.ZERO, true, 0.25)
			P.part(n, P.cyl(0.27, 0.27, 0.04), Vector3(0, 0.23, 0.06), Color("98a2ac"), Vector3(1, 1, 1.25))
			P.part(n, P.sph(0.06), Vector3(0, 0.38, 0.2), Color("ffffff"), Vector3(1, 1, 0.4), Vector3.ZERO, false, 1.0)
	return n


## A little white wing (on the WING CAP and on the hero while flying).
static func _wing(parent: Node3D, pos: Vector3, side: int, k: float) -> Node3D:
	var w := Toys.piv(parent, pos)
	for i in 3:
		Toys.part(w, Toys.sph(0.12 * k), Vector3(side * (0.08 + i * 0.1) * k, (0.06 - i * 0.03) * k, 0), Color.WHITE,
			Vector3(1.0, 0.45 - i * 0.08, 0.35), Vector3(0, 0, side * (0.3 + i * 0.15)))
	return w


func spawn_item(kind: String, pos: Vector3, pop := false) -> Dictionary:
	var n := item_model(kind)
	n.position = pos
	level.add_child(n)
	var it := {"kind": kind, "node": n, "vel": Vector3.ZERO, "sliding": kind in SLIDERS, "ready": 0.0 if not pop else 0.45}
	if it.sliding:
		var a := randf() * TAU
		it.vel = Vector3(cos(a), 0, sin(a)) * 2.4
	if pop:   # rises out of the block
		n.position.y -= 0.9
		var tw := n.create_tween()
		tw.tween_property(n, "position:y", pos.y, 0.4).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	items.append(it)
	return it


## A few items lying around each open world: one near the start, one on the tower stairs,
## one in the secret cave and some in random places.
func place_items() -> void:
	if not level.open or level.is_home:
		return
	var order := ["fire", "mushroom", "star", "wing", "ice", "propeller", "metal", "mini", "life"]
	var k := {"meadow": 0, "frost": 3, "dunes": 6}.get(level.world, 0) as int
	var spots: Array[Vector3] = []
	spots.append(level.start_pos + Vector3(4, 0.05, -4))
	if level.tower_tops.size() > 9:
		spots.append(level.tower_tops[9] + Vector3(0, 0.05, 0))
	for o in level.objectives:
		if o.id == "cave":
			spots.append(Vector3(o.pos.x - 1.5, 6.05, o.pos.z - 1.0))
	for i in 5:
		var p: Vector2i = level._free_spot(4)
		if p.x >= 0:
			spots.append(Vector3(p.x + 0.5, level.ground_at(p.x, p.y) + 0.05, p.y + 0.5))
	for i in spots.size():
		var kind: String = order[(k + i) % order.size()]
		var it := spawn_item(kind, spots[i])
		it.sliding = false   # the ones lying around wait for you
		it.vel = Vector3.ZERO


## Called by a ? block: maybe a power-up comes out (true) instead of coins / a heart.
func from_block(top: Vector3) -> bool:
	if randf() > 0.55:
		return false
	var pick := ["fire", "mushroom", "fire", "mushroom", "ice", "star", "wing", "propeller", "mini", "metal", "life"]
	if game.hearts <= 2:
		pick.append_array(["life", "life"])
	spawn_item(pick.pick_random(), top - Vector3(0, 0.1, 0), true)
	game.sfx("item")
	return true


func _step_items(dt: float) -> void:
	var l: Hero = game.leader()
	for it in items.duplicate():
		var n: Node3D = it.node
		if not is_instance_valid(n):
			items.erase(it)
			continue
		it.ready = maxf(0, it.ready - dt)
		n.rotation.y += dt * 2.2
		if it.sliding and it.ready <= 0:
			var v: Vector3 = it.vel
			v.y -= 22 * dt
			var p := n.position + v * dt
			var g := level.ground_below(p.x, p.z, n.position.y + 0.3)
			if p.y < g and g > -50:
				p.y = g
				v.y = 0
			var ahead := p + Vector3(v.x, 0, v.z).normalized() * 0.3
			if level.solid(floori(ahead.x), floori(p.y + 0.3), floori(ahead.z)):
				v.x = -v.x
				v.z = -v.z
				p = n.position
			n.position = p
			it.vel = v
			if p.y < -4:
				n.queue_free()
				items.erase(it)
				continue
		elif it.ready <= 0 and not it.sliding:
			n.position.y += sin(t * 3.0 + n.position.x) * 0.004
		if it.ready <= 0 and n.position.distance_to(l.position + Vector3(0, 0.3, 0)) < 1.0:
			items.erase(it)
			n.queue_free()
			give(it.kind)


# ------------------------------------------------------------------ getting and losing a power
func give(kind: String) -> void:
	game.fx.burst(game.leader().position + Vector3(0, 1, 0), COLORS[kind], 16, 4, 6)
	game.fx.text(game.leader().position + Vector3(0, 2.2, 0.3), NAMES[kind] + "!", COLORS[kind].lightened(0.3), 52, 1.2)
	game.sfx("heart" if kind == "life" else "item")
	if kind == "life":
		game.hearts = game.MAX_HEARTS
		game.hud.say("LIFE MUSHROOM!", HINTS[kind], 1.8)
		return
	_clear_look()
	power = kind
	power_t = TIME.get(kind, 0.0)
	prop_ready = true
	game.hud.say(NAMES[kind] + "!", HINTS[kind], 2.0)
	if kind == "star" and game.music:
		game.music.pitch_scale = 1.18
	_wear(game.leader())


func lose() -> void:
	if power == "":
		return
	game.fx.burst(game.leader().position + Vector3(0, 1, 0), Color.WHITE, 12, 3, 4)
	_clear_look()
	if power == "star" and game.music:
		game.music.pitch_scale = 1.0
	power = ""
	power_t = 0


func speed_mult() -> float:
	return {"star": 1.35, "mini": 1.1, "metal": 0.85, "wing": 1.1}.get(power, 1.0)


func jump_mult() -> float:
	return {"mini": 1.3, "metal": 0.85, "mushroom": 1.05}.get(power, 1.0)


func block_power() -> int:
	return 3 if power in ["mushroom", "metal"] else 1


## Hurt by something: true = the power took the hit instead of a heart.
func on_hurt() -> bool:
	if power in ["star", "metal"]:
		return true
	if power in ["mushroom", "fire", "ice"]:
		game.hud.say("OOF!", "The power-up saved you!", 1.2)
		game.sfx("hurt")
		lose()
		game.hurt_t = 1.4
		game.leader().flash_t = 1.4
		return true
	return false


## The steered hero touched a bad guy: true = the power dealt with it.
func on_touch(f: Foe) -> bool:
	if frozen.has(f):
		return true
	if power == "star" or (power == "metal" and not f.is_boss):
		f.hurt(99 if not f.is_boss else 3, game)
		game.sfx("stomp")
		game.fx.burst(f.position + Vector3(0, 0.6, 0), Color.from_hsv(randf(), 0.7, 1), 12, 4, 6)
		return true
	if power == "metal":
		return true
	return false


func is_frozen(f: Foe) -> bool:
	return frozen.has(f)


## ACTION with a flower: throw a fireball / ice ball (true = done, skip the normal move).
func on_action(h: Hero) -> bool:
	if power not in ["fire", "ice"] or h.action_t > 0:
		return false
	h.action_t = 0.28
	var f := Vector3(h.facing.x, 0, h.facing.z)
	if f.length() < 0.1:
		f = Vector3.RIGHT
	f = f.normalized()
	var from := h.position + Vector3(0, h.height * 0.6, 0) + f * 0.4
	if power == "fire":
		game.spawn_shot("fireball", from, f * 9.0 + Vector3(0, 2, 0), h, 2)
		game.sfx("zap", -4)
	else:
		var n: Node3D = game._shot_node("shot_ice", from, 0.5)
		balls.append({"node": n, "vel": f * 9.0 + Vector3(0, 2, 0), "life": 2.0})
		game.sfx("zap", -2)
	return true


func _step_balls(dt: float) -> void:
	for b in balls.duplicate():
		var n: Node3D = b.node
		b.life -= dt
		var v: Vector3 = b.vel
		v.y -= 24 * dt
		var p := n.position + v * dt
		var g := level.ground_below(p.x, p.z, n.position.y + 0.4)
		if p.y - 0.25 < g and g > -50:
			p.y = g + 0.25
			v.y = 6.0
		var gone: bool = b.life <= 0 or p.y < -4 or level.solid(floori(p.x + signf(v.x) * 0.3), floori(p.y), floori(p.z + signf(v.z) * 0.3))
		n.position = p
		n.rotation.y += dt * 8
		b.vel = v
		if not gone:
			for f in game.foes:
				if is_instance_valid(f) and f.dying < 0 and not frozen.has(f) and not f.is_boss \
						and Vector2(f.position.x - p.x, f.position.z - p.z).length() < f.hw + 0.5 and p.y > f.position.y - 0.3 and p.y < f.position.y + f.height + 0.4:
					freeze(f)
					gone = true
					break
				elif is_instance_valid(f) and f.is_boss and f.dying < 0 and f.position.distance_to(p) < 1.6:
					f.hurt(1, game)
					gone = true
					break
		if gone:
			game.fx.burst(p, Color("bfeeff"), 6, 3)
			n.queue_free()
			balls.erase(b)


## Turns a bad guy into ice blocks you can stand on for a few seconds.
func freeze(f: Foe) -> void:
	var cells: Array[Vector3i] = []
	var c := Vector3i(floori(f.position.x), floori(f.position.y + 0.1), floori(f.position.z))
	for dy in maxi(1, ceili(f.height)):
		var q := c + Vector3i(0, dy, 0)
		if level.get_cell(q.x, q.y, q.z) == Level.AIR:
			level.set_cell(q.x, q.y, q.z, Level.ICE)
			cells.append(q)
	frozen[f] = {"t": FREEZE_TIME, "cells": cells}
	f.vel = Vector3.ZERO
	f.position = Vector3(c.x + 0.5, c.y, c.z + 0.5)
	f.alert.visible = false
	game.sfx("break", -6)
	game.fx.burst(f.position + Vector3(0, 0.6, 0), Color("bfeeff"), 14, 4, 6)
	game.fx.text(f.position + Vector3(0, f.height + 0.8, 0.3), "FROZEN!", Color("bfeeff"), 44)


func _step_frozen(dt: float) -> void:
	for f in frozen.keys():
		var d: Dictionary = frozen[f]
		d.t -= dt
		var alive: bool = is_instance_valid(f) and f.alive and f.dying < 0
		if alive and f.model:
			f.model.visible = int(d.t * 8) % 2 == 0 if d.t < 1.0 else true
		if d.t <= 0 or not alive:
			for c in d.cells:
				if level.get_cell(c.x, c.y, c.z) == Level.ICE:
					level.set_cell(c.x, c.y, c.z, Level.AIR)
			if alive and f.model:
				f.model.visible = true
			frozen.erase(f)


# ------------------------------------------------------------------ every frame
func step(dt: float) -> void:
	t += dt
	_step_items(dt)
	_step_balls(dt)
	_step_frozen(dt)
	var l: Hero = game.leader()
	if power == "":
		return
	if worn_by != l:   # SWAP: the power goes to whoever you steer now
		_clear_look()
		_wear(l)
	if TIME.has(power):
		power_t -= dt
		if power_t <= 0:
			game.hud.say(NAMES[power], "ran out", 1.0)
			lose()
			return
	match power:
		"wing":
			if game.jump_held and not l.on_floor:
				l.vel.y = minf(maxf(l.vel.y, 5.0), 7.0)
				l.jumps_left = 0
				if int(t * 12) % 2 == 0:
					game.fx.burst(l.position, Color.WHITE, 1, 1.0, -1, 0.5)
		"propeller":
			if l.on_floor:
				prop_ready = true
				air_t = 0.0
			else:
				air_t += dt
			if not l.on_floor and game.jump_pressed and prop_ready and air_t > 0.1:   # not the jump off the ground
				prop_ready = false
				l.vel.y = 17.0
				l.jumps_left = 0
				l.spin()
				game.sfx("jump2")
				game.fx.burst(l.position, Color("ffb020"), 10, 4, 3)
			elif l.vel.y < -2.2 and game.jump_held:
				l.vel.y = -2.2   # float down slowly
		"metal":
			l.jumps_left = 0
			if not l.on_floor:
				l.vel.y -= 14 * dt   # heavy
			if l.landed and l.vel.y == 0 and game.fx.shake < 0.1:
				game.fx.shake = 0.25
				game.sfx("slam", -8)
	_show(l)


## How the power looks on the hero (called every frame, after the hero's animation).
func _show(h: Hero) -> void:
	var k := 1.0
	if power == "mushroom":
		k = 1.45
	elif power == "mini":
		k = 0.55
	h.scale = h.scale.lerp(Vector3.ONE * k, 0.2)
	var tint := Color(1, 1, 1, 0)
	match power:
		"fire":
			tint = Color(1.0, 0.85, 0.75, 0.22)
		"ice":
			tint = Color(0.55, 0.85, 1.0, 0.3)
		"star":
			tint = Color.from_hsv(fmod(t * 2.5, 1.0), 0.7, 1.0, 0.38)
		"metal":
			tint = Color(0.72, 0.76, 0.82, 0.55 + sin(t * 6) * 0.05)
	overlay.metallic = 0.8 if power == "metal" else 0.0
	overlay.albedo_color = tint
	if h.sprite:
		if tint.a > 0:
			var c := Color.WHITE.lerp(Color(tint.r, tint.g, tint.b), tint.a * 1.5)
			if h.flash_t <= 0:
				h.sprite.modulate = c
	if extra:
		if power == "propeller":
			var bl := extra.get_node_or_null("blades")
			if bl:
				bl.rotation.y += 0.6 if not h.on_floor else 0.2
		elif power == "wing":
			for w in extra.get_children():
				w.rotation.z = sin(t * (18 if not h.on_floor else 4)) * 0.35 * (1 if w.position.x > 0 else -1)


func _wear(h: Hero) -> void:
	worn_by = h
	if not base.has(h):
		base[h] = [h.hw, h.height]
	var b: Array = base[h]
	if power == "mini":
		h.hw = b[0] * 0.75
		h.height = b[1] * 0.55
	elif power == "mushroom":
		h.height = b[1] * 1.25
	if h.model:
		_set_overlay(h.model, overlay)
	# wings / propeller hat ride on the hero (both looks)
	var top := h.sprite_h * (0.92 if h.who != "mon" else 0.75)
	if power == "wing":
		extra = Node3D.new()
		h.add_child(extra)
		for s in [-1, 1]:
			var w := _wing(extra, Vector3(s * 0.26, h.height * 0.65, -0.18), s, 1.1)
			w.rotation.y = s * 0.3
	elif power == "propeller":
		extra = item_model("propeller") if Look.toy else _paper_extra("propeller")
		extra.position.y = top - 0.15
		extra.scale = Vector3.ONE * 0.8
		h.add_child(extra)


func _paper_extra(kind: String) -> Node3D:
	var n := Node3D.new()
	var s := Sprite3D.new()
	s.texture = load("res://art/pw_%s.png" % kind)
	s.pixel_size = 0.6 / s.texture.get_height()
	s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	s.shaded = false
	s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
	s.position.y = 0.3
	n.add_child(s)
	return n


func _clear_look() -> void:
	if worn_by and is_instance_valid(worn_by):
		var h := worn_by
		if base.has(h):
			h.hw = base[h][0]
			h.height = base[h][1]
		h.scale = Vector3.ONE
		if h.model:
			_set_overlay(h.model, null)
		if h.sprite:
			h.sprite.modulate = Color.WHITE
	if extra and is_instance_valid(extra):
		extra.queue_free()
	extra = null
	worn_by = null


static func _set_overlay(n: Node, m: Material) -> void:
	if n is GeometryInstance3D:
		n.material_overlay = m
	for c in n.get_children():
		_set_overlay(c, m)


## The power's name and time left (a shrinking ring), under the hearts.
func draw_hud(hud) -> void:
	if power == "":
		return
	var c := Vector2(hud.size.x - 150, 120)
	var col: Color = COLORS[power]
	hud.draw_circle(c, 34, Color(0.08, 0.1, 0.2, 0.7))
	if TIME.has(power):
		var f := clampf(power_t / TIME[power], 0, 1)
		hud.draw_arc(c, 30, -PI / 2, -PI / 2 + TAU * f, 40, col, 7, true)
	else:
		hud.draw_arc(c, 30, 0, TAU, 40, col, 4, true)
	hud.fit(hud.img("pw_" + power), Rect2(c - Vector2(22, 22), Vector2(44, 44)))
	hud.txt(NAMES[power], Vector2(c.x - 110, c.y + 58), 20, col.lightened(0.3), HORIZONTAL_ALIGNMENT_CENTER, 220, 6)
