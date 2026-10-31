class_name Bosses
## Who guards each world's castle, and what every boss can do. Our own look-alike
## designs with made-up names: KING KRAG (the big turtle-dragon king), his kid KRAG JR.,
## the seven KRAGLINGS, and the skeleton versions DRY KRAG and DRY KRAG JR.
##
## Flying bosses (Krag Jr., Dry Krag Jr.) use foes.gd `_kragjr_open`. Walking bosses use
## `_boss_open`: they chase you, shout "!", do one of their attacks, then get tired
## (a good time to stomp them). Attacks:
##   spin      hide in the shell and dash at you, bouncing off the castle walls
##   stomp     jump high and land near you: a shockwave hurts you if you're on the ground
##   wand      three magic shots
##   ring      a ring of eight magic shots
##   balls     two bouncing spike balls
##   fire      a stream of fire breath
##   bluefire  blue fire breath (Dry Krag)
##   bones     throws a spread of bones (Dry Krag)
##   teleport  vanish and pop up next to you, then wand shots

const INFO := {
	"kragjr": {"name": "KRAG JR.", "fly": true, "hint": "Stomp him when he swoops down!"},
	"drykragjr": {"name": "DRY KRAG JR.", "fly": true, "shot": "blueflame", "hint": "Stomp his bony copter when he swoops!"},
	"zippo": {"name": "ZIPPO KRAG", "attacks": ["spin", "wand", "spin"], "speed": 3.0,
		"hint": "He spins in his shell! Stomp him when he's tired."},
	"rocko": {"name": "ROCKO KRAG", "attacks": ["stomp", "stomp", "balls"], "speed": 2.2,
		"hint": "Jump when he lands so the shockwave misses you!"},
	"maestro": {"name": "MAESTRO KRAG", "attacks": ["wand", "ring", "wand"], "speed": 2.4,
		"hint": "Dodge his magic music notes, then stomp!"},
	"glamma": {"name": "GLAMMA KRAG", "attacks": ["ring", "balls", "spin"], "speed": 2.8,
		"hint": "Watch out for her sparkly rings!"},
	"bruto": {"name": "BRUTO KRAG", "attacks": ["fire", "stomp"], "speed": 2.0,
		"hint": "Big and grumpy! Get behind him when he breathes fire."},
	"specs": {"name": "SPECS KRAG", "attacks": ["teleport", "wand", "teleport"], "speed": 2.6,
		"hint": "He teleports! Keep moving and stomp him."},
	"tiny": {"name": "TINY KRAG", "attacks": ["balls", "spin", "balls"], "speed": 3.4,
		"hint": "Small and super fast! Stomp him when he stops."},
	"kingkrag": {"name": "KING KRAG", "attacks": ["fire", "stomp", "spin", "balls"], "speed": 2.2, "big": true,
		"hint": "The big boss! Fire, stomps and shell spins. You can do it!"},
	"drykrag": {"name": "DRY KRAG", "attacks": ["bluefire", "bones", "stomp"], "speed": 2.0, "big": true, "rebuild": true,
		"hint": "A skeleton King Krag! He might not stay down..."},
}

## The castle boss of each world. Meadow is Krag Jr. the first time and Tiny Krag after.
const BY_WORLD := {"meadow": "kragjr", "frost": "zippo", "dunes": "rocko", "swamp": "drykragjr", "moon": "maestro",
	"lava": "drykrag", "candy": "glamma", "beach": "bruto", "jungle": "specs", "cloud": "kingkrag"}
const KRAGLINGS := ["zippo", "rocko", "maestro", "glamma", "bruto", "specs", "tiny"]


static func is_boss(kind: String) -> bool:
	return INFO.has(kind)


static func flies(kind: String) -> bool:
	return INFO.has(kind) and INFO[kind].get("fly", false)


static func name_of(kind: String) -> String:
	return INFO[kind].name if INFO.has(kind) else "KRAG JR."


static func for_world(world: String, times_cleared := 0) -> String:
	if world == "meadow" and times_cleared > 0:
		return "tiny"
	return BY_WORLD.get(world, "kragjr")


## How many times this save file has cleared a world (0 for tests / no file).
static func cleared_in_save(slot: int, world: String) -> int:
	if slot <= 0:
		return 0
	var d := Saves.read(slot)
	return int(d.get("cleared_w", {}).get(world, 0))
