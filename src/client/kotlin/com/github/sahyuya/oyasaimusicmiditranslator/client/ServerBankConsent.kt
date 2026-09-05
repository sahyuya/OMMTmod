package com.github.sahyuya.oyasaimusicmiditranslator.client

import java.security.MessageDigest

/** Per-connection bank consent from the authoritative server (allow/deny + manifest hash). */
object ServerBankConsent {
  @Volatile private var allowed = false
  @Volatile private var hash: ByteArray = ByteArray(32)
  @Volatile private var received = false

  val isAllowed: Boolean get() = received && allowed
  /** Explicit server deny (preview veto). Absence of consent is NOT a deny. */
  val isDenied: Boolean get() = received && !allowed
  val manifestHash: ByteArray? get() = if (received && allowed) hash.copyOf() else null

  /** Whether the bundled mod pack is outdated vs server's configured hash. */
  fun isOutdated(bundledHash: ByteArray?): Boolean {
    if (!received) return false
    if (!allowed) return false
    if (bundledHash == null) return true
    return !MessageDigest.isEqual(bundledHash, hash)
  }

  internal fun update(allowed: Boolean, hash: ByteArray) {
    require(hash.size == 32)
    this.allowed = allowed
    this.hash = hash.copyOf()
    this.received = true
  }

  internal fun clear() {
    allowed = false
    hash = ByteArray(32)
    received = false
  }
}
