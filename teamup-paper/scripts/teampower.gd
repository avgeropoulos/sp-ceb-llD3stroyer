class_name TeamPower
extends Node3D
## TEAM POWER spots: the signature of "Rocco & Blocky: TEAM UP!". Every open world has 5 puzzle
## spots that need ONE teammate's special power, plus a TEAM SHRINE that needs all three:
##   MELT   an ICE door           -> a FIRE or DRAGON partner melts it (ACTION)
##   SPARK  a dead IRON door      -> an ELECTRIC, PSYCHIC or GHOST partner powers it up
##   GROW   a seed by a tall rock -> a WATER, GRASS, FAIRY or BUG partner grows a beanstalk staircase
##   SMASH  a cracked rock door   -> BLOCKY, ALIX, BRAWLO or a ROCK partner smashes it
##   KICK   a wall-kick shaft     -> ROCCO kicks up between the walls
## A glowing pillar shows WHO you need (their face) and tells you to SWAP, or to bring that partner
## from home. Behind each one: a treasure chest (coins, TEAM CRYSTALS, a power-up).
## The TEAM SHRINE has 3 pedestals: light each with the right teammate in turn (hero 1, hero 2,
## partner) and a TEAM STAR appears - a shard of the TEAM HEART.

const KINDS := ["melt", "spark", "grow", "smash", "kick"]
const NAMES := {"melt": "MELT THE ICE", "spark": "POWER THE DOOR", "grow": "GROW A BEANSTALK", "smash": "SMASH THE ROCK",
	"kick": "WALL KICK UP"}
const NEED_TEXT := {"melt": "FIRE power", "spark": "ELECTRIC power", "grow": "WATER or GRASS power", "smash": "STRONG ARM",
	"kick": "ROCCO's wall kick"}
## Who can do it: partner lines (heroes.mon.line) or hero characters (Hero.who).
const LINES := {"melt": ["fire", "dragon"], "spark": ["spark", "mind", "ghost"], "grow": ["water", "grass", "fairy", "bug"],
	"smash": ["rock"], "kick": []}
const HEROES := {"melt": [], "spark": [], "grow": [], "smash": ["blocky", "alix", "brawlo"], "kick": ["rocco"]}
const COLORS := {"melt": Color("ff6a2a"), "spark": Color("ffd23a"), "grow": Color("4cd06a"), "smash": Color("b08a60"),
	"kick": Color("ff4a5a"), "shrine": Color("ff7ad9")}
const BARRIER := {"melt": Level.ICE, "spark": Level.IRON_BLOCK, "smash": Level.STONE}
const REACH := 3.4            # how close you must be to use a power (blocks)
const PILLAR_H := 4.6

var game
var level: Level
var spots: Array = []          # runtime copies of level.team_spots + nodes
var shrine := {}               # {pos, pedestals: [Vector3], lit: int, nodes...}
var t := 0.0


# ------------------------------------------------------------------ building (during generate_open)
## Finds flat open ground for the 5 spots and the shrine and builds them into the block grid.
## Its own random numbers (so the rest of the world stays the same as before).
static func build(lv: Level, start: Vector2i) -> void:
	if lv.bonus or lv.is_home:
		return
	var r := RandomNumberGenerator.new()
	r.seed = lv.rng.seed * 31 + 4049
	var placed: Array[Vector2i] = []
	# the TEAM SHRINE first (3 pedestals in a row): it holds the world's TEAM STAR
	var s := _find(lv, r, start, placed, 3)
	if s.x >= 0:
		placed.append(s)
		var g := lv.ground_at(s.x, s.y)
		var face := _face_dir(s, start)
		var side := Vector2i(face.y, -face.x)   # along the row
		var peds := []
		for i in 3:
			var c := s + side * (i - 1) * 3
			_put(lv, c.x, g, c.y, Level.GOLD_BLOCK)
			peds.append(Vector3(c.x + 0.5, g + 1, c.y + 0.5))
		lv.team_shrine = {"pos": Vector3(s.x + 0.5, g, s.y + 0.5), "pedestals": peds}
		lv.keep_out.append([Vector2(s), 5.0])
		lv.objectives.append({"id": "team", "name": "TEAM SHRINE",
			"hint": "Light the 3 pedestals in turn: hero 1, hero 2, then your partner (SWAP!). A TEAM HEART shard!",
			"pos": Vector3(s.x + 0.5, g + 2.2, s.y + 0.5)})
	for kind in KINDS:
		var at := _find(lv, r, start, placed, 3 if kind == "kick" else 2)
		if at.x < 0:
			continue
		placed.append(at)
		var g := lv.ground_at(at.x, at.y)
		var face := _face_dir(at, start)
		var spot := {"id": kind, "kind": kind, "base": Vector3(at.x + 0.5, g, at.y + 0.5), "barrier": [], "stairs": [],
			"chest": Vector3.ZERO, "use": Vector3.ZERO}
		match kind:
			"melt", "spark", "smash":
				_vault(lv, at, g, face, kind, spot)
			"grow":
				_beanstalk_rock(lv, at, g, face, spot)
			"kick":
				_shaft(lv, at, g, face, spot)
		lv.team_spots.append(spot)
		lv.keep_out.append([Vector2(at), 4.0])


## A random flattish spot (within 2 blocks, nothing on top) away from the start, the castle,
## the other challenges and each other.
static func _find(lv: Level, r: RandomNumberGenerator, start: Vector2i, placed: Array[Vector2i], rad: int) -> Vector2i:
	for tries in 1500:
		var x := r.randi_range(8, lv.sx - 9)
		var z := r.randi_range(8, lv.sz - 9)
		var p := Vector2i(x, z)
		if Vector2(p).distance_to(Vector2(start)) < 16:
			continue
		if Rect2(lv.arena.position - Vector2(7, 7), lv.arena.size + Vector2(14, 14)).has_point(Vector2(x, z)):
			continue
		var ok := true
		for k in lv.keep_out:
			if Vector2(x, z).distance_to(k[0]) < float(k[1]) + rad - 1:
				ok = false
				break
		for q in placed:
			if Vector2(p).distance_to(Vector2(q)) < 12:
				ok = false
		for pt in lv.world_paintings:
			if Vector2(x, z).distance_to(Vector2(pt.pos.x, pt.pos.z)) < 9:
				ok = false
		if not ok:
			continue
		var g := 0
		var gmin := 999
		for dx in range(-rad, rad + 1):
			for dz in range(-rad, rad + 1):
				var gg := lv.ground_at(x + dx, z + dz)
				g = maxi(g, gg)
				gmin = mini(gmin, gg)
		if gmin < 2 or g - gmin > 2 or g > lv.sy - 13:
			continue
		for dx in range(-rad, rad + 1):
			for dz in range(-rad, rad + 1):
				var gg := lv.ground_at(x + dx, z + dz)
				if lv.get_cell(x + dx, gg - 1, z + dz) in [Level.WATER, Level.LAVA, Level.ICE] or lv.locked.has(Vector3i(x + dx, gg - 1, z + dz)):
					ok = false
					break
				for y in range(gg, g + 11):
					if lv.get_cell(x + dx, y, z + dz) != Level.AIR:
						ok = false
						break
			if not ok:
				break
		if not ok:
			continue
		# level it: fill the little dips so the whole spot is flat at g
		for dx in range(-rad, rad + 1):
			for dz in range(-rad, rad + 1):
				var gg := lv.ground_at(x + dx, z + dz)
				if gg < g:
					var top := lv.get_cell(x + dx, gg - 1, z + dz)
					for y in range(gg, g):
						_put(lv, x + dx, y, z + dz, top if top != Level.AIR else lv.top_block)
					lv.hmap[(x + dx) + (z + dz) * lv.sx] = g
		return p
	return Vector2i(-1, -1)


## Which side faces the start (so doors open toward where you come from).
static func _face_dir(at: Vector2i, start: Vector2i) -> Vector2i:
	var d := Vector2(start - at)
	if absf(d.x) > absf(d.y):
		return Vector2i(1 if d.x > 0 else -1, 0)
	return Vector2i(0, 1 if d.y > 0 else -1)


static func _put(lv: Level, x: int, y: int, z: int, t: int, lock := false) -> void:
	if lv.inside(x, y, z):
		lv.cells[lv.idx(x, y, z)] = t
		if lock:
			lv.locked[Vector3i(x, y, z)] = true


## A little stone vault (5x5, 3 high) with a 3x2 door made of the barrier block.
static func _vault(lv: Level, at: Vector2i, g: int, face: Vector2i, kind: String, spot: Dictionary) -> void:
	var side := Vector2i(face.y, -face.x)
	for a in range(-2, 3):
		for b in range(-2, 3):
			var c := at + face * a + side * b
			for y in range(g, g + 4):
				var wall: bool = absi(a) == 2 or absi(b) == 2 or y == g + 3
				if wall:
					var door: bool = a == 2 and absi(b) <= 1 and y < g + 2
					if door:
						_put(lv, c.x, y, c.y, BARRIER[kind], true)
						spot.barrier.append(Vector3i(c.x, y, c.y))
					else:
						_put(lv, c.x, y, c.y, Level.STONE_BRICK if (y + a + b) % 3 else Level.BRICK, true)
				else:
					_put(lv, c.x, y, c.y, Level.AIR)
	spot.chest = Vector3(at.x + 0.5, g, at.y + 0.5)
	var d := at + face * 3
	spot.use = Vector3(d.x + 0.5, g, d.y + 0.5)


## A tall stone rock (3x3, 9 high) with the chest on top and a seed patch in front; the beanstalk
## staircase (16 steps round the rock) is remembered and grows when the power is used.
static func _beanstalk_rock(lv: Level, at: Vector2i, g: int, face: Vector2i, spot: Dictionary) -> void:
	for a in range(-1, 2):
		for b in range(-1, 2):
			for y in range(g, g + 9):
				_put(lv, at.x + a, y, at.y + b, Level.STONE if (y + a) % 4 else Level.STONE_BRICK, true)
	spot.chest = Vector3(at.x + 0.5, g + 9, at.y + 0.5)
	# the ring round the rock, starting in front (the seed side), going round
	var ring: Array[Vector2i] = []
	for i in range(-2, 3):
		ring.append(Vector2i(i, 2))
	for i in range(1, -3, -1):
		ring.append(Vector2i(2, i))
	for i in range(1, -3, -1):
		ring.append(Vector2i(i, -2))
	for i in range(-1, 2):
		ring.append(Vector2i(-2, i))
	# rotate the ring so step 0 is on the side facing the start
	var start_i := 2
	var steps := []
	for k in 9:
		var rr: Vector2i = ring[(start_i + k) % ring.size()]
		var c := Vector2i(at.x + rr.x, at.y + rr.y)
		if face == Vector2i(0, -1):
			c = Vector2i(at.x - rr.x, at.y - rr.y)
		elif face == Vector2i(1, 0):
			c = Vector2i(at.x + rr.y, at.y - rr.x)
		elif face == Vector2i(-1, 0):
			c = Vector2i(at.x - rr.y, at.y + rr.x)
		steps.append(Vector3i(c.x, g + k, c.y))
	spot.stairs = steps
	var s0: Vector3i = steps[0]
	_put(lv, s0.x, g - 1, s0.z, Level.DIRT)   # the seed patch
	spot.use = Vector3(s0.x + 0.5, g, s0.z + 0.5)


## Two stone walls 2 apart and 10 high: only a wall kick gets you up. Chest on a ledge at the top.
static func _shaft(lv: Level, at: Vector2i, g: int, face: Vector2i, spot: Dictionary) -> void:
	var side := Vector2i(face.y, -face.x)
	for w: int in [-2, 1]:
		for a in range(-1, 2):
			var c := at + side * w + face * a
			for y in range(g, g + 10):
				_put(lv, c.x, y, c.y, Level.STONE_BRICK if y % 3 else Level.BRICK, true)
	# a ledge on top of the far wall, out to the side
	for a in range(-1, 2):
		for w: int in [2, 3]:
			var c := at + side * w + face * a
			_put(lv, c.x, g + 9, c.y, Level.PLANKS, true)
	var top := at + side * 2
	spot.chest = Vector3(top.x + 0.5 + side.x * 0.5, g + 10, top.y + 0.5 + side.y * 0.5)
	spot.use = Vector3(at.x + 0.5 - side.x * 0.5, g, at.y + 0.5 - side.y * 0.5)   # the bottom of the shaft


# ------------------------------------------------------------------ playing
func setup(g, lv: Level) -> void:
	game = g
	level = lv
	for s in level.team_spots:
		var sp: Dictionary = s.duplicate()
		sp["open"] = sp.kind == "kick"   # the shaft has no door: you just have to get up it
		sp["looted"] = false
		add_child(_pillar(sp))
		sp["chest_node"] = _chest(sp.chest)
		spots.append(sp)
	if not level.team_shrine.is_empty():
		shrine = level.team_shrine.duplicate()
		shrine["lit"] = 0
		shrine["glows"] = []
		shrine["faces"] = []
		for i in 3:
			var p: Vector3 = shrine.pedestals[i]
			var glow := _glow_orb(COLORS.shrine)
			glow.position = p + Vector3(0, 0.35, 0)
			glow.visible = false
			add_child(glow)
			shrine.glows.append(glow)
			var f := Sprite3D.new()
			f.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			f.shaded = false
			f.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
			f.position = p + Vector3(0, 1.9, 0)
			add_child(f)
			shrine.faces.append(f)
		var lbl := _label("TEAM SHRINE\nlight each pedestal\nwith the teammate shown!", Color("ffb3ec"))
		lbl.position = shrine.pos + Vector3(0, 4.6, 0)
		add_child(lbl)
		shrine["label"] = lbl
	sync()


## After the save is loaded (and after a TEAM change): spots already done stay open, the
## pillars show the faces of THIS team.
func sync() -> void:
	if game == null:
		return
	var done: Array = game.identity.spots_done(level.world)
	for sp in spots:
		if sp.id in done and not sp.looted:
			if not sp.open:
				_open_now(sp, false)
			sp.open = true
			sp.looted = true
			if sp.has("chest_node") and is_instance_valid(sp.chest_node):
				sp.chest_node.queue_free()
			sp.erase("chest_node")
		_refresh_pillar(sp)
	if not shrine.is_empty():
		if game.open_play and game.open_play.got.has("team"):
			shrine.lit = 3
			shrine.label.text = "TEAM SHRINE\nDONE!"
		_shrine_faces()


## Who in this team (if anyone) can do this kind: "rocco"/"blocky" slot name, "mon", or "".
func who_can(kind: String) -> String:
	if game.heroes.mon.line in LINES.get(kind, []):
		return "mon"
	for slot in ["rocco", "blocky"]:
		if game.heroes[slot].who in HEROES.get(kind, []):
			return slot
	return ""


func can_do(h: Hero, kind: String) -> bool:
	if h.who == "mon":
		return h.line in LINES.get(kind, [])
	return h.who in HEROES.get(kind, [])


## What the pillar says.
func need_text(kind: String) -> String:
	var w := who_can(kind)
	if w == "":
		var who := ""
		if not LINES[kind].is_empty():
			who = "a " + String(Mons.LINES[LINES[kind][0]].type) + " partner"
		else:
			who = Team.name_of(HEROES[kind][0])
		return "%s\nBring %s!\n(TEAM at home)" % [NAMES[kind], who]
	var name: String = game.display_name(w if w == "mon" else game.heroes[w].who)
	return "%s\nSWAP to %s!" % [NAMES[kind], name]


func _face_for(kind: String) -> String:
	var w := who_can(kind)
	if w == "mon":
		return game.face("mon")
	if w != "":
		return game.face(game.heroes[w].who)
	if not LINES[kind].is_empty():
		return Mons.face(LINES[kind][0], 0)
	return String(HEROES[kind][0]) + "_idle"


func _pillar(sp: Dictionary) -> Node3D:
	var n := Node3D.new()
	var col: Color = COLORS[sp.kind]
	var p: Vector3 = sp.use
	# stand the pillar a little to the side of where you use the power
	var off: Vector3 = sp.use - sp.base
	off.y = 0
	var side := Vector3(-off.z, 0, off.x).normalized() * 2.0 if off.length() > 0.1 else Vector3(2, 0, 0)
	n.position = p + side
	var m := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.18
	cm.bottom_radius = 0.28
	cm.height = PILLAR_H
	m.mesh = cm
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.albedo_color = Color(col, 0.55)
	mat.emission_enabled = true
	mat.emission = col
	m.material_override = mat
	m.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	m.position.y = PILLAR_H * 0.5
	n.add_child(m)
	var base := MeshInstance3D.new()
	var bm := CylinderMesh.new()
	bm.top_radius = 0.6
	bm.bottom_radius = 0.75
	bm.height = 0.4
	base.mesh = bm
	base.material_override = Toys.mat(Color("6c6f78"))
	base.position.y = 0.2
	n.add_child(base)
	var orb := _glow_orb(col)
	orb.position.y = PILLAR_H + 0.3
	n.add_child(orb)
	var face := Sprite3D.new()
	face.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	face.shaded = false
	face.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
	face.position.y = PILLAR_H + 1.4
	n.add_child(face)
	var lbl := _label("", col.lightened(0.5))
	lbl.position.y = PILLAR_H + 2.6
	n.add_child(lbl)
	sp["pillar"] = n
	sp["face"] = face
	sp["label"] = lbl
	sp["orb"] = orb
	_refresh_pillar(sp)
	return n


func _refresh_pillar(sp: Dictionary) -> void:
	var face: Sprite3D = sp.face
	var tex: Texture2D = load("res://art/%s.png" % _face_for(sp.kind))
	face.texture = tex
	if tex:
		face.pixel_size = 1.3 / tex.get_height()
	var lbl: Label3D = sp.label
	if sp.looted:
		lbl.text = "DONE!"
		face.modulate = Color(1, 1, 1, 0.5)
	elif sp.open and sp.kind != "kick":
		lbl.text = NAMES[sp.kind] + "\nOPEN! Get the chest!"
		face.modulate = Color(1, 1, 1, 0.8)
	else:
		lbl.text = need_text(sp.kind)
		face.modulate = Color.WHITE


func _label(text: String, col: Color) -> Label3D:
	var l := Label3D.new()
	l.text = text
	l.font_size = 40
	l.outline_size = 12
	l.pixel_size = 0.009
	l.modulate = col
	l.outline_modulate = Color(0.1, 0.06, 0.16)
	l.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	l.no_depth_test = false
	return l


func _glow_orb(col: Color) -> MeshInstance3D:
	var m := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = 0.34
	sm.height = 0.68
	m.mesh = sm
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.albedo_color = col.lightened(0.3)
	mat.emission_enabled = true
	mat.emission = col
	mat.emission_energy_multiplier = 2.0
	m.material_override = mat
	m.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	return m


## A treasure chest (wooden box, gold trim, a lid that opens).
func _chest(pos: Vector3) -> Node3D:
	var n := Node3D.new()
	n.position = pos
	var box := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.9, 0.55, 0.62)
	box.mesh = bm
	box.material_override = Toys.mat(Color("9a5a2a"))
	box.position.y = 0.28
	n.add_child(box)
	var trim := MeshInstance3D.new()
	var tm := BoxMesh.new()
	tm.size = Vector3(0.94, 0.1, 0.66)
	trim.mesh = tm
	trim.material_override = Toys.mat(Color("ffd23a"), false, 0.5)
	trim.position.y = 0.5
	n.add_child(trim)
	var lid := Node3D.new()
	lid.position = Vector3(0, 0.56, -0.31)
	n.add_child(lid)
	var lm := MeshInstance3D.new()
	var lb := BoxMesh.new()
	lb.size = Vector3(0.9, 0.24, 0.62)
	lm.mesh = lb
	lm.material_override = Toys.mat(Color("b06a32"))
	lm.position = Vector3(0, 0.12, 0.31)
	lid.add_child(lm)
	var lock := MeshInstance3D.new()
	var km := BoxMesh.new()
	km.size = Vector3(0.16, 0.2, 0.06)
	lock.mesh = km
	lock.material_override = Toys.mat(Color("ffd23a"), false, 0.6)
	lock.position = Vector3(0, 0.42, 0.33)
	n.add_child(lock)
	n.set_meta("lid", lid)
	add_child(n)
	return n


# ------------------------------------------------------------------ ACTION near a spot
## Called from main._action: uses a power if you're at a spot. True = ACTION was used up here.
func try_use(h: Hero) -> bool:
	if not shrine.is_empty() and _shrine_try(h):
		return true
	var best := {}
	var bd := REACH
	for sp in spots:
		if sp.open and sp.kind != "kick" or sp.looted:
			continue
		var d := Vector2(h.position.x - sp.use.x, h.position.z - sp.use.z).length()
		if d < bd and absf(h.position.y - sp.use.y) < 2.5:
			bd = d
			best = sp
	if best.is_empty():
		return false
	if not can_do(h, best.kind):
		game.sfx("bonk", -4)
		var w := who_can(best.kind)
		if w == "":
			game.hud.say(NAMES[best.kind] + "!", "You need %s. Change your TEAM at home!" % NEED_TEXT[best.kind], 2.4)
		else:
			game.hud.say("SWAP TO " + game.display_name(w if w == "mon" else game.heroes[w].who) + "!",
				"Only %s can %s." % [NEED_TEXT[best.kind], NAMES[best.kind].to_lower()], 2.4)
		return true
	if best.kind == "kick":
		game.hud.say("WALL KICK!", "Jump between the walls, and JUMP again each time you touch one!", 2.6)
		return true
	_use(best, h)
	return true


func _use(sp: Dictionary, h: Hero) -> void:
	sp.open = true
	h.action_t = 0.4
	h.anim_t = 0
	var col: Color = COLORS[sp.kind]
	match sp.kind:
		"melt":
			game.hud.say("MELT!", "The ice door melts away!", 1.8)
			game.sfx("break")
		"spark":
			game.hud.say("ZAP!", "The door wakes up and slides open!", 1.8)
			for c in sp.barrier:
				game.fx.bolt(Vector3(c) + Vector3(0.5, 0, 0.5))
			game.sfx("thunder", -6)
		"smash":
			game.hud.say("SMASH!", "The cracked rock breaks!", 1.8)
			game.fx.shake = 0.7
			game.sfx("break")
		"grow":
			game.hud.say("GROW!", "A beanstalk grows up the rock: climb it!", 2.2)
			game.sfx("item")
	_open_now(sp, true)
	game.identity.on_team_spot(level.world, sp.id)
	game.fx.burst(sp.use + Vector3(0, 1.0, 0), col, 26, 6, 8)
	_refresh_pillar(sp)


## Removes the barrier (or grows the beanstalk). `show` = with effects.
func _open_now(sp: Dictionary, show: bool) -> void:
	for c: Vector3i in sp.barrier:
		level.locked.erase(c)
		level.set_cell(c.x, c.y, c.z, Level.AIR)
		if show:
			game.fx.burst(Vector3(c) + Vector3(0.5, 0.5, 0.5), Level.COLORS[BARRIER.get(sp.kind, Level.STONE)], 8, 3)
	if sp.kind == "grow":
		var steps: Array = sp.stairs
		for i in steps.size():
			var c: Vector3i = steps[i]
			if show:
				var tw := create_tween()
				tw.tween_interval(0.12 * i)
				tw.tween_callback(func():
					level.set_cell(c.x, c.y, c.z, Level.LEAVES if i % 3 else Level.LOG)
					game.fx.burst(Vector3(c) + Vector3(0.5, 0.6, 0.5), Color("4cd06a"), 6, 2.5))
			else:
				level.set_cell(c.x, c.y, c.z, Level.LEAVES if i % 3 else Level.LOG)


func step(dt: float) -> void:
	t += dt
	var l: Hero = game.leader()
	for sp in spots:
		var orb: MeshInstance3D = sp.orb
		orb.position.y = PILLAR_H + 0.3 + sin(t * 3.0 + sp.base.x) * 0.15
		var pl: Node3D = sp.pillar
		pl.visible = not sp.looted or Vector2(l.position.x - pl.position.x, l.position.z - pl.position.z).length() < 30
		# open the chest when you reach it
		if sp.open and not sp.looted and sp.has("chest_node"):
			var c: Vector3 = sp.chest
			if l.position.distance_to(c) < 1.6 or (sp.kind == "kick" and l.position.distance_to(c) < 2.2):
				_loot(sp)
	# the shrine's pedestals: the next one to light pulses
	if not shrine.is_empty() and shrine.lit < 3:
		var f: Sprite3D = shrine.faces[shrine.lit]
		f.scale = Vector3.ONE * (1.0 + sin(t * 6.0) * 0.12)
		if int(t * 2) != int((t - dt) * 2):
			_shrine_faces()


func _loot(sp: Dictionary) -> void:
	sp.looted = true
	var n: Node3D = sp.chest_node
	var lid: Node3D = n.get_meta("lid")
	var tw := create_tween()
	tw.tween_property(lid, "rotation:x", -1.9, 0.35).set_trans(Tween.TRANS_BACK)
	tw.tween_interval(1.4)
	tw.tween_property(n, "scale", Vector3(0.01, 0.01, 0.01), 0.3)
	tw.tween_callback(n.queue_free)
	var coins := 15
	var crystals := 3
	game.coins += coins
	game.identity.add_crystals(crystals)
	game.sfx("clear")
	game.fx.burst(sp.chest + Vector3(0, 0.8, 0), Color("ffd23a"), 22, 5, 8)
	game.fx.text(sp.chest + Vector3(0, 1.6, 0), "+%d  +%d CRYSTALS" % [coins, crystals], Color("9ff2ff"), 48)
	game.hud.say("TREASURE!", "+%d coins, +%d TEAM CRYSTALS!" % [coins, crystals], 2.2)
	if game.powers and randf() < 0.6:
		game.powers.spawn_item(["fire", "star", "wing", "mushroom", "life"][randi() % 5], sp.chest + Vector3(0, 1.2, 0), true)
	game.identity.on_chest(level.world, sp.id)
	_refresh_pillar(sp)


# ------------------------------------------------------------------ the TEAM SHRINE
## The teammate each pedestal wants: [slot "rocco", slot "blocky", "mon"].
const SHRINE_SLOTS := ["rocco", "blocky", "mon"]


func _shrine_faces() -> void:
	for i in 3:
		var f: Sprite3D = shrine.faces[i]
		var slot: String = SHRINE_SLOTS[i]
		var tex: Texture2D = load("res://art/%s.png" % (game.face("mon") if slot == "mon" else game.face(game.heroes[slot].who)))
		f.texture = tex
		if tex:
			f.pixel_size = 1.1 / tex.get_height()
		f.modulate = Color(1, 1, 1, 1.0 if i >= shrine.lit else 0.45)
		var g: MeshInstance3D = shrine.glows[i]
		g.visible = i < shrine.lit


func _shrine_try(h: Hero) -> bool:
	if shrine.lit >= 3:
		return false
	for i in 3:
		var p: Vector3 = shrine.pedestals[i]
		if Vector2(h.position.x - p.x, h.position.z - p.z).length() < 1.7 and absf(h.position.y - p.y) < 1.6:
			if i < shrine.lit:
				game.hud.say("ALREADY LIT!", "Light the next one (the bouncing face)!", 1.6)
				return true
			if i > shrine.lit:
				game.sfx("bonk", -4)
				game.hud.say("IN ORDER!", "Start with the first pedestal: each teammate in turn.", 2.0)
				return true
			var slot: String = SHRINE_SLOTS[i]
			var right: bool = (slot == "mon" and h.who == "mon") or (slot != "mon" and h == game.heroes[slot])
			if not right:
				game.sfx("bonk", -4)
				var want: String = game.display_name("mon" if slot == "mon" else game.heroes[slot].who)
				game.hud.say("SWAP TO %s!" % want, "This pedestal wants %s." % want, 2.0)
				return true
			shrine.lit += 1
			game.sfx("heart")
			game.fx.burst(p + Vector3(0, 0.6, 0), COLORS.shrine, 20, 5, 7)
			_shrine_faces()
			game.identity.add_meter(0.15)
			if shrine.lit >= 3:
				game.hud.say("TEAM POWER!", "All three together: a TEAM HEART shard!", 2.6)
				game.open_play._drop_star("team", "All three teammates lit the shrine!")
			else:
				game.hud.say("PEDESTAL %d LIT!" % shrine.lit, "Now SWAP to the next teammate!", 1.8)
			return true
	return false
