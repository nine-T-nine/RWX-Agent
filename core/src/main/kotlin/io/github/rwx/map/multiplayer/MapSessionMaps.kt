package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.game.map.TileMap
import io.github.rwx.PlatformStorage
import io.github.rwx.map.MapLinkResolver
import java.io.IOException
import java.security.MessageDigest

/** Resolves the complete graph locally, then exchanges stable IDs and exact content digests. */
class MapSessionMaps(private val storage: PlatformStorage) {
    fun createSpec(
        sessionId: String, hostPeerId: String, rootPath: String, rules: MapRoomRules,
        players: List<MapSessionPlayer>
    ): MapSessionSpec {
        val graph = MapLinkResolver.resolveLinkedMapGraph(storage, rootPath)
        if (graph.missingTargetMapIds.isNotEmpty()) throw IOException("Missing linked maps: ${graph.missingTargetMapIds.joinToString()}")
        if (graph.maps.isEmpty()) throw IOException("No linked map graph found")
        val owners = players.filterNot { it.ai || it.spectator }.sortedBy { it.teamId }
        if (owners.size < graph.maps.size) throw IOException("Each map needs a simulation player: ${graph.maps.size} maps, ${owners.size} players")
        val worlds = graph.maps.mapIndexed { index, node ->
            val id = if (node.mapId == node.mapPath) node.fileName.removeSuffix(".tmx") else node.mapId
            MapSessionWorld(id, digest(node.mapPath), requireNotNull(owners[index].peerId))
        }
        return MapSessionCoordinator(
            MapSessionSpec(
                sessionId,
                hostPeerId,
                worlds.first().mapId,
                rules,
                players,
                worlds
            )
        ).spec
    }

    fun resolve(spec: MapSessionSpec): Map<String, String> {
        val manifest = MapSessionCoordinator(spec).spec
        val paths = MapLinkResolver.findMapPathsById(storage, manifest.worlds.map { it.mapId })
        for (world in manifest.worlds) {
            val path = paths[world.mapId] ?: throw IOException("Missing linked map: ${world.mapId}")
            if (digest(path) != world.contentHash) throw IOException("Linked map content differs: ${world.mapId}")
        }
        // The engine's legacy virtual-path loader depends on its working directory.
        // Workers own a different directory, and viewers can have different local roots.
        return paths.mapValues { (_, path) ->
            storage.resolveVirtualPath(path)?.takeIf { it.isFile }?.absolutePath ?: path
        }
    }

    fun digest(path: String): String {
        val input = storage.resolveVirtualPath(path)?.takeIf { it.isFile }?.inputStream()
            ?: storage.openAssetStream(path)
            ?: TileMap.openMapInputStreamWithMovedFallback(path)
            ?: throw IOException("Cannot read linked map: $path")
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
