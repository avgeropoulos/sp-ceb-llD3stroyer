class_name Look
## Which look the game uses: paper cutouts or chunky 3D toys. Remembered between launches.

static var toy := true
static var loaded := false
const FILE := "user://look.cfg"


static func load_choice() -> void:
	if loaded:
		return
	loaded = true
	var c := ConfigFile.new()
	if c.load(FILE) == OK:
		toy = bool(c.get_value("look", "toy", true))


static func save_choice() -> void:
	var c := ConfigFile.new()
	c.set_value("look", "toy", toy)
	c.save(FILE)
