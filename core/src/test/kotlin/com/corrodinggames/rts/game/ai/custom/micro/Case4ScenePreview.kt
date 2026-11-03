package com.corrodinggames.rts.game.ai.custom.micro

import com.corrodinggames.rts.game.ai.custom.micro.case4.Case4Retreat
import io.github.rwx.app.MicroJson
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline preview of CASE4 scene generation.
 *
 * `Case4Retreat.layout()` never reads from the engine instance, so a null engine yields exactly the
 * scene a real run builds. The preview therefore needs no match, no assets and no window: for every
 * seed it writes `map.tmx`, `layout.json` and a top-down `scene.png`, and with several seeds it adds
 * a `compare.png` contact sheet.
 *
 * ```
 * .\gradlew.bat :core:previewCase4 -Ptask=4_1_1 -Pseed=1,2,3,5,8
 * .\gradlew.bat :core:previewCase4 -Ptask=4_3_2 -Pseed=7 -Pscale=14
 * ```
 *
 * The task is evaluator tooling: it lives in the test source set, so it never ships in the jar.
 */
object Case4ScenePreview {
    private val LONG = Color(0xd6, 0x4d, 0x47)
    private val SHORT = Color(0x55, 0x99, 0xbd)
    private val BAY = Color(0x2e, 0x8b, 0x57)
    private val TANK = Color(0x7b, 0x4b, 0xb7)
    private val BIAS = Color(0x56, 0x50, 0xaa)
    private val INK = Color(0x24, 0x36, 0x4b)
    private val MUTED = Color(0x60, 0x71, 0x85)

    private data class Cell(val label: String, val meta: Map<*, *>)

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val task = args.getOrNull(0)?.trim()?.takeUnless { it.isEmpty() } ?: "4_1_1"
        val seeds = (args.getOrNull(1) ?: "1,2,3,5,8").split(',').mapNotNull { it.trim().toLongOrNull() }
        require(seeds.isNotEmpty()) { "no usable seed in the list" }
        val out = Path.of(args.getOrNull(2) ?: "agent-tmp/case4-preview").toAbsolutePath()
        val scale = (args.getOrNull(3)?.toDoubleOrNull() ?: 9.0).coerceIn(3.0, 24.0)
        val ranges = args.getOrNull(4)?.let { it == "1" || it.equals("true", true) } ?: false
        Files.createDirectories(out)
        println("CASE4 scene preview: task=$task seeds=${seeds.joinToString(",")} scale=${fmt(scale)} ranges=$ranges")
        println("task   seed   map      centre     radius fog turrets long short spacing(long/short) centreMax")

        val cells = ArrayList<Cell>()
        for (seed in seeds) {
            val dir = out.resolve("$task-s$seed")
            Files.createDirectories(dir)
            val config = MicroConfig.parse("CASE4-Retreat", "task=$task,seed=$seed,track=realtime,out=$dir", null)
            val scenario = Case4Retreat()
            scenario.prepareMap(config, dir)
            val meta = scenario.layout(config, null)
            Files.writeString(dir.resolve("layout.json"), MicroJson.encode(meta))
            ImageIO.write(render(listOf(Cell("$task seed $seed", meta)), scale, "$task seed $seed", ranges),
                "png", dir.resolve("scene.png").toFile())
            cells.add(Cell("seed $seed", meta))
            println(row(seed, meta, scale))
            println("       -> ${dir.resolve("scene.png")}")
        }
        if (cells.size > 1) {
            val sheet = out.resolve("compare-$task.png")
            ImageIO.write(render(cells, scale, "$task · ${cells.size} seeds", ranges), "png", sheet.toFile())
            println("contact sheet -> $sheet")
        }
    }

    /** One row of the console table: geometry the layout reports, e.g. for comparing seeds. */
    private fun row(seed: Long, meta: Map<*, *>, scale: Double): String {
        val region = meta["region"] as Map<*, *>
        val spacing = meta["achievedTurretSpacingTiles"] as Map<*, *>
        val turrets = meta["turrets"] as List<*>
        val longs = turrets.count { (it as Map<*, *>)["role"] == "long" }
        val longGap = if (longs >= 2) fmt(num(spacing, "long")) else "-"
        val shortGap = if (turrets.size - longs >= 2) fmt(num(spacing, "short")) else "-"
        return String.format(Locale.ROOT,
            "%-6s %-6d %-8s (%2d,%2d) %6d %3d %7d %4d %5d %10s %9.1f",
            meta["task"], seed,
            "${num(meta, "mapWidth").toInt()}x${num(meta, "mapHeight").toInt()}",
            num(region, "centreTileX").toInt(), num(region, "centreTileY").toInt(),
            num(region, "radiusTiles").toInt(),
            num(meta, "fogMode").toInt(),
            turrets.size, longs, turrets.size - longs,
            "$longGap/$shortGap",
            num(meta["achievedCentreDistanceTiles"] as Map<*, *>, "max"))
    }

    /** Draws one or more scenes into a single PNG, one panel per cell. */
    private fun render(cells: List<Cell>, scale: Double, title: String, ranges: Boolean): BufferedImage {
        val columns = if (cells.size <= 2) 1 else 2
        val rows = (cells.size + columns - 1) / columns
        val maxW = cells.maxOf { num(it.meta, "mapWidth") }
        val maxH = cells.maxOf { num(it.meta, "mapHeight") }
        val pad = 18
        val header = 76
        val label = 22
        val gap = 14
        val cellW = (maxW * scale).toInt()
        val cellH = (maxH * scale).toInt()
        val width = pad * 2 + columns * cellW + (columns - 1) * gap
        val height = pad + header + rows * (cellH + label) + (rows - 1) * gap + pad
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xf3, 0xf5, 0xf8)
        g.fillRect(0, 0, width, height)
        g.color = INK
        g.font = Font("Segoe UI", Font.BOLD, 18)
        g.drawString("CASE4 scene preview · $title", pad, pad + 20)
        g.color = MUTED
        g.font = Font("Segoe UI", Font.PLAIN, 11)
        g.drawString("red long-range · blue short-range · green repair bay · purple tank", pad, pad + 40)
        g.drawString("dashed circle = field radius · orange box = top-left 3/5 · arrow = 225° long-range bias" +
            (if (ranges) " · thin circle = attack range" else ""), pad, pad + 56)
        for ((i, cell) in cells.withIndex()) {
            val col = i % columns
            val row = i / columns
            val x = pad + col * (cellW + gap)
            val y = pad + header + row * (cellH + label + gap)
            g.color = Color.WHITE
            g.fillRoundRect(x - 6, y - 6, (num(cell.meta, "mapWidth") * scale).toInt() + 12,
                (num(cell.meta, "mapHeight") * scale).toInt() + 12, 8, 8)
            drawScene(g, cell.meta, scale, x, y, ranges)
            g.color = MUTED
            g.font = Font("Segoe UI", Font.PLAIN, 11)
            g.drawString(caption(cell), x, y + (num(cell.meta, "mapHeight") * scale).toInt() + 15)
        }
        g.dispose()
        return image
    }

    private fun drawScene(g: Graphics2D, meta: Map<*, *>, scale: Double, ox: Int, oy: Int, ranges: Boolean) {
        val w = num(meta, "mapWidth") * scale
        val h = num(meta, "mapHeight") * scale
        val region = meta["region"] as Map<*, *>
        val turrets = rows(meta, "turrets")
        val longs = turrets.filter { it["role"] == "long" }
        fun px(tile: Double) = ox + tile * scale
        fun py(tile: Double) = oy + tile * scale
        // Attack ranges can reach past the map border; clip them so a panel never paints over its
        // neighbours or the header.
        val outer = g.clip
        g.clip = java.awt.Rectangle(ox - 3, oy - 3, w.toInt() + 6, h.toInt() + 6)

        g.color = Color(0xf7, 0xf8, 0xf5)
        g.fillRect(ox, oy, w.toInt(), h.toInt())
        g.color = Color(0xea, 0xee, 0xf2)
        var k = 10.0
        while (k < num(meta, "mapWidth")) {
            g.drawLine(px(k).toInt(), oy, px(k).toInt(), (oy + h).toInt())
            k += 10
        }
        k = 10.0
        while (k < num(meta, "mapHeight")) {
            g.drawLine(ox, py(k).toInt(), (ox + w).toInt(), py(k).toInt())
            k += 10
        }

        // Top-left 3/5 box the turrets must stay inside.
        g.color = Color(0xf6, 0xe7, 0xcd, 0x80)
        g.fillRect(ox, oy, (px(num(region, "boxMaxX") + 1) - ox).toInt(), (py(num(region, "boxMaxY") + 1) - oy).toInt())
        g.color = Color(0xd8, 0xa8, 0x6a)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
        g.drawRect(ox, oy, (px(num(region, "boxMaxX") + 1) - ox).toInt(), (py(num(region, "boxMaxY") + 1) - oy).toInt())

        // Field disc.
        val cx = px(num(region, "centreTileX") + 0.5)
        val cy = py(num(region, "centreTileY") + 0.5)
        val radius = num(region, "radiusTiles") * scale
        g.color = Color(0xd6, 0xe6, 0xd8, 0x55)
        g.fillOval((cx - radius).toInt(), (cy - radius).toInt(), (radius * 2).toInt(), (radius * 2).toInt())
        g.color = Color(0x86, 0xa1, 0x8b)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 4f), 0f)
        g.drawOval((cx - radius).toInt(), (cy - radius).toInt(), (radius * 2).toInt(), (radius * 2).toInt())

        // Attack ranges, only when asked: overlapping coverage is what makes a field unfair.
        if (ranges) {
            g.stroke = BasicStroke(1f)
            for (turret in turrets) {
                val long = turret["role"] == "long"
                val range = num(turret, "attackRange") / 20.0 * scale
                val x = px(num(turret, "tileX") + 0.5)
                val y = py(num(turret, "tileY") + 0.5)
                g.color = if (long) Color(0xd6, 0x4d, 0x47, 0x30) else Color(0x55, 0x99, 0xbd, 0x30)
                g.drawOval((x - range).toInt(), (y - range).toInt(), (range * 2).toInt(), (range * 2).toInt())
            }
        }

        // 225 degree bias, only meaningful when the task actually has long-range guns.
        if (longs.isNotEmpty()) {
            val arrow = radius * 0.85
            val ax = cx + cos(Math.toRadians(225.0)) * arrow
            val ay = cy + sin(Math.toRadians(225.0)) * arrow
            g.color = BIAS
            g.stroke = BasicStroke(2f)
            g.drawLine(cx.toInt(), cy.toInt(), ax.toInt(), ay.toInt())
            val head = 7.0
            val back = Math.toRadians(225.0)
            for (turn in doubleArrayOf(2.65, -2.65)) {
                g.drawLine(ax.toInt(), ay.toInt(),
                    (ax + cos(back + turn) * head).toInt(), (ay + sin(back + turn) * head).toInt())
            }
        }
        g.color = Color(0x61, 0x72, 0x81)
        g.stroke = BasicStroke(1.5f)
        g.drawLine((cx - 4).toInt(), cy.toInt(), (cx + 4).toInt(), cy.toInt())
        g.drawLine(cx.toInt(), (cy - 4).toInt(), cx.toInt(), (cy + 4).toInt())

        // Minimum long-range gap, drawn around every long gun so overlaps are visible at a glance.
        if (longs.size >= 2) {
            g.color = Color(0xd6, 0x4d, 0x47, 0x24)
            g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 5f), 0f)
            val gap = num(region, "spacingLong") * scale
            for (turret in longs) {
                val x = px(num(turret, "tileX") + 0.5)
                val y = py(num(turret, "tileY") + 0.5)
                g.drawOval((x - gap).toInt(), (y - gap).toInt(), (gap * 2).toInt(), (gap * 2).toInt())
            }
        }

        val detailed = scale >= 7.0
        g.stroke = BasicStroke(1f)
        for (turret in turrets) {
            val long = turret["role"] == "long"
            val x = px(num(turret, "tileX") + 0.5 + num(turret, "jitterX"))
            val y = py(num(turret, "tileY") + 0.5 + num(turret, "jitterY"))
            val r = if (long) maxOf(2.6, scale * 0.62) else maxOf(1.9, scale * 0.44)
            g.color = if (long) LONG else SHORT
            g.fillOval((x - r).toInt(), (y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
            g.color = Color.WHITE
            g.drawOval((x - r).toInt(), (y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
            if (detailed) {
                g.color = MUTED
                g.font = Font("Segoe UI", Font.PLAIN, 9)
                g.drawString(turret["alias"].toString(), (x + r + 2).toInt(), (y + 3).toInt())
            }
        }
        for (bay in rows(meta, "repairBays")) square(g, bay, scale, ::px, ::py, BAY, 0.9)
        for (tank in rows(meta, "tanks")) square(g, tank, scale, ::px, ::py, TANK, 0.8)

        // Player corner: the base the turrets must not crowd.
        val corner = meta["baseCornerTile"] as Map<*, *>
        val bx = px(num(corner, "x") + 0.5)
        val by = py(num(corner, "y") + 0.5)
        g.color = Color(0x34, 0x49, 0x5e)
        g.stroke = BasicStroke(1.5f)
        g.drawLine((bx - 5).toInt(), by.toInt(), (bx + 5).toInt(), by.toInt())
        g.drawLine(bx.toInt(), (by - 5).toInt(), bx.toInt(), (by + 5).toInt())
        if (detailed) {
            g.color = MUTED
            g.font = Font("Segoe UI", Font.PLAIN, 9)
            g.drawString("base", (bx - 9).toInt(), (by + 16).toInt())
        }
        g.clip = outer
        g.color = Color(0xc9, 0xd2, 0xd9)
        g.stroke = BasicStroke(1f)
        g.drawRect(ox, oy, w.toInt(), h.toInt())
    }

    private fun square(
        g: Graphics2D, unit: Map<*, *>, scale: Double,
        px: (Double) -> Double, py: (Double) -> Double, color: Color, size: Double,
    ) {
        val side = maxOf(3.0, scale * size)
        val x = px(num(unit, "tileX") + 0.5) - side / 2
        val y = py(num(unit, "tileY") + 0.5) - side / 2
        g.color = color
        g.fillRect(x.toInt(), y.toInt(), side.toInt(), side.toInt())
        g.color = Color.WHITE
        g.drawRect(x.toInt(), y.toInt(), side.toInt(), side.toInt())
    }

    private fun caption(cell: Cell): String {
        val meta = cell.meta
        val region = meta["region"] as Map<*, *>
        val counts = (meta["turretsByType"] as Map<*, *>).entries.joinToString(",") { "${it.key}×${it.value}" }
        return "${cell.label} · ${num(meta, "mapWidth").toInt()}×${num(meta, "mapHeight").toInt()} · fog " +
            "${num(meta, "fogMode").toInt()} · ${(meta["turrets"] as List<*>).size} turrets ($counts) · " +
            "r ${num(region, "radiusTiles").toInt()} · tanks ${(meta["tanks"] as List<*>).size} · " +
            "bays ${(meta["repairBays"] as List<*>).size}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(meta: Map<*, *>, key: String): List<Map<*, *>> =
        (meta[key] as List<*>).map { it as Map<*, *> }

    private fun num(map: Map<*, *>, key: String): Double = (map[key] as Number).toDouble()

    private fun fmt(value: Double): String {
        val text = String.format(Locale.ROOT, "%.1f", value)
        return text.removeSuffix(".0")
    }
}
