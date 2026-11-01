package io.github.rwx.map.multiplayer

import io.github.rwx.PlatformStorage
import io.github.rwx.Preference
import io.github.rwx.PreferenceStorage
import io.github.rwx.StorageKind
import io.github.rwx.StorageLocation
import java.io.File

/** Reads active app settings, but keeps every worker mutation in memory. */
class MapWorkerPreferenceStorage(private val backing: PreferenceStorage) : PreferenceStorage {
    private val tables = mutableMapOf<String, Preference>()

    @Synchronized
    override fun preference(name: String): Preference =
        tables.getOrPut(name) { Overlay(backing.preference(name)) }

    override fun flush() = Unit

    private class Overlay(private val backing: Preference) : Preference {
        private val values = mutableMapOf<String, Any?>()
        private var cleared = false

        private fun <T> read(key: String, default: T, fallback: () -> T): T {
            if (key !in values) return if (cleared) default else fallback()
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? T) ?: default
        }

        @Synchronized
        override fun getBoolean(key: String, defValue: Boolean) =
            read(key, defValue) { backing.getBoolean(key, defValue) }

        @Synchronized
        override fun getInt(key: String, defValue: Int) = read(key, defValue) { backing.getInt(key, defValue) }

        @Synchronized
        override fun getLong(key: String, defValue: Long) = read(key, defValue) { backing.getLong(key, defValue) }

        @Synchronized
        override fun getFloat(key: String, defValue: Float) = read(key, defValue) { backing.getFloat(key, defValue) }

        @Synchronized
        override fun getString(key: String, defValue: String?) =
            read(key, defValue) { backing.getString(key, defValue) }

        @Synchronized
        override fun putBoolean(key: String, value: Boolean): Preference = apply { values[key] = value }

        @Synchronized
        override fun putInt(key: String, value: Int): Preference = apply { values[key] = value }

        @Synchronized
        override fun putLong(key: String, value: Long): Preference = apply { values[key] = value }

        @Synchronized
        override fun putFloat(key: String, value: Float): Preference = apply { values[key] = value }

        @Synchronized
        override fun putString(key: String, value: String?): Preference = apply { values[key] = value }

        @Synchronized
        override fun contains(key: String): Boolean =
            if (key in values) values[key] != null else !cleared && backing.contains(key)

        @Synchronized
        override fun remove(key: String): String? {
            val old = if (key in values) values[key]?.toString() else if (cleared) null else runCatching {
                backing.getString(
                    key,
                    null
                )
            }.getOrNull()
            values[key] = null
            return old
        }

        @Synchronized
        override fun clear() {
            values.clear()
            cleared = true
        }
    }
}

/** Shared content stays in place; worker caches, saves, logs and replays are private. */
class MapWorkerPlatformStorage(private val backing: PlatformStorage, stateDirectory: File) :
    PlatformStorage by backing {
    override val rootDir = StorageLocation(backing.rootDir.virtualPath, File(stateDirectory, "root"))
    override val localDir = StorageLocation(backing.localDir.virtualPath, File(stateDirectory, "local"))
    override val cacheDir = StorageLocation(backing.cacheDir.virtualPath, File(stateDirectory, "cache"))
    override val savesDir = StorageLocation(backing.savesDir.virtualPath, File(stateDirectory, "saves"))
    override val replaysDir = StorageLocation(backing.replaysDir.virtualPath, File(stateDirectory, "replays"))

    override fun location(kind: StorageKind): StorageLocation = when (kind) {
        StorageKind.ROOT -> rootDir
        StorageKind.LOCAL -> localDir
        StorageKind.CACHE -> cacheDir
        StorageKind.SAVES -> savesDir
        StorageKind.REPLAYS -> replaysDir
        else -> backing.location(kind)
    }

    override fun resolveVirtualPath(virtualPath: String): File? {
        val normalized =
            virtualPath.trim().replace('\\', '/').replace(Regex("\\[(?:INTERNAL|EXTERNAL|NULL)-PATH]/"), "")
        return listOf(cacheDir, mapsDir, unitsDir, savesDir, replaysDir, modsDir, localDir, rootDir)
            .firstNotNullOfOrNull { it.resolveLocationPath(normalized) }
            ?: backing.resolveVirtualPath(virtualPath)
    }

    override fun createDirectories() {
        listOf(rootDir, localDir, cacheDir, savesDir, replaysDir).forEach { it.file.mkdirs() }
    }
}
