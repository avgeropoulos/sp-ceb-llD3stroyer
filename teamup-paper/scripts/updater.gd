class_name Updater
extends Node
## In-app updates. On the title screen the game asks GitHub which build is newest
## (CI uploads version.txt next to the APK). If it's newer than this one, an UPDATE
## button shows up; tapping it downloads the APK inside the game and opens Android's
## installer ("Do you want to update this app?"). It's the same app with the same
## signing key, so it installs right over the old one.

const BASE := "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/teamup-paper-latest/"
const APK := "TeamUpPaper.apk"

## "" (nothing to say), "checking", "latest", "available", "downloading", "installing", "error"
var status := ""
var message := ""
var my_version := 0
var newest := 0
var progress := 0.0
var _http: HTTPRequest
var _mode := ""
var _apk_path := ""


func _ready() -> void:
	my_version = int(str(ProjectSettings.get_setting("application/config/version", "0")))
	_http = HTTPRequest.new()
	_http.timeout = 60
	_http.max_redirects = 10
	_http.use_threads = true
	add_child(_http)
	_http.request_completed.connect(_on_done)
	_apk_path = OS.get_user_data_dir().path_join("update.apk")


## Only builds made by GitHub know their version number, so only they can update.
func can_update() -> bool:
	return my_version > 0


func check() -> void:
	if not can_update() or _mode != "":
		return
	_mode = "check"
	status = "checking"
	message = "Checking for updates..."
	var err := _http.request(BASE + "version.txt?t=%d" % Time.get_unix_time_from_system())
	if err != OK:
		_fail("Couldn't check for updates")


func download() -> void:
	if status != "available" and status != "error":
		return
	if _mode != "":
		return
	_mode = "apk"
	status = "downloading"
	progress = 0.0
	message = "Downloading the update... 0%"
	_http.download_file = _apk_path
	var err := _http.request(BASE + APK)
	if err != OK:
		_fail("Couldn't start the download")


func _process(_dt: float) -> void:
	if _mode == "apk":
		var total := _http.get_body_size()
		if total > 0:
			progress = clampf(float(_http.get_downloaded_bytes()) / total, 0, 1)
			message = "Downloading the update... %d%%" % int(progress * 100)


func _on_done(result: int, code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	var mode := _mode
	_mode = ""
	_http.download_file = ""
	if result != HTTPRequest.RESULT_SUCCESS or code != 200:
		_fail("No internet? Tap UPDATE to try again" if mode == "apk" else "")
		return
	if mode == "check":
		newest = int(body.get_string_from_utf8().strip_edges())
		if newest > my_version:
			status = "available"
			message = "A NEW VERSION IS READY!"
		else:
			status = "latest"
			message = "You have the newest version"
	elif mode == "apk":
		var f := FileAccess.open(_apk_path, FileAccess.READ)
		var size := f.get_length() if f else 0
		if f:
			f.close()
		if size < 1_000_000:
			_fail("The download didn't finish. Tap UPDATE to try again")
			return
		status = "installing"
		message = "Tap INSTALL (or UPDATE) in the box that pops up"
		install()


## Hands the APK to Android's installer. Godot shares the file through its FileProvider.
## The very first time, Android asks to "allow this source": turn it on, come back, tap UPDATE again.
func install() -> void:
	if OS.get_name() != "Android":
		message = "Downloaded (installing only works on Android)"
		return
	var err := OS.shell_open(_apk_path)
	if err != OK:
		_fail("Couldn't open the installer. Tap UPDATE to try again")
	else:
		status = "error"   # lets UPDATE be tapped again if they cancel or had to allow the source first
		message = "If it asked to ALLOW, turn it on, come back and tap UPDATE again"


func _fail(why: String) -> void:
	if status == "checking":
		status = ""   # a silent check that failed: just don't show anything
		message = ""
		return
	status = "error"
	message = why
