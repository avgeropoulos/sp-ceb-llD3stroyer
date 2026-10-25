package com.roccoquest.team3d

/**
 * Big chunky 3D letters for the title screen: each letter is drawn on a 5x7 grid and every
 * filled square becomes a rounded cube, so the words look built out of Blocky's blocks.
 */
object TitleLetters {
    private val font = mapOf(
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".####", "#....", "#....", "#....", "#....", "#....", ".####"),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        '&' to arrayOf(".##..", "#..#.", ".##..", ".#..#", "#.##.", "#..#.", ".##.#"),
        '!' to arrayOf("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
    )

    private val meshes = HashMap<Char, Mesh>()

    /** Width of one letter cell, in grid squares (5 wide plus a gap). */
    const val ADVANCE = 6f

    /**
     * The mesh for [c], centred on its middle, one grid square = 1 unit, white (tint it).
     * (Rows run downward, so row y sits between 3.5 - y and 2.5 - y.)
     * Null for a space or a letter the font doesn't have.
     */
    fun mesh(c: Char): Mesh? {
        val rows = font[c] ?: return null
        return meshes.getOrPut(c) {
            MeshBuilder().apply {
                // Each run of filled squares in a row is one long block (few points to draw).
                rows.forEachIndexed { y, row ->
                    var x = 0
                    while (x < row.length) {
                        if (row[x] != '#') { x++; continue }
                        val start = x
                        while (x < row.length && row[x] == '#') x++
                        box(start - 2.5f, 2.5f - y, -0.8f, x - 2.5f, 3.5f - y, 0.8f, -1)
                    }
                }
            }.build()
        }
    }
}
