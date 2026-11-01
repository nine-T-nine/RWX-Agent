package io.github.rwx.ui.model

import io.github.rwx.PlatformStorage
import io.github.rwx.StorageKind
import io.github.rwx.StorageLocation
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Battle rooms show a map preview for whatever map the room currently uses. That lookup must work without opening the
 * level list first: hosting the default map or joining another player's room never scans [LevelSelectViewModel.items].
 */
class LevelSelectViewModelTest {

    @Test
    fun `map entry resolves the sibling preview without scanning the level list`() {
        val root = Files.createTempDirectory("rwx-map-preview").toFile()
        try {
            val mapDir = File(root, "maps/skirmish").apply { mkdirs() }
            File(mapDir, "[p2]Lake (2p).tmx").writeText("")
            File(mapDir, "[p2]Lake (2p)_map.png").writeText("")

            val entry = LevelSelectViewModel(LevelSelectMode.Skirmish, FakeStorage(root))
                .mapEntry("maps/skirmish/[p2]Lake (2p).tmx")

            assertEquals("maps/skirmish/[p2]Lake (2p)_map.png", entry.previewAssetPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `map entry tolerates previews with different letter case and no preview at all`() {
        val root = Files.createTempDirectory("rwx-map-preview-case").toFile()
        try {
            val mapDir = File(root, "maps/skirmish").apply { mkdirs() }
            File(mapDir, "[p2]Case (2p).tmx").writeText("")
            File(mapDir, "[p2]Case (2p)_MAP.PNG").writeText("")
            File(mapDir, "[p2]Bare (2p).tmx").writeText("")

            val viewModel = LevelSelectViewModel(LevelSelectMode.Skirmish, FakeStorage(root))

            assertEquals(
                "maps/skirmish/[p2]Case (2p)_MAP.PNG",
                viewModel.mapEntry("maps/skirmish/[p2]Case (2p).tmx").previewAssetPath,
            )
            assertEquals(null, viewModel.mapEntry("maps/skirmish/[p2]Bare (2p).tmx").previewAssetPath)
        } finally {
            root.deleteRecursively()
        }
    }
}

private class FakeStorage(private val root: File) : PlatformStorage {
    private fun location(name: String) = StorageLocation(name, if (name.isEmpty()) root else File(root, name))

    override val rootDir: StorageLocation = location("")
    override val localDir: StorageLocation = location("local")
    override val cacheDir: StorageLocation = location("cache")
    override val modsDir: StorageLocation = location("mods")
    override val unitsDir: StorageLocation = location("units")
    override val mapsDir: StorageLocation = location("maps")
    override val savesDir: StorageLocation = location("saves")
    override val replaysDir: StorageLocation = location("replays")

    override fun location(kind: StorageKind): StorageLocation = when (kind) {
        StorageKind.ROOT -> rootDir
        StorageKind.LOCAL -> localDir
        StorageKind.CACHE -> cacheDir
        StorageKind.MODS -> modsDir
        StorageKind.UNITS -> unitsDir
        StorageKind.MAPS -> mapsDir
        StorageKind.SAVES -> savesDir
        StorageKind.REPLAYS -> replaysDir
    }

    override fun listAssets(prefix: String): List<String> {
        val dir = File(root, prefix)
        return dir.listFiles()
            ?.map { "$prefix/${it.name}" }
            ?.sorted()
            ?: emptyList()
    }

    override fun assetFileExists(path: String): Boolean = File(root, path).isFile

    override fun createDirectories() {
        root.mkdirs()
    }
}
