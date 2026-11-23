package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CollabBoard
import com.example.model.FeatureCompatibilityReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureCompatibilitySheet(
  board: CollabBoard,
  report: FeatureCompatibilityReport,
  onFocusCoordinates: (Float, Float) -> Unit,
  onCopyToast: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current

  fun buildFullSpecPrompt(): String = buildString {
    appendLine("Implement the following new Web features detected on LinkDraw board \"${board.title}\" (schema v${report.boardSchemaVersion}):")
    report.findings.forEachIndexed { idx, f ->
      appendLine("${idx + 1}. [${f.category}] ${f.featureKey} (${f.sampleCount}x)")
      if (f.samplePayloadKeys.isNotEmpty()) {
        appendLine("   Payload/Extension keys: ${f.samplePayloadKeys.joinToString(", ")}")
      }
      appendLine("   Task: ${f.suggestedPrompt}")
    }
    if (report.unsupportedPlaceholders.isNotEmpty()) {
      appendLine("Sample remote payloads:")
      report.unsupportedPlaceholders.take(5).forEach { u ->
        appendLine(" - kind=\"${u.kind}\" id=\"${u.id}\" keys=${u.payloadKeys}")
      }
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = 280.dp, max = 580.dp)
          .padding(horizontal = 20.dp)
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
          imageVector = if (report.hasFindings) Icons.Default.WarningAmber else Icons.Default.Verified,
          contentDescription = null,
          tint = if (report.hasFindings) Color(0xFFD97706) else Color(0xFF059669),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text("Cross-Platform Provision & Sync", style = MaterialTheme.typography.titleLarge)
          Text(
            "Schema v${report.boardSchemaVersion} • ${report.totalRemoteElements} remote element(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text =
            if (report.hasFindings) {
              "Detected ${report.findings.size} Web addition(s) not yet implemented in Android. Unknown elements are rendered as placeholders and preserved non-destructively in Firestore."
            } else {
              "100% compatible. Forward-compatible Firestore rules and lossless passthrough sync are active for any future Web additions."
            },
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(12.dp),
        )
      }

      if (report.hasFindings) {
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(report.findings, key = { "${it.category}_${it.featureKey}" }) { finding ->
            ElevatedCard(
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  AssistChip(
                    onClick = {},
                    label = { Text(finding.category) },
                  )
                  Spacer(Modifier.weight(1f))
                  Text(
                    text = "${finding.sampleCount}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFD97706),
                    fontWeight = FontWeight.Bold,
                  )
                }
                Text(
                  text = finding.featureKey,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = finding.details,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                ) {
                  TextButton(
                    onClick = {
                      clipboard.setText(AnnotatedString(finding.suggestedPrompt))
                      onCopyToast("Copied task prompt for '${finding.featureKey}'")
                    }
                  ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Implement Task")
                  }
                }
              }
            }
          }

          if (report.unsupportedPlaceholders.isNotEmpty()) {
            item {
              Text(
                "Rendered Web Placeholders (${report.unsupportedPlaceholders.size})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
            items(report.unsupportedPlaceholders, key = { it.id }) { u ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFEF3C7).copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${u.kind}: ${u.label}",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                    )
                    Text(
                      text = "Keys: ${u.payloadKeys.joinToString(", ")}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(
                    onClick = {
                      onFocusCoordinates(u.x, u.y)
                      onDismiss()
                    }
                  ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Locate on Canvas")
                  }
                }
              }
            }
          }
        }

        Button(
          onClick = {
            clipboard.setText(AnnotatedString(buildFullSpecPrompt()))
            onCopyToast("Copied complete Web-to-Android spec to clipboard")
          },
          modifier = Modifier.fillMaxWidth().testTag("copy_all_compat_spec_button"),
        ) {
          Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Copy Full Web-to-Android Spec")
        }
      } else {
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Done")
        }
      }
    }
  }
}
