class_name HomeBase
extends Node3D
## The home base: BUILD mode (place / remove blocks) and DECOR mode (place / rotate /
## pick up decorations), the portal to 1-1, and saving it all in the save file.
## Things always go in the spot in front of whoever you're steering (the see-through
## preview shows where). Everything removed gives back exactly what it cost.

## Blocks you can build with and what each costs.
const BLOCKS := [
	{"t": Level.PLANKS, "cost": {"coins": 1}},
	{"t": Level.WOOL_RED, "cost": {"coins": 1}},
	{"t": Level.WOOL_BLUE, "cost": {"coins": 1}},
	{"t": Level.WOOL_YELLOW, "cost": {"coins": 1}},
	{"t": Level.WOOL_WHITE, "cost": {"coins": 1}},
	{"t": Level.WINDOW, "cost": {"coins": 2}},
	{"t": Level.BRICK, "cost": {"stone": 1}},
	{"t": Level.STONE_BRICK, "cost": {"stone": 1}},
	{"t": Level.STONE, "cost": {"stone": 1}},
	{"t": Level.IRON_BLOCK, "cost": {"iron": 1}},
	{"t": Level.GOLD_BLOCK, "cost": {"gold": 1}},
	{"t": Level.DIAMOND_BLOCK, "cost": {"diamond": 1}},
]
const GROUND_Y := 5          # blocks below this are the ground (never removed)
const MAX_UP := 4            # you can stack this many blocks above your feet

var game
var level: Level
var mode := ""               # "", "build", "decor"
var sel := {"build": 0, "decor": 0}
var decor := {}              # Vector3i -> {"id": String, "r": int, "node": Node3D}
var ghost: MeshInstance3D
var ghost_mat: StandardMaterial3D
var target := Vector3i(-1, -1, -1)     # where PLACE puts the next thing
var under := Vector3i(-1, -1, -1)      # what REMOVE takes (the block / decor in front)
var can_place := false
var why := ""                # why you can't place (shown on screen)
var portal: Node3D
var spin := 0.0


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	ghost_mat = StandardMaterial3D.new()
	ghost_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	ghost_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	ghost_mat.no_depth_test = false
	ghost = MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(1.02, 1.02, 1.02)
	ghost.mesh = bm
	ghost.material_override = ghost_mat
	ghost.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	ghost.visible = false
	add_child(ghost)
	_make_portal()


func _make_portal() -> void:
	portal = Node3D.new()
	portal.position = Vector3(Level.PORTAL_X, 5, 6.5)
	add_child(portal)
	var ring := MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 1.05
	tm.outer_radius = 1.35
	ring.mesh = tm
	ring.material_override = Toys.mat(Color("8a5bc4"), true, 0.4)
	ring.rotation.x = PI / 2
	ring.rotation.y = PI / 2
	ring.position.y = 1.4
	portal.add_child(ring)
	for k in 3:
		var disc := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 1.05 - k * 0.3
		cm.bottom_radius = cm.top_radius
		cm.height = 0.04
		disc.mesh = cm
		disc.material_override = Toys.mat([Color("b48cff"), Color("e0c8ff"), Color.WHITE][k], false, 1.2)
		disc.rotation.z = PI / 2
		disc.position = Vector3(k * 0.02, 1.4, 0)
		disc.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		portal.add_child(disc)
	var label := Label3D.new()
	label.text = "1-1  MEADOW MINE"
	label.font_size = 48
	label.outline_size = 12
	label.pixel_size = 0.008
	label.modulate = Color("ffd23a")
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.position.y = 3.2
	portal.add_child(label)


func _process(dt: float) -> void:
	spin += dt
	if portal:
		portal.get_child(0).rotation.z = spin * 0.6
		portal.scale = Vector3.ONE * (1.0 + sin(spin * 3) * 0.03)


## True when the hero walks into the portal.
func at_portal(p: Vector3) -> bool:
	return absf(p.x - Level.PORTAL_X) < 0.8 and absf(p.z - 6.5) < 1.3 and p.y < 7.5


# ------------------------------------------------------------------ where things go
## Works out the spot in front of the hero (called every frame in BUILD / DECOR mode).
func aim(h: Actor) -> void:
	var f: Vector3 = h.facing
	var dx := 0
	var dz := 0
	if absf(f.x) >= absf(f.z):
		dx = 1 if f.x >= 0 else -1
	else:
		dz = 1 if f.z >= 0 else -1
	var cx := floori(h.position.x) + dx
	var cz := clampi(floori(h.position.z) + dz, 3, Level.LZ - 1)
	var fy := maxi(floori(h.position.y + 0.05), GROUND_Y)
	# REMOVE takes the highest built thing in that column, PLACE goes on top of it
	under = Vector3i(-1, -1, -1)
	var top := GROUND_Y - 1
	for y in range(GROUND_Y, mini(fy + MAX_UP + 1, Level.LY - 1)):
		var c := Vector3i(cx, y, cz)
		if level.get_cell(cx, y, cz) != Level.AIR or decor.has(c):
			top = y
			under = c
	target = Vector3i(cx, top + 1, cz)
	can_place = true
	why = ""
	if cx < 0 or cx >= Level.HOME_W:
		can_place = false
		why = "Too far!"
	elif decor.has(under) and under.y == top:
		can_place = false
		why = "Something's in the way"
	elif target.y > fy + MAX_UP or target.y >= Level.LY - 1:
		can_place = false
		why = "Too high!"
	elif _hits_someone(target):
		can_place = false
		why = "Step back a little"
	elif absf(target.x + 0.5 - Level.PORTAL_X) < 2.0 and absf(target.z + 0.5 - 6.5) < 2.5:
		can_place = false
		why = "Keep the portal clear"
	if can_place and mode != "" and not _affordable():
		can_place = false
		why = "Not enough " + _missing()
	ghost.visible = mode != ""
	ghost.position = Vector3(target) + Vector3(0.5, 0.5, 0.5)
	ghost_mat.albedo_color = Color(0.4, 1, 0.5, 0.35) if can_place else Color(1, 0.3, 0.3, 0.3)


func _hits_someone(c: Vector3i) -> bool:
	for h in [game.leader(), game.follower()]:
		if h.visible and absf(h.position.x - (c.x + 0.5)) < h.hw + 0.5 and absf(h.position.z - (c.z + 0.5)) < h.hw + 0.5 \
				and h.position.y < c.y + 1 and h.position.y + h.height > c.y:
			return true
	return false


# ------------------------------------------------------------------ costs
func cost_of_sel() -> Dictionary:
	if mode == "build":
		return BLOCKS[sel.build].cost
	if mode == "decor":
		return {"coins": int(Decor.ITEMS[sel.decor].coins)}
	return {}


func _have(k: String) -> int:
	return int(game.coins) if k == "coins" else int(game.ores[k])


func _affordable() -> bool:
	if mode == "decor" and Decor.ITEMS[sel.decor].get("need_clear", false) and game.cleared <= 0:
		return false
	for k in cost_of_sel():
		if _have(k) < int(cost_of_sel()[k]):
			return false
	return true


func _missing() -> String:
	if mode == "decor" and Decor.ITEMS[sel.decor].get("need_clear", false) and game.cleared <= 0:
		return "trophies: beat Krag Jr.!"
	for k in cost_of_sel():
		if _have(k) < int(cost_of_sel()[k]):
			return k
	return ""


func _pay(cost: Dictionary, sign: int) -> void:
	for k in cost:
		if k == "coins":
			game.coins += sign * int(cost[k])
		else:
			game.ores[k] += sign * int(cost[k])


static func block_cost(t: int) -> Dictionary:
	for b in BLOCKS:
		if b.t == t:
			return b.cost
	return {}


# ------------------------------------------------------------------ PLACE / REMOVE / ROTATE
func place() -> bool:
	if not can_place:
		game.sfx("bonk", -4)
		if why != "":
			game.fx.text(Vector3(target) + Vector3(0.5, 1.4, 0.6), why, Color("ffb0a8"), 40, 1.0)
		return false
	_pay(cost_of_sel(), -1)
	if mode == "build":
		level.set_cell(target.x, target.y, target.z, BLOCKS[sel.build].t)
		game.fx.burst(Vector3(target) + Vector3(0.5, 0.5, 0.5), Level.COLORS[BLOCKS[sel.build].t], 6, 2.5)
		game.sfx("mine", -2)
	else:
		_put_decor(target, Decor.ITEMS[sel.decor].id, _auto_rot())
		game.fx.burst(Vector3(target) + Vector3(0.5, 0.4, 0.5), Color.WHITE, 8, 3, 4)
		game.sfx("item", -4)
	return true


func remove() -> bool:
	if under.y < GROUND_Y:
		game.sfx("bonk", -4)
		return false
	if decor.has(under):
		var d: Dictionary = decor[under]
		_pay({"coins": int(Decor.find(d.id).get("coins", 0))}, 1)
		d.node.queue_free()
		decor.erase(under)
	else:
		var t := level.get_cell(under.x, under.y, under.z)
		_pay(block_cost(t), 1)
		level.set_cell(under.x, under.y, under.z, Level.AIR)
		game.fx.burst(Vector3(under) + Vector3(0.5, 0.5, 0.5), Level.COLORS[t], 10, 3.5)
	game.sfx("break", -4)
	return true


func rotate_front() -> void:
	if decor.has(under):
		var d: Dictionary = decor[under]
		d.r = (int(d.r) + 1) % 4
		d.node.rotation.y = d.r * PI / 2
		game.sfx("swap", -8)


## New decorations face the hero (so you see the front of a bench or a bed).
func _auto_rot() -> int:
	var f: Vector3 = game.leader().facing
	if absf(f.x) >= absf(f.z):
		return 3 if f.x > 0 else 1
	return 2 if f.z > 0 else 0


func _put_decor(c: Vector3i, id: String, r: int) -> void:
	var n: Node3D
	if Look.toy:
		n = Decor.model(id)
	else:
		var s := Sprite3D.new()
		s.texture = load("res://art/decor_%s.png" % id)
		s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		s.shaded = false
		s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
		var h := 2.6 if id in ["tree", "lamp"] else (1.4 if id in ["fountain", "mailbox", "trophy"] else 1.0)
		s.pixel_size = h / s.texture.get_height()
		s.position.y = h * 0.5
		n = Node3D.new()
		n.add_child(s)
	n.position = Vector3(c) + Vector3(0.5, 0, 0.5)
	n.rotation.y = r * PI / 2 if Look.toy else 0.0
	add_child(n)
	decor[c] = {"id": id, "r": r, "node": n}


# ------------------------------------------------------------------ saving
func data() -> Dictionary:
	var blocks := []
	for z in Level.LZ:
		for y in range(GROUND_Y, Level.LY):
			for x in Level.HOME_W:
				var t := level.get_cell(x, y, z)
				if t != Level.AIR:
					blocks.append([x, y, z, t])
	var ds := []
	for c in decor:
		ds.append([decor[c].id, c.x, c.y, c.z, decor[c].r])
	return {"blocks": blocks, "decor": ds}


func load_data(d: Dictionary) -> void:
	for b in d.get("blocks", []):
		if b is Array and b.size() == 4 and int(b[1]) >= GROUND_Y:
			level.set_cell(int(b[0]), int(b[1]), int(b[2]), int(b[3]))
	for e in d.get("decor", []):
		if e is Array and e.size() == 5 and not Decor.find(String(e[0])).is_empty():
			_put_decor(Vector3i(int(e[1]), int(e[2]), int(e[3])), String(e[0]), int(e[4]))


## A new home gets a mailbox and some flowers by the sign.
func starter() -> void:
	_put_decor(Vector3i(6, 5, 3), "mailbox", 0)
	_put_decor(Vector3i(7, 5, 3), "flowers", 0)
	_put_decor(Vector3i(9, 5, 3), "bush", 0)
