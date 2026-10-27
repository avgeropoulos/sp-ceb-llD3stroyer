extends Node3D

const SPEED := 6.5
const ARENA := 16.0
var hero: CharacterBody3D
var model: Node3D
var limbs: Array[Node3D] = []
var spring: SpringArm3D
var rig: Node3D
var camera: Camera3D
var enemies: Array[Dictionary] = []
var gems: Array[Node3D] = []
var collected := 0
var victories := 0
var health := 5
var hurt_time := 0.0
var attack_time := 0.0
var jump_queued := false
var walk_time := 0.0
var started := false
var paused := false
var done := false
var yaw := 0.0
var stick := Vector2.ZERO
var stick_id := -1
var look_id := -1
var stick_base := Vector2.ZERO
var last_look := Vector2.ZERO
var gui: Control
var hud: Label
var fps: Label
var hint: Label
var menu: PanelContainer
var menu_title: Label
var menu_body: Label
var start_button: Button
var stick_pad: Panel
var stick_knob: Panel
var fire_material: StandardMaterial3D
var ring: MeshInstance3D
var materials: Dictionary = {}
var touch_actions: Array[Dictionary] = []

func material(color: Color, roughness: float = 0.85, metallic: float = 0.0) -> StandardMaterial3D:
	var key := str(color) + str(roughness) + str(metallic)
	if materials.has(key):
		return materials[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = roughness
	m.metallic = metallic
	materials[key] = m
	return m

func mesh_node(parent: Node3D, mesh: Mesh, pos: Vector3, mat: Material) -> MeshInstance3D:
	var n := MeshInstance3D.new()
	n.mesh = mesh
	n.material_override = mat
	n.position = pos
	parent.add_child(n)
	return n

func ball(parent: Node3D, pos: Vector3, size: Vector3, color: Color) -> MeshInstance3D:
	var shape := SphereMesh.new()
	shape.radius = 0.5
	shape.height = 1.0
	shape.radial_segments = 16
	shape.rings = 8
	var n := mesh_node(parent, shape, pos, material(color, 0.65))
	n.scale = size
	return n

func box(parent: Node3D, pos: Vector3, size: Vector3, mat: Material, solid := false) -> MeshInstance3D:
	var shape := BoxMesh.new()
	shape.size = size
	var n := mesh_node(parent, shape, pos, mat)
	if solid:
		var body := StaticBody3D.new()
		var collision := CollisionShape3D.new()
		var bounds := BoxShape3D.new()
		bounds.size = size
		collision.shape = bounds
		body.add_child(collision)
		n.add_child(body)
	return n

func _ready() -> void:
	Engine.max_fps = 60
	setup_input()
	build_world()
	build_hero()
	build_ui()
	reset()
	show_menu("TEAM UP", "SUNSET GARDEN\nA small Godot graphics test\n\nCollect 3 crystals and defeat 2 garden guardians.\nLeft thumb: move • Drag the open right side: camera\nJUMP to hop • ATTACK when close to an enemy", "LET'S PLAY")

func setup_input() -> void:
	var keys := {"left": KEY_A, "right": KEY_D, "forward": KEY_W, "back": KEY_S, "jump": KEY_SPACE, "attack": KEY_F, "pause": KEY_ESCAPE}
	for action in keys:
		InputMap.add_action(action)
		var event := InputEventKey.new()
		event.physical_keycode = keys[action]
		InputMap.action_add_event(action, event)

func build_world() -> void:
	var world := WorldEnvironment.new()
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var atmosphere := ProceduralSkyMaterial.new()
	atmosphere.sky_top_color = Color("568fc5")
	atmosphere.sky_horizon_color = Color("f7d9b4")
	atmosphere.ground_horizon_color = Color("ecc7a2")
	atmosphere.ground_bottom_color = Color("55747c")
	sky.sky_material = atmosphere
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("b7cfe7")
	env.ambient_light_energy = 0.55
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color("c5d8db")
	env.fog_density = 0.005
	world.environment = env
	add_child(world)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-42, -32, 0)
	sun.light_color = Color("ffdbab")
	sun.light_energy = 1.5
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 65.0
	add_child(sun)
	var grass := material(Color("84aa69"))
	var noise := FastNoiseLite.new()
	noise.frequency = 0.12
	var texture := NoiseTexture2D.new()
	texture.width = 256
	texture.height = 256
	texture.noise = noise
	texture.seamless = true
	var gradient := Gradient.new()
	gradient.set_color(0, Color("71885e"))
	gradient.set_color(1, Color("c4d49b"))
	texture.color_ramp = gradient
	grass.albedo_texture = texture
	grass.uv1_scale = Vector3(12, 12, 12)
	box(self, Vector3(0, -0.7, 0), Vector3(36, 1.4, 36), grass, true)
	box(self, Vector3(0, -2.0, 0), Vector3(37, 2, 37), material(Color("a89175")))
	var water := material(Color("449cae"), 0.18, 0.25)
	box(self, Vector3(0, -3.1, 0), Vector3(300, 0.1, 300), water)
	var stone := material(Color("d8c8aa"))
	for i in range(12):
		box(self, Vector3(sin(i * 0.6) * 0.4, 0.035, 10 - i * 1.65), Vector3(2.2, 0.07, 1.4), stone)
	# Raised terrace with accessible steps and a stone arch.
	box(self, Vector3(0, 0.7, -10), Vector3(10, 1.4, 7), stone, true)
	for i in range(4):
		box(self, Vector3(0, (i + 1) * 0.175, -5.4 - i * 0.65), Vector3(4, (i + 1) * 0.35, 0.7), stone, true)
	for side in [-1, 1]:
		box(self, Vector3(side * 3.2, 3.1, -11), Vector3(0.8, 3.4, 0.8), stone, true)
		ball(self, Vector3(side * 3.2, 4.9, -11), Vector3.ONE * 1.1, Color("d5b77d"))
	box(self, Vector3(0, 4.7, -11), Vector3(7.2, 0.6, 1), stone, true)
	var rng := RandomNumberGenerator.new()
	rng.seed = 27
	for i in range(18):
		var a := float(i) * TAU / 18.0
		var pos := Vector3(cos(a) * 15, 0, sin(a) * 15)
		var trunk := CylinderMesh.new()
		trunk.top_radius = 0.18
		trunk.bottom_radius = 0.35
		trunk.height = 2.8
		mesh_node(self, trunk, pos + Vector3.UP * 1.4, material(Color("7c6654")))
		ball(self, pos + Vector3(0, 3.1, 0), Vector3(3.5, 3.8, 3.4), Color("467756"))
		ball(self, pos + Vector3(0.7, 4, 0.2), Vector3(2.4, 2.4, 2.4), Color("78a365"))
	for i in range(70):
		var x := rng.randf_range(-15, 15)
		var z := rng.randf_range(-14, 14)
		if abs(x) < 5:
			continue
		if i % 4 == 0:
			ball(self, Vector3(x, 0.3, z), Vector3(1, 0.7, 0.8), Color("9da7a4"))
		else:
			ball(self, Vector3(x, 0.14, z), Vector3(0.15, 0.28, 0.15), Color("f6d9a2") if i % 2 == 0 else Color("d7a7bb"))
	fire_material = material(Color("ffe7a6"), 0.2, 0.25)
	fire_material.emission_enabled = true
	fire_material.emission = Color("ffce73")
	fire_material.emission_energy_multiplier = 1.5
	for pos in [Vector3(-8, 1, -2), Vector3(8, 1, 4), Vector3(0, 2.4, -10)]:
		var crystal := Node3D.new()
		crystal.position = pos
		crystal.set_meta("base_y", pos.y)
		add_child(crystal)
		var gem := PrismMesh.new()
		gem.size = Vector3(0.7, 1.2, 0.7)
		mesh_node(crystal, gem, Vector3.ZERO, fire_material)
		gems.append(crystal)

func build_hero() -> void:
	hero = CharacterBody3D.new()
	hero.name = "Rocco"
	hero.position = Vector3(0, 0.1, 9)
	add_child(hero)
	var collision := CollisionShape3D.new()
	var capsule := CapsuleShape3D.new()
	capsule.radius = 0.35
	capsule.height = 1.7
	collision.shape = capsule
	collision.position.y = 0.85
	hero.add_child(collision)
	model = Node3D.new()
	hero.add_child(model)
	ball(model, Vector3(0, 1, 0), Vector3(0.85, 0.95, 0.5), Color("d65745"))
	ball(model, Vector3(0, 1.65, 0), Vector3(0.72, 0.72, 0.68), Color("f2bd8f"))
	ball(model, Vector3(0, 1.96, -0.02), Vector3(0.84, 0.3, 0.75), Color("b63f39"))
	ball(model, Vector3(0, 1.88, 0.3), Vector3(0.8, 0.12, 0.55), Color("d65745"))
	for side in [-1, 1]:
		ball(model, Vector3(side * 0.15, 1.68, 0.31), Vector3(0.12, 0.18, 0.08), Color("24364b"))
		var leg := Node3D.new()
		leg.position = Vector3(side * 0.22, 0.7, 0)
		model.add_child(leg)
		ball(leg, Vector3(0, -0.28, 0), Vector3(0.34, 0.65, 0.35), Color("365679"))
		ball(leg, Vector3(0, -0.57, 0.1), Vector3(0.4, 0.22, 0.57), Color("4b4140"))
		limbs.append(leg)
		var arm := Node3D.new()
		arm.position = Vector3(side * 0.48, 1.25, 0)
		model.add_child(arm)
		ball(arm, Vector3(0, -0.22, 0), Vector3(0.26, 0.65, 0.28), Color("d65745"))
		ball(arm, Vector3(0, -0.53, 0), Vector3.ONE * 0.28, Color("f2bd8f"))
		limbs.append(arm)
	rig = Node3D.new()
	add_child(rig)
	spring = SpringArm3D.new()
	spring.spring_length = 8
	spring.rotation.x = -0.35
	spring.margin = 0.3
	spring.add_excluded_object(hero.get_rid())
	rig.add_child(spring)
	camera = Camera3D.new()
	camera.fov = 65
	spring.add_child(camera)
	camera.current = true
	for i in range(2):
		var foe := CharacterBody3D.new()
		var pos := Vector3(-6 if i == 0 else 7, 0.1, -1 if i == 0 else 2)
		foe.position = pos
		add_child(foe)
		var c := CollisionShape3D.new()
		var shape := SphereShape3D.new()
		shape.radius = 0.6
		c.shape = shape
		c.position.y = 0.6
		foe.add_child(c)
		var visual := Node3D.new()
		foe.add_child(visual)
		ball(visual, Vector3(0, 0.65, 0), Vector3(1.4, 1.3, 1.4), Color("9775b4") if i == 0 else Color("64a5a0"))
		for side in [-1, 1]:
			ball(visual, Vector3(side * 0.24, 0.85, 0.56), Vector3(0.3, 0.38, 0.16), Color.WHITE)
			ball(visual, Vector3(side * 0.24, 0.85, 0.66), Vector3(0.12, 0.2, 0.08), Color("263447"))
		enemies.append({"body": foe, "visual": visual, "hp": 3, "home": pos})

func panel_style(color: Color, radius := 20) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = color
	style.set_corner_radius_all(radius)
	style.content_margin_left = 20
	style.content_margin_right = 20
	style.content_margin_top = 15
	style.content_margin_bottom = 15
	return style

func label_at(text: String, pos: Vector2, size: int) -> Label:
	var label := Label.new()
	label.text = text
	label.position = pos
	label.add_theme_font_size_override("font_size", size)
	label.add_theme_color_override("font_shadow_color", Color(0.1, 0.2, 0.25, 0.9))
	label.add_theme_constant_override("shadow_offset_x", 1)
	label.add_theme_constant_override("shadow_offset_y", 2)
	gui.add_child(label)
	return label

func button(text: String, pos: Vector2, size: Vector2, callback: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.position = pos
	b.size = size
	b.add_theme_stylebox_override("normal", panel_style(Color("254f61")))
	b.add_theme_stylebox_override("pressed", panel_style(Color("b58451")))
	b.add_theme_font_size_override("font_size", 22)
	b.gui_input.connect(func(event: InputEvent):
		if event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_LEFT and event.device != InputEvent.DEVICE_ID_EMULATION:
			callback.call())
	touch_actions.append({"button": b, "callback": callback})
	gui.add_child(b)
	return b

func build_ui() -> void:
	var canvas := CanvasLayer.new()
	add_child(canvas)
	gui = Control.new()
	gui.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	gui.mouse_filter = Control.MOUSE_FILTER_IGNORE
	canvas.add_child(gui)
	hud = label_at("", Vector2(30, 25), 23)
	fps = label_at("", Vector2(30, 63), 16)
	hint = label_at("SUNSET GARDEN • GODOT MOBILE GRAPHICS TEST", Vector2(30, 93), 16)
	var jump := button("JUMP", Vector2.ZERO, Vector2(140, 90), func(): jump_queued = true)
	jump.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
	jump.offset_left = -175
	jump.offset_top = -125
	jump.offset_right = -35
	jump.offset_bottom = -35
	var attack_button := button("ATTACK", Vector2.ZERO, Vector2(140, 90), attack)
	attack_button.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
	attack_button.offset_left = -335
	attack_button.offset_top = -155
	attack_button.offset_right = -195
	attack_button.offset_bottom = -65
	var pause_button := button("PAUSE", Vector2.ZERO, Vector2(120, 55), toggle_pause)
	pause_button.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	pause_button.offset_left = -150
	pause_button.offset_top = 25
	pause_button.offset_right = -30
	pause_button.offset_bottom = 80
	stick_pad = Panel.new()
	stick_pad.size = Vector2(150, 150)
	stick_pad.mouse_filter = Control.MOUSE_FILTER_IGNORE
	stick_pad.add_theme_stylebox_override("panel", panel_style(Color(0.12, 0.25, 0.3, 0.45), 75))
	gui.add_child(stick_pad)
	stick_knob = Panel.new()
	stick_knob.size = Vector2(60, 60)
	stick_knob.mouse_filter = Control.MOUSE_FILTER_IGNORE
	stick_knob.add_theme_stylebox_override("panel", panel_style(Color(0.9, 0.95, 0.9, 0.8), 30))
	gui.add_child(stick_knob)
	menu = PanelContainer.new()
	menu.add_theme_stylebox_override("panel", panel_style(Color(0.07, 0.16, 0.22, 0.95)))
	menu.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	menu.offset_left = -340
	menu.offset_top = -230
	menu.offset_right = 340
	menu.offset_bottom = 230
	gui.add_child(menu)
	var column := VBoxContainer.new()
	column.add_theme_constant_override("separation", 18)
	menu.add_child(column)
	menu_title = Label.new()
	menu_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	menu_title.add_theme_font_size_override("font_size", 44)
	menu_title.modulate = Color("ffe0a1")
	column.add_child(menu_title)
	menu_body = Label.new()
	menu_body.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	menu_body.add_theme_font_size_override("font_size", 20)
	column.add_child(menu_body)
	start_button = Button.new()
	start_button.custom_minimum_size = Vector2(0, 65)
	start_button.add_theme_font_size_override("font_size", 24)
	start_button.pressed.connect(func():
		if done:
			reset()
		started = true
		paused = false
		menu.hide())
	column.add_child(start_button)

func show_menu(title: String, body: String, action: String) -> void:
	menu_title.text = title
	menu_body.text = body
	start_button.text = action
	menu.show()
	stick = Vector2.ZERO
	stick_id = -1
	look_id = -1
	jump_queued = false

func toggle_pause() -> void:
	if not started or done:
		return
	paused = not paused
	if paused:
		show_menu("TAKE A BREAK", "Your garden adventure is paused.\n\nKeyboard: WASD • Space jump • F attack\nController: left stick • A jump • B attack\nRight stick or screen drag: rotate camera", "RESUME")
	else:
		menu.hide()

func reset() -> void:
	hero.position = Vector3(0, 0.2, 9)
	hero.velocity = Vector3.ZERO
	collected = 0
	victories = 0
	health = 5
	hurt_time = 2
	attack_time = 0
	done = false
	for gem in gems:
		gem.show()
	for enemy in enemies:
		enemy.hp = 3
		enemy.body.position = enemy.home
		enemy.body.show()
		enemy.body.get_child(0).set_deferred("disabled", false)
	rig.position = hero.position + Vector3.UP * 1.4

func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT and started and not paused and not done:
		toggle_pause()

func _input(event: InputEvent) -> void:
	if event.is_action_pressed("pause"):
		toggle_pause()
	if menu == null or menu.visible:
		return
	var size := get_viewport().get_visible_rect().size
	if event is InputEventScreenTouch:
		if event.pressed:
			for action in touch_actions:
				if action.button.get_global_rect().has_point(event.position):
					action.callback.call()
					get_viewport().set_input_as_handled()
					return
		if event.pressed and event.position.x < size.x * 0.35 and event.position.y > size.y * 0.5 and stick_id == -1:
			stick_id = event.index
			stick_base = event.position
		elif not event.pressed:
			if event.index == stick_id:
				stick_id = -1
				stick = Vector2.ZERO
			if event.index == look_id:
				look_id = -1
	if event is InputEventScreenDrag and event.index == stick_id:
		stick = ((event.position - stick_base) / 65.0).limit_length()
	if event is InputEventJoypadButton and event.pressed:
		if event.button_index == JOY_BUTTON_A:
			jump_queued = true
		if event.button_index == JOY_BUTTON_B:
			attack()

func _unhandled_input(event: InputEvent) -> void:
	if not started or paused or done:
		return
	if event is InputEventScreenTouch and event.pressed and event.position.x > get_viewport().get_visible_rect().size.x * 0.4:
		look_id = event.index
		last_look = event.position
	if event is InputEventScreenDrag and event.index == look_id:
		yaw -= event.relative.x * 0.007
	if event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT):
		yaw -= event.relative.x * 0.007

func _physics_process(dt: float) -> void:
	if hud == null:
		return
	var screen := get_viewport().get_visible_rect().size
	var center := stick_base if stick_id >= 0 else Vector2(115, screen.y - 120)
	stick_pad.position = center - Vector2(75, 75)
	stick_knob.position = center + stick * 55 - Vector2(30, 30)
	hud.text = "CRYSTALS  %d / 3     GUARDIANS  %d / 2     HEALTH  %d / 5" % [collected, victories, health]
	fps.text = "%d FPS  •  60 FPS target  •  Mobile renderer" % Engine.get_frames_per_second()
	if not started or paused or done:
		return
	walk_time += dt
	hurt_time = maxf(0, hurt_time - dt)
	attack_time = maxf(0, attack_time - dt)
	var input := Input.get_vector("left", "right", "forward", "back") + stick
	var pads := Input.get_connected_joypads()
	if not pads.is_empty():
		var pad := pads[0]
		var axes := Vector2(Input.get_joy_axis(pad, JOY_AXIS_LEFT_X), Input.get_joy_axis(pad, JOY_AXIS_LEFT_Y))
		if axes.length() > 0.18:
			input += axes
		var look := Input.get_joy_axis(pad, JOY_AXIS_RIGHT_X)
		if absf(look) > 0.18:
			yaw -= look * dt * 2
	input = input.limit_length()
	var direction := Vector3(input.x, 0, input.y).rotated(Vector3.UP, yaw)
	hero.velocity.x = direction.x * SPEED
	hero.velocity.z = direction.z * SPEED
	if hero.is_on_floor():
		if jump_queued or Input.is_action_just_pressed("jump"):
			hero.velocity.y = 8
	else:
		hero.velocity.y -= 22 * dt
	jump_queued = false
	hero.move_and_slide()
	if direction.length() > 0.1:
		model.rotation.y = lerp_angle(model.rotation.y, atan2(direction.x, direction.z), dt * 12)
	for i in range(limbs.size()):
		limbs[i].rotation.x = sin(walk_time * 11 + (i % 2) * PI + (i / 2) * PI) * input.length() * 0.55
	if Input.is_action_just_pressed("attack"):
		attack()
	if hero.position.y < -5:
		hero.position = Vector3(0, 0.3, 9)
		hero.velocity = Vector3.ZERO
		health = 5
		hurt_time = 2
	rig.position = rig.position.lerp(hero.position + Vector3.UP * 1.4, 1 - exp(-dt * 10))
	rig.rotation.y = yaw
	for gem in gems:
		gem.rotation.y += dt
		gem.position.y = float(gem.get_meta("base_y")) + sin(walk_time * 2) * 0.15
		if gem.visible and hero.position.distance_to(gem.position - Vector3.UP * 0.7) < 1.1:
			gem.hide()
			collected += 1
	for enemy in enemies:
		if enemy.hp <= 0:
			continue
		var body: CharacterBody3D = enemy.body
		var delta := hero.position - body.position
		delta.y = 0
		body.velocity = Vector3(0, body.velocity.y - 22 * dt, 0)
		if delta.length() < 10 and delta.length() > 0.3:
			body.velocity.x = delta.normalized().x * 1.8
			body.velocity.z = delta.normalized().z * 1.8
			body.rotation.y = atan2(delta.x, delta.z)
		body.move_and_slide()
		enemy.visual.scale.y = 1 + sin(walk_time * 5) * 0.08
		if delta.length() < 1.1 and absf(body.position.y - hero.position.y) < 1.5 and hurt_time <= 0:
			health -= 1
			hurt_time = 1.5
			if health <= 0:
				hero.position = Vector3(0, 0.3, 9)
				health = 5
				hurt_time = 3
	if collected == 3 and victories == 2:
		done = true
		show_menu("GARDEN CLEARED!", "3 crystals collected • 2 guardians defeated\n\nThis is the small Godot graphics test.\nTry another lap to check sustained smoothness.\nYour other Team Up games are separate.", "PLAY AGAIN")

func attack() -> void:
	if not started or paused or done or attack_time > 0:
		return
	attack_time = 0.45
	var pulse := TorusMesh.new()
	pulse.inner_radius = 1.2
	pulse.outer_radius = 1.5
	pulse.rings = 24
	pulse.ring_segments = 8
	var visual := mesh_node(self, pulse, hero.position + Vector3.UP * 0.8, fire_material)
	var tween := create_tween()
	tween.tween_property(visual, "scale", Vector3(1.7, 0.15, 1.7), 0.2)
	tween.tween_callback(visual.queue_free)
	for enemy in enemies:
		if enemy.hp > 0 and hero.position.distance_to(enemy.body.position) < 3.2:
			enemy.hp -= 1
			var body: CharacterBody3D = enemy.body
			body.velocity.y = 2
			if enemy.hp <= 0:
				victories += 1
				body.hide()
				body.get_child(0).set_deferred("disabled", true)
