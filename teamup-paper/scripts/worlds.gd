class_name Worlds
## The worlds you fly to in the rocket. Each one is a level with its own look, song,
## bad guys and a Krag Jr. fight at the end. They unlock in order.

const ORDER := ["meadow", "frost", "dunes"]

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
}

## The home island on the sky map.
const HOME_MAP := Vector3(-2, 0, 10)


static func info(id: String) -> Dictionary:
	return INFO.get(id, INFO.meadow)


## A world is open once the one before it is cleared (the first one is always open).
static func unlocked(id: String, cleared_w: Dictionary) -> bool:
	var after: String = info(id).after
	return after == "" or int(cleared_w.get(after, 0)) > 0
