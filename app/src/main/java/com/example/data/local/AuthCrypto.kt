package com.example.data.local

import java.security.MessageDigest
import java.security.SecureRandom

object AuthCrypto {
  private val random = SecureRandom()

  fun generateSalt(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }

  fun hashPassword(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$salt:$password".toByteArray(Charsets.UTF_8)
    val hash = digest.digest(input)
    return hash.joinToString("") { "%02x".format(it) }
  }

  fun verifyPassword(password: String, salt: String, expectedHash: String): Boolean {
    val actual = hashPassword(password, salt)
    if (actual.length != expectedHash.length) return false
    var diff = 0
    for (i in actual.indices) {
      diff = diff or (actual[i].code xor expectedHash[i].code)
    }
    return diff == 0
  }
}
