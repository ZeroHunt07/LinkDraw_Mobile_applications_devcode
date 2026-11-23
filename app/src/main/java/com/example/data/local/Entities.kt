package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_accounts")
data class UserAccountEntity(
  @PrimaryKey val id: String,
  val displayName: String,
  val email: String,
  val passwordHash: String,
  val salt: String,
  val isGuest: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "graph_projects")
data class GraphProjectEntity(
  @PrimaryKey val id: String,
  val userId: String,
  val title: String,
  val description: String,
  val documentJson: String,
  val nodeCount: Int,
  val edgeCount: Int,
  val updatedAt: Long = System.currentTimeMillis(),
  val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "studio_notes")
data class StudioNoteEntity(
  @PrimaryKey val id: String,
  val userId: String,
  val projectId: String?,
  val title: String,
  val body: String,
  val noteType: String,
  val folder: String,
  val tagsCsv: String,
  val priority: String,
  val colorHex: String,
  val pinned: Boolean,
  val archived: Boolean,
  val linkedNodeId: String?,
  val linkedNodeName: String?,
  val sourceUrl: String,
  val checklistJson: String,
  val wordCount: Int,
  val updatedAt: Long,
  val createdAt: Long,
)
