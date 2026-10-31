class_name Boot
## Where the game goes when the scene (re)starts: the title screen, the home base or 1-1.

static var place := "title"   # "title", "home" or "level"
static var slot := 0          # which save file (1-3)
static var current: Node      # the running game (tests follow it across switches)
static var level := "meadow"  # which world 1-1 / 2-1 / 3-1 to build
static var from_level := ""   # the sky map starts the rocket over this island
static var painting := 1     # which giant painting (1-3) you jumped into (its bonus course)
