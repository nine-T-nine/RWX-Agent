package io.github.rwx.p2p

/** Bounds direct feature sends, including bytes already accepted by the native channel. */
object FeatureSendBudget {
    fun accepts(message: FeatureMessage, bufferedBytes: Long, encodedBytes: Int): Boolean {
        val kind = message.mapStream?.kind
        val bulk = kind == "view-data" || kind == "worker-data"
        // A bulk stream stops on backpressure, leaving room for close, pause and transfer control.
        val limit = if (bulk) 3L * 1024 * 1024 else 4L * 1024 * 1024
        return encodedBytes in 1..(1024 * 1024) && bufferedBytes in 0..(limit - encodedBytes)
    }
}
