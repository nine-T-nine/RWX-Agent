package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.*
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import java.io.*

/** Full polymorphic engine bodies with a closed, typed reference namespace. No live staging IDs. */
class UnitTransferCodec(private val teamIds: Set<Int>) {
    data class CapturedGroup(val bytes: ByteArray, val sourceIds: List<Long>)
    class StagedGroup internal constructor(val units: List<BaseUnit>, private val sourceIds: List<Long>) {
        fun activate(): Map<Long, Long> {
            check(units.all { it.objectId == 0L && !it.isDestroyed })
            units.forEach(BaseUnit::registerTransferredUnit)
            return sourceIds.zip(units.map { it.objectId }).toMap()
        }
    }

    private data class Member(val id: Long, val parent: Long, val type: ByteArray, val body: ByteArray)

    fun capture(roots: List<BaseUnit>, originX: Float, originY: Float): CapturedGroup {
        require(roots.isNotEmpty() && originX.isFinite() && originY.isFinite())
        val world = GameObject.fastGameObjectList.filterIsInstance<BaseUnit>()
        val group = linkedSetOf<BaseUnit>().apply { addAll(roots.sortedBy { it.objectId }) }
        var expanded: Boolean
        do {
            expanded = false
            for (unit in world) if (unit.transportContainer in group || unit.parentEntity in group) expanded =
                group.add(unit) || expanded
            require(group.size <= MAX_UNITS) { "Transfer group is too large" }
        } while (expanded)
        val ids = group.map { it.objectId }.toSet()
        require(ids.size == group.size && ids.all { it > 0 })
        group.forEach { unit ->
            require(!unit.isDead && !unit.isTransferUnavailable && unit.currentHealth > 0f)
            require(unit.team?.teamId in teamIds) { "Unit team is absent from session roster" }
            require(unit.transportContainer == null || unit.transportContainer in group)
            require(unit.parentEntity == null || unit.parentEntity in group)
            checkContainment(unit, group)
            val type = unit.r()
            if (type is CustomUnitConfig) require(type.borrowResourcesWhileAlive.c() && type.borrowResourcesWhileBuilt.c()) {
                "Borrowed team resources cannot be moved between independent resource economies"
            }
        }
        val ordered = arrayListOf<BaseUnit>()
        val visiting = hashSetOf<BaseUnit>()
        fun visit(unit: BaseUnit) {
            if (unit in ordered) return
            require(visiting.add(unit)) { "Cyclic physical containment" }
            unit.parentEntity?.let(::visit)
            unit.transportContainer?.let(::visit)
            visiting.remove(unit); ordered += unit
        }
        group.sortedBy { it.objectId }.forEach(::visit)
        val configs = linkedMapOf<String, Int>()
        val members = ordered.map { unit ->
            val type = AuditOutput(ids, configs).let { out ->
                out.writeUnitTypeId(unit.r()); out.toByteArray().also { out.close() }
            }
            val body = AuditOutput(ids, configs).let { out -> unit.a(out); out.toByteArray().also { out.close() } }
            Member(unit.objectId, (unit.parentEntity ?: unit.transportContainer)?.objectId ?: -1, type, body)
        }
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeInt(ENGINE_VERSION); out.writeInt(GameEngine.getInstance().gameTimeMillis)
                out.writeFloat(originX); out.writeFloat(originY)
                out.writeInt(configs.size); configs.forEach { (name, hash) -> out.writeUTF(name); out.writeInt(hash) }
                out.writeInt(members.size)
                members.forEach { member ->
                    out.writeLong(member.id); out.writeLong(member.parent)
                    out.writeInt(member.type.size); out.write(member.type)
                    out.writeInt(member.body.size); out.write(member.body)
                }
            }
        }.toByteArray()
        require(bytes.size <= PortalTransferEnvelope.MAX_PAYLOAD_BYTES) { "Transfer payload exceeds transport limit" }
        // Preflight checks serialization and containment before the authoritative freeze is allowed.
        stage(bytes, originX, originY, GameEngine.getInstance().gameTimeMillis)
        return CapturedGroup(bytes, ordered.map { it.objectId })
    }

    fun stage(bytes: ByteArray, destinationX: Float, destinationY: Float, destinationTime: Int): StagedGroup {
        require(bytes.size in 1..PortalTransferEnvelope.MAX_PAYLOAD_BYTES)
        require(destinationX.isFinite() && destinationY.isFinite())
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1 && input.readInt() == ENGINE_VERSION) { "Unsupported unit payload version" }
        val delta = Math.subtractExact(destinationTime, input.readInt())
        val dx = destinationX - input.readFloat();
        val dy = destinationY - input.readFloat()
        require(dx.isFinite() && dy.isFinite())
        repeat(bounded(input.readInt(), MAX_UNITS * 8)) {
            val name = input.readUTF();
            val hash = input.readInt()
            require(CustomUnitConfig.findConfigByName(name)?.configHash == hash) { "Custom unit configuration changed: $name" }
        }
        val size = bounded(input.readInt(), MAX_UNITS); require(size > 0)
        fun blob(): ByteArray {
            val n = input.readInt(); require(n in 1..input.available()) { "Invalid member length" }
            return ByteArray(n).also(input::readFully)
        }

        val members = List(size) { Member(input.readLong(), input.readLong(), blob(), blob()) }
        require(input.available() == 0 && members.map { it.id }.distinct().size == size && members.all { it.id > 0 })
        val shells = linkedMapOf<Long, BaseUnit>()
        members.forEach { member ->
            val stream = TransferInput(member.type, shells)
            val type = requireNotNull(stream.q()) { "Missing unit type" }
            require(stream.activeInputStream.available() == 0)
            shells[member.id] = when (type) {
                is CustomUnitConfig -> type.createCustomUnit(true)
                is UnitTypeEnum -> type.createUnitInstanceWithBoolean(true)
                else -> error("Unsupported unit representation")
            }
        }
        val loaded = hashSetOf<Long>()
        members.forEach { member ->
            require(member.parent == -1L || member.parent in loaded) { "Containment parents must precede children" }
            val shell = shells.getValue(member.id)
            val stream = TransferInput(member.body, shells)
            shell.a(stream)
            require(stream.activeInputStream.available() == 0) { "Trailing unit state" }
            require(!shell.isDead && !shell.isDestroyed && shell.currentHealth > 0 && shell.team?.teamId in teamIds)
            val parent = shell.parentEntity ?: shell.transportContainer
            require(parent === shells[member.parent]) { "Containment reference was not reconstructed" }
            loaded += member.id
        }
        shells.values.forEach { checkContainment(it, shells.values.toSet()) }
        shells.values.forEach { it.rebaseTransferState(dx, dy, delta) }
        val translated = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<BaseUnit, Boolean>())
        shells.values.forEach { it.rebaseTransferPositionValues(dx, dy, translated) }
        return StagedGroup(shells.values.toList(), members.map { it.id })
    }

    private fun checkContainment(unit: BaseUnit, group: Set<BaseUnit>) {
        val carried = (unit as? OrderableUnit)?.transportedUnitList?.filterIsInstance<BaseUnit>().orEmpty()
        require(carried.distinct().size == carried.size && carried.all { it in group && it.transportContainer === unit }) { "Inconsistent cargo list" }
        require(group.filter { it.transportContainer === unit }
            .toSet() == carried.toSet()) { "Missing cargo reference" }
        if (unit.parentEntity != null) require(unit.attachmentData != null && unit is OrderableUnit) { "Invalid attachment" }
    }

    private inner class AuditOutput(private val ids: Set<Long>, private val configs: MutableMap<String, Int>) :
        GameOutputStream(ENGINE_VERSION) {
        override fun writeUnitId(id: Long) {
            require(id == -1L || id in ids) { "External unit reference: $id" }; super.writeUnitId(id)
        }

        override fun writeObjectId(value: GameObject?) {
            require(value == null || value is BaseUnit && value.objectId in ids) { "External object reference" }; super.writeObjectId(
                value
            )
        }

        override fun writeExistingObjectId(value: GameObject?) {
            require(value == null || value is BaseUnit && value.objectId in ids) { "External object reference" }; super.writeExistingObjectId(
                value
            )
        }

        override fun writeUnitTypeId(type: UnitType?) {
            require(type == null || type is UnitTypeEnum || type is CustomUnitConfig)
            if (type is CustomUnitConfig) configs[type.name] = type.configHash
            super.writeUnitTypeId(type)
        }

        override fun writeTeamIdByte(team: PlayerTeam) {
            require(team.teamId in teamIds); super.writeTeamIdByte(team)
        }
    }

    private class TransferInput(bytes: ByteArray, private val shells: Map<Long, BaseUnit>) : GameInputStream(bytes) {
        init {
            setProtocolVersion(ENGINE_VERSION); setStreamVersion(ENGINE_VERSION)
        }

        override fun strictUnitTypes() = true
        override fun resolveObjectId(id: Long, type: Class<*>, expected: Boolean): GameObject? {
            if (id == -1L) return null
            val unit = requireNotNull(shells[id]) { "Unknown group reference: $id" }
            require(type.isInstance(unit)); return unit
        }

        override fun collectionSize(size: Int): Int = bounded(
            size,
            minOf(PortalTransferEnvelope.MAX_PAYLOAD_BYTES, activeInputStream.available())
        )
    }

    companion object {
        const val ENGINE_VERSION = 96
        const val MAX_UNITS = 128
        private fun bounded(value: Int, maximum: Int): Int {
            require(value in 0..maximum); return value
        }
    }
}
