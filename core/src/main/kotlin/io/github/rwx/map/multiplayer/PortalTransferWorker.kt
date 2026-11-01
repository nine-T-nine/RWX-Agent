package io.github.rwx.map.multiplayer

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64
import java.util.UUID

@Serializable
enum class PortalTransferMessageKind { PREPARE, ACCEPTED, COMMIT, COMMITTED, REJECTED }

@Serializable
data class PortalTransferMessage(
    val kind: PortalTransferMessageKind, val envelope: PortalTransferEnvelope,
    val portal: String? = null, val ids: Map<Long, Long> = emptyMap(), val reason: String? = null
) {
    val from: MapWorldId get() = if (kind == PortalTransferMessageKind.PREPARE || kind == PortalTransferMessageKind.COMMIT) envelope.source else envelope.target
    val to: MapWorldId get() = if (from == envelope.source) envelope.target else envelope.source
}

/** Simulation-thread transaction decisions. Send success never clears the retryable durable outbox. */
class PortalTransferWorker(
    private var state: LinkedWorldState, directory: File, private val worlds: List<MapWorldId>,
    private val schedule: (LinkedWorldOperation) -> Unit,
    private val resolvePortal: (String?) -> String?, private val portalCenter: (String) -> FloatArray?,
    private val worldPaths: Map<String, String> = emptyMap(),
) : AutoCloseable {
    val journal = PortalTransferJournal(directory, state.world)
    private val scheduling = hashSetOf<String>()
    private val sourceRemoved = hashSetOf<String>()
    private val completed = hashSetOf<String>()
    private val responses = linkedMapOf<String, PortalTransferMessage>()
    private var outstandingIntent: String? = null
    private var rejectedUntilTick = 0
    var lastRejection: String? = null; private set

    init {
        require(state.world in worlds && worlds.distinct().size == worlds.size)
        check(
            journal.all().isEmpty()
        ) { "Transfer journal has no verified same-generation world checkpoint; refusing fresh-world recovery" }
        state.authority = this
    }

    fun rebind(loaded: LinkedWorldState) {
        require(loaded.world == state.world)
        state.authority = null; state = loaded; state.authority = this
    }

    fun requestPortal(ids: List<Long>, targetMap: String, portal: String?, x: Float, y: Float) {
        val target = resolveTargetWorld(targetMap) ?: return
        prepare(ids.sorted(), target, portal, x, y)
    }

    /** Portal links carry map paths; worlds are identified by manifest ids. */
    private fun resolveTargetWorld(targetMap: String): MapWorldId? {
        worlds.firstOrNull { it.mapId == targetMap }?.let { return it }
        val targetKey = mapPathKey(targetMap)
        if (targetKey.isEmpty()) return null
        return worlds.firstOrNull { world ->
            worldPaths[world.mapId]?.let { mapPathKey(it) == targetKey } == true
        } ?: worlds.firstOrNull { it.mapId.equals(targetKey, ignoreCase = true) }
    }

    private fun mapPathKey(path: String): String =
        path.replace('\\', '/').substringAfterLast('/').removeSuffix(".tmx").trim().lowercase()

    fun prepare(ids: List<Long>, target: MapWorldId, portal: String?, x: Float, y: Float) {
        if (outstandingIntent != null || GameEngine.getInstance().currentTick < rejectedUntilTick || state.outcome?.winners?.isNotEmpty() == true) return
        require(target in worlds && target != state.world)
        if (journal.all().size >= 4096) return
        // Size/reference preflight before admitting a command; capture again at its authoritative frame.
        val roots = ids.map { requireNotNull(com.corrodinggames.rts.gameFramework.GameObject.a(it, true)) }
        try {
            UnitTransferCodec(state.players.map { it.teamId }.toSet()).capture(roots, x, y).also { group ->
                require(group.sourceIds.none(state::isArrivalProtected)) { "Group has not left its arrival portal" }
            }
        } catch (failure: Exception) {
            if (failure !is IllegalArgumentException && failure !is java.io.IOException) throw failure
            lastRejection = failure.message; rejectedUntilTick = GameEngine.getInstance().currentTick + 120; return
        }
        val transaction = UUID.randomUUID().toString()
        outstandingIntent = transaction
        schedule(
            LinkedWorldOperation(
                "prepare-$transaction", LinkedWorldAction.PREPARE_SOURCE, state.world,
                transaction, target, portal, ids.toList(), x, y
            )
        )
    }

    fun captureRejected(op: LinkedWorldOperation, reason: String) {
        outstandingIntent = null; lastRejection = reason; rejectedUntilTick = GameEngine.getInstance().currentTick + 120
    }

    fun prepared(op: LinkedWorldOperation, envelope: PortalTransferEnvelope, ids: List<Long>) {
        journal.prepare(envelope, ids, op.id); outstandingIntent = null
    }

    fun accepted(op: LinkedWorldOperation, envelope: PortalTransferEnvelope) {
        val record = journal.accept(envelope, requireNotNull(op.portal))
        scheduling -= op.id
        respond(record)
    }

    fun sourceRemoved(op: LinkedWorldOperation) {
        sourceRemoved += op.transactionId; scheduling -= op.id
    }

    fun targetActivated(op: LinkedWorldOperation, ids: Map<Long, Long>) {
        val record = journal.commit(requireNotNull(op.envelope), ids, op.id)
        scheduling -= op.id
        respond(record)
    }

    fun receive(message: PortalTransferMessage) {
        message.envelope.validate()
        val envelope = message.envelope
        require(message.to == state.world && message.from in worlds && envelope.source in worlds && envelope.target in worlds)
        require(message.ids.size <= UnitTransferCodec.MAX_UNITS && (message.reason?.length ?: 0) <= 512)
        val previous = journal.query(envelope)
        when (message.kind) {
            PortalTransferMessageKind.PREPARE -> {
                if (previous != null) {
                    respond(previous); return
                }
                val portal = resolvePortal(envelope.destinationPortal)
                if (portal == null) {
                    respond(journal.reject(envelope, "Unknown destination portal")); return
                }
                val center = requireNotNull(portalCenter(portal))
                try {
                    UnitTransferCodec(state.players.map { it.teamId }.toSet()).stage(
                        Base64.getDecoder().decode(envelope.payload),
                        center[0], center[1], GameEngine.getInstance().gameTimeMillis
                    )
                } catch (failure: Exception) {
                    if (failure !is IllegalArgumentException && failure !is java.io.IOException) throw failure
                    respond(journal.reject(envelope, failure.message ?: "Unsupported unit payload")); return
                }
                queue(
                    LinkedWorldOperation(
                        "accept-${envelope.transactionId}",
                        LinkedWorldAction.ACCEPT_TARGET,
                        state.world,
                        envelope.transactionId,
                        portal = portal,
                        x = center[0],
                        y = center[1],
                        envelope = envelope
                    )
                )
            }

            PortalTransferMessageKind.ACCEPTED -> {
                require(previous != null && message.portal != null)
                if (previous.state == TransferState.PREPARED) {
                    journal.commit(envelope, emptyMap(), "remove-${envelope.transactionId}")
                    queue(
                        LinkedWorldOperation(
                            "remove-${envelope.transactionId}",
                            LinkedWorldAction.REMOVE_SOURCE,
                            state.world,
                            envelope.transactionId
                        )
                    )
                } else require(previous.state == TransferState.COMMITTED)
            }

            PortalTransferMessageKind.COMMIT -> {
                require(previous != null)
                if (previous.state == TransferState.COMMITTED) {
                    respond(previous); return
                }
                require(previous.state == TransferState.ACCEPTED)
                val portal = requireNotNull(previous.portal)
                val center = requireNotNull(portalCenter(portal))
                queue(
                    LinkedWorldOperation(
                        "activate-${envelope.transactionId}",
                        LinkedWorldAction.ACTIVATE_TARGET,
                        state.world,
                        envelope.transactionId,
                        portal = portal,
                        x = center[0],
                        y = center[1],
                        envelope = envelope
                    )
                )
            }

            PortalTransferMessageKind.COMMITTED -> {
                require(previous?.state == TransferState.COMMITTED && message.ids.isNotEmpty())
                if (completed.add(envelope.transactionId)) {
                    journal.confirmDestination(envelope, message.ids)
                    queue(
                        LinkedWorldOperation(
                            "done-${envelope.transactionId}",
                            LinkedWorldAction.SOURCE_DONE,
                            state.world,
                            envelope.transactionId
                        )
                    )
                }
            }

            PortalTransferMessageKind.REJECTED -> {
                require(previous != null)
                if (previous.state == TransferState.REJECTED) return
                require(previous.state == TransferState.PREPARED)
                lastRejection = message.reason
                rejectedUntilTick = GameEngine.getInstance().currentTick + 120
                journal.reject(envelope, message.reason ?: "Destination rejected transfer")
                queue(
                    LinkedWorldOperation(
                        "restore-${envelope.transactionId}",
                        LinkedWorldAction.RESTORE_SOURCE,
                        state.world,
                        envelope.transactionId
                    )
                )
            }
        }
    }

    private fun queue(op: LinkedWorldOperation) {
        if (scheduling.add(op.id)) schedule(op)
    }

    private fun respond(record: TransferRecord) {
        val kind = when (record.state) {
            TransferState.ACCEPTED -> PortalTransferMessageKind.ACCEPTED
            TransferState.COMMITTED -> PortalTransferMessageKind.COMMITTED
            TransferState.REJECTED, TransferState.CANCELLED -> PortalTransferMessageKind.REJECTED
            TransferState.PREPARED -> return
        }
        // Responses are regenerated by duplicates. Retain at most one bounded response per transaction.
        responses[record.envelope.transactionId] = PortalTransferMessage(
            kind, record.envelope, record.portal,
            record.destinationIds, record.reason
        )
    }

    fun poll(): List<PortalTransferMessage> {
        responses.values.firstOrNull()?.let { return listOf(it) }
        val source = journal.all().firstOrNull {
            it.envelope.source == state.world &&
                    (it.state == TransferState.PREPARED || it.state == TransferState.COMMITTED && it.envelope.transactionId in sourceRemoved && !it.destinationConfirmed)
        }
        if (source != null) return listOf(
            PortalTransferMessage(
                if (source.state == TransferState.PREPARED)
                    PortalTransferMessageKind.PREPARE else PortalTransferMessageKind.COMMIT, source.envelope
            )
        )
        return responses.values.firstOrNull()?.let(::listOf) ?: emptyList()
    }

    fun acknowledgeResponse(transaction: String) {
        responses.remove(transaction)
    }

    fun hasPending() = outstandingIntent != null || state.unresolved.isNotEmpty() || journal.all().any {
        it.state == TransferState.PREPARED || it.state == TransferState.ACCEPTED ||
                it.envelope.source == state.world && it.state == TransferState.COMMITTED && !it.destinationConfirmed
    }

    override fun close() {
        if (state.authority === this) state.authority = null
    }
}
