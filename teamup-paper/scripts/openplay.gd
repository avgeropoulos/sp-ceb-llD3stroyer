class_name OpenPlay
extends Node3D
## The open worlds, Mario 64 style: a STAR SELECT screen when you land, one CURRENT
## QUEST at a time (a light beam on it, an arrow at the screen edge, the goal and
## progress at the top), a "YOU GOT A STAR!" moment for every star, big floating names
## over each challenge, the gold gate that opens with 5 stars, and the flag that
## appears when Krag Jr. is beaten. Any star counts, in any order.

const STARS_NEEDED := 5
const RED_COINS := 8

static var select_enabled := true     # tests switch the star select + celebration off

var current := ""            # the quest you picked (its beam and arrow show the way)
var select_from := "start"   # "start" (just landed) or "pause"
var sel := 0                 # highlighted card on the star select screen
var picked := -1             # first tap picks a card, the second tap (or GO) goes
var reds: Array = []         # the red coins still out
var red_count := 0
var dennis: Node3D           # LOST DOG
var dog_following := false
var beam: Node3D
var beam_mats: Array = []
var cel_t := -1.0            # >= 0 while "YOU GOT A STAR!" plays
var cel_id := ""
var cel_star: Node3D
var cel_cam_from := Vector3.ZERO
var gate_msg := false
var fr_was_visible := true
var sparkle_t := 0.0
var game
var level: Level
var got := {}                # star id -> true
var stars := {}              # star id -> the spinning star (only while it's out)
var beacons := {}            # star id -> floating name
var flag: Node3D
var t := 0.0


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	for o in level.objectives:
		var b := Label3D.new()
		b.text = "★ " + o.name
		b.font_size = 64
		b.outline_size = 16
		b.fixed_size = true
		b.pixel_size = 0.0009
		b.no_depth_test = true
		b.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		b.modulate = Color("ffe14a")
		b.outline_modulate = Color(0.25, 0.12, 0.02)
		b.position = o.pos + Vector3(0, 3.2, 0)
		add_child(b)
		beacons[o.id] = b
		if o.id in ["tower", "cave", "sky"]:
			_make_star(o.id)
	for p in level.red_spots:
		var c := level.prop("coin", p - Vector3(0, 0.35, 0), 0.8)
		_redden(c)
		reds.append(c)
	if level.dog_home != Vector3.ZERO:
		dennis = level.prop("dennis", level.dog_spot, 1.0)
		var lbl := Label3D.new()   # "lost!" over him so you know it's him
		lbl.text = "WOOF? (lost!)"
		lbl.font_size = 40
		lbl.outline_size = 10
		lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		lbl.pixel_size = 0.008
		lbl.modulate = Color("ffe08a")
		lbl.position = Vector3(0, 1.6, 0) - Vector3(0, dennis.position.y - level.dog_spot.y, 0)
		lbl.name = "help"
		dennis.add_child(lbl)
	_make_beam()
	pick(first_open(), false)


## Called after loading a save: stars already found stay found.
func restore(ids: Array) -> void:
	for id in ids:
		got[String(id)] = true
		if stars.has(id):
			stars[id].queue_free()
			stars.erase(id)
		if beacons.has(id):
			beacons[id].visible = false
	if got.has("reds"):
		for c in reds:
			c.queue_free()
		reds.clear()
	if got.has("dog") and dennis:
		dennis.position = level.dog_home + Vector3(0, 0, -0.6)
		if dennis.has_node("help"):
			dennis.get_node("help").queue_free()
	if got.size() >= STARS_NEEDED:
		game.gate_open = true
		if level.gate.size() > 0 and level.get_cell(level.gate[0].x, level.gate[0].y, level.gate[0].z) != Level.AIR:
			for c in level.gate:   # stars from an earlier visit: the gate is already open
				level.set_cell(c.x, c.y, c.z, Level.AIR)
	if current == "" or got.has(current):
		pick(first_open(), false)


func count() -> int:
	return got.size()


func objective(id: String) -> Dictionary:
	for o in level.objectives:
		if o.id == id:
			return o
	return {}


## The nearest challenge not done yet (for the hint on screen).
func nearest() -> Dictionary:
	var best := {}
	var bd := 16.0
	var p: Vector3 = game.leader().position
	for o in level.objectives:
		if got.has(o.id):
			continue
		var d := Vector2(o.pos.x - p.x, o.pos.z - p.z).length()
		if d < bd:
			bd = d
			best = o
	return best


static func star_model() -> Node3D:
	var n := Node3D.new()
	if Look.toy:
		var mi := MeshInstance3D.new()
		mi.mesh = _star_mesh()
		mi.material_override = Toys.mat(Color("ffd23a"), true, 0.6)
		n.add_child(mi)
		for s in [-1, 1]:
			var e := MeshInstance3D.new()
			var sm := SphereMesh.new()
			sm.radius = 0.06
			sm.height = 0.16
			e.mesh = sm
			e.material_override = Toys.mat(Color("1e2a44"), false)
			e.position = Vector3(s * 0.12, 0.08, 0.13)
			n.add_child(e)
	else:
		var sp := Sprite3D.new()
		sp.texture = load("res://art/star.png")
		sp.pixel_size = 1.1 / sp.texture.get_height()
		sp.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
		sp.shaded = false
		sp.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
		n.add_child(sp)
	return n


## A puffy 5-point star (a star outline pushed out front and back).
static func _star_mesh() -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var pts := []
	for i in 10:
		var a := PI / 2 + TAU * i / 10.0
		var r := 0.55 if i % 2 == 0 else 0.24
		pts.append(Vector3(cos(a) * r, sin(a) * r, 0))
	for side: float in [1.0, -1.0]:
		var c := Vector3(0, 0, 0.16 * side)
		for i in 10:
			var a: Vector3 = pts[i]
			var b: Vector3 = pts[(i + 1) % 10]
			var nrm: Vector3 = (b - a).cross(c - a).normalized() * side
			st.set_normal(nrm)
			if side > 0:   # Godot's front faces go clockwise
				st.add_vertex(b); st.add_vertex(a); st.add_vertex(c)
			else:
				st.add_vertex(a); st.add_vertex(b); st.add_vertex(c)
	st.generate_normals()
	return st.commit()


func _make_star(id: String) -> void:
	if stars.has(id) or got.has(id):
		return
	var s := star_model()
	s.position = objective(id).pos
	add_child(s)
	stars[id] = s


func _drop_star(id: String, why: String) -> void:
	_make_star(id)
	game.sfx("item")
	game.hud.say("A STAR APPEARED!", why, 2.4)
	game.fx.burst(objective(id).pos, Color("ffe14a"), 18, 5, 6)


func step(dt: float) -> void:
	t += dt
	var l: Hero = game.leader()
	for id in stars.keys():
		var s: Node3D = stars[id]
		s.rotation.y = t * 2.5
		s.position.y = objective(id).pos.y + sin(t * 3.0) * 0.12
		if s.position.distance_to(l.position + Vector3(0, l.height * 0.5, 0)) < 1.3:
			_collect(id)
	# the camp: beat all its bad guys
	if not got.has("camp") and not stars.has("camp"):
		var left := 0
		for f in game.foes:
			if is_instance_valid(f) and f.dying < 0 and f.get_meta("camp", false):
				left += 1
		if left == 0:
			_drop_star("camp", "You beat the whole camp!")
	# red coins
	for i in range(reds.size() - 1, -1, -1):
		var c: Node3D = reds[i]
		c.rotation.y = t * 3.0 + i
		if c is Sprite3D:
			c.scale.x = cos(t * 3.5 + i)
		var cc: Vector3 = c.position + (Vector3.ZERO if c is Sprite3D else Vector3(0, 0.35, 0))
		if cc.distance_to(l.position + Vector3(0, l.height * 0.5, 0)) < 1.0:
			red_count += 1
			game.sfx("coin")
			game.fx.burst(cc, Color("ff3b3b"), 10, 3, 6)
			game.fx.text(cc + Vector3(0, 0.8, 0), str(red_count), Color("ff6b6b"), 64)
			c.queue_free()
			reds.remove_at(i)
			if red_count >= RED_COINS or reds.is_empty():
				_drop_star("reds", "All %d red coins! The star is in the red circle." % RED_COINS)
	# the BIG GRUMBLER
	if not got.has("boss") and not stars.has("boss") and not objective("boss").is_empty() and boss_foe() == null:
		_drop_star("boss", "You beat the BIG GRUMBLER!")
	# the lost dog
	if dennis and not got.has("dog") and not stars.has("dog"):
		_dog_step(dt, l)
	_beam_step(dt)
	for bid in beacons:   # only the quest you're doing gets a floating name (less clutter)
		beacons[bid].visible = bid == current and not got.has(bid) and game.state == "play"
	# the coin hunt
	if not got.has("coins") and not stars.has("coins") and game.coins_taken.size() >= Level.STAR_COINS:
		_drop_star("coins", "%d coins! It's back at the start." % Level.STAR_COINS)
	# Krag Jr. beaten: the flag comes down from the sky
	if game.boss_done and flag == null:
		flag = level.prop("flag", level.flag_pos + Vector3(0, 6, 0), 4.6)
		var tw := create_tween()
		tw.tween_property(flag, "position:y", level.flag_pos.y, 1.0).set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
	if flag and game.boss_done and Vector2(l.position.x - level.flag_pos.x, l.position.z - level.flag_pos.z).length() < 1.3:
		game.clear_world()


func _collect(id: String) -> void:
	got[id] = true
	stars[id].queue_free()
	stars.erase(id)
	if beacons.has(id):
		beacons[id].visible = false
	var o := objective(id)
	game.coins += 10
	game.sfx("clear")
	game.fx.burst(o.pos, Color("ffe14a"), 24, 6, 8)
	game.fx.text(o.pos + Vector3(0, 1.2, 0), "+10", Color("ffd23a"), 48)
	game.speak("We got a star!", "rocco")
	gate_msg = false
	if count() >= STARS_NEEDED and not game.gate_open:
		open_gate()
		gate_msg = true
	if current == id or got.has(current):
		pick(next_open(id), false)
	game.stars_w[level.world] = got.keys()
	game.save_game()
	if select_enabled and Cutscene.enabled and not game.bot:
		celebrate(id)
	else:
		_after_star(id)


## After the star moment: say what's next (and sometimes a funny scene).
func _after_star(id: String) -> void:
	if gate_msg:
		game.hud.say("%d STARS!" % count(), "The gold gate is open. Go get Krag Jr.!", 3.0)
	elif current != "":
		game.hud.say("STAR %d / %d!" % [count(), level.objectives.size()], "Next: " + quest_title(current), 2.6)
	else:
		game.hud.say("ALL %d STARS!" % count(), "Now go get Krag Jr. in his castle!", 3.0)
	if count() != STARS_NEEDED:
		game.maybe_funny()


func open_gate() -> void:
	game.gate_open = true
	for c in level.gate:
		level.set_cell(c.x, c.y, c.z, Level.AIR)
		game.fx.burst(Vector3(c) + Vector3(0.5, 0.5, 0.5), Color("ffd23a"), 6, 3)
	game.fx.shake = 0.6
	game.sfx("boss_down")


# ------------------------------------------------------------------ quests
func ids() -> Array:
	var out := []
	for o in level.objectives:
		out.append(o.id)
	return out


func number_of(id: String) -> int:
	return ids().find(id) + 1


func quest_title(id: String) -> String:
	if id == "":
		return "KRAG JR.'S CASTLE"
	return "STAR %d: %s" % [number_of(id), objective(id).name]


func first_open() -> String:
	for id in ids():
		if not got.has(id):
			return id
	return ""


## The next quest not done yet, after `after` in the list (wrapping round).
func next_open(after: String) -> String:
	var l := ids()
	var i := l.find(after)
	for k in l.size():
		var id: String = l[(i + 1 + k) % l.size()]
		if not got.has(id):
			return id
	return ""


## Makes a quest the current one (the beam and the arrow go there).
func pick(id: String, banner := true) -> void:
	current = id
	if banner:
		var o := objective(id)
		game.hud.say(quest_title(id), o.get("hint", "Go and get Krag Jr.!"), 3.2)
		game.sfx("item", -4)


func boss_foe() -> Foe:
	for f in game.foes:
		if is_instance_valid(f) and f.kind == "biggrumbler" and f.dying < 0:
			return f
	return null


## Where the current quest wants you to go right now (moves for some quests:
## the nearest red coin, the BIG GRUMBLER, Dennis, then his doghouse).
func target_pos(id: String = current) -> Vector3:
	if id == "":
		if level.gate.size() > 0:
			return Vector3(level.gate[level.gate.size() / 2]) + Vector3(0.5, 0, 1.5)
		return level.flag_pos
	if stars.has(id):
		return stars[id].position
	match id:
		"reds":
			return _nearest(reds, objective(id).pos)
		"coins":
			return _nearest(game.coin_nodes, objective(id).pos)
		"boss":
			var f := boss_foe()
			if f:
				return f.position + Vector3(0, f.height, 0)
		"dog":
			if dennis and not dog_following:
				return dennis.position + Vector3(0, 0.8, 0)
			return level.dog_home + Vector3(0, 0.8, 0)
	return objective(id).pos


func _nearest(nodes: Array, fallback: Vector3) -> Vector3:
	var best := fallback
	var bd := INF
	var p: Vector3 = game.leader().position
	for c in nodes:
		if is_instance_valid(c):
			var d: float = c.position.distance_to(p)
			if d < bd:
				bd = d
				best = c.position
	return best


## The goal and how far along you are, for the top of the screen.
func progress(id: String = current) -> String:
	if id == "":
		return "The gold gate is open! Go and get Krag Jr.!" if game.gate_open else "Find more stars to open the gold gate!"
	if stars.has(id) and not id in ["tower", "cave", "sky"]:   # those three are out from the start
		return "The star is out! Go and grab it!"
	match id:
		"camp":
			var left := 0
			for f in game.foes:
				if is_instance_valid(f) and f.dying < 0 and f.get_meta("camp", false):
					left += 1
			return "Beat the bad guys at the camp!   Left: %d" % left
		"coins":
			return "Collect coins anywhere!   %d / %d" % [mini(game.coins_taken.size(), Level.STAR_COINS), Level.STAR_COINS]
		"reds":
			return "Find the red coins!   %d / %d" % [red_count, RED_COINS]
		"boss":
			var f := boss_foe()
			return "Beat the BIG GRUMBLER!   Health %d" % (f.hp if f else 0)
		"dog":
			return "Walk Dennis back to his doghouse!" if dog_following else "Dennis is lost! Find him!"
	return objective(id).get("hint", "")


# ------------------------------------------------------------------ the red coins, the dog
func _redden(c: Node3D) -> void:
	if c is Sprite3D:
		c.modulate = Color(1.0, 0.35, 0.35)
	else:
		for ch in c.find_children("*", "MeshInstance3D", true, false):
			ch.material_override = Toys.mat(Color("ff3b3b"), true, 0.5)


func _dog_step(dt: float, l: Hero) -> void:
	var to_l := Vector3(l.position.x - dennis.position.x, 0, l.position.z - dennis.position.z)
	if not dog_following:
		dennis.position.y = level.dog_spot.y + absf(sin(t * 6.0)) * 0.12   # hopping about, lost
		if to_l.length() < 2.6:
			dog_following = true
			game.sfx("woof")
			game.fx.text(dennis.position + Vector3(0, 1.2, 0), "WOOF! WOOF!", Color("ffe08a"), 56, 1.4)
			game.hud.say("YOU FOUND DENNIS!", "Walk him back to his doghouse!", 2.6)
			if dennis.has_node("help"):
				dennis.get_node("help").queue_free()
		return
	# follows you, a little behind
	var want := l.position - Vector3(l.facing.x, 0, l.facing.z).normalized() * 1.6
	var d := Vector3(want.x - dennis.position.x, 0, want.z - dennis.position.z)
	if to_l.length() > 25:
		dennis.position = want
	elif d.length() > 0.4:
		dennis.position += d.normalized() * minf(d.length(), dt * 9.0)
	var g := level.ground_below(dennis.position.x, dennis.position.z, maxf(dennis.position.y, l.position.y) + 1.5)
	dennis.position.y = (g if g > -50 else l.position.y) + absf(sin(t * 12.0)) * 0.08
	if dennis is Sprite3D:
		dennis.flip_h = d.dot(Actor.view_right) < 0
	elif d.length() > 0.2:
		dennis.rotation.y = atan2(d.x, d.z)
	var home := level.dog_home
	if Vector2(dennis.position.x - home.x, dennis.position.z - home.z).length() < 2.0:
		dennis.position = home + Vector3(0, 0, -0.6)
		dog_following = false
		game.sfx("heart")
		_drop_star("dog", "Dennis is home! Thank you!")


# ------------------------------------------------------------------ the light beam on the quest
func _make_beam() -> void:
	beam = Node3D.new()
	add_child(beam)
	for layer in [[1.3, Color(1.0, 0.72, 0.1, 0.32)], [0.5, Color(1.0, 0.95, 0.55, 0.6)]]:
		var mi := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.top_radius = layer[0] * 0.6
		cm.bottom_radius = layer[0]
		cm.height = 60.0
		cm.radial_segments = 12
		cm.cap_top = false
		cm.cap_bottom = false
		mi.mesh = cm
		var m := StandardMaterial3D.new()
		m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
		m.albedo_color = layer[1]
		m.set("disable_fog", true)
		mi.material_override = m
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		mi.position.y = 30.0
		beam.add_child(mi)
		beam_mats.append(m)
	var ring := MeshInstance3D.new()   # a glowing ring on the ground
	var tm := TorusMesh.new()
	tm.inner_radius = 0.9
	tm.outer_radius = 1.15
	ring.mesh = tm
	ring.material_override = beam_mats[1]
	ring.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	ring.position.y = 0.05
	ring.name = "ring"
	beam.add_child(ring)


func _beam_step(dt: float) -> void:
	if beam == null:
		return
	var show: bool = not (game.boss_on or game.boss_done) and game.state == "play"
	beam.visible = show
	if not show:
		return
	var p := target_pos()
	var g := level.ground_below(p.x, p.z, p.y + 0.5)
	beam.position = Vector3(p.x, (g if g > -50 else p.y - 1.0), p.z)
	var pulse := 0.8 + 0.2 * sin(t * 4.0)
	beam_mats[0].albedo_color.a = 0.32 * pulse
	var ring: Node3D = beam.get_node("ring")
	ring.scale = Vector3.ONE * (1.0 + 0.15 * sin(t * 5.0))
	# sparkles on the spot when you're getting close
	var l: Hero = game.leader()
	if Vector2(p.x - l.position.x, p.z - l.position.z).length() < 14:
		sparkle_t -= dt
		if sparkle_t <= 0:
			sparkle_t = 0.35
			game.fx.burst(beam.position + Vector3(0, 0.3, 0), Color("fff2a8"), 3, 1.5, 4, 0.9)


# ------------------------------------------------------------------ STAR SELECT
## The Mario 64 star select screen (when you land, or STARS in the pause menu).
func open_select(from := "start") -> void:
	if not select_enabled or not Cutscene.enabled or game.bot:
		pick(first_open() if current == "" or got.has(current) else current, false)
		return
	select_from = from
	var i := ids().find(current)
	sel = maxi(0, i if i >= 0 else ids().find(first_open()))
	picked = sel
	game.state = "starselect"
	game.hud.message_t = 0


func select_move(d: int) -> void:
	var n := ids().size()
	if n > 0:
		sel = (sel + d + n) % n
		picked = sel
		game.sfx("bonk", -10)


## Tapping a card: the first tap picks it, tapping it again (or GO) starts.
func select_tap(i: int) -> void:
	if i == picked:
		choose(i)
	else:
		sel = i
		picked = i
		game.sfx("bonk", -10)


func choose(i: int) -> void:
	var l := ids()
	game.state = "play"
	if l.is_empty():
		return
	var id: String = l[clampi(i, 0, l.size() - 1)]
	if got.has(id):   # already have that one: go for the next one instead
		id = next_open(id)
	pick(id, true)
	game.sfx("item")


# ------------------------------------------------------------------ YOU GOT A STAR!
func celebrate(id: String) -> void:
	cel_id = id
	cel_t = 0.0
	game.state = "stargot"
	game.hud.message_t = 0
	cel_cam_from = game.cam.position
	cel_star = star_model()
	cel_star.scale = Vector3.ONE * 0.8
	add_child(cel_star)
	game.play_music("fanfare", false)
	game.leader().vel = Vector3.ZERO
	# the friend steps out of the picture so the camera sees the hero holding the star
	var fr: Hero = game.follower()
	fr_was_visible = fr.visible
	fr.visible = false
	fr.shadow.visible = false
	fr.vel = Vector3.ZERO


func step_celebrate(dt: float) -> void:
	cel_t += dt
	var l: Hero = game.leader()
	var face := Actor.view_back
	l.facing = face
	l.play("win")
	l.vel.x = 0
	l.vel.z = 0
	l.vel.y -= Actor.GRAVITY * dt
	l.move(dt)
	l.animate(dt)
	l.update_shadow()
	var head := l.position + Vector3(0, l.height + 0.75 + minf(cel_t, 0.4) * 0.5, 0)
	cel_star.position = head
	cel_star.rotation.y = cel_t * 6.0
	# the camera swings round in front of the hero
	var k := clampf(cel_t / 0.6, 0, 1)
	k = k * k * (3.0 - 2.0 * k)
	var want := l.position + face * 4.2 + Vector3(0, 1.7, 0)
	game.cam.position = cel_cam_from.lerp(want, k)
	game.cam.look_at(l.position + Vector3(0, 1.1, 0), Vector3.UP)
	if int(cel_t * 10) % 3 == 0:
		game.fx.burst(head, Color.from_hsv(fmod(cel_t, 1.0), 0.5, 1.0), 2, 3, 4, 0.6)
	if cel_t > 2.6:
		end_celebrate()


func end_celebrate() -> void:
	if cel_t < 0:
		return
	cel_t = -1.0
	if cel_star:
		cel_star.queue_free()
		cel_star = null
	game.state = "play"
	var fr: Hero = game.follower()
	fr.visible = fr_was_visible
	fr.position = game.leader().position + Vector3(0, 0.3, 0)   # pops back in beside you
	game.cam_focus = game.leader().position + Vector3(0, 1.4, 0)
	game.play_music(game.world_info().song)
	_after_star(cel_id)
