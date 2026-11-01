package io.github.rwx.map.multiplayer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class PortalTransferEnvelope(
    val transactionId: String, val source: MapWorldId, val target: MapWorldId,
    val destinationPortal: String?, val payload: String, val version: Int = 1,
    val digest: String = transferDigest(Base64.getDecoder().decode(payload))
) {
    fun validate() {
        require(version == 1 && transactionId.length in 1..128)
        require(source.sessionId == target.sessionId && source != target)
        for (world in listOf(source, target)) require(
            world.generation > 0 &&
                    world.sessionId.length in 1..128 && world.mapId.length in 1..256
        )
        require(destinationPortal == null || destinationPortal.length in 1..256)
        require(payload.length <= MAX_PAYLOAD_BYTES * 4 / 3 + 4)
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size in 1..MAX_PAYLOAD_BYTES && transferDigest(bytes) == digest)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 512 * 1024
    }
}

internal fun transferDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

@Serializable
enum class TransferState { PREPARED, ACCEPTED, COMMITTED, REJECTED, CANCELLED }

@Serializable
data class TransferRecord(
    val envelope: PortalTransferEnvelope,
    val state: TransferState,
    val portal: String? = null,
    val sourceIds: List<Long> = emptyList(),
    val destinationIds: Map<Long, Long> = emptyMap(),
    val destinationConfirmed: Boolean = false,
    val operation: String? = null,
    val reason: String? = null
)

/** Durable decisions, never a timer-based recovery policy. Caller owns the simulation thread. */
class PortalTransferJournal(private val directory: File, private val world: MapWorldId) {
    private val json = Json { encodeDefaults = true }
    private val records = linkedMapOf<String, TransferRecord>()

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create transfer journal" }
        val files = directory.listFiles() ?: error("Cannot read transfer journal")
        require(files.count { it.extension == "record" } <= 4096) { "Transfer journal capacity exceeded" }
        files.filter { it.extension == "record" }.sortedBy { it.name }.forEach { file ->
            require(file.length() in 1..(1024 * 1024)) { "Invalid journal record size" }
            val record = DataInputStream(file.inputStream()).use { input ->
                require(input.readInt() == 1) { "Unknown journal version" }
                val size = input.readInt(); require(size in 1..(900 * 1024))
                val digest = input.readUTF()
                val bytes = ByteArray(size).also(input::readFully)
                require(input.read() == -1 && transferDigest(bytes) == digest) { "Corrupt transfer journal" }
                json.decodeFromString<TransferRecord>(bytes.toString(Charsets.UTF_8))
            }
            validate(record.envelope)
            require(file.nameWithoutExtension == key(record.envelope)) { "Journal identity mismatch" }
            records[key(record.envelope)] = record
        }
    }

    fun all(): List<TransferRecord> = records.values.toList()
    fun unresolved(): List<TransferRecord> =
        all().filter { it.state == TransferState.PREPARED || it.state == TransferState.ACCEPTED }

    fun query(envelope: PortalTransferEnvelope): TransferRecord? {
        validate(envelope)
        return records[key(envelope)]?.also { require(it.envelope == envelope) { "Transfer identity reused with different content" } }
    }

    fun prepare(envelope: PortalTransferEnvelope, ids: List<Long>, operation: String): TransferRecord {
        require(world == envelope.source && ids.isNotEmpty() && ids.distinct().size == ids.size)
        query(envelope)?.let { require(it.sourceIds == ids); return it }
        return persist(
            TransferRecord(
                envelope,
                TransferState.PREPARED,
                sourceIds = ids.toList(),
                operation = operation
            )
        )
    }

    fun accept(envelope: PortalTransferEnvelope, portal: String): TransferRecord {
        require(world == envelope.target && portal.isNotBlank())
        query(envelope)?.let {
            require(
                it.portal == portal || it.state in setOf(
                    TransferState.REJECTED,
                    TransferState.CANCELLED
                )
            ); return it
        }
        return persist(TransferRecord(envelope, TransferState.ACCEPTED, portal = portal))
    }

    fun commit(envelope: PortalTransferEnvelope, ids: Map<Long, Long>, operation: String): TransferRecord {
        val previous = requireNotNull(query(envelope)) { "Transfer was not prepared or accepted" }
        if (previous.state == TransferState.COMMITTED) {
            require(previous.destinationIds == ids && previous.operation == operation) { "Commit result changed" }
            return previous
        }
        require(previous.state == if (world == envelope.source) TransferState.PREPARED else TransferState.ACCEPTED)
        if (world == envelope.target) require(ids.isNotEmpty() && ids.values.distinct().size == ids.size)
        return persist(
            previous.copy(
                state = TransferState.COMMITTED,
                destinationIds = ids.toMap(),
                operation = operation
            )
        )
    }

    fun confirmDestination(envelope: PortalTransferEnvelope, ids: Map<Long, Long>): TransferRecord {
        val previous = requireNotNull(query(envelope))
        require(world == envelope.source && previous.state == TransferState.COMMITTED && ids.isNotEmpty())
        if (previous.destinationConfirmed) {
            require(previous.destinationIds == ids); return previous
        }
        return persist(previous.copy(destinationConfirmed = true, destinationIds = ids.toMap()))
    }

    fun reject(envelope: PortalTransferEnvelope, reason: String): TransferRecord {
        val previous = query(envelope)
        if (previous?.state == TransferState.REJECTED) return previous
        require(previous == null || world == envelope.source && previous.state == TransferState.PREPARED)
        return persist(
            (previous ?: TransferRecord(envelope, TransferState.REJECTED)).copy(
                state = TransferState.REJECTED, reason = reason.take(512)
            )
        )
    }

    fun cancel(envelope: PortalTransferEnvelope): TransferRecord {
        val previous = query(envelope)
        if (previous?.state == TransferState.CANCELLED) return previous
        require(previous == null) { "An accepted or prepared transfer needs a terminal remote decision" }
        return persist(TransferRecord(envelope, TransferState.CANCELLED))
    }

    private fun validate(envelope: PortalTransferEnvelope) {
        envelope.validate()
        require(world == envelope.source || world == envelope.target) { "Wrong journal world" }
    }

    private fun key(envelope: PortalTransferEnvelope): String = transferDigest(
        json.encodeToString(
            listOf(
                envelope.source.sessionId, envelope.source.mapId,
                envelope.source.generation.toString(), envelope.transactionId
            )
        ).toByteArray()
    )

    private fun persist(record: TransferRecord): TransferRecord {
        validate(record.envelope)
        val id = key(record.envelope)
        require(id in records || records.size < 4096) { "Transfer journal capacity exceeded" }
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 900 * 1024)
        val temporary = File(directory, "$id.pending")
        FileOutputStream(temporary).use { file ->
            val data = DataOutputStream(file)
            data.writeInt(1); data.writeInt(bytes.size); data.writeUTF(transferDigest(bytes)); data.write(bytes)
            data.flush(); file.fd.sync()
        }
        java.nio.file.Files.move(
            temporary.toPath(), File(directory, "$id.record").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        records[id] = record
        return record
    }
}
