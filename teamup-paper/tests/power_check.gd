extends SceneTree
## Power-ups: items lie around the open world, ? blocks can give them, and every power
## does its thing (fireballs, freezing into ice blocks, star beats bad guys, wing flight,
## mushroom extra hit, mini size, metal can't be hurt, propeller spin, life mushroom).
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/power_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""


func _initialize() -> void:
	Look.loaded = true
	OpenPlay.select_enabled = false   # tests/quest_check.gd covers the star select
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Boot.place = "level"
	Boot.level = "meadow"
	root.add_child(load("res://main.tscn").instantiate())


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]
		print("FAIL: " + why)


func L() -> Hero:
	return g.leader()


## Runs the hero's own update (real physics) for n frames.
func run(n: int) -> void:
	for i in n:
		g._update_leader(L(), 1.0 / 60)
		g.powers.step(1.0 / 60)
		g.jump_pressed = false


## A fresh grumbler right next to the hero.
func dummy(off: Vector3) -> Foe:
	var d := Foe.new()
	g.level.add_child(d)
	d.init(g.level, "grumbler", L().position + off)
	d.set_meta("spawn", -1)
	g.foes.append(d)
	return d


func flat_spot() -> Vector3:
	return g.level.start_pos + Vector3(2, 0, 2)


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree() or g.state != "play":
		return false
	if step == 0:
		g.process_mode = Node.PROCESS_MODE_DISABLED   # the test moves everyone by hand
		var p: Powers = g.powers
		check(p != null, "an open world should have power-ups")
		check(p.items.size() >= 5, "items should lie around the world (got %d)" % p.items.size())
		# ? blocks: some give power-ups
		var got := 0
		for i in 40:
			var c := Vector3i(int(flat_spot().x) + 5, 12, int(flat_spot().z))
			g.level.set_cell(c.x, c.y, c.z, Level.MYSTERY)
			var before: int = p.items.size()
			g._open_mystery(c)
			if p.items.size() > before:
				got += 1
		check(got > 8, "? blocks should often give power-ups (%d of 40)" % got)
		for it in p.items.duplicate():   # clear them away for the tests below
			it.node.queue_free()
		p.items.clear()
		# walking into an item gives it
		L().position = flat_spot()
		p.spawn_item("fire", L().position + Vector3(0.4, 0.05, 0))
		run(2)
		check(p.power == "fire", "touching a FIRE FLOWER should give it (power '%s')" % p.power)
		# FIRE: ACTION throws a fireball
		var shots: int = g.shots.size()
		L().action_t = 0
		g._action(L())
		check(g.shots.size() == shots + 1 and g.shots.back().kind == "fireball", "FIRE FLOWER should throw a fireball")
		# getting hurt with a flower: lose the flower, keep the hearts
		var hearts: int = g.hearts
		g.hurt_t = 0
		g.hurt_hero(L().position + Vector3(1, 0, 0))
		check(g.hearts == hearts and p.power == "", "a hit should take the flower, not a heart")
		# ICE: the ice ball freezes a bad guy into a block you can stand on
		g.hurt_t = 0
		L().position = flat_spot()
		L().facing = Vector3.RIGHT
		p.give("ice")
		var foe := dummy(Vector3(2.5, 0, 0))
		L().action_t = 0
		g._action(L())
		check(p.balls.size() == 1, "ICE FLOWER should throw an ice ball")
		for i in 60:
			p.step(1.0 / 60)
		check(p.is_frozen(foe), "the ice ball should freeze the bad guy")
		var fc := Vector3i(floori(foe.position.x), floori(foe.position.y + 0.1), floori(foe.position.z))
		check(g.level.get_cell(fc.x, fc.y, fc.z) == Level.ICE, "a frozen bad guy is an ice block")
		L().position = Vector3(fc.x + 0.5, fc.y + 2.5, fc.z + 0.5)
		L().vel = Vector3.ZERO
		run(50)
		check(L().on_floor and absf(L().position.y - (fc.y + 1)) < 0.05, "you can stand on a frozen bad guy (y %.2f)" % L().position.y)
		for i in int(Powers.FREEZE_TIME * 60) + 5:
			p.step(1.0 / 60)
		check(not p.is_frozen(foe) and g.level.get_cell(fc.x, fc.y, fc.z) == Level.AIR, "the ice should melt")
		foe.remove()
		# SUPER STAR: touching a bad guy beats it; nothing hurts
		L().position = flat_spot()
		L().vel = Vector3.ZERO
		run(20)
		p.give("star")
		check(p.speed_mult() > 1.2, "SUPER STAR should be faster")
		var f2 := dummy(Vector3(0.3, 0, 0))
		var beaten: int = g.beaten
		g._update_foes(1.0 / 60)
		check(g.beaten == beaten + 1, "SUPER STAR should beat a bad guy on touch")
		hearts = g.hearts
		g.hurt_t = 0
		g.hurt_hero(L().position + Vector3(1, 0, 0))
		check(g.hearts == hearts, "SUPER STAR: nothing hurts")
		p.power_t = 0.01
		p.step(0.02)
		check(p.power == "", "the star should run out")
		# SUPER MUSHROOM: big, one free hit, smashes blocks with a head bump
		p.give("mushroom")
		run(30)
		check(L().scale.x > 1.3, "SUPER MUSHROOM should make you big (scale %.2f)" % L().scale.x)
		check(p.block_power() >= 3, "SUPER MUSHROOM smashes blocks")
		hearts = g.hearts
		g.hurt_t = 0
		g.hurt_hero(L().position + Vector3(1, 0, 0))
		check(g.hearts == hearts and p.power == "", "the mushroom should take one hit for you")
		run(30)
		check(L().scale.x < 1.05, "losing the mushroom shrinks you back")
		# MINI: tiny, jumps higher
		g.hurt_t = 0
		var h0: float = L().height
		p.give("mini")
		run(30)
		check(L().scale.x < 0.65 and L().height < h0 * 0.7, "MINI MUSHROOM should make you tiny")
		check(p.jump_mult() > 1.2, "MINI jumps higher")
		p.lose()
		check(absf(L().height - h0) < 0.01, "back to normal size after MINI")
		# WING CAP: hold JUMP to fly up
		L().position = flat_spot()
		L().vel = Vector3.ZERO
		run(10)
		p.give("wing")
		var y0: float = L().position.y
		g.jump_pressed = true
		g.jump_held = true
		run(120)
		check(L().position.y > y0 + 6, "WING CAP should fly up while JUMP is held (rose %.1f)" % (L().position.y - y0))
		g.jump_held = false
		p.lose()
		run(200)
		# PROPELLER: JUMP in the air = big spin up
		p.give("propeller")
		g.jump_pressed = true
		g.jump_held = true
		run(1)
		run(12)
		var before_y: float = L().position.y
		g.jump_pressed = true
		run(1)
		check(L().vel.y > 14, "PROPELLER: JUMP in the air should spin you up (vel %.1f)" % L().vel.y)
		g.jump_held = false
		p.lose()
		run(200)
		# METAL CAP: nothing hurts, bad guys are beaten by touch
		p.give("metal")
		hearts = g.hearts
		g.hurt_t = 0
		g.hurt_hero(L().position + Vector3(1, 0, 0))
		check(g.hearts == hearts, "METAL CAP: nothing hurts")
		var f3 := dummy(Vector3(0.3, 0, 0))
		beaten = g.beaten
		g._update_foes(1.0 / 60)
		check(g.beaten == beaten + 1, "METAL CAP should flatten bad guys it touches")
		p.lose()
		# LIFE MUSHROOM: all hearts back
		g.hearts = 1
		p.give("life")
		check(g.hearts == g.MAX_HEARTS and p.power == "", "LIFE MUSHROOM should refill the hearts")
		# SWAP: the power moves to whoever you steer
		p.give("fire")
		run(2)
		g.do_swap()
		run(2)
		check(p.worn_by == L() and L().who == "blocky", "the power should go to the hero you swap to")
		L().action_t = 0
		shots = g.shots.size()
		g._action(L())
		check(g.shots.size() == shots + 1, "Blocky can throw fireballs too")
		step = 1
	elif step == 1:
		print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
		quit(1 if failed != "" else 0)
		return true
	if f > 60 * 60:
		print("TEST FAILED: timed out")
		quit(1)
		return true
	return false
