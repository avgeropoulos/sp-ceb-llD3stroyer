class_name Team
## Everyone you can play as. Pick 2 human-like heroes and 1 partner on the TEAM screen.
## Our own look-alikes with made-up names: LUCKY (tall green brother), ALIX (Minecraft girl
## with orange hair), PETAL (a princess who floats), SPOTTY (mushroom-cap kid, super fast)
## and BRAWLO (a big yellow-capped bruiser who shoulder-bashes).
##
## The game keeps two human slots, heroes.rocco (slot 1) and heroes.blocky (slot 2); the
## hero in each slot is whoever `Hero.who` says (Rocco and Blocky by default).

const ORDER := ["rocco", "blocky", "lucky", "alix", "petal", "spotty", "brawlo"]

const INFO := {
	"rocco": {"name": "ROCCO", "speed": 5.6, "jump": 12.6, "hw": 0.3, "h": 1.45, "size": 1.8, "feet": 28.0,
		"action": "LONG JUMP", "need": 0, "pitch": 1.15, "italian": true,
		"about": "Long jump, triple jump, backflip, wall kick, dive. The Mario 64 moves!",
		"go": "Okey dokey!", "hi": "Let's-a go!", "color": Color("e8413b")},
	"blocky": {"name": "BLOCKY", "speed": 5.0, "jump": 10.8, "hw": 0.32, "h": 1.5, "size": 1.9, "feet": 30.0,
		"action": "MINE", "need": 0, "pitch": 0.8,
		"about": "Digs through any block with his pickaxe. Finds ores!",
		"go": "Let's dig!", "hi": "Time to mine!", "color": Color("3f7fd9")},
	"lucky": {"name": "LUCKY", "speed": 5.4, "jump": 14.2, "hw": 0.3, "h": 1.7, "size": 2.1, "feet": 28.0,
		"action": "SPIN JUMP", "need": 1, "pitch": 1.0, "italian": true,
		"about": "Jumps the highest and floats down slowly. ACTION: a spinning super jump!",
		"go": "Lucky time!", "hi": "Okey dokey, I'm-a ready!", "color": Color("3cb85a")},
	"alix": {"name": "ALIX", "speed": 5.2, "jump": 11.4, "hw": 0.3, "h": 1.5, "size": 1.9, "feet": 30.0,
		"action": "MINE / BOW", "need": 2, "pitch": 1.25,
		"about": "Mines blocks like Blocky, and shoots arrows at bad guys in front of her!",
		"go": "Bow ready!", "hi": "Let's build something awesome!", "color": Color("ff8a2a")},
	"petal": {"name": "PETAL", "speed": 5.3, "jump": 12.0, "hw": 0.3, "h": 1.55, "size": 1.95, "feet": 28.0,
		"action": "TWIRL", "need": 3, "pitch": 1.4,
		"about": "Hold JUMP in the air to float! ACTION: a twirl that bonks bad guys around her.",
		"go": "Here I come!", "hi": "A princess can jump too!", "color": Color("ff8fc0")},
	"spotty": {"name": "SPOTTY", "speed": 7.0, "jump": 12.2, "hw": 0.28, "h": 1.2, "size": 1.55, "feet": 26.0,
		"action": "DASH", "need": 4, "pitch": 1.6,
		"about": "The fastest runner! ACTION: a super-speed dash that knocks bad guys over.",
		"go": "Zoom zoom!", "hi": "Hooray! I'm on the team!", "color": Color("e8413b")},
	"brawlo": {"name": "BRAWLO", "speed": 4.8, "jump": 11.0, "hw": 0.38, "h": 1.5, "size": 2.0, "feet": 30.0,
		"action": "BASH", "need": 5, "pitch": 0.7,
		"about": "Big and strong! ACTION: a shoulder bash that smashes blocks and bad guys.",
		"go": "Wa ha ha!", "hi": "BRAWLO is the BEST!", "color": Color("ffd23a")},
}

## Heroes with a mid-air double jump (Rocco's is special: see main.gd _rocco_*).
const DOUBLE_JUMPERS := ["rocco", "lucky", "petal", "spotty"]
## Heroes who mine with ACTION (and swing a pickaxe).
const MINERS := ["blocky", "alix"]


static func info(id: String) -> Dictionary:
	return INFO.get(id, INFO.rocco)


static func name_of(id: String) -> String:
	return String(info(id).name)


## Worlds you must clear to unlock this hero.
static func need(id: String) -> int:
	return int(info(id).need)


static func unlocked(cleared_w: Dictionary) -> Array:
	var n := 0
	for w in cleared_w:
		if int(cleared_w[w]) > 0:
			n += 1
	var out := []
	for id in ORDER:
		if need(id) <= n:
			out.append(id)
	return out


## Paper pictures for a hero (the same names for every look).
static func frames(id: String) -> Dictionary:
	match id:
		"rocco":
			return {"idle": ["rocco_idle"], "blink": ["rocco_blink"], "run": ["rocco_run1", "rocco_idle", "rocco_run2", "rocco_idle"],
				"jump": ["rocco_jump"], "fall": ["rocco_fall"], "slam": ["rocco_slam"], "hurt": ["rocco_hurt"], "win": ["rocco_win"],
				"long": ["rocco_long"], "dive": ["rocco_dive"], "slide": ["rocco_slide"], "wallkick": ["rocco_kick"], "sideflip": ["rocco_jump"],
				"flip": ["rocco_tuck0", "rocco_tuck1", "rocco_tuck2", "rocco_tuck3", "rocco_tuck4", "rocco_tuck5", "rocco_tuck6", "rocco_tuck7"],
				"backflip": ["rocco_tuck0", "rocco_tuck7", "rocco_tuck6", "rocco_tuck5", "rocco_tuck4", "rocco_tuck3", "rocco_tuck2", "rocco_tuck1"]}
		"blocky":
			return {"idle": ["blocky_idle"], "run": ["blocky_run1", "blocky_idle", "blocky_run2", "blocky_idle"],
				"jump": ["blocky_jump"], "fall": ["blocky_jump"], "mine": ["blocky_mine1", "blocky_mine2"], "hurt": ["blocky_hurt"], "win": ["blocky_mine1"]}
	var b := id + "_"
	return {"idle": [b + "idle"], "run": [b + "run1", b + "idle", b + "run2", b + "idle"], "jump": [b + "jump"], "fall": [b + "jump"],
		"slam": [b + "jump"], "hurt": [b + "hurt"], "win": [b + "jump"], "mine": [b + "act", b + "idle"], "act": [b + "act"]}
