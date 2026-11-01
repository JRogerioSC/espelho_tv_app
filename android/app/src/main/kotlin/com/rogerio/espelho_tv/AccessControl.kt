package com.rogerio.espelho_tv

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Controle de acesso por PIN.
 * - O navegador envia o PIN uma vez (POST /auth) e recebe um token aleatório.
 * - O token é exigido em /stream e /audio.
 * - Tentativas erradas são limitadas por IP e no total, para dificultar adivinhação.
 */
class AccessControl(private val pin: String) {

    sealed class Result {
        data class Ok(val token: String) : Result()
        object Wrong : Result()
        data class Locked(val seconds: Int) : Result()
    }

    companion object {
        private const val MAX_FAILS_PER_IP = 5
        private const val IP_WINDOW_MS = 60_000L
        private const val MAX_FAILS_GLOBAL = 30
        private const val GLOBAL_WINDOW_MS = 5 * 60_000L
        private const val MAX_TOKENS = 20
        private const val MAX_TRACKED_IPS = 256
    }

    private val random = SecureRandom()
    private val tokens = LinkedHashSet<String>()
    private val failsByIp = HashMap<String, ArrayDeque<Long>>()
    private val globalFails = ArrayDeque<Long>()

    @Synchronized
    fun login(ip: String, attempt: String): Result {
        val now = System.currentTimeMillis()

        prune(globalFails, now, GLOBAL_WINDOW_MS)
        if (globalFails.size >= MAX_FAILS_GLOBAL) {
            return Result.Locked(secondsLeft(globalFails.first(), GLOBAL_WINDOW_MS, now))
        }

        if (failsByIp.size > MAX_TRACKED_IPS) failsByIp.clear()
        val mine = failsByIp.getOrPut(ip) { ArrayDeque() }
        prune(mine, now, IP_WINDOW_MS)
        if (mine.size >= MAX_FAILS_PER_IP) {
            return Result.Locked(secondsLeft(mine.first(), IP_WINDOW_MS, now))
        }

        // Comparação em tempo constante.
        if (MessageDigest.isEqual(attempt.toByteArray(), pin.toByteArray())) {
            mine.clear()
            val token = newToken()
            tokens.add(token)
            if (tokens.size > MAX_TOKENS) tokens.remove(tokens.first())
            return Result.Ok(token)
        }

        mine.addLast(now)
        globalFails.addLast(now)
        return Result.Wrong
    }

    @Synchronized
    fun isValid(token: String?): Boolean = token != null && tokens.contains(token)

    private fun prune(list: ArrayDeque<Long>, now: Long, window: Long) {
        while (list.isNotEmpty() && now - list.first() > window) list.removeFirst()
    }

    private fun secondsLeft(oldest: Long, window: Long, now: Long): Int =
        (((oldest + window) - now) / 1000).toInt() + 1

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}