class_name Extras
extends Node3D
## Mario 64-style extras in every open world (made-up look-alikes, original designs):
## * the 100 COIN STAR: 100 coins in one visit and a star pops out above you;
## * friendly MUSHROOM FOLK with hints, and one star quest per world from a friend:
##   ZOOMY THE QUICK races you up the mountain path, or MAMA CLUCK lost her 5 chicks;
## * green 1-UP mushrooms hidden around, and ! CAP BLOCKS (WING CAP, METAL CAP);
## * DASH THE DINO to ride (faster, flutter jump, ACTION = tongue gobbles bad guys);
## * butterflies, birds that fly off when you run near, fish jumping in the ponds.
## Made by OpenPlay; star objectives are added to `level.objectives` in `add_objectives`.

const COINS_STAR := 100
## The friend with a star quest in each world ("race" needs the spiral mountain path).
const QUEST := {"meadow": "race", "frost": "chicks", "dunes": "chicks", "swamp": "chicks", "moon": "race",
	"lava": "race", "candy": "chicks", "beach": "chicks", "jungle": "race", "cloud": "chicks"}
const DASH_WORLDS := ["meadow", "dunes", "candy", "beach", "jungle", "cloud"]
const CHICKS := 5
const RACER_SPEED := 5.0         # blocks a second up the path (Rocco runs 5.6, sprints faster)
const HINTS := {
	"all": ["Hop in the CANNON and aim for the floating island!", "King Bombo hates being picked up from behind!",
		"Collect 100 coins in one visit for a secret star!", "Green mushrooms give you all your hearts back!",
		"Hit a ! block from below for a cap!", "Jump into the GIANT PAINTINGS for more stars!"],
	"meadow": ["Chompy is chained up... mostly!"],
	"frost": ["The ice slide is super fast. Hold on!", "Mama Cluck's chicks love the snow."],
	"dunes": ["Quicksand makes you slow. Keep jumping!", "There's treasure inside the pyramid!"],
	"swamp": ["Ghosts hide when you look at them!"],
	"moon": ["Low gravity! Jump super high!"],
	"lava": ["Lava burns! Don't touch it!"],
	"candy": ["Everything here tastes like sugar!"],
	"beach": ["There's a pirate ship in the bay!"],
	"jungle": ["The giant mushrooms are bouncy... I think!"],
	"cloud": ["Don't look down!"],
}

var game
var level: Level
var t := 0.0
var started := false
var coins0 := 0                  # coins when this visit started (100 COIN STAR)
var coin_star_t := -1.0          # >= 0 while the 100-coin star floats down to you
var folk: Array = []             # [{node, bubble, text}]
var quest := ""                  # "race" or "chicks" ("" = none here)
# ZOOMY THE QUICK's race
var racer: Node3D
var racer_bubble: Label3D
var race := ""                   # "", "ready", "count", "run", "won", "lost"
var race_t := 0.0
var race_s := 0.0                # how far up the path ZOOMY is (0..1)
var race_len := 1.0
var near_t := 0.0
# MAMA CLUCK and her chicks
var mama: Node3D
var mama_bubble: Label3D
var chicks: Array = []           # [{node, state: "lost"/"follow"/"home", pos}]
var chicks_home := 0
# 1-UPs and ! blocks
var oneups: Array = []           # [node]
var caps: Array = []             # [{node, kind, used}]
# DASH THE DINO
var dash: Node3D
var dash_sprite: Sprite3D
var riding := false
var flutter := 0.0
var dash_away_t := 0.0
var dash_vel := Vector3.ZERO
var tongue: MeshInstance3D
var tongue_t := 0.0
var rider_raise := 0.0
# critters
var butterflies: Array = []      # [{node, anchor, ph}]
var birds: Array = []            # [{node, home, state, vel, t}]
var fish: Array = []             # [{node, at, t}]
var water: Array[Vector3] = []


# ------------------------------------------------------------------ setting up
## The new star missions (called before OpenPlay makes its floating names).
static func add_objectives(lv: Level) -> void:
	if lv.bonus or lv.is_home or lv.objectives.is_empty():
		return
	lv.objectives.append({"id": "coins100", "name": "100 COINS", "hint": "Collect 100 coins in one visit!",
		"pos": lv.start_pos + Vector3(0, 2.6, 0)})
	var q: String = QUEST.get(lv.world, "chicks")
	if q == "race" and lv.summit_spot != Vector3.ZERO and float(lv.course().get("turns", 0.0)) > 0.0:
		lv.objectives.append({"id": "npc", "name": "RACE ZOOMY", "hint": "Race ZOOMY THE QUICK to the top of the mountain!",
			"pos": lv.summit_spot + Vector3(-2.5, 0.9, 0)})
	else:
		var m := _mama_spot(lv)
		lv.objectives.append({"id": "npc", "name": "LOST CHICKS", "hint": "Find MAMA CLUCK's 5 lost chicks and bring them home!",
			"pos": m + Vector3(0, 2.4, 0)})


static func _mama_spot(lv: Level) -> Vector3:
	for off: Vector2i in [Vector2i(-7, 3), Vector2i(7, 3), Vector2i(-6, -5), Vector2i(6, -6), Vector2i(0, 6)]:
		var x: int = int(lv.start_pos.x) + off.x
		var z: int = int(lv.start_pos.z) + off.y
		var g := lv.ground_at(x, z)
		if g > 0 and lv.get_cell(x, g, z) == Level.AIR and lv.get_cell(x, g + 1, z) == Level.AIR and lv.get_cell(x, g + 2, z) == Level.AIR:
			return Vector3(x + 0.5, g, z + 0.5)
	return lv.start_pos + Vector3(-3, 0, 2)


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	for o in level.objectives:
		if o.id == "npc":
			quest = "race" if o.name == "RACE ZOOMY" else "chicks"
	_make_folk()
	if quest == "race":
		_make_racer()
	elif quest == "chicks":
		_make_mama()
	_make_oneups()
	_make_caps()
	if level.world in DASH_WORLDS:
		_make_dash()
	_make_critters()


func _ground_spot(x: float, z: float) -> Vector3:
	var g := level.ground_at(int(x), int(z))
	return Vector3(floorf(x) + 0.5, g, floorf(z) + 0.5)


func _free(p: Vector3) -> bool:
	var x := floori(p.x)
	var z := floori(p.z)
	var y := floori(p.y)
	return level.get_cell(x, y, z) == Level.AIR and level.get_cell(x, y + 1, z) == Level.AIR


static func bubble(text: String, y: float) -> Label3D:
	var b := Label3D.new()
	b.text = text
	b.font_size = 40
	b.outline_size = 14
	b.pixel_size = 0.011
	b.width = 460
	b.autowrap_mode = TextServer.AUTOWRAP_WORD
	b.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	b.no_depth_test = true
	b.modulate = Color("fffbe8")
	b.outline_modulate = Color(0.16, 0.1, 0.22)
	b.position.y = y
	b.visible = false
	return b


## A paper cutout (paper look) for the friends.
func _paper(tex: String, h: float) -> Sprite3D:
	var s := Sprite3D.new()
	s.texture = load("res://art/%s.png" % tex)
	s.pixel_size = h / s.texture.get_height()
	s.position.y = h * 0.5
	s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
	s.shaded = false
	s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
	s.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
	return s


# ------------------------------------------------------------------ MUSHROOM FOLK with hints
func _make_folk() -> void:
	var lines: Array = (HINTS.get(level.world, []) as Array) + (HINTS["all"] as Array)
	var spots: Array[Vector3] = []
	spots.append(_ground_spot(level.start_pos.x - 4, level.start_pos.z + 2))
	if level.summit_spot != Vector3.ZERO and float(level.course().get("turns", 0.0)) > 0.0:
		var foot := level.ramp_point(0.0)
		spots.append(_ground_spot(foot.x + 3.5, foot.z + 2.5))
	if level.gate.size() > 0:
		var gc: Vector3i = level.gate[level.gate.size() / 2]
		spots.append(_ground_spot(gc.x + 4.5, gc.z + 4.5))
	var k := 0
	for p in spots:
		if not _free(p):
			continue
		var n := Node3D.new()
		n.position = p
		add_child(n)
		if Look.toy:
			var m := Toys.build("spotty")
			m.scale = Vector3.ONE * 0.9
			m.rotation.y = randf() * TAU
			n.add_child(m)
		else:
			n.add_child(_paper("spotty_idle", 1.4))
		var text: String = lines[k % lines.size()]
		var b := bubble(text, 2.3)
		n.add_child(b)
		folk.append({"node": n, "bubble": b, "text": text})
		k += 1


# ------------------------------------------------------------------ ZOOMY THE QUICK
func _racer_model() -> Node3D:
	if not Look.toy:
		var n := Node3D.new()
		var s := _paper("zoomy_1", 1.5)
		s.name = "pic"
		n.add_child(s)
		return n
	var r := Node3D.new()
	var P := Toys
	for s in [-1, 1]:
		var leg := P.piv(r, Vector3(s * 0.16, 0.32, 0))
		leg.name = "legL" if s < 0 else "legR"
		P.part(leg, P.cyl(0.07, 0.08, 0.28), Vector3(0, -0.14, 0), Color("ffd96a"))
		P.part(leg, P.box(Vector3(0.16, 0.1, 0.28)), Vector3(0, -0.3, 0.05), Color("e8413b"))
	P.part(r, P.sph(0.36), Vector3(0, 0.62, -0.05), Color("3fae4a"), Vector3(1, 0.85, 1.1))   # shell
	P.part(r, P.sph(0.3), Vector3(0, 0.56, 0.06), Color("fff3dc"), Vector3(1, 0.8, 0.9))
	var head := P.piv(r, Vector3(0, 1.05, 0.22))
	P.part(head, P.sph(0.22), Vector3.ZERO, Color("ffd96a"))
	P.part(head, P.cyl(0.23, 0.23, 0.07), Vector3(0, 0.1, 0), Color("3f7fd9"))   # headband
	P.eyes(head, 0.03, 0.17, 0.08, 0.06)
	return r


func _make_racer() -> void:
	var pts: Array = []
	for i in 41:
		pts.append(level.ramp_point(i / 40.0))
	race_len = 0.0
	for i in 40:
		race_len += (pts[i + 1] as Vector3).distance_to(pts[i])
	race_len += 4.0
	racer = _racer_model()
	add_child(racer)
	racer_bubble = bubble("I'm ZOOMY THE QUICK! Race me to the top! Stand next to me to start!", 2.4)
	racer.add_child(racer_bubble)
	_racer_home()


func _racer_home() -> void:
	var foot := level.ramp_point(0.0)
	racer.position = Vector3(foot.x - 2.0, foot.y, foot.z + 1.0)
	racer.rotation.y = 0.0
	race = "ready"
	race_s = 0.0
	near_t = 0.0


func _race_step(dt: float, l: Hero) -> void:
	race_t += dt
	var d := Vector2(racer.position.x - l.position.x, racer.position.z - l.position.z).length()
	racer_bubble.visible = d < 5.0 and race in ["ready", "won", "lost"]
	match race:
		"ready":
			if game.open_play.got.has("npc"):
				racer_bubble.text = "You're the fastest! Want to race again? Just for fun!"
			if d < 2.6 and l.on_floor and game.state == "play":
				near_t += dt
				if near_t > 1.0:
					race = "count"
					race_t = 0.0
					game.hud.say("ZOOMY'S RACE!", "To the top of the mountain! Ready...", 1.4)
					game.sfx("alert")
			else:
				near_t = 0.0
		"count":
			var n := 3 - int(race_t)
			if race_t >= 3.0:
				race = "run"
				race_t = 0.0
				game.hud.say("GO!", "Race to the top of the mountain!", 1.0)
				game.sfx("jump")
			elif int((race_t - dt) * 1.0) != int(race_t) or race_t < dt * 1.5:
				game.fx.text(racer.position + Vector3(0, 2.2, 0), str(n), Color("ffe14a"), 80, 0.8)
		"run":
			race_s = minf(1.0, race_s + dt * RACER_SPEED / race_len)
			var p := level.ramp_point(race_s) if race_s < 1.0 else level.summit_spot
			var dir := Vector3(p.x - racer.position.x, 0, p.z - racer.position.z)
			if dir.length() > 0.01:
				racer.rotation.y = atan2(dir.x, dir.z)
			racer.position = racer.position.lerp(p, 1 - exp(-dt * 10))
			_wiggle(racer, 14.0)
			var top := level.summit_spot
			if Vector2(l.position.x - top.x, l.position.z - top.z).length() < 4.5 and l.position.y > top.y - 0.6:
				race = "won"
				race_t = 0.0
				racer_bubble.text = "WOW! You beat me! Here's your star!"
				racer_bubble.visible = true
				if not game.open_play.got.has("npc") and not game.open_play.stars.has("npc"):
					game.open_play._drop_star("npc", "You beat ZOOMY THE QUICK!")
				else:
					game.hud.say("YOU WIN!", "Fastest in the world!", 2.0)
			elif race_s >= 1.0 and racer.position.distance_to(top) < 1.0:
				race = "lost"
				race_t = 0.0
				racer_bubble.text = "Hee hee! I win! Try again: I'll be at the bottom!"
				racer_bubble.visible = true
				game.hud.say("ZOOMY WINS!", "Run faster! (Push the stick all the way to sprint)", 2.4)
		"won", "lost":
			if race_t > 4.0:
				_racer_home()


func _wiggle(n: Node3D, spd: float) -> void:
	if n.has_node("legL"):
		(n.get_node("legL") as Node3D).rotation.x = sin(t * spd) * 0.8
		(n.get_node("legR") as Node3D).rotation.x = -sin(t * spd) * 0.8
	elif n.has_node("pic"):
		var s: Sprite3D = n.get_node("pic")
		s.texture = load("res://art/zoomy_%d.png" % (1 + int(t * spd * 0.3) % 2))


# ------------------------------------------------------------------ MAMA CLUCK's chicks
func _chick_model() -> Node3D:
	var n := Node3D.new()
	if not Look.toy:
		n.add_child(_paper("chick", 0.55))
		return n
	var P := Toys
	P.part(n, P.sph(0.2), Vector3(0, 0.22, 0), Color("ffe14a"))
	P.part(n, P.sph(0.07), Vector3(0, 0.24, 0.2), Color("ff9a3c"), Vector3(1, 0.7, 1.4))
	for s in [-1, 1]:
		P.part(n, P.sph(0.035), Vector3(s * 0.08, 0.3, 0.16), Color("1e2a44"), Vector3.ONE, Vector3.ZERO, false)
	return n


func _make_mama() -> void:
	var p := _mama_spot(level)
	mama = Node3D.new()
	mama.position = p
	add_child(mama)
	if Look.toy:
		var P := Toys
		var b := P.piv(mama, Vector3.ZERO)
		P.part(b, P.sph(0.5), Vector3(0, 0.7, 0), Color("ff9a4a"), Vector3(1, 1, 1.1))
		P.part(b, P.sph(0.32), Vector3(0, 0.62, 0.3), Color("fff0d8"), Vector3(1, 1, 0.6))
		P.part(b, P.sph(0.3), Vector3(0, 1.35, 0.1), Color("ff9a4a"))
		P.part(b, P.hemi(0.32), Vector3(0, 1.5, 0.05), Color("ff8fc0"), Vector3(1, 0.8, 1))   # bonnet
		P.part(b, P.sph(0.09), Vector3(0, 1.32, 0.38), Color("ffd23a"), Vector3(1, 0.7, 1.6))
		P.eyes(b, 1.42, 0.3, 0.12, 0.07)
		for s in [-1, 1]:
			P.part(b, P.cyl(0.03, 0.03, 0.3), Vector3(s * 0.15, 0.15, 0), Color("ffb020"))
	else:
		mama.add_child(_paper("mama", 1.7))
	mama_bubble = bubble("Oh no! My 5 chicks wandered off! Please find them and bring them back to me!", 2.6)
	mama.add_child(mama_bubble)
	# the chicks: far away, each in a different direction
	var tries := 0
	while chicks.size() < CHICKS and tries < 200:
		tries += 1
		var s: Vector2i = level._free_spot(3)
		if s.x < 0:
			continue
		var cp := Vector3(s.x + 0.5, level.ground_at(s.x, s.y), s.y + 0.5)
		if Vector2(cp.x - p.x, cp.z - p.z).length() < 18 or not _free(cp):
			continue
		var far := true
		for c in chicks:
			if (c.pos as Vector3).distance_to(cp) < 14:
				far = false
		if not far and tries < 150:
			continue
		var n := _chick_model()
		n.position = cp
		add_child(n)
		var lb := bubble("Peep! (lost!)", 0.9)
		lb.font_size = 28
		n.add_child(lb)
		chicks.append({"node": n, "state": "lost", "pos": cp, "help": lb})


func lost_chicks() -> Array:
	var out := []
	for c in chicks:
		if c.state == "lost":
			out.append(c.node)
	return out


func following() -> int:
	var k := 0
	for c in chicks:
		if c.state == "follow":
			k += 1
	return k


func _chicks_step(dt: float, l: Hero) -> void:
	var dm := Vector2(mama.position.x - l.position.x, mama.position.z - l.position.z).length()
	mama_bubble.visible = dm < 5.5
	if game.open_play.got.has("npc") or game.open_play.stars.has("npc"):
		mama_bubble.text = "Thank you, thank you! My babies are home!"
	elif chicks_home > 0 or following() > 0:
		mama_bubble.text = "%d of my %d chicks are home! Keep looking, please!" % [chicks_home, CHICKS]
	var lead: Vector3 = l.position - l.facing * 1.0
	var k := 0
	for c in chicks:
		var n: Node3D = c.node
		match c.state:
			"lost":
				n.position.y = c.pos.y + absf(sin(t * 5.0 + k)) * 0.25
				(c.help as Label3D).visible = l.position.distance_to(n.position) < 6
				if l.position.distance_to(n.position) < 1.6:
					c.state = "follow"
					(c.help as Label3D).visible = false
					game.sfx("coin", -4)
					game.fx.text(n.position + Vector3(0, 1.0, 0), "PEEP!", Color("ffe14a"), 48, 0.8)
					game.hud.say("FOUND A CHICK!", "Take it back to MAMA CLUCK (%d following)" % following(), 1.8)
			"follow":
				var want := lead - l.facing * (0.7 * k)
				var g := level.ground_below(want.x, want.z, l.position.y + 2.0)
				if g > -50:
					want.y = g
				n.position = n.position.lerp(want, 1 - exp(-dt * 6.0))
				n.position.y += absf(sin(t * 10.0 + k)) * 0.1
				var dir := Vector3(want.x - n.position.x, 0, want.z - n.position.z)
				if dir.length() > 0.02:
					n.rotation.y = atan2(dir.x, dir.z)
				if dm < 3.2:
					c.state = "home"
					chicks_home += 1
					game.sfx("heart", -4)
					game.fx.burst(n.position + Vector3(0, 0.4, 0), Color("ffe14a"), 8, 3, 6)
				k += 1
			"home":
				var a := TAU * chicks.find(c) / CHICKS + t * 0.4
				var hp: Vector3 = mama.position + Vector3(cos(a) * 1.3, 0, sin(a) * 1.3)
				n.position = n.position.lerp(hp, 1 - exp(-dt * 4.0))
				n.position.y = mama.position.y + absf(sin(t * 6.0 + a)) * 0.15
	if chicks_home >= chicks.size() and chicks.size() > 0 and not game.open_play.got.has("npc") and not game.open_play.stars.has("npc"):
		game.open_play._drop_star("npc", "All %d chicks are home with MAMA CLUCK!" % chicks.size())


# ------------------------------------------------------------------ 1-UPs and ! blocks
func _make_oneups() -> void:
	var spots: Array[Vector3] = []
	if level.summit_spot != Vector3.ZERO:
		spots.append(level.summit_spot + Vector3(3.2, 0.05, 3.2))   # a corner of the summit
	for i in 30:
		if spots.size() >= 3:
			break
		var s: Vector2i = level._free_spot(4)
		if s.x < 0:
			continue
		var p := Vector3(s.x + 0.5, level.ground_at(s.x, s.y) + 0.05, s.y + 0.5)
		if p.distance_to(level.start_pos) > 26 and _free(p):
			spots.append(p)
	for p in spots:
		var n := Powers.item_model("life")
		n.position = p
		add_child(n)
		oneups.append(n)


func _oneup_step(l: Hero) -> void:
	for i in range(oneups.size() - 1, -1, -1):
		var n: Node3D = oneups[i]
		n.rotation.y = t * 2.0
		if n.position.distance_to(l.position) < 1.1:
			game.hearts = game.MAX_HEARTS
			game.sfx("heart")
			game.fx.burst(n.position + Vector3(0, 0.5, 0), Color("3fc06a"), 14, 4, 6)
			game.fx.text(n.position + Vector3(0, 1.4, 0), "1-UP!", Color("7dff8a"), 72, 1.2)
			game.hud.say("1-UP!", "All your hearts are back!", 1.6)
			n.queue_free()
			oneups.remove_at(i)


func _cap_block(kind: String) -> Node3D:
	var n := Node3D.new()
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3.ONE * 0.96
	mi.mesh = bm
	mi.material_override = Toys.mat(Color("e8413b") if kind == "wing" else Color("8a96a2"), true, 0.25)
	mi.position.y = 0.5
	mi.name = "box"
	n.add_child(mi)
	var lb := Label3D.new()
	lb.text = "!"
	lb.font_size = 120
	lb.outline_size = 16
	lb.pixel_size = 0.006
	lb.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lb.position.y = 0.5
	lb.name = "mark"
	n.add_child(lb)
	return n


func _make_caps() -> void:
	var k := 0
	for off in [Vector3(-2, 0, -7), Vector3(7, 0, -3)]:
		var g := _ground_spot(level.start_pos.x + off.x, level.start_pos.z + off.z)
		var p := g + Vector3(0, 3.0, 0)
		if not (_free(g) and _free(g + Vector3(0, 2, 0)) and _free(p)):
			continue
		var kind := "wing" if k == 0 else "metal"
		var n := _cap_block(kind)
		n.position = p - Vector3(0, 0, 0)
		add_child(n)
		caps.append({"node": n, "kind": kind, "used": false})
		k += 1


func hit_cap(c: Dictionary) -> void:
	if c.used:
		return
	c.used = true
	var n: Node3D = c.node
	(n.get_node("box") as MeshInstance3D).material_override = Toys.mat(Color("967860"))
	(n.get_node("mark") as Label3D).visible = false
	var tw := n.create_tween()
	tw.tween_property(n, "position:y", n.position.y + 0.3, 0.08)
	tw.tween_property(n, "position:y", n.position.y, 0.12)
	game.sfx("bonk")
	if game.powers:
		game.powers.spawn_item(c.kind, n.position + Vector3(0, 1.1, 0), true)


func _caps_step(l: Hero) -> void:
	for c in caps:
		var n: Node3D = c.node
		var dx := absf(l.position.x - n.position.x)
		var dz := absf(l.position.z - n.position.z)
		if dx < 0.5 + l.hw and dz < 0.5 + l.hw:
			var head := l.position.y + l.height
			if l.vel.y > 0 and head > n.position.y - 0.1 and l.position.y < n.position.y:
				l.position.y = n.position.y - l.height - 0.01   # bonk: the block is solid from below
				l.vel.y = -1.0
				hit_cap(c)
			elif l.position.y >= n.position.y + 0.6 and l.position.y < n.position.y + 1.2 and l.vel.y <= 0:
				l.position.y = n.position.y + 1.0   # stand on it
				l.vel.y = 0
				l.on_floor = true


# ------------------------------------------------------------------ DASH THE DINO
func _dash_model() -> Node3D:
	var n := Node3D.new()
	if not Look.toy:
		dash_sprite = _paper("dash_1", 1.8)
		n.add_child(dash_sprite)
		return n
	var P := Toys
	var G := Color("4cc85a")
	var b := P.piv(n, Vector3.ZERO)
	b.name = "body"
	for s in [-1, 1]:
		var leg := P.piv(b, Vector3(s * 0.2, 0.4, 0.05))
		leg.name = "legL" if s < 0 else "legR"
		P.part(leg, P.cyl(0.1, 0.11, 0.34), Vector3(0, -0.15, 0), G)
		P.part(leg, P.sph(0.14), Vector3(0, -0.33, 0.08), Color("ff9a3c"), Vector3(1, 0.6, 1.4))
	P.part(b, P.sph(0.42), Vector3(0, 0.72, 0), G, Vector3(0.95, 0.85, 1.25))
	P.part(b, P.sph(0.3), Vector3(0, 0.66, 0.2), Color.WHITE, Vector3(0.9, 0.8, 0.8))
	P.part(b, P.sph(0.25), Vector3(0, 0.98, -0.05), Color("e8413b"), Vector3(1.1, 0.45, 1.3))   # saddle
	P.part(b, P.cyl(0.08, 0.14, 0.5), Vector3(0, 0.6, -0.55), G, Vector3.ONE, Vector3(-1.2, 0, 0))   # tail
	var head := P.piv(b, Vector3(0, 1.25, 0.45))
	head.name = "head"
	P.part(head, P.sph(0.3), Vector3.ZERO, G, Vector3(0.95, 0.9, 1.05))
	P.part(head, P.sph(0.2), Vector3(0, -0.02, 0.3), Color("5ad86a"), Vector3(1.1, 0.95, 1))   # the big nose
	for i in 3:
		P.part(head, P.sph(0.07), Vector3(0, 0.26 - i * 0.12, -0.24 - i * 0.05), Color("e8413b"))
	P.eyes(head, 0.14, 0.2, 0.12, 0.08)
	return n


func _make_dash() -> void:
	var p := _ground_spot(level.start_pos.x + 4, level.start_pos.z + 4)
	if not _free(p) or not _free(p + Vector3(0, 1, 0)):
		return
	dash = _dash_model()
	dash.position = p
	dash.rotation.y = PI
	add_child(dash)
	var lb := bubble("DASH THE DINO: jump on to ride!", 2.3)
	lb.name = "help"
	dash.add_child(lb)


func can_ride(h: Hero) -> bool:
	return dash != null and h.who != "mon"


func mount(h: Hero) -> void:
	riding = true
	flutter = 0.0
	dash_away_t = 0.0
	(dash.get_node("help") as Label3D).visible = false
	rider_raise = 0.8
	if h.model:
		h.model.position.y += rider_raise
	else:
		h.sprite_base_y += rider_raise
	game.sfx("item")
	game.hud.say("DASH THE DINO!", "Faster! Hold JUMP to flutter. ACTION = tongue! SWAP to get off.", 2.4)
	game.speak("Yahoo! Let's go, Dash!", h.who)


func dismount(h: Hero, run_away := false) -> void:
	if not riding:
		return
	riding = false
	if is_instance_valid(h):
		if h.model:
			h.model.position.y -= rider_raise
		else:
			h.sprite_base_y -= rider_raise
		if not run_away:
			h.vel.y = 7.0
	rider_raise = 0.0
	if run_away:
		dash_away_t = 2.5
		var a := randf() * TAU
		dash_vel = Vector3(cos(a), 0, sin(a)) * 6.0
	dash.position.y = level.ground_below(dash.position.x, dash.position.z, dash.position.y + 1.0)


func ride_speed() -> float:
	return 1.45 if riding else 1.0


func ride_jump() -> float:
	return 1.1 if riding else 1.0


## ACTION while riding: Dash's tongue gobbles the bad guy in front (bosses are too big!).
func on_action(h: Hero) -> bool:
	if not riding:
		return false
	var best: Foe = null
	var bd := 3.4
	for f in game.foes:
		if not is_instance_valid(f) or f.dying >= 0 or f.is_boss or f.kind in ["kingbombo", "biggrumbler"]:
			continue
		var d: Vector3 = f.position - h.position
		var flat := Vector2(d.x, d.z)
		if flat.length() < bd and absf(d.y) < 1.8 and (flat.length() < 0.8 or flat.normalized().dot(Vector2(h.facing.x, h.facing.z).normalized()) > 0.3):
			bd = flat.length()
			best = f
	_show_tongue(h, best.position + Vector3(0, best.height * 0.5, 0) if best else h.position + h.facing * 2.5 + Vector3(0, 1.2, 0))
	game.sfx("swap", -2)
	if best:
		best.hurt(99, game)
		game.fx.text(best.position + Vector3(0, 1.4, 0), "GULP!", Color("7dff8a"), 60, 0.9)
		game.coins += 1
	return true


func _show_tongue(h: Hero, to: Vector3) -> void:
	if tongue == null:
		tongue = MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = 0.07
		cm.bottom_radius = 0.07
		cm.height = 1.0
		tongue.mesh = cm
		tongue.material_override = Toys.mat(Color("ff6f8f"), false)
		add_child(tongue)
	var from := h.position + Vector3(0, 1.3, 0) + h.facing * 0.5
	var d := to - from
	tongue.position = from + d * 0.5
	tongue.scale = Vector3(1, d.length(), 1)
	tongue.look_at_from_position(tongue.position, to, Vector3.UP if absf(d.normalized().y) < 0.95 else Vector3.RIGHT)
	tongue.rotate_object_local(Vector3.RIGHT, PI / 2)
	tongue.visible = true
	tongue_t = 0.22


func on_swap() -> bool:
	if riding:
		dismount(game.leader())
		return true
	return false


func on_hurt() -> bool:
	if not riding:
		return false
	var h: Hero = game.leader()
	dismount(h, true)
	game.hurt_t = 1.2
	h.flash_t = 1.2
	h.vel = Vector3(-h.facing.x * 4, 6, -h.facing.z * 4)
	game.sfx("hurt")
	game.hud.say("OOPS!", "Dash ran off! Catch him and jump back on.", 1.8)
	return true


func _dash_step(dt: float, l: Hero) -> void:
	if riding:
		if l.who == "mon" or not l.visible:   # someone else is out now
			dismount(l)
			return
		dash.position = l.position
		dash.rotation.y = lerp_angle(dash.rotation.y, atan2(l.facing.x, l.facing.z), 1 - exp(-dt * 12))
		if l.on_floor:
			flutter = 0.0
		elif game.jump_held and l.vel.y < 0 and flutter < 0.9:
			flutter += dt
			l.vel.y = lerpf(l.vel.y, 2.2, 0.5)   # the flutter jump
		_dash_legs(Vector2(l.vel.x, l.vel.z).length())
		return
	if dash_away_t > 0:
		dash_away_t -= dt
		var np := dash.position + dash_vel * dt
		var g := level.ground_below(np.x, np.z, dash.position.y + 1.5)
		if g > -50 and g <= dash.position.y + 1.1 and not level.solid(floori(np.x), floori(g + 0.5), floori(np.z)):
			dash.position = Vector3(np.x, g, np.z)
			dash.rotation.y = atan2(dash_vel.x, dash_vel.z)
		_dash_legs(6.0)
	else:
		dash.position.y = level.ground_below(dash.position.x, dash.position.z, dash.position.y + 1.0) + absf(sin(t * 2.0)) * 0.04
		_dash_legs(0.0)
	var d := Vector2(dash.position.x - l.position.x, dash.position.z - l.position.z).length()
	(dash.get_node("help") as Label3D).visible = d < 5 and dash_away_t <= 0
	if can_ride(l) and d < 1.1 and l.vel.y < 0 and l.position.y > dash.position.y + 0.35 and l.position.y < dash.position.y + 2.6 and game.hurt_t <= 0.3:
		mount(l)


func _dash_legs(speed: float) -> void:
	if dash_sprite:
		dash_sprite.texture = load("res://art/dash_%d.png" % (1 + int(t * 8) % 2 if speed > 0.5 else 1))
		var fx: float = (game.leader().facing if riding else Vector3(sin(dash.rotation.y), 0, cos(dash.rotation.y))).dot(Actor.view_right)
		dash_sprite.flip_h = fx < 0
		return
	var b: Node3D = dash.get_node("body")
	var sw := sin(t * 14.0) * clampf(speed / 6.0, 0, 1)
	(b.get_node("legL") as Node3D).rotation.x = sw * 0.8
	(b.get_node("legR") as Node3D).rotation.x = -sw * 0.8
	b.position.y = absf(sw) * 0.06


# ------------------------------------------------------------------ butterflies, birds, fish
func _make_critters() -> void:
	var wings := ["meadow", "jungle", "candy", "beach", "dunes", "frost", "cloud"]
	if level.world in wings:
		var cols := [Color("ff8fc0"), Color("7fb8ff"), Color("ffe14a"), Color("ffffff"), Color("ff9a3c"), Color("b78aff")]
		for i in 10:
			var s: Vector2i = level._free_spot(2)
			if s.x < 0:
				continue
			var a := Vector3(s.x + 0.5, level.ground_at(s.x, s.y) + 1.2, s.y + 0.5)
			var n := _butterfly(cols[i % cols.size()])
			n.position = a
			add_child(n)
			butterflies.append({"node": n, "anchor": a, "ph": randf() * TAU})
	if not level.world in ["moon", "lava"]:
		for i in 6:
			var s: Vector2i = level._free_spot(3)
			if s.x < 0:
				continue
			var hpos := Vector3(s.x + 0.5, level.ground_at(s.x, s.y), s.y + 0.5)
			var n := _bird()
			n.position = hpos
			add_child(n)
			birds.append({"node": n, "home": hpos, "state": "peck", "vel": Vector3.ZERO, "t": randf() * 3.0})
	# fish: in the top layer of water (ponds, the bay)
	for tries in 400:
		if water.size() >= 5:
			break
		var x := randi_range(2, level.sx - 3)
		var z := randi_range(2, level.sz - 3)
		var g := level.ground_at(x, z)
		if g > 0 and level.get_cell(x, g - 1, z) == Level.WATER:
			water.append(Vector3(x + 0.5, g, z + 0.5))
	for w in water:
		var n := Node3D.new()
		var P := Toys
		P.part(n, P.sph(0.18), Vector3.ZERO, Color("ff9a3c"), Vector3(0.6, 0.8, 1.4))
		P.part(n, P.box(Vector3(0.04, 0.24, 0.16)), Vector3(0, 0, -0.26), Color("ff7a2a"), Vector3.ONE, Vector3.ZERO, false)
		n.visible = false
		add_child(n)
		fish.append({"node": n, "at": w, "t": randf_range(1.0, 6.0)})


func _butterfly(c: Color) -> Node3D:
	var n := Node3D.new()
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	m.albedo_color = c
	for s in [-1, 1]:
		var w := MeshInstance3D.new()
		var q := QuadMesh.new()
		q.size = Vector2(0.22, 0.2)
		w.mesh = q
		w.material_override = m
		w.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		var piv := Node3D.new()
		piv.name = "L" if s < 0 else "R"
		piv.add_child(w)
		w.position = Vector3(s * 0.11, 0, 0)
		w.rotation.x = -PI / 2
		n.add_child(piv)
	return n


func _bird() -> Node3D:
	var n := Node3D.new()
	var P := Toys
	var c := [Color("5a7fd8"), Color("e8413b"), Color("8a5a35")].pick_random() as Color
	P.part(n, P.sph(0.14), Vector3(0, 0.16, 0), c, Vector3(0.9, 0.85, 1.2), Vector3.ZERO, false)
	P.part(n, P.sph(0.1), Vector3(0, 0.3, 0.1), c, Vector3.ONE, Vector3.ZERO, false)
	P.part(n, P.sph(0.04), Vector3(0, 0.29, 0.21), Color("ffb020"), Vector3(1, 0.7, 1.6), Vector3.ZERO, false)
	for s in [-1, 1]:
		var w := P.piv(n, Vector3(s * 0.1, 0.2, 0))
		w.name = "wL" if s < 0 else "wR"
		P.part(w, P.box(Vector3(0.22, 0.03, 0.14)), Vector3(s * 0.1, 0, 0), c.darkened(0.2), Vector3.ONE, Vector3.ZERO, false)
	return n


func _critters_step(dt: float, l: Hero) -> void:
	for b in butterflies:
		var n: Node3D = b.node
		var a: Vector3 = b.anchor
		var ph: float = b.ph + t * 0.6
		n.position = a + Vector3(sin(ph) * 2.2, sin(ph * 2.3) * 0.4 + 0.3, cos(ph * 0.8) * 2.2)
		n.rotation.y = -ph
		var flap := sin(t * 18.0 + b.ph) * 1.0
		(n.get_node("L") as Node3D).rotation.z = -flap
		(n.get_node("R") as Node3D).rotation.z = flap
	for b in birds:
		var n: Node3D = b.node
		b.t += dt
		match b.state:
			"peck":
				n.rotation.x = absf(sin(b.t * 4.0)) * 0.4 if int(b.t) % 3 == 0 else 0.0
				if n.position.distance_to(l.position) < 4.5:
					b.state = "fly"
					b.t = 0.0
					var away := Vector3(n.position.x - l.position.x, 0, n.position.z - l.position.z).normalized()
					b.vel = away * 5.0 + Vector3(0, 5.0, 0)
					n.rotation = Vector3(0, atan2(away.x, away.z), 0)
					game.sfx("swap", -14)
			"fly":
				n.position += b.vel * dt
				var flap := sin(b.t * 30.0)
				(n.get_node("wL") as Node3D).rotation.z = flap
				(n.get_node("wR") as Node3D).rotation.z = -flap
				if b.t > 3.0:
					n.visible = false
					b.state = "gone"
					b.t = 0.0
			"gone":
				if b.t > 14.0 and (b.home as Vector3).distance_to(l.position) > 12:
					n.position = b.home
					n.rotation = Vector3.ZERO
					n.visible = true
					b.state = "peck"
	for f in fish:
		var n: Node3D = f.node
		f.t -= dt
		if f.t <= 0 and not n.visible:
			n.visible = true
			f.t = 1.0
		if n.visible:
			var k: float = 1.0 - f.t   # 0..1 through the jump
			if k >= 1.0:
				n.visible = false
				f.t = randf_range(3.0, 8.0)
				game.fx.burst(f.at + Vector3(0.8, 0, 0), Color("bfe8ff"), 4, 2, 10, 0.6)
			else:
				n.position = f.at + Vector3(-0.8 + k * 1.6, sin(k * PI) * 1.4 - 0.2, 0)
				n.rotation = Vector3(-cos(k * PI) * 1.2, PI / 2, 0)


# ------------------------------------------------------------------ the 100 COIN STAR
func visit_coins() -> int:
	return maxi(0, game.coins - coins0) if started else 0


func _coin_star_step(dt: float, l: Hero) -> void:
	var op = game.open_play
	if not op.got.has("coins100") and not op.stars.has("coins100") and visit_coins() >= COINS_STAR:
		var o: Dictionary = op.objective("coins100")
		if o.is_empty():
			return
		var above := l.position + Vector3(0, 3.0, 0)
		if level.solid(floori(above.x), floori(above.y), floori(above.z)) or level.solid(floori(above.x), floori(above.y - 1), floori(above.z)):
			above = l.position + Vector3(0, 1.0, 0)
		o.pos = above
		op._drop_star("coins100", "100 coins! Grab the star!")
		coin_star_t = 0.0
	if coin_star_t >= 0 and op.stars.has("coins100"):   # it floats down to you, like Mario 64
		coin_star_t += dt
		var o2: Dictionary = op.objective("coins100")
		var floor_y := level.ground_below(o2.pos.x, o2.pos.z, o2.pos.y) + 1.0
		o2.pos.y = maxf(floor_y, o2.pos.y - dt * 1.2)
		if coin_star_t > 4.0:
			coin_star_t = -1.0


# ------------------------------------------------------------------ every frame
func step(dt: float) -> void:
	t += dt
	if not started:
		started = true
		coins0 = game.coins
	var l: Hero = game.leader()
	for f in folk:
		var n: Node3D = f.node
		var b: Label3D = f.bubble
		b.visible = n.position.distance_to(l.position) < 4.5
		n.position.y = n.position.y   # (they just stand there and chat)
	if racer:
		_race_step(dt, l)
	if mama:
		_chicks_step(dt, l)
	_oneup_step(l)
	_caps_step(l)
	if dash:
		_dash_step(dt, l)
	if tongue and tongue.visible:
		tongue_t -= dt
		if tongue_t <= 0:
			tongue.visible = false
	_critters_step(dt, l)
	_coin_star_step(dt, l)


## Where the quest's beam and arrow point (OpenPlay.target_pos asks).
func target(id: String) -> Vector3:
	match id:
		"coins100":
			return game.open_play._nearest(game.coin_nodes, level.start_pos + Vector3(0, 1, 0))
		"npc":
			if racer:
				return racer.position + Vector3(0, 1.4, 0) if race != "run" else level.summit_spot + Vector3(0, 1, 0)
			if mama:
				if following() > 0 or lost_chicks().is_empty():
					return mama.position + Vector3(0, 1.4, 0)
				return game.open_play._nearest(lost_chicks(), mama.position)
	return Vector3.ZERO


## The goal and progress text for the top of the screen.
func progress(id: String) -> String:
	match id:
		"coins100":
			return "Collect 100 coins in this visit!   %d / %d" % [mini(visit_coins(), COINS_STAR), COINS_STAR]
		"npc":
			if racer:
				match race:
					"run":
						return "RACE! Get to the top of the mountain before ZOOMY!"
					"count":
						return "Get ready..."
				return "Stand next to ZOOMY THE QUICK at the bottom of the mountain path to race!"
			if mama:
				return "Find MAMA CLUCK's chicks!   Home %d / %d   Following you: %d" % [chicks_home, chicks.size(), following()]
	return ""
