package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>

  data class Success<T>(val data: T) : UiState<T>

  data class Error(val message: String) : UiState<Nothing>
}

@Keep
@IgnoreExtraProperties
data class CollabBoard(
  @DocumentId val id: String = "",
  val ownerId: String = "",
  val ownerName: String = "",
  val title: String = "",
  val description: String = "",
  val inviteCode: String = "",
  val memberUids: List<String> = emptyList(),
  val memberNames: Map<String, String> = emptyMap(),
  val gridSnap: Boolean = true,
  val gridSize: Long = 24L,
  val flowAnimation: Boolean = false,
  val extensions: Map<String, Any> = emptyMap(),
  val schemaVersion: Long = 1L,
  val sourcePlatform: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class BoardInvite(
  @DocumentId val inviteCode: String = "",
  val boardId: String = "",
  val ownerId: String = "",
  val title: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class BoardElement(
  @DocumentId val id: String = "",
  val kind: String = "node",
  val payload: Map<String, Any> = emptyMap(),
  val updatedByUid: String = "",
  val updatedByName: String = "",
  val schemaVersion: Long = 1L,
  val sourcePlatform: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class BoardPresence(
  @DocumentId val id: String = "",
  val uid: String = "",
  val displayName: String = "",
  val colorHex: String = "#2563EB",
  val selectedNodeId: String? = null,
  val activeTab: String = "canvas",
  val cursorX: Double? = null,
  val cursorY: Double? = null,
  val viewportZoom: Double? = null,
  val sourcePlatform: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class BoardComment(
  @DocumentId val id: String = "",
  val authorId: String = "",
  val authorName: String = "",
  val text: String = "",
  val linkedNodeId: String? = null,
  val linkedNodeLabel: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class UnsupportedElementPlaceholder(
  val id: String,
  val kind: String,
  val x: Float,
  val y: Float,
  val w: Float,
  val h: Float,
  val label: String,
  val colorHex: String,
  val updatedByName: String,
  val sourcePlatform: String?,
  val payloadKeys: List<String>,
  val rawPayload: Map<String, Any>,
)

data class UnknownFeatureFinding(
  val category: String,
  val featureKey: String,
  val sampleCount: Int,
  val details: String,
  val samplePayloadKeys: List<String> = emptyList(),
  val suggestedPrompt: String,
)

data class FeatureCompatibilityReport(
  val totalRemoteElements: Int = 0,
  val boardSchemaVersion: Long = 1L,
  val findings: List<UnknownFeatureFinding> = emptyList(),
  val unsupportedPlaceholders: List<UnsupportedElementPlaceholder> = emptyList(),
) {
  val hasFindings: Boolean
    get() = findings.isNotEmpty()
}

@Keep
@IgnoreExtraProperties
data class CollabNote(
  @DocumentId val id: String = "",
  val projectId: String? = null,
  val title: String = "Untitled Note",
  val body: String = "",
  val noteType: String = "Observation",
  val folder: String = "General",
  val tags: List<String> = emptyList(),
  val priority: String = "normal",
  val colorHex: String = "#EEF2FF",
  val pinned: Boolean = false,
  val archived: Boolean = false,
  val linkedNodeId: String? = null,
  val linkedNodeName: String? = null,
  val sourceUrl: String = "",
  val checklist: List<Map<String, Any>> = emptyList(),
  val wordCount: Long = 0L,
  val updatedByUid: String = "",
  val updatedByName: String = "",
  val extensions: Map<String, Any> = emptyMap(),
  val schemaVersion: Long = 1L,
  val sourcePlatform: String? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)
