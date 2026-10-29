extends SceneTree
## Digs and places hundreds of random blocks, then checks the quick per-block
## updates draw exactly what a full redraw would. Also times one block change.
var f := 0
func _process(_dt):
	f += 1
	if f < 3:
		return false
	var lv := Level.new()
	root.add_child(lv)
	lv.generate()
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var worst := 0.0
	for i in 800:
		var c := Vector3i(rng.randi_range(0, Level.LX - 1), rng.randi_range(0, 10), rng.randi_range(0, Level.LZ - 1))
		var t := Level.AIR if rng.randf() < 0.75 else rng.randi_range(1, Level.ATLAS_TILES - 1)
		var t0 := Time.get_ticks_usec()
		lv.set_cell(c.x, c.y, c.z, t)
		worst = maxf(worst, (Time.get_ticks_usec() - t0) / 1000.0)
	var quick := {}
	for k in lv.slot_of:
		quick[k] = lv.slot_of[k].x
	for t in range(1, Level.ATLAS_TILES):
		var mm: MultiMesh = lv.mm_nodes[t].multimesh
		for i in lv.slots[t].size():
			if Vector3i(mm.get_instance_transform(i).origin) != lv.slots[t][i]:
				print("TEST FAILED: slot %d of type %d draws %s, should be %s" % [i, t, mm.get_instance_transform(i).origin, lv.slots[t][i]])
				quit(1)
				return true
	lv.rebuild()
	var full := {}
	for k in lv.slot_of:
		full[k] = lv.slot_of[k].x
	if quick != full:
		print("TEST FAILED: quick updates show %d blocks, full redraw shows %d" % [quick.size(), full.size()])
		quit(1)
		return true
	print("one block change: worst %.2f ms (800 random changes)" % worst)
	print("TEST PASSED")
	quit(0)
	return true
