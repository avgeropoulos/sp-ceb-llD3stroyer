class_name Cutscene
extends Node3D
## Little movies: the camera glides, characters walk, hop and talk (speech bubbles +
## the text-to-speech voices), with black bars at the top and bottom. Tap to skip.
## A scene is a timeline of "tracks" (each runs for a while, getting 0..1) built with
## the helpers below (walk, hop, say, cam...). Scenes only move things for show: when
## a scene ends (or is skipped) everyone goes back to where they were.

static var enabled := true             # tests switch this off
static var last_funny_ms := -10000000
static var last_funny := ""

const TITLES := {"intro": "KRAG JR. STEALS THE STARS", "gate": "BONK!", "krag": "KRAG JR.'S CASTLE",
	"victory": "HOORAY, DENNIS!", "banana": "BANANA PEEL", "pickaxe": "STUCK PICKAXE", "sneeze": "ACHOO!",
	"zoomies": "DENNIS ZOOMIES"}
const ORDER := ["intro", "gate", "krag", "victory", "banana", "pickaxe", "sneeze", "zoomies"]
const FUNNY := ["banana", "pickaxe", "sneeze", "zoomies"]

var game
var id := ""
var t := 0.0
var length := 0.0
var cursor := 0.0
var tracks := []
var spawned: Array[Node] = []
var saved := {}
var actors: Array = []        # characters to animate every frame
var caption := ""
var speaker := ""
var bubble: Label3D
var bubble_on: Node3D
var look_now := Vector3.ZERO
var P := Vector3.ZERO         # where the scene happens (on the ground)
var done := false


func start(g, scene_id: String) -> void:
	game = g
	id = scene_id
	var l: Hero = game.leader()
	P = l.position
	look_now = game.cam_focus
	for k in game.heroes:
		var h: Hero = game.heroes[k]
		saved[k] = [h.position, h.visible, h.facing, h.vel]
		h.vel = Vector3.ZERO
		actors.append(h)
	bubble = Label3D.new()
	bubble.font_size = 40
	bubble.outline_size = 12
	bubble.pixel_size = 0.0065
	bubble.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	bubble.no_depth_test = true
	bubble.render_priority = 5
	bubble.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	bubble.width = 560
	bubble.modulate = Color.WHITE
	bubble.outline_modulate = Color(0.08, 0.06, 0.16)
	bubble.visible = false
	add_child(bubble)
	if game.open_play:   # no floating challenge names in the movies
		game.open_play.visible = false
	call("_scene_" + id)
	game.note("scene " + id)


# ------------------------------------------------------------------ running
func step(dt: float) -> void:
	if done:
		return
	t += dt
	for tr in tracks:
		if tr.done or t < tr.a:
			continue
		var k := 1.0 if tr.d <= 0.0 else clampf((t - tr.a) / tr.d, 0, 1)
		tr.f.call(k)
		if k >= 1.0:
			tr.done = true
	for a in actors:
		if is_instance_valid(a) and a.visible:
			a.animate(dt)
			a.update_shadow()
	if bubble.visible and is_instance_valid(bubble_on):
		var hgt: float = bubble_on.height if bubble_on is Actor else 1.0
		bubble.position = bubble_on.global_position + Vector3(0, hgt + 1.1, 0)
	# who's left / right on screen, for which way the paper cutouts face
	var back: Vector3 = game.cam.global_position - look_now
	back.y = 0
	if back.length() > 0.1:
		Actor.view_back = back.normalized()
		Actor.view_right = Actor.view_back.rotated(Vector3.UP, PI / 2)
	if t >= length:
		finish()


## Ends the scene (also SKIP): puts everyone back and tells the game.
func finish() -> void:
	if done:
		return
	done = true
	for n in spawned:
		if is_instance_valid(n):
			if n is Actor:
				n.remove()
			else:
				n.queue_free()
	for k in saved:
		var h: Hero = game.heroes[k]
		h.position = saved[k][0]
		h.visible = saved[k][1]
		h.facing = saved[k][2]
		h.vel = Vector3.ZERO
		h.spin_t = 0
		h.squash = Vector2.ONE
		h.flash_t = 0
		h.shadow.visible = h.visible
		h.play("idle")
	caption = ""
	speaker = ""
	if game.open_play:
		game.open_play.visible = true
	game._scene_done(id)


# ------------------------------------------------------------------ building a scene
func _add(d: float, f: Callable, par := false) -> void:
	tracks.append({"a": cursor, "d": d, "f": f, "done": false})
	length = maxf(length, cursor + d)
	if not par:
		cursor += d


func wait(d: float) -> void:
	cursor += d
	length = maxf(length, cursor)


func once(f: Callable) -> void:
	_add(0.0, func(_k): f.call(), true)


## A spot on the ground near the middle of the scene.
func gp(dx: float, dz: float, up := 0.0) -> Vector3:
	var x := P.x + dx
	var z := P.z + dz
	var g: float = game.level.ground_below(x, z, P.y + 4.0)
	if g < -50:
		g = P.y
	return Vector3(x, g + up, z)


static func _ease(k: float) -> float:
	return k * k * (3.0 - 2.0 * k)


## Glides the camera to `pos`, looking at `look`.
func cam(pos: Vector3, look: Vector3, d: float, par := true) -> void:
	var st := {}
	_add(d, func(k):
		if st.is_empty():
			st.p = game.cam.position
			st.l = look_now
		var e := _ease(k)
		game.cam.position = st.p.lerp(pos, e)
		look_now = st.l.lerp(look, e)
		if game.cam.position.distance_to(look_now) > 0.01:
			game.cam.look_at(look_now, Vector3.UP), par)


## Puts a character somewhere straight away (and shows it).
func put(a: Node3D, pos: Vector3, face := Vector3.ZERO) -> void:
	once(func():
		a.position = pos
		a.visible = true
		if face != Vector3.ZERO:
			_face(a, face))


func _face(a: Node3D, dir: Vector3) -> void:
	dir.y = 0
	if dir.length() < 0.01:
		return
	if a is Actor:
		a.facing = dir.normalized()
	elif a is Sprite3D:
		a.flip_h = dir.dot(Actor.view_right) < 0
	else:
		a.rotation.y = atan2(dir.x, dir.z)


## Walks (or flies) from where it is to `to`. keep_face: slide without turning round.
func walk(a: Node3D, to: Vector3, d: float, par := false, anim := "run", keep_face := false) -> void:
	var st := {}
	_add(d, func(k):
		if st.is_empty():
			st.from = a.position
		var p: Vector3 = st.from.lerp(to, k)
		if not keep_face:
			_face(a, to - st.from)
		if a is Actor:
			a.play(anim if k < 1.0 else ("idle" if anim == "run" else anim))
		elif k < 1.0:
			p.y += absf(sin(k * d * 14.0)) * 0.15   # little bounces for Dennis and friends
		a.position = p, par)


## Jumps up and down on the spot (height in blocks).
func hop(a: Node3D, height: float, d: float, par := false, flip := false) -> void:
	var st := {}
	_add(d, func(k):
		if st.is_empty():
			st.y = a.position.y
			if flip and a is Actor:
				a.spin()
		a.position.y = st.y + sin(k * PI) * height
		if a is Actor:
			a.play("jump" if k < 0.5 else ("fall" if k < 1.0 else "idle")), par)


## Plays one picture / pose for a while.
func pose(a: Actor, anim: String, d: float, par := false) -> void:
	_add(d, func(k): a.play(anim), par)


## Says something: a speech bubble over them, the words at the bottom, and the voice.
func say(a: Node3D, who: String, name: String, text: String, d: float, par := false) -> void:
	_add(d, func(k):
		if k < 1.0:
			if bubble_on != a or bubble.text != text:
				bubble_on = a
				bubble.text = text
				bubble.visible = true
				caption = text
				speaker = name
				game.speak(text, who)
		elif bubble_on == a and bubble.text == text:
			bubble.visible = false
			caption = ""
			speaker = "", par)


## Words at the bottom with nobody talking (the narrator).
func tell(text: String, d: float) -> void:
	_add(d, func(k):
		caption = text if k < 1.0 else ""
		speaker = "" )


func sfx(name: String, vol := 0.0) -> void:
	once(func(): game.sfx(name, vol))


func burst(pos: Vector3, col: Color, n := 14, speed := 5.0) -> void:
	once(func(): game.fx.burst(pos, col, n, speed, 6))


func shake(amount: float) -> void:
	once(func(): game.fx.shake = amount)


# ------------------------------------------------------------------ cast
func R() -> Hero:
	return game.heroes.rocco


func B() -> Hero:
	return game.heroes.blocky


func M() -> Hero:
	return game.heroes.mon


func mon_name() -> String:
	return Mons.name_of(M().line, M().stage)


func krag(pos: Vector3, kind := "kragjr") -> Foe:
	var f := Foe.new()
	add_child(f)
	f.init(game.level, kind, pos)
	f.position = pos
	spawned.append(f)
	actors.append(f)
	return f


func dennis(pos: Vector3) -> Node3D:
	var d: Node3D = game.level.prop("dennis", pos, 1.0)
	spawned.append(d)
	return d


## The three heroes in a row facing the camera (the camera sits on the +z side).
func line_up(spread := 1.7) -> void:
	put(R(), gp(-spread, 0), Vector3(0.3, 0, 1))
	put(B(), gp(0, 0.4), Vector3(0, 0, 1))
	put(M(), gp(spread, 0.1), Vector3(-0.3, 0, 1))


# ------------------------------------------------------------------ the scenes
## A new file: Krag Jr. flies in, grabs the stars of every world, and Dennis
## accidentally jumps into his copter and gets carried off.
func _scene_intro() -> void:
	line_up()
	var dog := dennis(gp(3.6, 1.4))
	var kj := krag(P + Vector3(16, 10, -14))
	var stars := []
	for i in 5:
		var s := OpenPlay.star_model()
		s.visible = false
		add_child(s)
		stars.append(s)
	once(func():
		game.cam.position = P + Vector3(0, 2.6, 9.5)
		look_now = P + Vector3(0, 1.0, 0)
		game.cam.look_at(look_now, Vector3.UP)
		game.play_music("cozy"))
	say(R(), "rocco", "ROCCO", "What a lovely day at home!", 2.6)
	hop(dog, 0.6, 0.5, true)
	sfx("woof")
	say(dog, "", "DENNIS", "WOOF! WOOF!", 1.4)
	sfx("alert")
	once(func(): game.play_music("krag"))
	cam(P + Vector3(1, 4.2, 11), P + Vector3(1.5, 3.2, -2), 2.4)
	walk(kj, P + Vector3(2, 4.6, -3), 2.4, false, "fly")
	var orbit_at := cursor
	say(kj, "", "KRAG JR.", "Hee hee hee! I took ALL the stars from every world!", 3.2)
	say(kj, "", "KRAG JR.", "No stars, no way into my castles! Ha!", 3.0)
	say(B(), "blocky", "BLOCKY", "Hey! Give those back!", 2.0)
	walk(dog, gp(2.2, -2.6), 0.8)
	sfx("woof")
	walk(dog, P + Vector3(2, 5.5, -3), 0.5)   # a huge doggy leap
	pose(kj, "angry", 2.6, true)
	once(func():
		dog.reparent(kj)
		dog.position = Vector3(0, 0.9, 0))
	say(kj, "", "KRAG JR.", "Hey! Get out of my copter, you silly dog!", 2.6)
	sfx("woof")
	walk(kj, P + Vector3(-22, 15, -34), 2.6, true, "fly")
	cam(P + Vector3(0, 3.0, 10), P + Vector3(-4, 4, -10), 2.4)
	hop(R(), 0.8, 0.6, true, true)
	say(R(), "rocco", "ROCCO", "Mamma mia! DENNIS!", 2.4)
	once(func(): game.play_music("cozy"))
	cam(P + Vector3(0, 2.4, 8.5), P + Vector3(0, 1.0, 0), 1.2)
	hop(M(), 0.7, 0.6, true)
	say(M(), "mon", mon_name(), mon_name() + "!!", 1.6)
	say(B(), "blocky", "BLOCKY", "Let's take the ROCKET, find the stars and save Dennis!", 3.4)
	tell("Find 5 STARS in every world to open Krag Jr.'s castle!", 3.0)
	# the stars circle round Krag Jr. from when he arrives to the end
	var end := length
	cursor = orbit_at
	_add(end - orbit_at, func(k):
		for i in stars.size():
			var s: Node3D = stars[i]
			s.visible = is_instance_valid(kj) and kj.visible and k < 1.0
			var a := t * 2.4 + TAU * i / 5.0
			s.position = kj.position + Vector3(cos(a) * 1.6, 0.6 + sin(t * 3 + i) * 0.2, sin(a) * 1.6)
			s.rotation.y = t * 3, true)
	cursor = end


## Walking up to the castle before you have 5 stars: Krag Jr. zooms home, forgets
## his own gate is shut and bonks right into it.
func _scene_gate() -> void:
	var gc: Vector3 = Vector3(game.level.gate[game.level.gate.size() / 2]) + Vector3(0.5, 0, 0.5)
	var kj := krag(gc + Vector3(9, 6, 9))
	once(func():
		game.cam.position = gc + Vector3(5.5, 3.0, 9.5)
		look_now = gc + Vector3(0.5, 2.0, 0)
		game.cam.look_at(look_now, Vector3.UP))
	say(kj, "", "KRAG JR.", "La la la, home time!", 1.6, true)
	walk(kj, gc + Vector3(0, 1.4, 1.6), 1.6, false, "fly")
	sfx("bonk")
	burst(gc + Vector3(0, 3.2, 1.4), Color("ffe14a"), 14, 4)
	shake(0.5)
	walk(kj, gc + Vector3(0, 1.0, 2.6), 0.3, true, "hurt", true)
	once(func(): kj.spin())
	wait(0.3)
	once(func(): kj.facing = Vector3(0.3, 0, 1).normalized())
	pose(kj, "hurt", 1.8, true)
	say(kj, "", "KRAG JR.", "OW! My head! Who shut my gate?!", 2.2)
	pose(kj, "angry", 3.0, true)
	say(kj, "", "KRAG JR.", "Oh right, it needs FIVE STARS to open! And I'm not telling you that!", 3.4)
	walk(kj, gc + Vector3(0, 10, -8), 1.2, false, "fly")
	cam(game.leader().position + Vector3(0, 2.6, 7), game.leader().position + Vector3(0, 1.2, 0), 1.0, false)
	say(game.leader(), "rocco" if game.leader().who == "rocco" else ("blocky" if game.leader().who == "blocky" else "mon"),
		game.display_name(game.leader().who), "He just told us! Let's find 5 stars!", 2.4)


## All 5 stars: inside the castle, Krag Jr. is NOT happy.
func _scene_krag() -> void:
	var kj: Foe = game.boss
	var nm: String = kj.boss_name() if is_instance_valid(kj) else "KRAG JR."
	var short: String = nm.capitalize()
	var kp: Vector3 = kj.position if is_instance_valid(kj) else P + Vector3(0, 4.5, -6)
	if is_instance_valid(kj):
		actors.append(kj)
	once(func():
		game.cam.position = P + Vector3(2.5, 2.4, 6)
		look_now = kp
		game.cam.look_at(look_now, Vector3.UP))
	cam(kp + Vector3(1.5, 0.5, 5.5), kp, 1.4)
	wait(0.4)
	if is_instance_valid(kj):
		pose(kj, "angry", 2.8, true)
	sfx("alert")
	say(kj, "", nm, "WHAT?! You found all 5 stars?!", 2.8)
	cam(P + Vector3(1.5, 2.2, 5.5), P + Vector3(0, 1.4, 0), 1.0)
	say(game.leader(), "rocco", "ROCCO", "Give back Dennis, Krag Jr.!" if game.level.world == "meadow" and nm == "KRAG JR." else "Here we come, %s!" % short, 2.4)
	cam(kp + Vector3(-1.5, 0.3, 5), kp, 1.0)
	if is_instance_valid(kj):
		pose(kj, "angry", 2.8, true)
	shake(0.6)
	say(kj, "", nm, "Never! Time for a BIG BOSS FIGHT!", 2.8)


## Krag Jr. beaten: his copter crashes, Dennis pops out, everybody cheers.
func _scene_victory() -> void:
	line_up(1.9)
	var crash := gp(0.5, -4.5)
	# this world's boss (Krag Jr., a Kragling, King Krag or Dry Krag)
	var bk: String = game.boss.kind if is_instance_valid(game.boss) else Bosses.for_world(game.level.world)
	var nm := Bosses.name_of(bk)
	var flyer := Bosses.flies(bk)
	var kj := krag(P + Vector3(-4, 10, -8), bk)
	var dog := dennis(crash + Vector3(0, -5, 0))
	once(func():
		dog.visible = false
		game.cam.position = P + Vector3(0, 3.2, 10)
		look_now = P + Vector3(0, 1.6, -2)
		game.cam.look_at(look_now, Vector3.UP))
	_add(1.3, func(k): kj.spin_t = 0.3 if k < 1.0 else 0.0, true)
	walk(kj, crash + Vector3(0, 0.3, 0), 1.3, false, "hurt")
	sfx("boss_down")
	burst(crash + Vector3(0, 0.6, 0), Color(0.55, 0.55, 0.6), 24, 5)
	shake(1.0)
	pose(kj, "hurt", 2.6, true)
	say(kj, "", nm, "Ow ow ow... my copter!" if flyer else "Ow ow ow... my shell!", 2.4)
	put(dog, crash + Vector3(0.6, 1.4, 0.4))
	sfx("woof")
	walk(dog, gp(1.0, -1.6), 0.7)
	say(dog, "", "DENNIS", "WOOF! WOOF!", 1.4)
	say(R(), "rocco", "ROCCO", "Dennis! You're safe!" if game.level.world == "meadow" else ("Dennis! Did you sneak into the copter AGAIN?" if flyer else "Dennis! Were you hiding in the castle AGAIN?"), 2.6)
	sfx("clear")
	for a in [R(), B(), M()]:
		hop(a, 0.9, 0.55, true, a == R())
	hop(dog, 0.8, 0.55, true)
	burst(P + Vector3(0, 3, 0), Color("ff6fa8"), 16, 6)
	wait(0.6)
	for a in [R(), B(), M()]:
		hop(a, 0.9, 0.55, true, a == B())
	burst(P + Vector3(1, 3.4, -1), Color("54c8ff"), 16, 6)
	wait(0.7)
	pose(kj, "angry", 2.6, true)
	say(kj, "", nm, "I'll get you next time, Team Up!", 2.6)
	walk(kj, crash + Vector3(-10, 12, -16), 1.8, true, "fly")
	_add(1.8, func(k): kj.spin_t = 0.3 if k < 1.0 else 0.0, true)
	say(B(), "blocky", "BLOCKY", "Bye bye, %s!" % nm.capitalize(), 1.8)


## Rocco slips on a banana peel.
func _scene_banana() -> void:
	line_up(2.4)
	var peel := _banana(gp(0.3, 1.2))
	put(R(), gp(-3.2, 1.2), Vector3.RIGHT)
	once(func():
		game.cam.position = P + Vector3(0, 2.4, 8)
		look_now = P + Vector3(0, 1.0, 0)
		game.cam.look_at(look_now, Vector3.UP))
	say(R(), "rocco", "ROCCO", "La la la, what a nice walk...", 2.0)
	walk(R(), gp(0.3, 1.2), 1.1)
	sfx("bonk")
	hop(R(), 2.0, 0.9, false, true)
	once(func():
		R().squash = Vector2(1.4, 0.55)
		peel.visible = false)
	pose(R(), "hurt", 2.8, true)
	say(R(), "rocco", "ROCCO", "Mamma mia! Who left a banana here?!", 2.8)
	hop(B(), 0.5, 0.4, true)
	say(B(), "blocky", "BLOCKY", "Hahaha!", 1.4)
	hop(M(), 0.6, 0.4, true)
	say(M(), "mon", mon_name(), "Hee hee hee!", 1.4)


func _banana(pos: Vector3) -> Node3D:
	var n: Node3D
	if Look.toy:
		n = Node3D.new()
		for i in 3:
			var mi := MeshInstance3D.new()
			var cm := CapsuleMesh.new()
			cm.radius = 0.07
			cm.height = 0.34
			mi.mesh = cm
			mi.material_override = Toys.mat(Color("ffd84a"))
			mi.rotation.z = PI / 2 + (i - 1) * 0.6
			mi.position = Vector3((i - 1) * 0.22, 0.07 + absf(i - 1) * 0.06, 0)
			n.add_child(mi)
		n.position = pos
		add_child(n)
	else:
		n = game.level.paper("banana", pos, 0.35)
	spawned.append(n)
	return n


## Blocky's pickaxe gets stuck in a rock and he flies backwards.
func _scene_pickaxe() -> void:
	line_up(2.6)
	var rock := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3.ONE
	rock.mesh = bm
	var mat := StandardMaterial3D.new()
	mat.albedo_texture = load("res://art/t_stone.png")
	mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	rock.material_override = mat
	add_child(rock)
	spawned.append(rock)
	var rp := gp(1.2, 0.4, 0.5)
	once(func(): rock.position = rp)
	put(B(), gp(0, 0.4), Vector3.RIGHT)
	once(func():
		game.cam.position = P + Vector3(-0.5, 2.2, 7.5)
		look_now = P + Vector3(0.4, 1.0, 0)
		game.cam.look_at(look_now, Vector3.UP))
	say(B(), "blocky", "BLOCKY", "One big swing!", 1.6)
	pose(B(), "mine", 1.0)
	sfx("mine")
	say(B(), "blocky", "BLOCKY", "Huh? It's stuck!", 1.8)
	_add(1.4, func(k):
		B().play("mine")
		B().position.x = P.x + sin(k * 30) * 0.08)
	sfx("bonk")
	walk(B(), gp(-3.0, 0.4), 0.6, true, "hurt", true)
	hop(B(), 1.2, 0.6, false, true)
	once(func(): B().squash = Vector2(1.4, 0.6))
	say(B(), "blocky", "BLOCKY", "Oof!", 1.2)
	hop(M(), 0.5, 0.4, true)
	say(R(), "rocco", "ROCCO", "Teamwork makes the dream work!", 2.4)


## The partner sneezes a big blast and Rocco goes flying.
func _scene_sneeze() -> void:
	line_up(1.4)
	put(M(), gp(0.8, 0.4), Vector3.LEFT)
	put(R(), gp(-0.9, 0.4), Vector3.RIGHT)
	put(B(), gp(-2.8, 0.2), Vector3.RIGHT)
	once(func():
		game.cam.position = P + Vector3(0, 2.0, 7)
		look_now = P + Vector3(0, 0.9, 0)
		game.cam.look_at(look_now, Vector3.UP))
	say(M(), "mon", mon_name(), "Ah... ah...", 1.4)
	_add(1.0, func(k): M().squash = Vector2(1.0 - 0.15 * k, 1.0 + 0.3 * k))
	say(M(), "mon", mon_name(), "ACHOOO!", 0.6, true)
	once(func(): M().squash = Vector2(1.3, 0.75))
	sfx("slam")
	burst(gp(-0.2, 0.4, 0.6), Mons.LINES[game.heroes.mon.line].color, 26, 7)
	shake(0.4)
	walk(R(), gp(-4.2, 0.4), 0.7, true, "hurt", true)
	hop(R(), 1.4, 0.7, false, true)
	once(func(): R().flash_t = 0.6)
	say(R(), "rocco", "ROCCO", "Bless you!", 1.6)
	say(M(), "mon", mon_name(), "Oops! Hee hee!", 1.6)


## Dennis gets the zoomies and runs circles round the team until they're dizzy.
func _scene_zoomies() -> void:
	line_up(1.1)
	var dog := dennis(gp(4, 1))
	once(func():
		game.cam.position = P + Vector3(0, 3.4, 8.5)
		look_now = P + Vector3(0, 0.8, 0)
		game.cam.look_at(look_now, Vector3.UP))
	sfx("woof")
	say(dog, "", "DENNIS", "WOOF!", 1.0)
	_add(3.2, func(k):
		var a: float = k * TAU * 3.0
		var p := gp(cos(a) * 3.0, sin(a) * 3.0)
		_face(dog, p - dog.position)
		dog.position = p + Vector3(0, absf(sin(k * 40)) * 0.15, 0)
		for h in [R(), B(), M()]:
			h.facing = (dog.position - h.position).normalized())
	once(func():
		for h in [R(), B(), M()]:
			h.spin()
			h.squash = Vector2(1.2, 0.8))
	pose(R(), "hurt", 2.0, true)
	say(R(), "rocco", "ROCCO", "I'm so dizzy...", 2.0)
	walk(dog, gp(-12, -8), 1.2, true)
	sfx("woof")
	say(B(), "blocky", "BLOCKY", "Silly Dennis!", 1.6)
