package com.cryptostorm.xray

// ─────────────────────────────────────────────────────────────────────────────
// gRPC channel
// ─────────────────────────────────────────────────────────────────────────────
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder

import xray.app.stats.command.StatsServiceGrpc
import xray.app.stats.command.StatsServiceGrpc.StatsServiceBlockingStub
import xray.app.stats.command.GetStatsRequest

/**
 * Minimal blocking client for Xray/V2Ray StatsService (GetStats).
 * Reads cumulative counters with reset=false so values don’t zero on each read.
 */
class XrayStatsClient(private val host: String, private val port: Int) {
    @Volatile private var channel: ManagedChannel? = null
    @Volatile private var stub: StatsServiceBlockingStub? = null

    fun start() {
        if (channel != null) return
        val ch = OkHttpChannelBuilder
            .forAddress(host, port)
            .usePlaintext() // local loopback; API is plaintext
            .build()
        channel = ch
        stub = StatsServiceGrpc.newBlockingStub(ch)
    }

    fun stop() {
        try { channel?.shutdownNow() } catch (_: Throwable) {}
        channel = null
        stub = null
    }

    /** Returns the cumulative stat value, or null if unavailable/error. */
    fun getStat(name: String): Long? {
        val s = stub ?: return null
        return try {
            val req = GetStatsRequest.newBuilder()
                .setName(name)
                .setReset(false)      // ← critical: don’t zero counters
                .build()
            val res = s.getStats(req)
            if (res.hasStat()) res.stat.value else 0L
        } catch (_: Throwable) {
            null
        }
    }
}