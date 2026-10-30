class_name Mons
## The partner lines (same names as the real Team Up!), how they level up and evolve.
## Our own designs: a water turtle, a grass dino, a fire lizard, the electric puffball,
## and 8 newer lines (rock armadillo, sheet ghost, bird, psychic fox, ice seal, beetle,
## little dragon, fairy bunny). Evolving takes a while: Lv 7 and Lv 14 (Sparky: Lv 10).

const ORDER := ["water", "grass", "fire", "spark", "rock", "ghost", "bird", "mind", "ice", "bug", "dragon", "fairy"]

const LINES := {
	"water": {"names": ["SPLASHY", "TIDAL", "HYDRO"], "type": "WATER", "color": Color("3f9fe0"),
		"action": "WATER GUN", "super": "HYDRO WAVE", "evolve": [7, 14],
		"speed": [5.4, 5.2, 5.0], "jump": [12.6, 12.4, 12.2],
		"about": "Water turtle. Heavy stomps. SUPER: a giant wave both ways!"},
	"grass": {"names": ["SEEDY", "SPROUT", "BLOOM"], "type": "GRASS", "color": Color("3cb878"),
		"action": "VINE WHIP", "super": "SOLAR BEAM", "evolve": [7, 14],
		"speed": [5.6, 5.5, 5.3], "jump": [12.8, 12.6, 12.4],
		"about": "Grass dino. Vine whip hits hard and digs. SUPER: a huge beam!"},
	"fire": {"names": ["EMBER", "SCORCH", "BLAZE"], "type": "FIRE", "color": Color("ff7a3a"),
		"action": "FIREBALL", "super": "FIRE SPIN", "evolve": [7, 14],
		"speed": [6.4, 6.6, 6.8], "jump": [13.0, 13.2, 13.4],
		"about": "Fire lizard. Bouncing fireballs. BLAZE glides! SUPER: a ring of fire!"},
	"spark": {"names": ["SPARKY", "ZAPPER"], "type": "ELECTRIC", "color": Color("ffcf33"),
		"action": "ZAP", "super": "THUNDERBOLT", "evolve": [10],
		"speed": [6.6, 6.9], "jump": [13.2, 13.6],
		"about": "Electric puffball. Fast, jumps highest. SUPER: thunderbolts!"},
	"rock": {"names": ["PEBBLIT", "BOULDRO", "TERRADON"], "type": "ROCK", "color": Color("9a7b5a"),
		"action": "ROCK THROW", "super": "EARTHQUAKE", "evolve": [7, 14],
		"speed": [5.0, 4.9, 4.8], "jump": [12.2, 12.1, 12.0],
		"about": "Rocky armadillo. Boulders that bounce and smash blocks. SUPER: an earthquake!"},
	"ghost": {"names": ["BOOLET", "SPOOKLE", "PHANTOMA"], "type": "GHOST", "color": Color("8a6fd6"),
		"action": "SHADOW BALL", "super": "BIG SCARE", "evolve": [7, 14],
		"speed": [5.8, 6.0, 6.2], "jump": [12.6, 12.8, 13.0],
		"about": "Floaty sheet ghost. Shots fly through walls. Floats down slowly. SUPER: scares everyone!"},
	"bird": {"names": ["PIPWING", "SWOOPER", "SKYHAWK"], "type": "FLYING", "color": Color("5ab0e8"),
		"action": "GUST", "super": "TORNADO", "evolve": [7, 14],
		"speed": [6.2, 6.5, 6.8], "jump": [13.0, 13.3, 13.6],
		"about": "Little bird. Hold JUMP to glide! Gusts push bad guys away. SUPER: a tornado!"},
	"mind": {"names": ["MYSTIKIT", "FOXSEER", "ASTRAFOX"], "type": "PSYCHIC", "color": Color("e05fb0"),
		"action": "PSY ORB", "super": "MIND BLAST", "evolve": [7, 14],
		"speed": [5.9, 6.1, 6.3], "jump": [12.8, 13.0, 13.2],
		"about": "Floaty fox with a gem. Orbs chase bad guys. SUPER: hits everyone near!"},
	"ice": {"names": ["SNOWPUP", "FROSTEAL", "BLIZZARC"], "type": "ICE", "color": Color("7fd8f0"),
		"action": "ICE BEAM", "super": "BLIZZARD", "evolve": [7, 14],
		"speed": [5.5, 5.4, 5.3], "jump": [12.4, 12.3, 12.2],
		"about": "Snow seal. Ice beams go through a whole line of bad guys. SUPER: a blizzard!"},
	"bug": {"names": ["BUZZLET", "BEETLEBUD", "TITANBUG"], "type": "BUG", "color": Color("7cb342"),
		"action": "STRING SHOT", "super": "BUG SWARM", "evolve": [7, 14],
		"speed": [5.7, 5.6, 5.5], "jump": [12.6, 12.6, 12.6],
		"about": "Beetle with a horn. Long sticky string. SUPER: a swarm of bugs!"},
	"dragon": {"names": ["DRAKLET", "WYVERNO", "DRAGOROAR"], "type": "DRAGON", "color": Color("5a6fd6"),
		"action": "DRAGON BREATH", "super": "DRAGON METEOR", "evolve": [7, 14],
		"speed": [6.0, 6.2, 6.4], "jump": [12.8, 13.0, 13.2],
		"about": "Little dragon. Breathes fire in front. SUPER: meteors from the sky!"},
	"fairy": {"names": ["PIXIBUN", "FLUFFAIRY", "STARABBIT"], "type": "FAIRY", "color": Color("ff8fc8"),
		"action": "SPARKLE", "super": "HEAL BLOOM", "evolve": [7, 14],
		"speed": [6.0, 6.2, 6.4], "jump": [13.4, 13.6, 13.8],
		"about": "Pink bunny. Jumps high. SUPER: hurts bad guys and heals your hearts!"},
}

const MAX_LEVEL := 20
## Size (sprite height), collision half-width and height for each evolution stage.
const STAGE_SIZE := [[1.95, 0.32, 0.9], [2.4, 0.4, 1.1], [3.0, 0.5, 1.4]]

const XP_FOE := 12
const XP_BOSS := 60
const XP_CLEAR := 40


## XP needed to go from level L to L+1.
static func need(level: int) -> int:
	return 30 + 18 * level


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
