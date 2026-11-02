class_name Identity
extends Node3D
## What makes Team Up! its own game: TEAMWORK, and a reason to come back every day.
##  * TEAM CRYSTALS (a new money, from TEAM POWER chests, quests, the daily chest, the BUDDY BOOK)
##  * the TEAM meter: SWAPs and swap-combo stomps fill it; full -> TEAM UP! (teamup_attack.gd)
##  * QUEST BOARD at home: 3 quests, new ones every day (and when you claim one)
##  * DAILY CHEST at home: once per real day, a streak makes it bigger
##  * HOME LEVEL from stars, quests, chests and buddies: unlocks the TEAM PLAZA statue, fountain, TEAM HQ...
##  * BUDDY BOOK: every kind of bad guy you beat (and every partner kind) becomes a buddy
##  * the TEAM HEART: King Krag cracked it into shards (the TEAM STARS from each world's TEAM SHRINE)
## Saved as "identity" in the save file (old saves just start at zero).

static var today_override := ""   # tests fake the date ("2026-11-02")

const BOOK_FOES := ["grumbler", "buzzer", "hopper", "bomber", "skelly", "spiky", "penguin", "roller", "boohoo",
	"scorpion", "mummy", "cactusman", "chompy", "bat", "biggrumbler"]
const FOE_NAMES := {"grumbler": "GRUMBLER", "buzzer": "BUZZER", "hopper": "HOPPER", "bomber": "BOMBER", "skelly": "SKELLY",
	"spiky": "SPIKY", "penguin": "PENGO", "roller": "ROLLER", "boohoo": "BOOHOO", "scorpion": "PINCHY", "mummy": "MUMMO",
	"cactusman": "CACTO", "chompy": "CHOMPY", "bat": "FLAPPY", "biggrumbler": "BIG GRUMBLER"}
const FACTS := {"grumbler": "Grumbles at breakfast. And lunch. And naps.", "buzzer": "Can't stop humming its favourite song.",
	"hopper": "Has never once walked. Not even to the bathroom.", "bomber": "Hates birthday candles. Too scary!",
	"skelly": "Collects bones. Mostly its own.", "spiky": "Gives the pokiest hugs in the world.",
	"penguin": "Thinks sliding on its belly is the only way to go.", "roller": "Gets dizzy, but never stops rolling.",
	"boohoo": "Cries when you look at it. Also when you don't.", "scorpion": "Waves its claws to say hello. Promise!",
	"mummy": "Is wrapped in 300 metres of toilet paper.", "cactusman": "Would love a hug. Nobody will hug it.",
	"chompy": "Bites everything. Even the air.", "bat": "Sleeps upside down and dreams the right way up.",
	"biggrumbler": "The grumpiest. Secretly loves kittens.",
	"water": "Splashes everyone. On purpose.", "grass": "Gets taller when it's happy.", "fire": "Toasts marshmallows with its tail.",
	"spark": "Makes your hair stand up when it's excited.", "rock": "Rocks to music. Rock music.", "ghost": "Says BOO and then giggles.",
	"bird": "Sings at sunrise. Every. Single. Sunrise.", "mind": "Knows what you're thinking: snacks.", "ice": "Has never been warm and likes it.",
	"bug": "Has 6 legs and still trips over.", "dragon": "Breathes tiny friendly flames.", "fairy": "Sprinkles glitter wherever it goes."}
## HOME LEVEL thresholds (home XP) and what each new level adds to the TEAM PLAZA.
const HOME_XP := [0, 15, 40, 80, 130, 200, 300, 450]
const UNLOCKS := ["", "QUEST BOARD", "TEAM STATUE", "FOUNTAIN", "TEAM HQ", "RAINBOW ARCH", "CRYSTAL TREE", "GOLDEN FLAGS"]
const QUEST_KINDS := ["stomp", "stars", "coins", "spots", "swap", "teamup", "chest"]
## The TEAM PLAZA (south of the start plot): quest board, daily chest, team heart, buddy book.
const PLAZA_Z := 17.0
const SPOTS := {"board": Vector2(2.5, PLAZA_Z), "chest": Vector2(7.0, PLAZA_Z), "heart": Vector2(12.0, PLAZA_Z + 1.0),
	"book": Vector2(17.0, PLAZA_Z)}
const BUTTON_LABEL := {"board": "QUESTS", "chest": "CHEST", "heart": "HEART", "book": "BUDDIES"}
const BUTTON_COLOR := {"board": Color("c47a2c"), "chest": Color("ffb020"), "heart": Color("ff5fa8"), "book": Color("3fae6a")}

var game
var crystals := 0
var meter := 0.0             # the TEAM meter, 0..1
var swap_t := 99.0           # time since the last SWAP (a stomp soon after = SWAP COMBO)
var swap_cool := 0.0
var quests: Array = []       # {kind, goal, n, coins, crystals}
var quest_day := ""
var quests_done := 0
var chest_day := ""
var streak := 0
var chests := 0
var buddies: Array = []      # "grumbler", "mon_water"...
var book_paid := 0           # how many 5-buddy rewards were given
var spots := {}              # world -> [team spot ids opened]
var golden := false          # the TEAM HEART is whole: golden team aura
var home_lv_seen := 1
var heart_seen := false      # the finale was played
var nudge_t := 1.0
var heart_told := false
var loaded := false          # load_data ran (so saving won't wipe the file's progress)
# screens (state "teampanel")
var panel := ""              # board / chest / book / heart
var panel_back := "home"
var panel_t := 0.0
var book_page := 0
var near := ""               # which plaza thing you're standing by
var plaza: Node3D
var plaza_extra: Node3D
var walkers: Array = []
var heart_mesh: Node3D
var heart_label: Label3D
var auras: Array = []
var teamup: TeamUpAttack


func _init() -> void:
	name = "Identity"


func setup(g) -> void:
	game = g
	teamup = TeamUpAttack.new()
	add_child(teamup)
	teamup.setup(g, self)


# ------------------------------------------------------------------ saving
func data() -> Dictionary:
	return {"crystals": crystals, "meter": meter, "quests": quests, "quest_day": quest_day, "quests_done": quests_done,
		"chest_day": chest_day, "streak": streak, "chests": chests, "buddies": buddies, "book_paid": book_paid,
		"spots": spots, "golden": golden, "home_lv": home_lv_seen, "heart_seen": heart_seen}


## Into the save (a file that was never loaded here keeps what it had).
func save_into(d: Dictionary, slot: int) -> void:
	if loaded:
		d["identity"] = data()
	elif slot > 0:
		var old: Dictionary = Saves.read(slot)
		if old.has("identity"):
			d["identity"] = old.identity


func load_data(d: Dictionary) -> void:
	loaded = true
	crystals = int(d.get("crystals", 0))
	meter = clampf(float(d.get("meter", 0.0)), 0, 1)
	quests = []
	for q in d.get("quests", []):
		if q is Dictionary and q.has("kind"):
			quests.append({"kind": String(q.kind), "goal": int(q.get("goal", 1)), "n": int(q.get("n", 0)),
				"coins": int(q.get("coins", 30)), "crystals": int(q.get("crystals", 3))})
	quest_day = String(d.get("quest_day", ""))
	quests_done = int(d.get("quests_done", 0))
	chest_day = String(d.get("chest_day", ""))
	streak = int(d.get("streak", 0))
	chests = int(d.get("chests", 0))
	buddies = []
	for b in d.get("buddies", []):
		buddies.append(String(b))
	book_paid = int(d.get("book_paid", 0))
	spots = {}
	var sp: Dictionary = d.get("spots", {})
	for w in sp:
		spots[String(w)] = Array(sp[w]).map(func(x): return String(x))
	golden = bool(d.get("golden", false))
	home_lv_seen = int(d.get("home_lv", 1))
	heart_seen = bool(d.get("heart_seen", false))
	refresh_quests()
	if game and game.heroes.has("mon"):
		befriend("mon_" + String(game.heroes.mon.line), false)
	if game and game.team_power:
		game.team_power.sync()
	if plaza and game.level:
		build_plaza(game.level)
	home_lv_seen = maxi(home_lv_seen, 1)
	apply_aura()


# ------------------------------------------------------------------ the date
static func today() -> String:
	if today_override != "":
		return today_override
	return Time.get_date_string_from_system()


static func day_before(day: String) -> String:
	var u := Time.get_unix_time_from_datetime_string(day + "T12:00:00")
	return Time.get_date_string_from_unix_time(u - 86400)


# ------------------------------------------------------------------ hooks from the game
func add_crystals(n: int) -> void:
	crystals += n


func add_meter(k: float) -> void:
	var was := meter
	meter = clampf(meter + k, 0, 1)
	if was < 1.0 and meter >= 1.0 and game and game.hud:
		game.hud.say("TEAM UP! READY!", "Tap the glowing TEAM UP! button", 2.0)
		game.sfx("alert", -4)


func step(dt: float) -> void:
	swap_t += dt
	swap_cool = maxf(0, swap_cool - dt)
	if game.state == "home" and plaza:
		_step_plaza(dt)


func on_swap() -> void:
	swap_t = 0.0
	if swap_cool <= 0 and game.state == "play":
		swap_cool = 1.5
		add_meter(0.12)
	progress("swap")


func on_foe_beaten(f) -> void:
	var k := 0.06
	if swap_t < 3.0 and game.state == "play":   # beaten right after a SWAP: a SWAP COMBO
		k += 0.1
		game.fx.text(f.position + Vector3(0, f.height + 1.1, 0.4), "SWAP COMBO!", Color("ff9ae0"), 40)
	add_meter(k)
	progress("stomp")
	if f.kind in BOOK_FOES:
		befriend(f.kind)


func on_coin() -> void:
	progress("coins")


func on_star(_world: String, id: String) -> void:
	progress("stars")
	if id == "team" and game.hud:
		game.hud.say("TEAM HEART SHARD!", "%d of %d shards. Bring them home to the TEAM HEART!" % [shards(), Worlds.ORDER.size()], 3.0)


func on_team_spot(world: String, id: String) -> void:
	var l: Array = spots.get(world, [])
	if not id in l:
		l.append(id)
	spots[world] = l
	add_meter(0.2)
	progress("spots")


func on_chest(_world: String, _id: String) -> void:
	if game:
		game.save_game(false)


func on_event(what: String) -> void:
	progress(what)


func spots_done(world: String) -> Array:
	return spots.get(world, [])


func spots_total() -> int:
	var n := 0
	for w in spots:
		n += spots[w].size()
	return n


# ------------------------------------------------------------------ quests
func refresh_quests() -> void:
	var d := today()
	if quest_day != d or quests.size() < 3:
		if quest_day != d:
			quests = []
		quest_day = d
		while quests.size() < 3:
			quests.append(_new_quest(quests.size()))


func _new_quest(salt: int) -> Dictionary:
	var r := RandomNumberGenerator.new()
	r.seed = hash(quest_day + ":" + str(quests_done) + ":" + str(salt))
	var have := []
	for q in quests:
		have.append(q.kind)
	var kinds := []
	for k in QUEST_KINDS:
		if not k in have:
			kinds.append(k)
	var kind: String = kinds[r.randi() % kinds.size()]
	var goal := 1
	match kind:
		"stomp": goal = [8, 12, 16][r.randi() % 3]
		"stars": goal = [1, 2, 3][r.randi() % 3]
		"coins": goal = [30, 50, 80][r.randi() % 3]
		"spots": goal = [1, 2][r.randi() % 2]
		"swap": goal = [8, 15][r.randi() % 2]
		"teamup", "chest": goal = 1
	return {"kind": kind, "goal": goal, "n": 0, "coins": 20 + r.randi_range(1, 4) * 10, "crystals": r.randi_range(3, 6)}


static func quest_text(q: Dictionary) -> String:
	var g: int = q.goal
	match String(q.kind):
		"stomp": return "Beat %d bad guys" % g
		"stars": return "Find %d star%s" % [g, "" if g == 1 else "s"]
		"coins": return "Grab %d coins" % g
		"spots": return "Open %d TEAM POWER spot%s" % [g, "" if g == 1 else "s"]
		"swap": return "SWAP teammates %d times" % g
		"teamup": return "Do a TEAM UP! attack"
		"chest": return "Open the DAILY CHEST"
	return "?"


func progress(kind: String, n := 1) -> void:
	for q in quests:
		if q.kind == kind and q.n < q.goal:
			q.n = mini(q.goal, q.n + n)
			if q.n >= q.goal and game and game.hud:
				game.hud.say("QUEST DONE!", quest_text(q) + ": claim it at the QUEST BOARD at home!", 2.4)
				game.sfx("heart", -4)


func ready_quests() -> int:
	var n := 0
	for q in quests:
		if q.n >= q.goal:
			n += 1
	return n


func claim(i: int) -> bool:
	if i < 0 or i >= quests.size() or quests[i].n < quests[i].goal:
		return false
	var q: Dictionary = quests[i]
	game.coins += q.coins
	crystals += q.crystals
	quests_done += 1
	quests[i] = _new_quest(i + 7)
	game.sfx("clear")
	game.hud.say("+%d COINS  +%d CRYSTALS!" % [q.coins, q.crystals], "A new quest is on the board!", 2.2)
	_check_home_level()
	game.save_game(false)
	return true


# ------------------------------------------------------------------ the daily chest
func chest_ready() -> bool:
	return chest_day != today()


func open_daily_chest() -> bool:
	if not chest_ready():
		return false
	var d := today()
	streak = streak + 1 if chest_day == day_before(d) else 1
	chest_day = d
	chests += 1
	var k := mini(streak, 7)
	var c := 20 + 10 * k
	var cr := 2 + k
	game.coins += c
	crystals += cr
	game.sfx("clear")
	game.hud.say("DAILY CHEST: +%d COINS  +%d CRYSTALS!" % [c, cr], "Day %d in a row! Come back tomorrow for more." % streak, 2.6)
	progress("chest")
	_check_home_level()
	game.save_game(false)
	return true


# ------------------------------------------------------------------ home level
func stars_total() -> int:
	var n := 0
	for w in game.stars_w:
		n += Array(game.stars_w[w]).size()
	return n


func home_xp() -> int:
	return stars_total() * 3 + quests_done * 5 + chests * 2 + buddies.size() * 2 + spots_total()


func home_level() -> int:
	var x := home_xp()
	var lv := 1
	for i in range(1, HOME_XP.size()):
		if x >= HOME_XP[i]:
			lv = i + 1
	return mini(lv, HOME_XP.size())


## "Home Lv 3: 47/80 to Lv 4"
func home_text() -> String:
	var lv := home_level()
	if lv >= HOME_XP.size():
		return "HOME Lv %d: the best home ever!" % lv
	return "HOME Lv %d:  %d/%d to Lv %d (%s)" % [lv, home_xp(), HOME_XP[lv], lv + 1, UNLOCKS[lv]]


func _check_home_level() -> void:
	var lv := home_level()
	if lv > home_lv_seen:
		home_lv_seen = lv
		if game.state in ["home", "teampanel"]:
			game.hud.say("HOME LEVEL %d!" % lv, "New at the TEAM PLAZA: " + UNLOCKS[lv - 1] + "!", 3.0)
			game.sfx("boss_down", -4)
			if plaza:
				_build_unlocks()


# ------------------------------------------------------------------ the buddy book
func book_entries() -> Array:
	var out := []
	for k in BOOK_FOES:
		out.append(k)
	for l in Mons.ORDER:
		out.append("mon_" + l)
	return out


func befriend(id: String, loud := true) -> void:
	if id in buddies or not id in book_entries():
		return
	buddies.append(id)
	if loud and game and game.hud:
		game.hud.say("NEW BUDDY: %s!" % buddy_name(id), "It's in your BUDDY BOOK (and visits your home)", 2.2)
	while buddies.size() >= (book_paid + 1) * 5:
		book_paid += 1
		crystals += 5
		if loud and game and game.hud:
			game.hud.say("%d BUDDIES! +5 CRYSTALS" % buddies.size(), "", 2.0)
	if buddies.size() == book_entries().size():
		crystals += 30
		if loud and game and game.hud:
			game.hud.say("BUDDY BOOK COMPLETE!", "+30 TEAM CRYSTALS!", 3.0)


static func buddy_name(id: String) -> String:
	if id.begins_with("mon_"):
		return Mons.name_of(id.substr(4), 0)
	return FOE_NAMES.get(id, id.to_upper())


static func buddy_pic(id: String) -> String:
	if id.begins_with("mon_"):
		return Mons.face(id.substr(4), 0)
	return id + "_1" if id != "biggrumbler" else "grumbler_1"


static func buddy_fact(id: String) -> String:
	return FACTS.get(id.substr(4) if id.begins_with("mon_") else id, "")


# ------------------------------------------------------------------ the TEAM HEART
func shards() -> int:
	var n := 0
	for w in Worlds.ORDER:
		if "team" in Array(game.stars_w.get(w, [])):
			n += 1
	return n


## At home: once every shard is back, the finale plays and the team gets its golden aura.
func heart_complete() -> bool:
	return shards() >= Worlds.ORDER.size()


func finish_heart() -> void:
	golden = true
	heart_seen = true
	crystals += 50
	apply_aura()
	game.save_game(false)


## Golden sparkles round every hero (once the TEAM HEART is whole).
func apply_aura() -> void:
	if not golden or game == null or not auras.is_empty():
		return
	for h in game.heroes.values():
		var p := CPUParticles3D.new()
		p.amount = 10
		p.lifetime = 0.9
		p.emission_shape = CPUParticles3D.EMISSION_SHAPE_SPHERE
		p.emission_sphere_radius = 0.6
		p.direction = Vector3.UP
		p.spread = 30
		p.gravity = Vector3(0, 1.2, 0)
		p.initial_velocity_min = 0.2
		p.initial_velocity_max = 0.6
		p.scale_amount_min = 0.06
		p.scale_amount_max = 0.12
		var m := SphereMesh.new()
		m.radius = 0.5
		m.height = 1.0
		m.radial_segments = 6
		m.rings = 3
		var mat := StandardMaterial3D.new()
		mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		mat.albedo_color = Color("ffe36a")
		m.material = mat
		p.mesh = m
		p.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		p.position = Vector3(0, 0.8, 0)
		h.add_child(p)
		auras.append(p)


# ------------------------------------------------------------------ the TEAM PLAZA at home
func _o() -> Vector3:
	return Vector3(Level.HOME_OX, 0, Level.HOME_OZ)


func spot_pos(k: String) -> Vector3:
	var v: Vector2 = SPOTS[k]
	var x := Level.HOME_OX + v.x
	var z := Level.HOME_OZ + v.y
	return Vector3(x, game.level.ground_at(floori(x), floori(z)), z)


func build_plaza(lv: Level) -> void:
	refresh_quests()
	if plaza:   # built again after the save is loaded (statue, heart and buddies follow the file)
		plaza.queue_free()
	walkers.clear()
	plaza = Node3D.new()
	lv.add_child(plaza)
	# a paved square under it all
	var pave := MeshInstance3D.new()
	var pm := BoxMesh.new()
	pm.size = Vector3(21, 0.06, 7)
	pave.mesh = pm
	pave.material_override = Toys.mat(Color("d9c9a3"), false)
	pave.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	var c := spot_pos("heart")
	pave.position = Vector3(Level.HOME_OX + 9.8, c.y + 0.03, Level.HOME_OZ + PLAZA_Z + 1.2)
	plaza.add_child(pave)
	_sign("TEAM PLAZA", Vector3(Level.HOME_OX + 9.8, c.y + 5.6, Level.HOME_OZ + PLAZA_Z - 1.6), Color("ffd23a"), 64)
	_board(spot_pos("board"))
	_daily_chest(spot_pos("chest"))
	_heart(spot_pos("heart"))
	_lectern(spot_pos("book"))
	plaza_extra = Node3D.new()
	plaza.add_child(plaza_extra)
	_build_unlocks()
	_walkers()


func _sign(text: String, pos: Vector3, col: Color, fs := 44) -> Label3D:
	var l := Label3D.new()
	l.text = text
	l.font_size = fs
	l.outline_size = 12
	l.pixel_size = 0.009
	l.modulate = col
	l.outline_modulate = Color(0.12, 0.08, 0.16)
	l.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	l.position = pos
	plaza.add_child(l)
	return l


func _box(parent: Node3D, size: Vector3, pos: Vector3, col: Color, glow := 0.0) -> MeshInstance3D:
	var m := MeshInstance3D.new()
	var b := BoxMesh.new()
	b.size = size
	m.mesh = b
	m.material_override = Toys.mat(col, true, glow)
	m.position = pos
	parent.add_child(m)
	return m


func _board(p: Vector3) -> void:
	var n := Node3D.new()
	n.position = p
	plaza.add_child(n)
	_box(n, Vector3(0.2, 2.2, 0.2), Vector3(-1.1, 1.1, 0), Color("7a4a26"))
	_box(n, Vector3(0.2, 2.2, 0.2), Vector3(1.1, 1.1, 0), Color("7a4a26"))
	_box(n, Vector3(2.6, 1.5, 0.14), Vector3(0, 1.75, 0), Color("b07a44"))
	for i in 3:   # three paper notes
		var note := _box(n, Vector3(0.62, 0.8, 0.04), Vector3(-0.8 + i * 0.8, 1.75, 0.09), [Color("fff4c2"), Color("ffd6ec"), Color("d6f0ff")][i])
		note.rotation.z = (i - 1) * 0.08
	_box(n, Vector3(2.9, 0.2, 0.4), Vector3(0, 2.6, 0), Color("c4402c"))
	_sign("QUEST BOARD", p + Vector3(0, 3.3, 0), Color("ffe08a"))


func _daily_chest(p: Vector3) -> void:
	var n := Node3D.new()
	n.position = p
	plaza.add_child(n)
	_box(n, Vector3(1.6, 0.9, 1.1), Vector3(0, 0.45, 0), Color("a0582a"))
	_box(n, Vector3(1.66, 0.14, 1.16), Vector3(0, 0.82, 0), Color("ffd23a"), 0.4)
	_box(n, Vector3(1.6, 0.4, 1.1), Vector3(0, 1.1, 0), Color("b8692f"))
	_box(n, Vector3(0.3, 0.36, 0.08), Vector3(0, 0.78, 0.58), Color("ffd23a"), 0.6)
	_sign("DAILY CHEST", p + Vector3(0, 2.2, 0), Color("ffd23a"))


## The TEAM HEART on a stone stand: dim, and brighter with each shard.
func _heart(p: Vector3) -> void:
	var n := Node3D.new()
	n.position = p
	plaza.add_child(n)
	_box(n, Vector3(1.6, 1.0, 1.6), Vector3(0, 0.5, 0), Color("8a8e96"))
	_box(n, Vector3(1.9, 0.2, 1.9), Vector3(0, 1.05, 0), Color("6c6f78"))
	var k := float(shards()) / Worlds.ORDER.size()
	var col := Color("6a5a70").lerp(Color("ff4f9a"), k)
	if heart_complete():
		col = Color("ffd23a")
	heart_mesh = heart_model(col, 0.2 + k * 1.2)
	heart_mesh.position = Vector3(0, 2.4, 0)
	n.add_child(heart_mesh)
	heart_label = _sign("TEAM HEART  %d/%d" % [shards(), Worlds.ORDER.size()], p + Vector3(0, 4.0, 0), Color("ff9ad0"))


## A chunky heart (two balls and a cone), also used by the "heart" cutscene.
static func heart_model(col: Color, glow := 0.6) -> Node3D:
	var h := Node3D.new()
	for sx in [-0.32, 0.32]:
		var s := MeshInstance3D.new()
		var sm := SphereMesh.new()
		sm.radius = 0.5
		sm.height = 1.0
		s.mesh = sm
		s.material_override = Toys.mat(col, true, glow)
		s.position = Vector3(sx, 0.18, 0)
		s.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		h.add_child(s)
	var cone := MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.78
	cm.bottom_radius = 0.02
	cm.height = 1.0
	cone.mesh = cm
	cone.material_override = Toys.mat(col, true, glow)
	cone.position = Vector3(0, -0.32, 0)
	cone.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	h.add_child(cone)
	h.scale = Vector3(1, 1, 0.55)
	return h


static func color_heart(h: Node3D, col: Color) -> void:
	for c in h.get_children():
		if c is MeshInstance3D:
			c.material_override = Toys.mat(col, true, 1.4)


func _lectern(p: Vector3) -> void:
	var n := Node3D.new()
	n.position = p
	plaza.add_child(n)
	_box(n, Vector3(0.5, 1.1, 0.5), Vector3(0, 0.55, 0), Color("7a4a26"))
	var top := _box(n, Vector3(1.2, 0.12, 0.9), Vector3(0, 1.2, 0), Color("7a4a26"))
	top.rotation.x = -0.35
	var book := _box(n, Vector3(1.0, 0.1, 0.72), Vector3(0, 1.3, 0.02), Color("3fae6a"))
	book.rotation.x = -0.35
	_sign("BUDDY BOOK", p + Vector3(0, 2.3, 0), Color("9df0a8"))


## The HOME LEVEL things (rebuilt when the level goes up).
func _build_unlocks() -> void:
	for c in plaza_extra.get_children():
		c.queue_free()
	var lv := home_level()
	var y: float = spot_pos("heart").y
	var ox := float(Level.HOME_OX)
	var oz := float(Level.HOME_OZ) + PLAZA_Z
	if lv >= 2:   # a golden statue of the team
		var base := Vector3(ox + 9.8, y, oz + 4.6)
		_box(plaza_extra, Vector3(3.4, 0.6, 1.6), base + Vector3(0, 0.3, 0), Color("8a8e96"))
		var i := 0
		for who in [game.heroes.rocco.who, game.heroes.blocky.who, "mon:%s:%d" % [game.heroes.mon.line, game.heroes.mon.stage]]:
			var t := Toys.build(who)
			_gild(t)
			t.position = base + Vector3(-1.1 + i * 1.1, 0.6, 0)
			t.rotation.y = PI
			plaza_extra.add_child(t)
			i += 1
	if lv >= 3:   # a fountain
		var f := Decor.model("fountain")
		if f:
			f.position = Vector3(ox + 4.6, y, oz + 4.4)
			f.scale = Vector3.ONE * 1.6
			plaza_extra.add_child(f)
	if lv >= 4:   # TEAM HQ: a little clubhouse with a flag
		var hq := Node3D.new()
		hq.position = Vector3(ox + 22.5, y, oz + 1.5)
		plaza_extra.add_child(hq)
		_box(hq, Vector3(4.2, 3.0, 3.6), Vector3(0, 1.5, 0), Color("5a8ae0"))
		_box(hq, Vector3(4.6, 0.5, 4.0), Vector3(0, 3.2, 0), Color("e8413b"))
		_box(hq, Vector3(1.1, 1.8, 0.1), Vector3(0, 0.9, -1.82), Color("7a4a26"))
		_box(hq, Vector3(0.12, 2.4, 0.12), Vector3(1.6, 4.6, 0), Color("dddddd"))
		_box(hq, Vector3(1.0, 0.6, 0.06), Vector3(2.1, 5.4, 0), Color("ffd23a"), 0.3)
		var l := Label3D.new()
		l.text = "TEAM HQ"
		l.font_size = 44
		l.outline_size = 12
		l.pixel_size = 0.009
		l.modulate = Color("ffffff")
		l.outline_modulate = Color(0.12, 0.08, 0.16)
		l.position = Vector3(0, 2.4, -1.85)
		l.rotation.y = PI
		hq.add_child(l)
	if lv >= 5:   # a rainbow arch over the plaza
		var cols := [Color("ff4f4f"), Color("ff9a2f"), Color("ffe23a"), Color("4cd06a"), Color("3f9fe0"), Color("8a5bd8")]
		for i in cols.size():
			var arc := MeshInstance3D.new()
			var tm := TorusMesh.new()
			tm.inner_radius = 5.6 - i * 0.28
			tm.outer_radius = 5.88 - i * 0.28
			tm.rings = 32
			arc.mesh = tm
			arc.material_override = Toys.mat(cols[i], false, 0.3)
			arc.rotation.x = PI * 0.5
			arc.position = Vector3(ox + 9.8, y, oz - 2.6)
			arc.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			plaza_extra.add_child(arc)
	if lv >= 6:   # a crystal tree
		var tr := Node3D.new()
		tr.position = Vector3(ox + 15.0, y, oz + 4.6)
		plaza_extra.add_child(tr)
		_box(tr, Vector3(0.4, 2.2, 0.4), Vector3(0, 1.1, 0), Color("7a4a26"))
		for i in 6:
			var cr := _box(tr, Vector3(0.5, 0.9, 0.5), Vector3(cos(i * 1.05) * 0.8, 2.4 + (i % 2) * 0.5, sin(i * 1.05) * 0.8), Color("7af0ff"), 0.9)
			cr.rotation = Vector3(0.4, i, 0.3)
	if lv >= 7:   # golden flags round the plaza
		for i in 4:
			var fp := Vector3(ox - 0.5 + i * 7.0, y, oz - 2.2)
			_box(plaza_extra, Vector3(0.12, 3.2, 0.12), fp + Vector3(0, 1.6, 0), Color("dddddd"))
			_box(plaza_extra, Vector3(0.9, 0.55, 0.05), fp + Vector3(0.5, 2.9, 0), Color("ffd23a"), 0.5)


func _gild(n: Node) -> void:
	var gold := Toys.mat(Color("ffcf33"), true, 0.25)
	for c in n.get_children():
		if c is MeshInstance3D:
			c.material_override = gold
		_gild(c)


## Up to 6 befriended buddies wander about the plaza.
func _walkers() -> void:
	var list := buddies.slice(maxi(0, buddies.size() - 6))
	var i := 0
	for id: String in list:
		var n := Node3D.new()
		var kind := id
		if id.begins_with("mon_"):
			kind = "mon:%s:0" % id.substr(4)
		if Look.toy:
			n.add_child(Toys.build(kind))
			n.scale = Vector3.ONE * 0.8
		else:
			var s := Sprite3D.new()
			s.texture = load("res://art/%s.png" % (buddy_pic(id) if id.begins_with("mon_") else (id if id != "biggrumbler" else "grumbler") + "_1"))
			if s.texture:
				s.pixel_size = 1.1 / s.texture.get_height()
			s.position.y = 0.55
			s.billboard = BaseMaterial3D.BILLBOARD_FIXED_Y
			s.shaded = false
			s.alpha_cut = SpriteBase3D.ALPHA_CUT_OPAQUE_PREPASS
			n.add_child(s)
		var home := Vector3(Level.HOME_OX + 3.0 + i * 3.2, 0, Level.HOME_OZ + PLAZA_Z + 2.8)
		home.y = game.level.ground_at(floori(home.x), floori(home.z))
		n.position = home
		n.set_meta("home", home)
		n.set_meta("to", home)
		plaza.add_child(n)
		walkers.append(n)
		i += 1


func _step_plaza(dt: float) -> void:
	var t := Time.get_ticks_msec() / 1000.0
	if heart_mesh:
		heart_mesh.rotation.y = t * 0.8
		heart_mesh.position.y = 2.4 + sin(t * 2.0) * 0.12
	for w: Node3D in walkers:
		var to: Vector3 = w.get_meta("to")
		var d := to - w.position
		d.y = 0
		if d.length() < 0.2:
			var h: Vector3 = w.get_meta("home")
			to = h + Vector3(randf_range(-2.5, 2.5), 0, randf_range(-1.5, 1.5))
			to.y = game.level.ground_at(floori(to.x), floori(to.z))
			w.set_meta("to", to)
		else:
			w.position += d.normalized() * minf(d.length(), dt * 1.4)
			w.position.y = to.y + absf(sin(t * 7.0 + w.position.x)) * 0.15
			w.rotation.y = atan2(d.x, d.z)
	# now and then: a new HOME LEVEL from stars found out there, and the TEAM HEART is ready
	nudge_t -= dt
	if nudge_t <= 0:
		nudge_t = 2.0
		if home_level() > home_lv_seen:
			_check_home_level()
		elif heart_complete() and not heart_seen and not heart_told:
			heart_told = true
			game.hud.say("ALL THE TEAM HEART SHARDS!", "Go to the TEAM HEART at the TEAM PLAZA!", 3.0)
	# which plaza thing is the leader standing by?
	near = ""
	if game.home and game.home.mode == "" and not game.flying:
		var p: Vector3 = game.leader().position
		for k in SPOTS:
			var s := spot_pos(k)
			if Vector2(p.x - s.x, p.z - s.z).length() < 2.8:
				near = k


## Home buttons (from hud._layout): the plaza thing you're standing by. Play: TEAM UP!
func layout_buttons(hud, s: Vector2, u: float) -> void:
	if game.state == "home" and near != "" and hud.buttons.has("build"):
		hud.buttons["plaza"] = [Vector2(s.x - 445 * u, s.y - 175 * u), 56 * u]
	elif game.state == "play" and meter >= 1.0 and teamup.can_start():
		hud.buttons["teamup"] = [Vector2(s.x - 470 * u, s.y - 105 * u), 62 * u]


func button_labels() -> Dictionary:
	return {"plaza": BUTTON_LABEL.get(near, "LOOK"), "teamup": "TEAM UP!"}


func button_colors() -> Dictionary:
	var t := Time.get_ticks_msec() / 1000.0
	return {"plaza": BUTTON_COLOR.get(near, Color("c47a2c")), "teamup": Color.from_hsv(fmod(t * 0.6, 1.0), 0.6, 1.0)}


## From main._step_home: the plaza button opens the screen for what you're near.
func home_taps(taps: Dictionary) -> bool:
	if taps.has("plaza") and near != "":
		open_panel(near)
		return true
	return false


# ------------------------------------------------------------------ the plaza screens (state "teampanel")
func open_panel(kind: String) -> void:
	refresh_quests()
	panel = kind
	panel_back = game.state
	panel_t = 0.0
	book_page = 0
	game.state = "teampanel"
	game.sfx("swap", -6)


func close_panel() -> void:
	panel = ""
	game.state = panel_back if panel_back != "teampanel" else "home"
	game.sfx("swap", -6)
	if heart_mesh and is_instance_valid(heart_label):
		heart_label.text = "TEAM HEART  %d/%d" % [shards(), Worlds.ORDER.size()]


func panel_step(dt: float, jump: bool, action: bool, back: bool) -> void:
	panel_t += dt
	if panel_t < 0.25:
		return
	if back:
		close_panel()
		return
	if Input.is_action_just_pressed("left"):
		book_page = maxi(0, book_page - 1)
	if Input.is_action_just_pressed("right"):
		book_page = mini(_pages() - 1, book_page + 1)
	if jump or action:
		match panel:
			"board":
				for i in quests.size():
					if quests[i].n >= quests[i].goal:
						claim(i)
						return
				close_panel()
			"chest":
				if not open_daily_chest():
					close_panel()
			"heart":
				close_panel()
				if heart_complete() and not heart_seen and not game.play_scene("heart", "home"):
					finish_heart()
			_:
				if book_page < _pages() - 1:
					book_page += 1
				else:
					close_panel()


func _pages() -> int:
	return ceili(book_entries().size() / 9.0)


func panel_rect(s: Vector2) -> Rect2:
	var w := minf(s.x - 40, 980.0)
	var h := minf(s.y - 40, 600.0)
	return Rect2((s.x - w) * 0.5, (s.y - h) * 0.5, w, h)


func _close_rect(s: Vector2) -> Rect2:
	var r := panel_rect(s)
	return Rect2(r.end.x - 74, r.position.y + 12, 60, 60)


func _claim_rect(s: Vector2, i: int) -> Rect2:
	var r := panel_rect(s)
	return Rect2(r.end.x - 230, r.position.y + 150 + i * 128 + 22, 190, 70)


func _main_rect(s: Vector2) -> Rect2:
	var r := panel_rect(s)
	return Rect2(r.position.x + r.size.x * 0.5 - 150, r.end.y - 90, 300, 70)


func _page_rect(s: Vector2, dir: int) -> Rect2:
	var r := panel_rect(s)
	return Rect2((r.position.x + 20) if dir < 0 else (r.end.x - 110), r.end.y - 96, 90, 70)


func panel_tap(p: Vector2, s: Vector2) -> void:
	if panel_t < 0.25:
		return
	if _close_rect(s).grow(10).has_point(p) or not panel_rect(s).has_point(p):
		close_panel()
		return
	match panel:
		"board":
			for i in quests.size():
				if _claim_rect(s, i).grow(8).has_point(p):
					claim(i)
		"chest":
			if _main_rect(s).grow(8).has_point(p):
				if not open_daily_chest():
					close_panel()
		"heart":
			if _main_rect(s).grow(8).has_point(p):
				close_panel()
				if heart_complete() and not heart_seen and not game.play_scene("heart", "home"):
					finish_heart()
		"book":
			if _page_rect(s, -1).grow(8).has_point(p):
				book_page = maxi(0, book_page - 1)
			elif _page_rect(s, 1).grow(8).has_point(p):
				book_page = mini(_pages() - 1, book_page + 1)


func _button(hud, r: Rect2, text: String, col: Color, fs := 30) -> void:
	hud.draw_rect(Rect2(r.position + Vector2(0, 6), r.size), Color(0, 0, 0, 0.3))
	hud.draw_rect(r, col)
	hud.draw_rect(r, Color(1, 1, 1, 0.85), false, 3.0)
	hud.txt(text, Vector2(r.position.x, r.position.y + r.size.y * 0.5 + fs * 0.36), fs, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 6)


func draw_panel(hud) -> void:
	var s: Vector2 = hud.size
	hud.draw_rect(Rect2(Vector2.ZERO, s), Color(0.05, 0.03, 0.12, 0.55))
	var r := panel_rect(s)
	var pop := clampf(panel_t * 6.0, 0, 1)
	var k := 0.85 + 0.15 * (1.0 - pow(1.0 - pop, 3.0))
	var mid := r.get_center()
	hud.draw_set_transform(mid * (1.0 - k), 0.0, Vector2(k, k))
	var col: Color = BUTTON_COLOR.get(panel, Color("c47a2c"))
	hud.draw_rect(Rect2(r.position + Vector2(8, 10), r.size), Color(0, 0, 0, 0.35))
	hud.draw_rect(r, Color("fff6e0"))
	hud.draw_rect(Rect2(r.position, Vector2(r.size.x, 84)), col)
	hud.draw_rect(r, Color(0.13, 0.09, 0.18), false, 5.0)
	var title: String = {"board": "QUEST BOARD", "chest": "DAILY CHEST", "heart": "THE TEAM HEART", "book": "BUDDY BOOK"}.get(panel, "")
	hud.txt(title, Vector2(r.position.x, r.position.y + 60), 46, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 10)
	_crystal(hud, Vector2(r.position.x + 38, r.position.y + 42), 16)
	hud.txt("%d" % crystals, Vector2(r.position.x + 62, r.position.y + 54), 30, Color("bff6ff"))
	var cr := _close_rect(s)
	hud.draw_rect(cr, Color("e8413b"))
	hud.draw_rect(cr, Color.WHITE, false, 3.0)
	hud.txt("X", Vector2(cr.position.x, cr.position.y + 44), 40, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, cr.size.x)
	match panel:
		"board": _draw_board(hud, s, r)
		"chest": _draw_chest(hud, s, r)
		"heart": _draw_heart(hud, s, r)
		"book": _draw_book(hud, s, r)
	hud.draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _draw_board(hud, s: Vector2, r: Rect2) -> void:
	var ink := Color(0.2, 0.13, 0.1)
	hud.txt("New quests every day!", Vector2(r.position.x, r.position.y + 124), 26, Color("8a5a2a"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	for i in quests.size():
		var q: Dictionary = quests[i]
		var y := r.position.y + 150 + i * 128
		var card := Rect2(r.position.x + 30, y, r.size.x - 60, 112)
		hud.draw_rect(card, [Color("fff4c2"), Color("ffe1f0"), Color("dff1ff")][i % 3])
		hud.draw_rect(card, Color(0.5, 0.35, 0.2), false, 3.0)
		hud.txt(quest_text(q), Vector2(card.position.x + 22, y + 44), 32, ink, HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
		var bw := card.size.x - 300
		var k := float(q.n) / maxf(1, q.goal)
		hud.draw_rect(Rect2(card.position.x + 22, y + 62, bw, 26), Color(0.2, 0.15, 0.15, 0.25))
		hud.draw_rect(Rect2(card.position.x + 22, y + 62, bw * k, 26), Color("3fc06a") if k >= 1 else Color("ffb020"))
		hud.txt("%d / %d" % [q.n, q.goal], Vector2(card.position.x + 22, y + 84), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, bw, 5)
		hud.txt("+%d coins  +%d" % [q.coins, q.crystals], Vector2(card.position.x + 22, y + 108), 20, Color("8a5a2a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
		var cl := _claim_rect(s, i)
		if q.n >= q.goal:
			var glow := 0.5 + 0.5 * sin(panel_t * 7.0)
			hud.draw_rect(cl.grow(4 + glow * 4), Color(1, 0.9, 0.3, 0.5))
			_button(hud, cl, "CLAIM!", Color("3fc06a"), 34)
		else:
			_button(hud, cl, "...", Color(0.6, 0.6, 0.65), 34)
	# home level
	var lv := home_level()
	var y2 := r.end.y - 58
	var x0 := float(HOME_XP[lv - 1])
	var x1 := float(HOME_XP[mini(lv, HOME_XP.size() - 1)])
	var kk := 1.0 if x1 <= x0 else clampf((home_xp() - x0) / (x1 - x0), 0, 1)
	hud.draw_rect(Rect2(r.position.x + 30, y2 - 8, r.size.x - 60, 44), Color("3b2a5a"))
	hud.draw_rect(Rect2(r.position.x + 30, y2 - 8, (r.size.x - 60) * kk, 44), Color("8a5bd8"))
	hud.txt(home_text(), Vector2(r.position.x + 30, y2 + 24), 26, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, r.size.x - 60, 6)


func _draw_chest(hud, s: Vector2, r: Rect2) -> void:
	var c := Vector2(r.get_center().x, r.position.y + 212)
	var open := not chest_ready()
	var wob := 0.0 if open else sin(panel_t * 9.0) * 4.0
	var ink := Color(0.13, 0.09, 0.18)
	# the chest: a box, a gold band, a lid (open: up, with crystals popping out)
	var box := Rect2(c + Vector2(-110 + wob, -20), Vector2(220, 110))
	hud.draw_rect(box, Color("a0582a"))
	hud.draw_rect(box, ink, false, 4.0)
	if open:
		var lid := Rect2(c + Vector2(-110, -120), Vector2(220, 34))
		hud.draw_rect(lid, Color("b8692f"))
		hud.draw_rect(lid, ink, false, 4.0)
		hud.draw_rect(Rect2(c + Vector2(-100, -26), Vector2(200, 10)), Color(0.2, 0.1, 0.05))
		for i in 6:
			_crystal(hud, c + Vector2(-62 + i * 25, -50 - (i % 2) * 16), 13)
	else:
		var lid := Rect2(c + Vector2(-110 + wob, -84), Vector2(220, 64))
		hud.draw_rect(lid, Color("b8692f"))
		hud.draw_rect(lid, ink, false, 4.0)
		hud.draw_rect(Rect2(c + Vector2(-112 + wob, -28), Vector2(224, 18)), Color("ffd23a"))
		hud.draw_rect(Rect2(c + Vector2(-18 + wob, -40), Vector2(36, 42)), Color("ffd23a"))
		hud.draw_rect(Rect2(c + Vector2(-18 + wob, -40), Vector2(36, 42)), ink, false, 3.0)
		var glow := 0.5 + 0.5 * sin(panel_t * 5.0)
		for i in 4:   # sparkles round the closed chest
			var a := panel_t * 1.5 + i * TAU / 4.0
			_crystal(hud, c + Vector2(cos(a) * 150, -20 + sin(a) * 50), 6 + glow * 3)
	# the streak: 7 days
	var y := r.position.y + 336
	var show := streak if open or chest_day == day_before(today()) else 0
	hud.txt("DAY STREAK: %d   (bigger every day you come back!)" % show, Vector2(r.position.x, y), 24, Color("8a5a2a"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	for i in 7:
		var p := Vector2(r.get_center().x - 3 * 64 + i * 64, y + 36)
		var lit := i < mini(show, 7)
		hud.draw_circle(p, 22, Color("ffb020") if lit else Color(0.75, 0.72, 0.68))
		hud.txt(str(i + 1), Vector2(p.x - 22, p.y + 8), 22, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, 44, 5)
	if open:
		_button(hud, _main_rect(s), "SEE YOU TOMORROW!", Color(0.55, 0.55, 0.62), 26)
	else:
		_button(hud, _main_rect(s), "OPEN!", Color("3fc06a"), 40)


func _draw_heart(hud, s: Vector2, r: Rect2) -> void:
	var c := Vector2(r.position.x + 230, r.position.y + 300)
	var n := Worlds.ORDER.size()
	var sc := 9.0 + sin(panel_t * 3.0) * 0.3
	for i in n:
		var w: String = Worlds.ORDER[i]
		var have := "team" in Array(game.stars_w.get(w, []))
		var pts := PackedVector2Array([c])
		for j in 7:
			var t := TAU * (i + j / 6.0) / n
			pts.append(c + Vector2(16 * pow(sin(t), 3), -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))) * sc)
		var col := Color.from_hsv(0.92 + i * 0.008, 0.65, 1.0) if have else Color(0.42, 0.38, 0.48)
		if heart_complete():
			col = Color("ffd23a").lerp(Color("fff2a8"), 0.5 + 0.5 * sin(panel_t * 4 + i))
		hud.draw_colored_polygon(pts, col)
		hud.draw_polyline(pts, Color(0.13, 0.09, 0.18), 3.0)
	var ink := Color(0.2, 0.13, 0.1)
	var x := r.position.x + 440
	hud.txt("%d / %d SHARDS" % [shards(), n], Vector2(x, r.position.y + 140), 40, Color("e8417a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	var lines := ["King Krag cracked the TEAM HEART", "into shards and hid them in every world.", "",
		"Each world's TEAM SHRINE holds one:", "light its 3 pedestals in turn with", "hero 1, hero 2 and your partner!"]
	if heart_complete():
		lines = ["The TEAM HEART is whole again!", "Krag's trick didn't work:", "nothing beats a TEAM!", "", "Your team glows GOLD now."]
	for i in lines.size():
		hud.txt(lines[i], Vector2(x, r.position.y + 190 + i * 36), 26, ink, HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	var y := r.position.y + 420
	for i in n:
		var w: String = Worlds.ORDER[i]
		var have := "team" in Array(game.stars_w.get(w, []))
		var p := Vector2(x + 16 + (i % 5) * 96, y + (i / 5) * 44)
		hud.draw_circle(p, 13, Color("ff5fa8") if have else Color(0.75, 0.72, 0.68))
		hud.txt(String(Worlds.INFO[w].name).split(" ")[0].left(6), Vector2(p.x + 18, p.y + 8), 20, ink, HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	if heart_complete() and not heart_seen:
		_button(hud, _main_rect(s), "MAKE IT WHOLE!", Color("ff5fa8"), 30)
	else:
		_button(hud, _main_rect(s), "OK", Color("3f7fd9"), 40)


func _draw_book(hud, s: Vector2, r: Rect2) -> void:
	var all := book_entries()
	var ink := Color(0.2, 0.13, 0.1)
	hud.txt("%d / %d buddies  (every 5: +5 crystals)" % [buddies.size(), all.size()], Vector2(r.position.x, r.position.y + 120), 26,
		Color("2c7a4a"), HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	var cw := (r.size.x - 60) / 3.0
	for j in 9:
		var i := book_page * 9 + j
		if i >= all.size():
			break
		var id: String = all[i]
		var has := id in buddies
		var cell := Rect2(r.position.x + 30 + (j % 3) * cw, r.position.y + 140 + (j / 3) * 112, cw - 10, 104)
		hud.draw_rect(cell, Color("e6f7e6") if has else Color(0.86, 0.84, 0.8))
		hud.draw_rect(cell, Color(0.3, 0.5, 0.35) if has else Color(0.6, 0.58, 0.55), false, 3.0)
		var pic: Texture2D = hud.img(buddy_pic(id))
		if pic:
			if has:
				hud.fit(pic, Rect2(cell.position + Vector2(6, 8), Vector2(88, 88)))
			else:
				var k := minf(88.0 / pic.get_width(), 88.0 / pic.get_height())
				var sz := Vector2(pic.get_width(), pic.get_height()) * k
				hud.draw_texture_rect(pic, Rect2(cell.position + Vector2(6, 8) + (Vector2(88, 88) - sz) * 0.5, sz), false, Color(0.15, 0.13, 0.2, 0.8))
		if has:
			hud.txt(buddy_name(id), Vector2(cell.position.x + 102, cell.position.y + 34), 24, Color("2c7a4a"), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			var f := buddy_fact(id)
			var words := f.split(" ")
			var line := ""
			var ly := cell.position.y + 60
			for wd in words:
				if (line + " " + wd).length() > 24 and line != "":
					hud.txt(line, Vector2(cell.position.x + 102, ly), 16, ink, HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
					ly += 19
					line = wd
				else:
					line = wd if line == "" else line + " " + wd
			if line != "":
				hud.txt(line, Vector2(cell.position.x + 102, ly), 16, ink, HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
		else:
			hud.txt("???", Vector2(cell.position.x + 102, cell.position.y + 44), 24, Color(0.45, 0.43, 0.42), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
			hud.txt("Beat one to befriend" if not id.begins_with("mon_") else "Team up with one", Vector2(cell.position.x + 102, cell.position.y + 72), 16, Color(0.45, 0.43, 0.42), HORIZONTAL_ALIGNMENT_LEFT, -1, 0)
	hud.txt("PAGE %d / %d" % [book_page + 1, _pages()], Vector2(r.position.x, r.end.y - 50), 26, ink, HORIZONTAL_ALIGNMENT_CENTER, r.size.x, 0)
	if book_page > 0:
		_button(hud, _page_rect(s, -1), "<", Color("3fae6a"), 44)
	if book_page < _pages() - 1:
		_button(hud, _page_rect(s, 1), ">", Color("3fae6a"), 44)


## A TEAM CRYSTAL (a little blue gem).
static func _crystal(hud, c: Vector2, r: float) -> void:
	var pts := PackedVector2Array([c + Vector2(0, -r * 1.3), c + Vector2(r, -r * 0.2), c + Vector2(0, r * 1.2), c + Vector2(-r, -r * 0.2)])
	hud.draw_colored_polygon(pts, Color("5ae6ff"))
	hud.draw_colored_polygon(PackedVector2Array([c + Vector2(0, -r * 1.3), c + Vector2(r, -r * 0.2), c + Vector2(0, -r * 0.1)]), Color("c8fbff"))
	pts.append(pts[0])
	hud.draw_polyline(pts, Color(0.1, 0.15, 0.3), 2.5)


## The TEAM meter and crystals under the partner box (play), the plaza nudge (home).
func draw_hud(hud) -> void:
	if game.state == "play" and game.level and game.level.open:
		var x := 22.0
		var y := 280.0
		hud.draw_rect(Rect2(x, y, 250, 34), Color(0.08, 0.12, 0.25, 0.55))
		hud.txt("TEAM", Vector2(x + 6, y + 25), 20, Color("ffb3ec"), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
		hud.draw_rect(Rect2(x + 64, y + 9, 140, 16), Color(0.1, 0.1, 0.15))
		var col := Color("ff5fa8")
		if meter >= 1.0:
			col = Color.from_hsv(fmod(hud.title_t * 0.6, 1.0), 0.55, 1.0)
		hud.draw_rect(Rect2(x + 64, y + 9, 140 * meter, 16), col)
		_crystal(hud, Vector2(x + 222, y + 17), 9)
		hud.txt(str(crystals), Vector2(x + 234, y + 26), 20, Color("bff6ff"), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
	elif game.state == "home":
		var x := 22.0
		var y := 280.0
		hud.draw_rect(Rect2(x, y, 250, 34), Color(0.08, 0.12, 0.25, 0.55))
		_crystal(hud, Vector2(x + 18, y + 17), 9)
		hud.txt("%d   HOME Lv %d" % [crystals, home_level()], Vector2(x + 34, y + 26), 20, Color("bff6ff"), HORIZONTAL_ALIGNMENT_LEFT, -1, 5)
		var badge := ready_quests() + (1 if chest_ready() else 0)
		if badge > 0:
			hud.draw_circle(Vector2(x + 238, y + 4), 14, Color("e8413b"))
			hud.txt(str(badge), Vector2(x + 224, y + 12), 20, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER, 28, 4)
