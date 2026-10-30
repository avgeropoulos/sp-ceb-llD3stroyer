class_name Saves
## The three save files (user://save1.json .. save3.json), like the real Team Up!.
## Writing goes to a temp file first and is then renamed, so a save can't be half
## written if the tablet dies mid-save. ERASE keeps a .bak copy so it can be undone.

static var prefix := "user://save"   # tests point this somewhere else


static func path(slot: int) -> String:
	return "%s%d.json" % [prefix, slot]


static func exists(slot: int) -> bool:
	return FileAccess.file_exists(path(slot))


static func read(slot: int) -> Dictionary:
	if not exists(slot):
		return {}
	var f := FileAccess.open(path(slot), FileAccess.READ)
	if f == null:
		return {}
	var d = JSON.parse_string(f.get_as_text())
	f.close()
	return d if d is Dictionary else {}


static func write(slot: int, d: Dictionary) -> bool:
	var tmp := path(slot) + ".tmp"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	if f == null:
		return false
	f.store_string(JSON.stringify(d))
	f.close()
	return DirAccess.rename_absolute(ProjectSettings.globalize_path(tmp), ProjectSettings.globalize_path(path(slot))) == OK


## Moves the file to a backup (so UNDO ERASE can bring it back).
static func erase(slot: int) -> void:
	if exists(slot):
		DirAccess.rename_absolute(ProjectSettings.globalize_path(path(slot)), ProjectSettings.globalize_path(path(slot) + ".bak"))


static func can_undo(slot: int) -> bool:
	return not exists(slot) and FileAccess.file_exists(path(slot) + ".bak")


static func undo_erase(slot: int) -> void:
	if can_undo(slot):
		DirAccess.rename_absolute(ProjectSettings.globalize_path(path(slot) + ".bak"), ProjectSettings.globalize_path(path(slot)))
