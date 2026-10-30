class_name Boot
## Where the game goes when the scene (re)starts: the title screen, the home base or 1-1.

static var place := "title"   # "title", "home" or "level"
static var slot := 0          # which save file (1-3)
static var current: Node      # the running game (tests follow it across switches)
