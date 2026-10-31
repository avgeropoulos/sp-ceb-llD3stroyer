extends SceneTree
## Digs and places hundreds of random blocks, then checks the quick per-block
## updates draw exactly what a full redraw would. Also times one block change.
## Does it for a plain level and for the big home (drawn in chunks, top-only quads).
var f := 0


func _process(_dt):
	f += 1
	if f < 3:
		return false
	var lv := Level.new()
	root.add_child(lv)
	lv.generate()
	var why := _check(lv, Vector3i(0, 0, 0), Vector3i(Level.LX - 1, 10, Level.LZ - 1))
	if why == "":
		Level.cache_path = ""
		var home := Level.new()
		root.add_child(home)
		home.generate_home()
		var a := Vector3i(Level.HOME_OX - 10, Level.HOME_GY - 6, Level.HOME_OZ - 10)
		why = _check(home, a, a + Vector3i(30, 10, 30))
	if why != "":
		print("TEST FAILED: " + why)
		quit(1)
		return true
	print("TEST PASSED")
	quit(0)
	return true


func _check(lv: Level, lo: Vector3i, hi: Vector3i) -> String:
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var worst := 0.0
	for i in 800:
		var c := Vector3i(rng.randi_range(lo.x, hi.x), rng.randi_range(lo.y, hi.y), rng.randi_range(lo.z, hi.z))
		var t := Level.AIR if rng.randf() < 0.75 else rng.randi_range(1, Level.BLOCK_TYPES - 1)
		var t0 := Time.get_ticks_usec()
		lv.set_cell(c.x, c.y, c.z, t)
		worst = maxf(worst, (Time.get_ticks_usec() - t0) / 1000.0)
	var quick := {}
	for k in lv.slot_of:
		quick[k] = lv.slot_of[k].x
	for k in lv.mm_nodes.size():
		if lv.mm_nodes[k] == null:
			continue
		var mm: MultiMesh = lv.mm_nodes[k].multimesh
		for i in lv.slots[k].size():
			if Vector3i(mm.get_instance_transform(i).origin) != lv.slots[k][i]:
				return "slot %d of key %d draws %s, should be %s" % [i, k, mm.get_instance_transform(i).origin, lv.slots[k][i]]
	lv.rebuild()
	var full := {}
	for k in lv.slot_of:
		full[k] = lv.slot_of[k].x
	if quick != full:
		var miss := 0
		for k in full:
			if not quick.has(k) or quick[k] != full[k]:
				miss += 1
		return "quick updates show %d blocks, full redraw shows %d (%d differ)" % [quick.size(), full.size(), miss]
	print("%s: one block change: worst %.2f ms (800 random changes)" % ["home" if lv.is_home else "level", worst])
	return ""
