class_name Grab
extends Node3D
## GRAB, CARRY and THROW (Mario 64 style), for the human heroes in the open worlds.
##   * ACTION right next to a small bad guy (a Grumbler, a walking bomb...) picks it up and
##     (Rocco grabs any small bad guy; the other heroes keep their own attack and only grab bombs)
##     holds it over your head; ACTION again throws it in an arc. Thrown things knock out
##     the bad guys they hit, and walking bombs blow up where they land.
##   * KING BOMBO (the bomb king on the summit) can only be picked up from BEHIND (or
##     while he's dizzy). Throw him down onto his summit 3 times.
##   * TAIL SWING: get behind a walking Krag boss and press ACTION to grab his TAIL. Spin
##     him by turning the stick round and round (or tapping ACTION), then JUMP to let go.
##     KING KRAG's arena (Cloud Castle) has SPIKE BOMBS round the edge: throw him into one
##     and he loses a third of his health (3 bombs beat him). Other Kraglings just get a
##     bonus bonk when they land.

const GRABBABLE := ["grumbler", "bomber", "hopper", "mummy", "penguin", "scorpion", "spiky"]
const REACH := 1.5
const TAIL_REACH := 2.8
const SWING_R := 2.6
const BOMB_COOL := 4.0

var game
var level: Level
var holding: Foe              # the thing over your head
var tail: Foe                 # the boss you're swinging by the tail
var held_by: Foe              # KING BOMBO is holding YOU
var thrown: Array = []        # [{foe, by_king}] things flying through the air
var flung: Foe                # a boss flying after a tail throw
var ang := 0.0                # tail swing: where the boss is round you
var spin_w := 0.0             # tail swing speed (radians a second)
var spin_dir := 1.0
var stick_ang := 0.0
var had_stick := false
var swing_t := 0.0
var pin := Vector3.ZERO       # the hero stays on this spot while swinging
var bombs: Array = []         # spike bombs: {pos, node, cool}
var arrow: MeshInstance3D


func setup(g, lv: Level) -> void:
	game = g
	level = lv
	arrow = MeshInstance3D.new()
	var pm := PrismMesh.new()
	pm.size = Vector3(0.9, 1.4, 0.12)
	arrow.mesh = pm
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(1, 0.85, 0.2, 0.85)
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.no_depth_test = true
	arrow.material_override = m
	arrow.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	arrow.visible = false
	add_child(arrow)
	if game.boss and is_instance_valid(game.boss) and game.boss.kind == "kingkrag":
		_place_spike_bombs()


# ------------------------------------------------------------------ spike bombs
func _place_spike_bombs() -> void:
	var ar: Rect2 = level.arena
	var y := float(level.arena_y)
	var spots := []
	for i in 4:   # four on each side, kept away from the corners and the gate
		var f := (i + 0.5) / 4.0
		spots.append(Vector3(ar.position.x + 2.6, y, ar.position.y + 2.5 + f * (ar.size.y - 5.0)))
		spots.append(Vector3(ar.end.x - 1.6, y, ar.position.y + 2.5 + f * (ar.size.y - 5.0)))
		spots.append(Vector3(ar.position.x + 3.0 + f * (ar.size.x - 6.0), y, ar.position.y + 2.6))
	for p in spots:
		var n := level.prop("spikeball", p, 1.6)
		if not (n is Sprite3D):
			n.scale = Vector3.ONE * 2.2   # the toy spike ball is small: make the mine big
		bombs.append({"pos": p, "node": n, "cool": 0.0})


func bomb_near(p: Vector3, r: float) -> int:
	for i in bombs.size():
		var b: Dictionary = bombs[i]
		if b.cool <= 0 and Vector2(b.pos.x - p.x, b.pos.z - p.z).length() < r and absf(p.y - b.pos.y) < 2.5:
			return i
	return -1


func _blow_bomb(i: int) -> void:
	var b: Dictionary = bombs[i]
	b.cool = BOMB_COOL
	b.node.visible = false
	var c: Vector3 = b.pos + Vector3(0, 0.6, 0)
	game.fx.burst(c, Color("ffb02e"), 36, 8, 7)
	game.fx.burst(c, Color(0.3, 0.3, 0.32), 20, 5, 5)
	game.fx.shake = 1.0
	game.sfx("break")
	game.sfx("boss_hit")


# ------------------------------------------------------------------ what ACTION does
## The best thing to grab right now (or null): close, in front, not flying.
func _grab_target(h: Hero) -> Foe:
	if not h.on_floor or h.who == "mon":
		return null
	var f2 := Vector2(h.facing.x, h.facing.z).normalized()
	var best: Foe = null
	var bd := 99.0
	for fo in game.foes:   # (untyped: a freed bad guy can still be in the list for a moment)
		if not is_instance_valid(fo):
			continue
		var f: Foe = fo
		if f.dying >= 0 or f.grabbed:
			continue
		var d := f.position - h.position
		var dd := Vector2(d.x, d.z)
		if absf(d.y) > 1.2:
			continue
		var reach: float = REACH + f.hw
		if f.kind == "kingbombo":
			if dd.length() > reach + 0.4 or f.state == "hold":
				continue
			# only from BEHIND him (you're on his back side), unless he's dizzy
			var back := Vector2(f.facing.x, f.facing.z).normalized().dot(-dd.normalized()) < -0.25
			if not back and f.state != "dizzy":
				continue
		elif f.kind in GRABBABLE:
			# Rocco grabs any small bad guy; the others keep their own attack and only grab bombs
			if dd.length() > reach or (h.who != "rocco" and f.kind != "bomber"):
				continue
		else:
			continue
		if dd.length() > 0.3 and dd.normalized().dot(f2) < 0.3:
			continue
		if dd.length() < bd:
			bd = dd.length()
			best = f
	return best


## A walking Krag boss whose TAIL you can grab (you're behind him, close).
func _tail_target(h: Hero) -> Foe:
	var b: Foe = game.boss
	if b == null or not is_instance_valid(b) or b.dying >= 0 or not game.boss_on or h.who == "mon":
		return null
	if Bosses.flies(b.kind) or not Bosses.is_boss(b.kind) or b.grabbed:
		return null
	if not (b.state in ["walk", "warn", "tired", "wand", "ring", "balls", "fire", "bluefire", "bones"]):
		return null
	var d := h.position - b.position
	var dd := Vector2(d.x, d.z)
	if dd.length() > TAIL_REACH + b.hw or absf(d.y) > 1.5:
		return null
	var fb := Vector2(b.facing.x, b.facing.z).normalized()
	if fb.dot(dd.normalized()) > -0.2:   # you have to be BEHIND him (where the tail is)
		return null
	return b


## The ACTION button's name for this moment ("" = the hero's own move).
func label() -> String:
	var h: Hero = game.leader()
	if held_by:
		return "WIGGLE"
	if tail:
		return "SPIN!"
	if holding:
		return "THROW"
	if _tail_target(h):
		return "GRAB TAIL"
	if _grab_target(h):
		return "GRAB"
	return ""


## ACTION pressed: returns true when grab handled it (else the hero's own move happens).
func on_action(h: Hero) -> bool:
	if held_by:
		return true
	if tail:   # tap ACTION to spin him faster
		spin_w = minf(spin_w + 2.6, 13.0)
		game.sfx("swap", -10)
		return true
	if holding:
		_throw(h)
		return true
	var b := _tail_target(h)
	if b:
		_grab_tail(h, b)
		return true
	var f := _grab_target(h)
	if f:
		_pick_up(h, f)
		return true
	return false


func _pick_up(h: Hero, f: Foe) -> void:
	holding = f
	f.grabbed = true
	f.vel = Vector3.ZERO
	f.alert.visible = false
	if f.kind == "kingbombo":
		f.state = "held"
		game.hud.say("GOT HIM!", "Now THROW him (ACTION)!", 1.4)
		game.speak("Put me down!", "rocco")
	h.carry = true
	h.squash = Vector2(1.15, 0.85)
	game.sfx("item", -6)


func _throw(h: Hero) -> void:
	var f := holding
	holding = null
	h.carry = false
	if not is_instance_valid(f):
		return
	var dir := Vector3(h.facing.x, 0, h.facing.z).normalized()
	var king := f.kind == "kingbombo"
	f.vel = dir * (6.0 if king else 8.5) + Vector3(0, 6.5 if king else 5.5, 0) + Vector3(h.vel.x, 0, h.vel.z) * 0.3
	f.position = h.position + Vector3(0, h.height + 0.2, 0) + dir * 0.5
	f.state = "thrown"
	f.state_t = 0
	thrown.append({"foe": f, "king": king, "t": 0.0})
	h.squash = Vector2(0.85, 1.15)
	h.action_t = 0.3
	game.sfx("jump2")


func _grab_tail(h: Hero, b: Foe) -> void:
	tail = b
	b.grabbed = true
	b.state = "swung"
	b.state_t = 0
	b.alert.visible = false
	b.vel = Vector3.ZERO
	var d := b.position - h.position
	ang = atan2(d.x, d.z)
	spin_w = 2.0
	spin_dir = 1.0
	had_stick = false
	swing_t = 0.0
	pin = h.position
	h.swing = true
	game.hud.say("GOT HIS TAIL!", "Turn the stick round and round (or tap ACTION), then JUMP to throw!", 2.4)
	game.speak("Here we go-o-o!", "rocco")
	game.sfx("item")


func _release_tail(h: Hero) -> void:
	var b := tail
	tail = null
	h.swing = false
	arrow.visible = false
	if not is_instance_valid(b):
		return
	var out := Vector3(sin(ang), 0, cos(ang))
	b.vel = out * (6.0 + spin_w * 1.2) + Vector3(0, 6.0, 0)
	b.state = "flung"
	b.state_t = 0
	flung = b
	game.speak("So long, King Krag!" if b.kind == "kingkrag" else "Bye bye!", "rocco")
	game.sfx("jump2")
	game.fx.burst(b.position + Vector3(0, 1, 0), Color(1, 1, 1), 10, 4, 5)


# ------------------------------------------------------------------ every frame
func step(dt: float) -> void:
	var h: Hero = game.leader()
	for i in bombs.size():   # spike bombs come back after a while; touching one hurts
		var b: Dictionary = bombs[i]
		if b.cool > 0:
			b.cool -= dt
			if b.cool <= 0:
				b.node.visible = true
				game.fx.burst(b.pos + Vector3(0, 0.6, 0), Color(1, 1, 1), 8, 3, 4)
		elif tail == null and Vector2(h.position.x - b.pos.x, h.position.z - b.pos.z).length() < 0.95 and absf(h.position.y - b.pos.y) < 1.3:
			_blow_bomb(i)
			game.hurt_hero(b.pos)
	if held_by and (not is_instance_valid(held_by) or held_by.state != "hold"):
		held_by = null
	if holding:
		_step_holding(h)
	if tail:
		_step_tail(h, dt)
	_step_thrown(dt)
	if flung:
		_step_flung(dt)


func _step_holding(h: Hero) -> void:
	if not is_instance_valid(holding) or holding.dying >= 0:
		holding = null
		h.carry = false
		return
	if game.hurt_t > 1.2 or game.leader() != h or h.who == "mon":   # hurt or swapped: drop it
		var f := holding
		holding = null
		h.carry = false
		f.grabbed = false
		f.state = "walk" if f.kind == "kingbombo" else "idle"
		return
	holding.position = h.position + Vector3(0, h.height + 0.1, 0)
	holding.vel = Vector3.ZERO
	holding.facing = h.facing


func _step_tail(h: Hero, dt: float) -> void:
	var b := tail
	if not is_instance_valid(b) or b.dying >= 0:
		tail = null
		h.swing = false
		arrow.visible = false
		return
	swing_t += dt
	# turning the stick round and round spins him faster (either way round)
	var mv: Vector2 = game.move_vec
	if mv.length() > 0.5:
		var a := atan2(mv.x, mv.y)
		if had_stick:
			var da := wrapf(a - stick_ang, -PI, PI)
			if absf(da) > 0.01:
				spin_dir = signf(da) if absf(da) > 0.05 else spin_dir
				spin_w = minf(spin_w + absf(da) * 2.2, 13.0)
		stick_ang = a
		had_stick = true
	else:
		had_stick = false
	spin_w = maxf(1.5, spin_w - dt * 1.2)
	ang += spin_w * spin_dir * dt
	h.position = Vector3(pin.x, h.position.y, pin.z)
	h.vel.x = 0
	h.vel.z = 0
	var out := Vector3(sin(ang), 0, cos(ang))
	h.facing = out
	b.position = Vector3(pin.x, h.position.y + 0.3, pin.z) + out * SWING_R
	b.vel = Vector3.ZERO
	b.facing = -out
	arrow.visible = true
	arrow.position = b.position + out * 1.8 + Vector3(0, 0.2, 0)
	arrow.basis = Basis.looking_at(Vector3.UP, out) * Basis(Vector3.RIGHT, -PI / 2)
	if spin_w > 9:
		game.hud.say("SPIN! SPIN!", "JUMP to let go!", 0.3)
	if game.jump_pressed or swing_t > 6.0:
		_release_tail(h)


func _step_thrown(dt: float) -> void:
	for e in thrown.duplicate():
		var f: Foe = e.foe
		e.t += dt
		if not is_instance_valid(f) or f.dying >= 0:
			thrown.erase(e)
			continue
		f.vel.y -= Actor.GRAVITY * dt
		f.move(dt)
		f.spin_t = maxf(f.spin_t, 0.2)
		f.update_shadow()
		# hits another bad guy on the way
		if not e.king:
			for oo in game.foes:
				if not is_instance_valid(oo):
					continue
				var o: Foe = oo
				if o != f and o.dying < 0 and not o.grabbed and o.kind != "kingbombo" \
						and Vector2(o.position.x - f.position.x, o.position.z - f.position.z).length() < o.hw + f.hw + 0.6 \
						and f.position.y < o.position.y + o.height + 0.7 and f.position.y > o.position.y - 1.0:
					o.hurt(3, game)
					game.sfx("stomp")
					_land(e, true)
					break
		if not thrown.has(e):
			continue
		if f.on_floor or f.walls_hit or e.t > 3.0:
			_land(e, false)


func _land(e: Dictionary, hit_something: bool) -> void:
	thrown.erase(e)
	var f: Foe = e.foe
	f.grabbed = false
	f.vel = Vector3.ZERO
	if f.kind == "bomber":   # a walking bomb blows up where it lands
		f._explode(game)
		return
	if e.king:
		var top: Vector3 = level.summit_spot
		var on_top := f.position.y > top.y - 0.6 and Vector2(f.position.x - top.x, f.position.z - top.z).length() < 5.2
		if on_top:
			f.bombo_hit(game)
		else:
			f.state = "walk"   # off the summit: he'll jump back up (foes.gd)
		return
	game.fx.burst(f.position + Vector3(0, 0.4, 0), Color(1, 1, 1), 8, 3, 4)
	f.hurt(3, game)
	if f.dying < 0:
		f.state = "idle"


func _step_flung(dt: float) -> void:
	var b := flung
	if not is_instance_valid(b) or b.dying >= 0:
		flung = null
		return
	b.state_t += dt
	b.vel.y -= Actor.GRAVITY * dt
	b.move(dt)
	var ar: Rect2 = level.arena   # stays inside the castle walls
	b.position.x = clampf(b.position.x, ar.position.x + 1.4, ar.end.x - 0.4)
	b.position.z = clampf(b.position.z, ar.position.y + 1.4, ar.end.y - 0.4)
	b.spin_t = maxf(b.spin_t, 0.2)
	b.update_shadow()
	var i := bomb_near(b.position, b.hw + 1.5)   # forgiving: he's big and it's a kid's game
	if i >= 0:   # BOOM! into a spike bomb
		_blow_bomb(i)
		var dmg := ceili(b.max_hp / 3.0) if b.kind == "kingkrag" else ceili(b.max_hp / 4.0)
		b.safe_t = 0
		b.hurt(dmg, game)
		game.hud.say("BOOM!", "Into the spike bomb!" if b.hp > 0 else "", 1.6)
		_end_flung(b)
		return
	if b.on_floor and b.state_t > 0.15:   # skids along the floor (into a bomb, with luck)
		var fl := Vector2(b.vel.x, b.vel.z)
		fl = fl.move_toward(Vector2.ZERO, 11.0 * dt)
		b.vel.x = fl.x
		b.vel.z = fl.y
		if fl.length() > 1.2 and b.state_t < 3.0:
			if int(b.state_t * 20) % 3 == 0:
				game.fx.burst(b.position, Color(0.9, 0.9, 0.85), 3, 2, 3, 0.8)
			return
	if (b.on_floor and b.state_t > 0.15) or b.state_t > 3.0:
		if bombs.is_empty() and b.state_t < 3.0:   # no bombs here: a bonus bonk on landing
			b.safe_t = 0
			b.hurt(maxi(2, b.max_hp / 8), game)
		game.fx.shake = 0.5
		game.sfx("bonk")
		_end_flung(b)


func _end_flung(b: Foe) -> void:
	flung = null
	b.grabbed = false
	b.vel = Vector3.ZERO
	if b.dying < 0 and b.state != "heap":
		b.state = "tired"
		b.state_t = 0
