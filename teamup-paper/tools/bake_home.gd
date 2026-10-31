extends SceneTree
## Bakes the home's starting world into data/home_cache.bin so the game loads it in a
## blink (making it takes about a second on a computer, longer on a tablet).
## CI runs this right before exporting the APK, so the file always matches the code.
## Don't commit the file (it's in .gitignore): without it the game just makes the home.
## Run: godot --headless --path . --script res://tools/bake_home.gd


func _initialize() -> void:
	Level.cache_path = ""   # make it fresh, don't load an old one
	var lv := Level.new()
	root.add_child(lv)
	var t := Time.get_ticks_msec()
	lv.generate_home()
	var ok := lv.save_home_cache(ProjectSettings.globalize_path(Level.HOME_CACHE))
	print("baked the home in %d ms: %s" % [Time.get_ticks_msec() - t, "OK" if ok else "FAILED"])
	quit(0 if ok else 1)
