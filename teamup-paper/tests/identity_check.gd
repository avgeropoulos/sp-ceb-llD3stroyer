extends SceneTree
## The game's own TEAMWORK features (identity.gd, teampower.gd, teamup_attack.gd):
##  * TEAM POWER spots only open for the right teammate, and their chests pay coins + TEAM CRYSTALS
##  * the TEAM SHRINE: 3 pedestals lit in order by hero 1, hero 2, partner -> a TEAM STAR (TEAM HEART shard)
##  * the TEAM meter fills from SWAPs, TEAM UP! plays its poster and hits every bad guy nearby (and a boss hard)
##  * QUEST BOARD: progress, claiming, new quests on a new day (faked date)
##  * DAILY CHEST: once a day, streaks
##  * HOME LEVEL goes up, BUDDY BOOK befriends, everything is saved and loads back
##  * at home: the TEAM PLAZA and its screens
## Run: godot --headless --fixed-fps 60 --path . --script res://tests/identity_check.gd [-- toy]

var g
var f := 0
var step := 0
var failed := ""
var coins0 := 0
var foe: Foe
var boss_hp0 := 0


func _initialize() -> void:
	Look.loaded = true
	Look.toy = OS.get_cmdline_user_args().has("toy")
	Cutscene.enabled = false
	OpenPlay.select_enabled = false
	Identity.today_override = "2026-11-02"
	Saves.prefix = "user://test_identity"
	_clean()
	# an old save (no "identity" at all): it must load and start from zero
	Saves.write(1, {"v": 2, "in_level": false, "cleared_w": {"meadow": 1}, "cleared": 1, "coins": 10,
		"mon": "water", "team": ["rocco", "blocky"], "stars_w": {"meadow": ["tower"]}})
	Boot.place = "level"
	Boot.level = "meadow"
	Boot.slot = 1
	root.add_child(load("res://main.tscn").instantiate())


func _clean() -> void:
	for e in ["", ".bak", ".tmp"]:
		if FileAccess.file_exists(Saves.path(1) + e):
			DirAccess.remove_absolute(ProjectSettings.globalize_path(Saves.path(1) + e))


func check(ok: bool, why: String) -> void:
	if not ok and failed == "":
		failed = "step %d: %s" % [step, why]


func spot(kind: String) -> Dictionary:
	for sp in g.team_power.spots:
		if sp.kind == kind:
			return sp
	return {}


func put(h: Hero, p: Vector3) -> void:
	h.position = p + Vector3(0, 0.02, 0)
	h.vel = Vector3.ZERO


func lead(slot: String) -> Hero:
	if slot == "mon":
		g.control = "mon"
	else:
		g.control = "human"
		g.human = slot
	return g.leader()


func _process(_dt: float) -> bool:
	f += 1
	g = Boot.current
	if not is_instance_valid(g) or not g.is_inside_tree():
		return false
	match step:
		0:
			if g.state == "play" and g.slot == 1 and g.team_power != null:
				g.invincible = true
				var id: Identity = g.identity
				check(id.loaded and id.crystals == 0 and id.buddies.has("mon_water"), "an old save should load with no crystals (and the partner as a buddy)")
				check(g.level.team_spots.size() >= 4, "every open world should have 4+ TEAM POWER spots (got %d)" % g.level.team_spots.size())
				check(not g.level.team_shrine.is_empty() and not g.open_play.objective("team").is_empty(), "every open world has a TEAM SHRINE star")
				# the same world builds the same spots (the rest of the world isn't moved either)
				var lv2 := Level.new()
				lv2.generate("meadow")
				check(lv2.team_spots.size() == g.level.team_spots.size() and (lv2.team_spots.is_empty() or lv2.team_spots[0].base == g.level.team_spots[0].base), "spots should be the same every visit (%d vs %d)" % [lv2.team_spots.size(), g.level.team_spots.size()])
				lv2.free()
				# ---- MELT: the team has no FIRE partner (water), so it can't be done
				var sp := spot("melt")
				check(not sp.is_empty(), "a MELT THE ICE spot")
				if not sp.is_empty():
					var c: Vector3i = sp.barrier[0]
					check(g.level.get_cell(c.x, c.y, c.z) == Level.ICE and g.level.locked.has(c), "the ice door is ice (and can't be mined)")
					check(g.team_power.need_text("melt").contains("Bring"), "the pillar should say to bring a FIRE partner (%s)" % g.team_power.need_text("melt"))
					var l := lead("mon")
					put(l, sp.use)
					g._action(l)
					check(not sp.open and g.level.get_cell(c.x, c.y, c.z) == Level.ICE, "a WATER partner can't melt ice")
					# a FIRE partner can
					g.set_partner("fire")
					g.team_power.sync()
					check(g.team_power.need_text("melt").contains("SWAP"), "with a fire partner the pillar says SWAP")
					l = lead("rocco")
					put(l, sp.use)
					g._action(l)
					check(not sp.open, "Rocco can't melt ice")
					l = lead("mon")
					put(l, sp.use)
					g._action(l)
					check(sp.open and g.level.get_cell(c.x, c.y, c.z) == Level.AIR and not g.level.locked.has(c), "FIRE should melt the ice door")
					check("melt" in id.spots_done("meadow"), "the opened spot should be remembered")
					coins0 = g.coins
					put(l, sp.chest)
					g.team_power.step(1.0 / 60)
					check(sp.looted and g.coins >= coins0 + 15 and id.crystals >= 3, "the chest should give coins and TEAM CRYSTALS (coins %d, crystals %d)" % [g.coins - coins0, id.crystals])
				# ---- SMASH: Blocky can, the partner can't
				sp = spot("smash")
				if not sp.is_empty():
					var l := lead("mon")
					put(l, sp.use)
					g._action(l)
					check(not sp.open, "a fire partner can't smash rock")
					l = lead("blocky")
					put(l, sp.use)
					g._action(l)
					check(sp.open, "BLOCKY should smash the cracked rock")
				# ---- GROW: a beanstalk staircase needs WATER/GRASS
				sp = spot("grow")
				if not sp.is_empty():
					var l := lead("mon")
					put(l, sp.use)
					g._action(l)
					check(not sp.open, "fire can't grow a beanstalk")
					g.set_partner("water")
					l = lead("mon")
					put(l, sp.use)
					g._action(l)
					check(sp.open, "WATER should grow the beanstalk")
				# ---- KICK: Rocco only (no door: you have to get up there)
				sp = spot("kick")
				if not sp.is_empty():
					check(g.team_power.can_do(g.heroes.rocco, "kick") and not g.team_power.can_do(g.heroes.blocky, "kick"), "only Rocco can wall kick")
				step = 1
				f = 0
			elif f > 60 * 40:
				check(false, "the world never started (state %s)" % g.state)
				step = 9
		1:
			if f == 90:   # the grown beanstalk is there (it grows step by step)
				var sp := spot("grow")
				if not sp.is_empty():
					var top: Vector3i = sp.stairs[sp.stairs.size() - 1]
					check(g.level.get_cell(top.x, top.y, top.z) in [Level.LEAVES, Level.LOG], "the beanstalk should reach the top")
				# ---- the TEAM SHRINE: in order, each with the right teammate
				var tp: TeamPower = g.team_power
				var peds: Array = tp.shrine.pedestals
				var l := lead("blocky")
				put(l, peds[0])
				g._action(l)
				check(tp.shrine.lit == 0, "pedestal 1 wants hero 1, not hero 2")
				l = lead("rocco")
				put(l, peds[1])
				g._action(l)
				check(tp.shrine.lit == 0, "the pedestals go in order")
				put(l, peds[0])
				g._action(l)
				check(tp.shrine.lit == 1, "hero 1 lights pedestal 1")
				l = lead("blocky")
				put(l, peds[1])
				g._action(l)
				l = lead("mon")
				put(l, peds[2])
				g._action(l)
				check(tp.shrine.lit == 3 and g.open_play.stars.has("team"), "all three lit -> a TEAM STAR")
				if g.open_play.stars.has("team"):
					put(l, g.open_play.stars["team"].position - Vector3(0, 0.8, 0))
				step = 2
				f = 0
		2:
			if f == 10:
				var id: Identity = g.identity
				check(g.open_play.got.has("team"), "the TEAM STAR should be collected")
				check(id.shards() == 1, "one TEAM HEART shard (got %d)" % id.shards())
				# ---- the TEAM meter: SWAPs fill it (with a cool-down so mashing doesn't)
				id.meter = 0.0
				lead("rocco")
				g.do_swap()
				var m1 := id.meter
				g.do_swap()
				check(m1 > 0.05 and is_equal_approx(id.meter, m1), "a SWAP fills the meter, mashing doesn't (%.2f, %.2f)" % [m1, id.meter])
				# ---- TEAM UP!: a bad guy next to us and the boss too
				id.meter = 1.0
				lead("rocco")
				var l: Hero = g.leader()
				put(l, g.level.start_pos)
				foe = null
				for fo in g.foes:
					if is_instance_valid(fo) and fo.dying < 0 and not fo.is_boss and fo.kind != "bomber":
						foe = fo
						break
				check(foe != null, "a bad guy to hit")
				if foe:
					foe.position = l.position + Vector3(3, 0, 0)
				if g.boss and is_instance_valid(g.boss):
					g.boss.position = l.position + Vector3(-5, 0, 2)
					g.boss_on = true
					boss_hp0 = g.boss.hp
				g.hud._layout()
				check(g.hud.buttons.has("teamup"), "a full meter shows the TEAM UP! button")
				g.hud.pressed_now["teamup"] = true
				step = 3
				f = 0
		3:
			if f == 3:
				check(g.state == "teamup", "the TEAM UP! button should start the attack (state %s)" % g.state)
				check(g.identity.meter == 0.0, "TEAM UP! uses the meter")
				check(g.heroes.rocco.visible and g.heroes.blocky.visible and g.heroes.mon.visible, "all three heroes appear")
				check(TeamUpAttack.combo_name("rocco", "blocky", "fire") == "BLOCK-BUSTER BLAZE", "Rocco + Blocky + fire = BLOCK-BUSTER BLAZE")
				check(g.identity.teamup.combo == TeamUpAttack.combo_name(g.heroes.rocco.who, g.heroes.blocky.who, g.heroes.mon.line), "named by the team")
			if f == 90:
				check(g.state == "teamup" and g.identity.teamup.t > TeamUpAttack.APPEAR and g.identity.teamup.t < TeamUpAttack.POSTER, "the comic poster shows mid-way")
			if g.state == "play" and f > 10:
				check(foe == null or foe.dying >= 0, "TEAM UP! should beat the bad guy next to us")
				if g.boss and is_instance_valid(g.boss) and boss_hp0 > 0:
					check(g.boss.hp <= boss_hp0 - 4, "TEAM UP! should hit the boss hard (%d -> %d)" % [boss_hp0, g.boss.hp])
					g.boss_on = false
				check(not (g.heroes.rocco.visible and g.heroes.blocky.visible), "back to one human out afterwards")
				step = 4
				f = 0
			elif f > 60 * 8:
				check(false, "TEAM UP! never finished")
				step = 9
		4:
			var id: Identity = g.identity
			# ---- quests
			check(id.quests.size() == 3 and id.quest_day == "2026-11-02", "3 quests for today")
			var kinds := {}
			for q in id.quests:
				kinds[q.kind] = true
			check(kinds.size() == 3, "3 different quests")
			id.quests[0] = {"kind": "stomp", "goal": 2, "n": 0, "coins": 40, "crystals": 4}
			check(not id.claim(0), "can't claim an unfinished quest")
			id.on_foe_beaten(foe if foe else g.foes[0])
			id.progress("stomp")
			check(id.quests[0].n == 2, "quest progress counts")
			var c0: int = g.coins
			var k0 := id.crystals
			check(id.claim(0) and g.coins == c0 + 40 and id.crystals == k0 + 4 and id.quests_done == 1, "claiming pays out")
			check(id.quests[0].n == 0, "a new quest takes its place")
			var before := str(id.quests)
			id.refresh_quests()
			check(str(id.quests) == before, "the same day keeps the same quests")
			Identity.today_override = "2026-11-03"
			id.refresh_quests()
			check(id.quest_day == "2026-11-03" and str(id.quests) != before, "a new day brings new quests")
			# ---- the daily chest + streak
			Identity.today_override = "2026-11-02"
			id.chest_day = ""
			id.streak = 0
			check(id.open_daily_chest() and id.streak == 1, "the daily chest opens")
			check(not id.open_daily_chest(), "only once a day")
			Identity.today_override = "2026-11-03"
			check(id.open_daily_chest() and id.streak == 2, "the next day: streak 2")
			Identity.today_override = "2026-11-06"
			check(id.open_daily_chest() and id.streak == 1, "missing days starts the streak again")
			check(Identity.day_before("2026-03-01") == "2026-02-28", "yesterday works across months")
			# ---- home level
			var lv0 := id.home_level()
			id.quests_done += 20
			check(id.home_level() > lv0, "quests raise the HOME LEVEL")
			check(id.home_text().begins_with("HOME Lv"), "home level text")
			# ---- the buddy book
			check(foe == null or foe.kind in id.buddies, "a beaten bad guy becomes a buddy")
			var b0 := id.buddies.size()
			id.befriend("biggrumbler")
			id.befriend("biggrumbler")
			check(id.buddies.size() == b0 + 1 and "biggrumbler" in id.buddies, "befriend once each")
			var k1 := id.crystals
			var paid0 := id.book_paid
			for k in Identity.BOOK_FOES:
				id.befriend(k)
			check(id.crystals >= k1 + 5 and id.book_paid > paid0, "every 5 buddies pays crystals")
			id.befriend("not_a_real_foe")
			check(not "not_a_real_foe" in id.buddies, "only book entries")
			# ---- saved and loaded back
			g.save_game()
			var d := Saves.read(1)
			var sd: Dictionary = d.get("identity", {})
			check(int(sd.get("crystals", -1)) == id.crystals and "biggrumbler" in sd.get("buddies", []), "identity is saved")
			check("melt" in sd.get("spots", {}).get("meadow", []), "opened spots are saved")
			var id2 := Identity.new()
			id2.load_data(JSON.parse_string(JSON.stringify(sd)))
			check(id2.crystals == id.crystals and id2.streak == id.streak and id2.quests_done == id.quests_done and id2.buddies.size() == id.buddies.size(), "identity loads back the same")
			id2.free()
			check("team" in d.get("stars_w", {}).get("meadow", []), "the TEAM STAR is saved")
			# ---- off home: the TEAM PLAZA
			g.go("home", 1)
			step = 5
			f = 0
		5:
			if g.state == "home" and g.identity.plaza != null:
				var id: Identity = g.identity
				check(id.crystals > 0 and "melt" in id.spots_done("meadow"), "progress comes home")
				check(id.heart_label.text.contains("1/"), "the TEAM HEART shows 1 shard (%s)" % id.heart_label.text)
				var l: Hero = g.leader()
				var bp := id.spot_pos("board")
				l.position = bp + Vector3(0, 0.05, 1.2)
				step = 6
				f = 0
			elif f > 60 * 40:
				check(false, "home never loaded (state %s)" % g.state)
				step = 9
		6:
			if f == 5:
				var id: Identity = g.identity
				check(id.near == "board", "standing by the QUEST BOARD (near '%s')" % id.near)
				check(g.hud.buttons.has("plaza"), "the QUESTS button shows by the board")
				g.hud.pressed_now["plaza"] = true
			if f == 10:
				check(g.state == "teampanel" and g.identity.panel == "board", "QUESTS opens the board (state %s)" % g.state)
				g.identity.panel_t = 1.0
				g.identity.panel_step(0.1, false, false, true)
				check(g.state == "home", "closing the board goes back home")
				g.identity.open_panel("book")
				check(g.state == "teampanel", "the BUDDY BOOK opens")
				g.identity.close_panel()
				g.identity.open_panel("heart")
				g.identity.close_panel()
				Identity.today_override = "2026-11-10"
				g.identity.open_panel("chest")
				g.identity.panel_t = 1.0
				var c0: int = g.coins
				g.identity.panel_step(0.1, true, false, false)
				check(g.coins > c0, "OPEN! in the chest screen opens the daily chest")
				g.identity.close_panel()
				step = 9
		9:
			_clean()
			print("TEST FAILED: " + failed if failed != "" else "TEST PASSED")
			quit(1 if failed != "" else 0)
			return true
	if failed != "":
		_clean()
		print("TEST FAILED: " + failed)
		quit(1)
		return true
	return false
