package com.critters.game

/**
 * The helper for young players: works out what to do next from the story flags,
 * finds the way there (across maps), and tells the game which way to walk.
 */
object Guide {
    /** Where the story wants you to go: an NPC on a map, and what to say about it. */
    class Goal(val map: String, val npc: String, val away: String, val here: String)

    fun goal(g: Game): Goal? {
        val f = g.flags
        return when {
            "starter" !in f -> Goal("lab", "ball3", "GO TO PROFESSOR HAZEL'S LAB. FOLLOW THE BLINKING ARROW!",
                "PICK A CRITTER! WALK UP TO A BALL ON THE TABLE AND PRESS A.")
            "dex" !in f -> Goal("lab", "prof", "GO BACK TO PROFESSOR HAZEL'S LAB.",
                "TALK TO PROFESSOR HAZEL. WALK NEXT TO HER AND PRESS A.")
            "badge1" !in f -> Goal("gym1", "leader", "WALK NORTH TO MAPLE CITY AND BEAT THE GYM LEADER! FOLLOW THE ARROW.",
                "WALK UP TO THE GYM LEADER AND PRESS A TO BATTLE!")
            "badge2" !in f -> Goal("gym2", "leader", "GO EAST THROUGH THE FOREST TO TIDEPORT AND BEAT THE GYM LEADER! FOLLOW THE ARROW.",
                "WALK UP TO THE GYM LEADER AND PRESS A TO BATTLE!")
            "badge3" !in f -> Goal("gym3", "leader", "WALK NORTH TO SPARKTON AND BEAT THE GYM LEADER! FOLLOW THE ARROW.",
                "WALK UP TO THE GYM LEADER AND PRESS A TO BATTLE!")
            "rival3" !in f -> Goal("peak", "rival", "CLIMB SUMMIT CAVE TO THE TOP OF THE MOUNTAIN! FOLLOW THE ARROW.",
                "YOUR RIVAL IS WAITING. WALK UP TO HIM!")
            "solaris" !in f -> Goal("peak", "solaris", "GO BACK UP TO SUMMIT PEAK.",
                "WALK UP TO THE LEGENDARY SOLARIS AND PRESS A!")
            else -> null
        }
    }

    /** What the guide shows on the current map. */
    class Plan(
        /** Which way to step next, or null when you're already there. */
        val step: Dir?,
        /** A tile to mark with a bouncing arrow (a door, or the person to talk to). */
        val markX: Int, val markY: Int,
        /** True when you should press A (you're next to the person). */
        val pressA: Boolean,
        val hint: String,
    )

    /** One way off a map: either a warp tile or an edge of the map. */
    private class Exit(val to: String, val tiles: List<Pair<Int, Int>>, val dir: Dir?)

    private fun exits(g: Game, m: MapDef, current: Boolean): List<Exit> {
        val out = ArrayList<Exit>()
        for (w in m.warps) {
            // "BACK" leads wherever you came in from; failing that, to whichever map has a door here.
            val to = if (w.map == "BACK") {
                if (current) g.backMap() ?: World.maps.values.firstOrNull { o -> o.warps.any { it.map == m.id } }?.id else null
            } else w.map
            if (to != null) out += Exit(to, listOf(w.x to w.y), if (m.at(w.x, w.y) == 'E') Dir.DOWN else null)
        }
        for (d in Dir.entries) {
            val link = m.link(d) ?: continue
            val edge = when (d) {
                Dir.UP -> (0 until m.w).map { it to 0 }
                Dir.DOWN -> (0 until m.w).map { it to m.h - 1 }
                Dir.LEFT -> (0 until m.h).map { 0 to it }
                Dir.RIGHT -> (0 until m.h).map { m.w - 1 to it }
            }.filter { (x, y) -> m.at(x, y) !in Art.SOLID && m.at(x, y) != 'L' }
            out += Exit(link.map, edge, d)
        }
        return out
    }

    /** The first exit to take from the current map to reach [target], by map-to-map search. */
    private fun firstExit(g: Game, target: String): Exit? {
        val start = g.map
        val first = HashMap<String, Exit>()
        val queue = ArrayDeque<String>()
        for (e in exits(g, start, true)) {
            if (e.to !in first && e.to != start.id) {
                first[e.to] = e
                queue += e.to
            }
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == target) return first[id]
            val m = World.maps[id] ?: continue
            for (e in exits(g, m, false)) {
                if (e.to !in first && e.to != start.id) {
                    first[e.to] = first.getValue(id)
                    queue += e.to
                }
            }
        }
        return null
    }

    fun plan(g: Game): Plan? {
        val goal = goal(g) ?: return null
        val m = g.map
        if (m.id == goal.map) {
            val npc = g.npcs.firstOrNull { it.id == goal.npc } ?: return null
            // Stand next to them, or across a counter from them.
            val spots = HashMap<Pair<Int, Int>, Dir>()
            for (d in Dir.entries) {
                val x = npc.x - d.dx
                val y = npc.y - d.dy
                if (m.at(x, y) == 'T') spots[(x - d.dx) to (y - d.dy)] = d else spots[x to y] = d
            }
            val here = g.px to g.py
            spots[here]?.let { return Plan(it, npc.x, npc.y, true, goal.here) }
            val step = path(g, spots.keys, null)
            return Plan(step, npc.x, npc.y, false, goal.here)
        }
        val exit = firstExit(g, goal.map) ?: return null
        val here = g.px to g.py
        if (here in exit.tiles && exit.dir != null) {
            val mark = exit.tiles.first()
            return Plan(exit.dir, mark.first, mark.second, false, goal.away)
        }
        val step = path(g, exit.tiles.toSet(), exit.dir)
        val mark = if (exit.dir == null || m.at(exit.tiles[0].first, exit.tiles[0].second) == 'E') exit.tiles[0] else -1 to -1
        return Plan(step, mark.first, mark.second, false, goal.away)
    }

    /** Breadth-first search over walkable tiles; returns the first step toward any goal tile. */
    private fun path(g: Game, goals: Set<Pair<Int, Int>>, exitDir: Dir?): Dir? {
        val m = g.map
        val start = g.px to g.py
        if (start in goals) return exitDir
        val firstStep = HashMap<Pair<Int, Int>, Dir>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        firstStep[start] = Dir.DOWN
        queue += start
        val blocked = g.npcs.filter { !it.def.wander }.map { it.x to it.y }.toSet()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (d in Dir.entries) {
                var nx = cur.first + d.dx
                var ny = cur.second + d.dy
                if (nx !in 0 until m.w || ny !in 0 until m.h) continue
                val ch = m.at(nx, ny)
                if (ch == 'L') {
                    if (d != Dir.DOWN) continue
                    ny += 1
                    if (m.at(nx, ny) in Art.SOLID || m.at(nx, ny) == 'L') continue
                }
                val p = nx to ny
                if (p in firstStep) continue
                val isGoal = p in goals
                if (!isGoal) {
                    if (m.at(nx, ny) in Art.SOLID || m.at(nx, ny) in Art.WARPS || p in blocked) continue
                } else if (m.at(nx, ny) in Art.SOLID) continue
                firstStep[p] = if (cur == start) d else firstStep.getValue(cur)
                if (isGoal) return firstStep[p]
                queue += p
            }
        }
        return null
    }
}
