extends SceneTree
var failures := 0
func _initialize() -> void:
	call_deferred("run")
func verify(condition: bool, message: String) -> void:
	if not condition:
		failures += 1
		push_error("TEST FAILED: " + message)
func frames(count: int) -> void:
	for i in range(count):
		await physics_frame
func run() -> void:
	var scene = load("res://main.tscn").instantiate()
	root.add_child(scene)
	await frames(3)
	verify(scene.enemies.size() == 2, "Two enemies")
	verify(scene.gems.size() == 3, "Three crystals")
	verify(scene.menu.visible, "Start menu visible")
	var screen := root.get_visible_rect()
	for control in scene.gui.get_children():
		if control is Button:
			verify(screen.encloses(control.get_global_rect()), "Touch button is on screen: " + control.text)
	verify(screen.encloses(scene.menu.get_global_rect()), "Menu fits the screen")
	scene.started = true
	scene.menu.hide()
	await frames(15)
	var finger := InputEventScreenTouch.new()
	finger.index = 0
	finger.pressed = true
	finger.position = Vector2(100, 600)
	scene._input(finger)
	verify(scene.stick_id == 0, "Movement finger captured")
	var second := InputEventScreenTouch.new()
	second.index = 1
	second.pressed = true
	second.position = scene.touch_actions[0].button.get_global_rect().get_center()
	scene._input(second)
	verify(scene.jump_queued, "Second finger can jump while movement finger is held")
	verify(scene.stick_id == 0, "Jump does not release movement finger")
	finger.pressed = false
	scene._input(finger)
	scene.jump_queued = false
	var before: Vector3 = scene.hero.position
	Input.action_press("forward")
	await frames(30)
	Input.action_release("forward")
	verify(scene.hero.position.z < before.z - 1, "Forward movement")
	scene.jump_queued = true
	await frames(12)
	verify(scene.hero.position.y > 0.6, "Jump leaves ground")
	await frames(55)
	scene.toggle_pause()
	before = scene.hero.position
	Input.action_press("right")
	await frames(5)
	Input.action_release("right")
	verify(scene.hero.position.is_equal_approx(before), "Pause stops movement")
	scene.toggle_pause()
	scene.enemies[0].body.position.y = -10
	await frames(3)
	verify(scene.enemies[0].body.position.y > -1, "Fallen enemy returns to arena")
	for enemy in scene.enemies:
		scene.hero.position = enemy.body.position + Vector3(0, 0, 1.8)
		scene.attack_time = 0
		scene.attack()
		var hp: int = enemy.hp
		scene.attack()
		verify(enemy.hp == hp, "Attack cooldown")
		for i in range(2):
			scene.attack_time = 0
			scene.attack()
		verify(enemy.hp == 0, "Enemy defeated by three hits")
	verify(scene.victories == 2, "Victory count")
	for gem in scene.gems:
		scene.hero.position = gem.position - Vector3.UP * 0.7
		await frames(3)
	verify(scene.collected == 3, "Crystals collected")
	verify(scene.done and scene.menu.visible, "Level completion menu")
	scene.reset()
	verify(scene.victories == 0 and scene.collected == 0 and scene.health == 5, "Replay resets objectives")
	verify(scene.enemies[0].hp == 3 and scene.gems[0].visible, "Replay restores enemies and crystals")
	print("Godot smoke tests completed; failures: ", failures)
	quit(0 if failures == 0 else 1)
