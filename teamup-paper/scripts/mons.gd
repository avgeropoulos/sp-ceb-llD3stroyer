class_name Mons
## The partner lines (same names as the real Team Up!), how they level up and evolve.
## Our own designs: a water turtle, a grass dino, a fire lizard and the electric puffball.

const ORDER := ["water", "grass", "fire", "spark"]

const LINES := {
	"water": {"names": ["SPLASHY", "TIDAL", "HYDRO"], "type": "WATER", "color": Color("3f9fe0"),
		"action": "WATER GUN", "super": "HYDRO WAVE", "evolve": [4, 8],
		"speed": [5.4, 5.2, 5.0], "jump": [12.6, 12.4, 12.2],
		"about": "Water turtle. Heavy stomps. SUPER: a giant wave both ways!"},
	"grass": {"names": ["SEEDY", "SPROUT", "BLOOM"], "type": "GRASS", "color": Color("3cb878"),
		"action": "VINE WHIP", "super": "SOLAR BEAM", "evolve": [4, 8],
		"speed": [5.6, 5.5, 5.3], "jump": [12.8, 12.6, 12.4],
		"about": "Grass dino. Vine whip hits hard and digs. SUPER: a huge beam!"},
	"fire": {"names": ["EMBER", "SCORCH", "BLAZE"], "type": "FIRE", "color": Color("ff7a3a"),
		"action": "FIREBALL", "super": "FIRE SPIN", "evolve": [4, 8],
		"speed": [6.4, 6.6, 6.8], "jump": [13.0, 13.2, 13.4],
		"about": "Fire lizard. Bouncing fireballs. BLAZE glides! SUPER: a ring of fire!"},
	"spark": {"names": ["SPARKY", "ZAPPER"], "type": "ELECTRIC", "color": Color("ffcf33"),
		"action": "ZAP", "super": "THUNDERBOLT", "evolve": [6],
		"speed": [6.6, 6.9], "jump": [13.2, 13.6],
		"about": "Electric puffball. Fast, jumps highest. SUPER: thunderbolts!"},
}

const MAX_LEVEL := 15
## Size (sprite height), collision half-width and height for each evolution stage.
const STAGE_SIZE := [[1.95, 0.32, 0.9], [2.4, 0.4, 1.1], [3.0, 0.5, 1.4]]

const XP_FOE := 12
const XP_BOSS := 60
const XP_CLEAR := 40


## XP needed to go from level L to L+1.
static func need(level: int) -> int:
	return 20 + 10 * level


static func level_of(xp: int) -> int:
	var lv := 1
	var left := xp
	while lv < MAX_LEVEL and left >= need(lv):
		left -= need(lv)
		lv += 1
	return lv


## How far into the current level (0..1), for the XP bar.
static func progress(xp: int) -> float:
	var lv := 1
	var left := xp
	while lv < MAX_LEVEL and left >= need(lv):
		left -= need(lv)
		lv += 1
	return 1.0 if lv >= MAX_LEVEL else float(left) / need(lv)


static func stage_of(line: String, level: int) -> int:
	var st := 0
	for e in LINES[line].evolve:
		if level >= e:
			st += 1
	return st


static func name_of(line: String, stage: int) -> String:
	return LINES[line].names[stage]


## Picture / model name, e.g. "water1". Sparky's first form keeps its old pictures.
static func form(line: String, stage: int) -> String:
	return "%s%d" % [line, stage]


## The paper picture used for faces on menus.
static func face(line: String, stage: int) -> String:
	if line == "spark" and stage == 0:
		return "sparky_idle"
	return "mon_%s_idle" % form(line, stage)
