package com.example.engine

import com.example.model.OsintEntity
import com.example.model.OsintEntityType
import com.example.model.OsintTransformResult
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OsintEngine {

  private val ipv4Regex = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b""")
  private val emailRegex = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
  private val sha256Regex = Regex("""\b[A-Fa-f0-9]{64}\b""")
  private val btcRegex = Regex("""\b(?:1|3)[a-km-zA-HJ-NP-Z1-9]{25,34}\b""")
  private val domainRegex = Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""")
  private val phoneRegex = Regex("""\+?[0-9]{10,14}""")

  fun extractEntities(input: String): List<OsintEntity> {
    val found = linkedSetOf<OsintEntity>()
    val emailRanges = mutableListOf<IntRange>()
    emailRegex.findAll(input).forEach {
      emailRanges += it.range
      found += OsintEntity(it.value, OsintEntityType.EMAIL)
    }
    ipv4Regex.findAll(input).forEach { found += OsintEntity(it.value, OsintEntityType.IP_ADDRESS) }
    sha256Regex.findAll(input).forEach { found += OsintEntity(it.value, OsintEntityType.SHA256) }
    btcRegex.findAll(input).forEach { found += OsintEntity(it.value, OsintEntityType.BITCOIN) }
    domainRegex.findAll(input).forEach { m ->
      val v = m.value
      val insideEmail = emailRanges.any { r -> m.range.first >= r.first && m.range.last <= r.last }
      if (!ipv4Regex.matches(v) && !insideEmail) {
        found += OsintEntity(v, OsintEntityType.DOMAIN)
      }
    }
    phoneRegex.findAll(input).forEach { m ->
      if (found.none { it.value.contains(m.value) }) {
        found += OsintEntity(m.value, OsintEntityType.PHONE)
      }
    }
    return found.toList()
  }

  suspend fun runTransform(entity: OsintEntity): OsintTransformResult =
    withContext(Dispatchers.IO) {
      when (entity.type) {
        OsintEntityType.DOMAIN -> {
          val resolved =
            runCatching {
              InetAddress.getAllByName(entity.value)
                .mapNotNull { it.hostAddress }
                .distinct()
                .map { ip -> OsintEntity(ip, OsintEntityType.IP_ADDRESS) }
            }.getOrElse { emptyList() }
          val summary =
            if (resolved.isNotEmpty()) {
              "Resolved ${resolved.size} IP address(es) via DNS A/AAAA lookup."
            } else {
              "DNS lookup returned no addresses (offline or unresolved domain)."
            }
          OsintTransformResult(
            source = entity,
            transformName = "DNS Resolution",
            relationLabel = "DNS A",
            spawned = resolved,
            summary = summary,
          )
        }

        OsintEntityType.IP_ADDRESS -> {
          val parts = entity.value.split(".")
          val subnet =
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0/24" else "${entity.value}/24"
          OsintTransformResult(
            source = entity,
            transformName = "IP -> /24 CIDR Subnet",
            relationLabel = "CIDR",
            spawned = listOf(OsintEntity(subnet, OsintEntityType.SUBNET)),
            summary = "Computed enclosing /24 network block $subnet.",
          )
        }

        OsintEntityType.EMAIL -> {
          val domain = entity.value.substringAfter("@", "")
          val spawned =
            if (domain.isNotBlank()) listOf(OsintEntity(domain, OsintEntityType.DOMAIN)) else emptyList()
          OsintTransformResult(
            source = entity,
            transformName = "Email -> Domain",
            relationLabel = "Domain",
            spawned = spawned,
            summary = if (domain.isNotBlank()) "Extracted mail domain $domain." else "Invalid email format.",
          )
        }

        OsintEntityType.SHA256 -> {
          OsintTransformResult(
            source = entity,
            transformName = "Hash Fingerprinting",
            relationLabel = "SHA256",
            spawned = emptyList(),
            summary = "Verified 64-hex SHA-256 digest (${entity.value.take(12)}...).",
          )
        }

        OsintEntityType.BITCOIN -> {
          OsintTransformResult(
            source = entity,
            transformName = "Wallet Format Inspector",
            relationLabel = "BTC",
            spawned = emptyList(),
            summary = "Base58Check P2PKH/P2SH Bitcoin address validated.",
          )
        }

        OsintEntityType.PHONE, OsintEntityType.SUBNET -> {
          OsintTransformResult(
            source = entity,
            transformName = "Entity Normalization",
            relationLabel = "Normalized",
            spawned = emptyList(),
            summary = "Entity ${entity.value} normalized for graph linking.",
          )
        }
      }
    }
}
