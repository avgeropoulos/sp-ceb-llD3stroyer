class_name Worlds
## The worlds you fly to in the rocket. Each one is a level with its own look, song,
## bad guys and a Krag Jr. fight at the end. They unlock in order.

const ORDER := ["meadow", "frost", "dunes", "swamp", "moon"]

const INFO := {
	"meadow": {"name": "1-1  MEADOW MINE", "short": "MEADOW MINE", "song": "meadow", "after": "",
		"sky": ["3f8fe6", "c5e6ff", "7fb2dd"], "fog": "bfe0ff", "sun": "fff1d6", "ambient": "dce8f5",
		"map": Vector3(-14, 0, -6), "top": "grass_top", "side": "grass_side", "boss_hp": 18,
		"hello": "Go, team, go!"},
	"frost": {"name": "2-1  FROSTY PEAKS", "short": "FROSTY PEAKS", "song": "snow", "after": "meadow",
		"sky": ["8fb8e8", "eef6ff", "cfe0f2"], "fog": "e8f2ff", "sun": "eef4ff", "ambient": "e6eef8",
		"map": Vector3(4, 0, -20), "top": "snow_top", "side": "snow_side", "boss_hp": 24,
		"hello": "Brr! The ice is slippery!"},
	"dunes": {"name": "3-1  SUNSET DUNES", "short": "SUNSET DUNES", "song": "desert", "after": "frost",
		"sky": ["e8704a", "ffd29a", "f2b07a"], "fog": "ffd8a8", "sun": "ffc890", "ambient": "f8dcc0",
		"map": Vector3(20, 0, -4), "top": "sand", "side": "sandstone", "boss_hp": 30,
		"hello": "Hot hot hot! Watch for scorpions!"},
	"swamp": {"name": "4-1  SPOOKY SWAMP", "short": "SPOOKY SWAMP", "song": "spooky", "after": "dunes",
		"sky": ["2a1f45", "6a4f8a", "4a3a6a"], "fog": "6a5a8c", "sun": "ece0ff", "ambient": "c4b4e8",
		"fog_begin": 20.0, "fog_end": 85.0, "fog_density": 0.75,
		"map": Vector3(18, 0, 18), "top": "swamp_top", "side": "swamp_side", "boss_hp": 36,
		"hello": "Spooky! Watch out for skeletons in the mist!"},
	"moon": {"name": "5-1  MOON BASE", "short": "MOON BASE", "song": "moon", "after": "swamp",
		"sky": ["05081a", "1c2450", "101638"], "fog": "1c2450", "sun": "ffffff", "ambient": "b8c0e0",
		"fog_begin": 60.0, "fog_end": 220.0, "fog_density": 0.3, "gravity": 0.6,
		"map": Vector3(-22, 0, 20), "top": "moon_top", "side": "moon_rock", "boss_hp": 42,
		"hello": "Low gravity! Jump super high!"},
}

## The home island on the sky map.
const HOME_MAP := Vector3(-2, 0, 10)


static func info(id: String) -> Dictionary:
	return INFO.get(id, INFO.meadow)


## A world is open once the one before it is cleared (the first one is always open).
static func unlocked(id: String, cleared_w: Dictionary) -> bool:
	var after: String = info(id).after
	return after == "" or int(cleared_w.get(after, 0)) > 0
