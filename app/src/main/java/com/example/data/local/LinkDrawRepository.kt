package com.example.data.local

import com.example.data.GraphJsonCodec
import com.example.model.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LinkDrawRepository(
  private val dao: LinkDrawDao,
  private val prefs: SessionPreferences,
) {

  val preferences: Flow<AppUserPreferences> = prefs.preferences
  val allAccounts: Flow<List<UserAccountEntity>> = dao.observeAccounts()

  suspend fun getAccount(userId: String): UserAccountEntity? = dao.findAccountById(userId)

  suspend fun signIn(email: String, password: String): Result<UserAccountEntity> {
    val cleanEmail = email.trim()
    if (cleanEmail.isBlank() || password.isBlank()) {
      return Result.failure(IllegalArgumentException("Email/username and password are required."))
    }
    val account =
      dao.findAccountByEmail(cleanEmail)
        ?: return Result.failure(IllegalArgumentException("No local account found for \"$cleanEmail\"."))
    if (account.isGuest) {
      activateUserSession(account.id)
      return Result.success(account)
    }
    if (!AuthCrypto.verifyPassword(password, account.salt, account.passwordHash)) {
      return Result.failure(IllegalArgumentException("Incorrect password. Please try again."))
    }
    activateUserSession(account.id)
    return Result.success(account)
  }

  suspend fun registerAccount(
    displayName: String,
    email: String,
    password: String,
  ): Result<UserAccountEntity> {
    val name = displayName.trim()
    val cleanEmail = email.trim()
    if (name.length < 2) {
      return Result.failure(IllegalArgumentException("Display name must be at least 2 characters."))
    }
    if (cleanEmail.length < 3) {
      return Result.failure(IllegalArgumentException("Enter a valid email or username."))
    }
    if (password.length < 4) {
      return Result.failure(IllegalArgumentException("Password must be at least 4 characters."))
    }
    if (dao.findAccountByEmail(cleanEmail) != null) {
      return Result.failure(IllegalArgumentException("An account with \"$cleanEmail\" already exists."))
    }
    val salt = AuthCrypto.generateSalt()
    val hash = AuthCrypto.hashPassword(password, salt)
    val account =
      UserAccountEntity(
        id = "u_${UUID.randomUUID().toString().take(8)}",
        displayName = name,
        email = cleanEmail,
        passwordHash = hash,
        salt = salt,
        isGuest = false,
      )
    dao.upsertAccount(account)
    seedWorkspaceIfEmpty(account.id)
    activateUserSession(account.id)
    return Result.success(account)
  }

  suspend fun continueAsGuest(): UserAccountEntity {
    val existing = dao.findAccountByEmail("guest@local")
    val account =
      existing
        ?: UserAccountEntity(
          id = "u_guest_local",
          displayName = "Analyst Guest",
          email = "guest@local",
          passwordHash = "",
          salt = "",
          isGuest = true,
        ).also { dao.upsertAccount(it) }
    seedWorkspaceIfEmpty(account.id)
    activateUserSession(account.id)
    return account
  }

  suspend fun ensureGoogleUserWorkspace(
    uid: String,
    displayName: String,
    email: String,
  ): UserAccountEntity {
    val existing = dao.findAccountById(uid)
    val cleanName = displayName.trim().ifBlank { email.substringBefore("@").ifBlank { "Analyst" } }
    val account =
      UserAccountEntity(
        id = uid,
        displayName = cleanName,
        email = email.trim().ifBlank { "$uid@google" },
        passwordHash = existing?.passwordHash ?: "",
        salt = existing?.salt ?: "",
        isGuest = false,
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
      )
    dao.upsertAccount(account)
    seedWorkspaceIfEmpty(uid)
    prefs.setActiveUser(uid)
    return account
  }

  suspend fun quickSwitchAccount(userId: String): UserAccountEntity? {
    val account = dao.findAccountById(userId) ?: return null
    seedWorkspaceIfEmpty(account.id)
    activateUserSession(account.id)
    return account
  }

  suspend fun signOut() {
    prefs.setActiveUser(null)
  }

  private suspend fun activateUserSession(userId: String) {
    seedWorkspaceIfEmpty(userId)
    prefs.setActiveUser(userId)
  }

  private suspend fun seedWorkspaceIfEmpty(userId: String) {
    if (dao.countProjectsForUser(userId) > 0) return
    val now = System.currentTimeMillis()
    val p1Id = "proj_${userId}_1"
    val doc1 = StarterTemplates.buildDocument(ProjectTemplate.MICROSERVICES)
    val p1 =
      GraphProjectEntity(
        id = p1Id,
        userId = userId,
        title = "Microservices Blueprint",
        description = "API Gateway, Auth, Analytics, Postgres & Telemetry Table",
        documentJson = GraphJsonCodec.encodeDocument(doc1),
        nodeCount = doc1.nodes.size,
        edgeCount = doc1.edges.size,
        updatedAt = now,
        createdAt = now,
      )
    val doc2 = StarterTemplates.buildDocument(ProjectTemplate.OSINT_INVESTIGATION)
    val p2 =
      GraphProjectEntity(
        id = "proj_${userId}_2",
        userId = userId,
        title = "OSINT Threat Map",
        description = "Domain resolution, IPv4 C2, subnet & crypto wallet links",
        documentJson = GraphJsonCodec.encodeDocument(doc2),
        nodeCount = doc2.nodes.size,
        edgeCount = doc2.edges.size,
        updatedAt = now - 10_000L,
        createdAt = now - 10_000L,
      )
    dao.upsertProject(p1)
    dao.upsertProject(p2)
    StarterTemplates.starterNotes(userId, p1Id).forEach { dao.upsertNote(it) }
    prefs.setActiveProject(p1Id)
  }

  fun observeProjects(userId: String): Flow<List<GraphProjectEntity>> =
    dao.observeProjectsForUser(userId)

  suspend fun createProject(
    userId: String,
    title: String,
    description: String,
    template: ProjectTemplate,
  ): GraphProjectEntity {
    val doc = StarterTemplates.buildDocument(template)
    return createProjectFromDocument(userId, title, description, doc)
  }

  suspend fun createProjectFromDocument(
    userId: String,
    title: String,
    description: String,
    document: GraphDocument,
  ): GraphProjectEntity {
    val now = System.currentTimeMillis()
    val entity =
      GraphProjectEntity(
        id = "proj_${UUID.randomUUID().toString().take(8)}",
        userId = userId,
        title = title.ifBlank { "Untitled Graph" },
        description = description.ifBlank { "LinkDraw workspace" },
        documentJson = GraphJsonCodec.encodeDocument(document),
        nodeCount = document.nodes.size,
        edgeCount = document.edges.size,
        updatedAt = now,
        createdAt = now,
      )
    dao.upsertProject(entity)
    prefs.setActiveProject(entity.id)
    return entity
  }

  suspend fun saveProjectDocument(
    userId: String,
    projectId: String,
    title: String? = null,
    description: String? = null,
    document: GraphDocument,
  ) {
    val existing = dao.getProjectById(projectId, userId) ?: return
    dao.upsertProject(
      existing.copy(
        title = title ?: existing.title,
        description = description ?: existing.description,
        documentJson = GraphJsonCodec.encodeDocument(document),
        nodeCount = document.nodes.size,
        edgeCount = document.edges.size,
        updatedAt = System.currentTimeMillis(),
      )
    )
  }

  suspend fun duplicateProject(userId: String, projectId: String): GraphProjectEntity? {
    val existing = dao.getProjectById(projectId, userId) ?: return null
    val doc = GraphJsonCodec.decodeDocument(existing.documentJson)
    return createProjectFromDocument(
      userId = userId,
      title = "${existing.title} (Copy)",
      description = existing.description,
      document = doc,
    )
  }

  suspend fun deleteProject(userId: String, projectId: String) {
    dao.deleteProject(projectId, userId)
  }

  suspend fun setActiveProject(projectId: String?) {
    prefs.setActiveProject(projectId)
  }

  suspend fun setThemeMode(mode: AppThemeMode) = prefs.setThemeMode(mode)
  suspend fun setGridSnap(enabled: Boolean) = prefs.setGridSnap(enabled)
  suspend fun setGridSize(size: Int) = prefs.setGridSize(size)
  suspend fun setFlowAnimation(enabled: Boolean) = prefs.setFlowAnimation(enabled)

  fun observeNotes(userId: String): Flow<List<StudioNote>> =
    dao.observeNotesForUser(userId).map { list -> list.map { it.toDomain() } }

  suspend fun saveNote(userId: String, note: StudioNote) {
    val words = note.body.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val entity =
      StudioNoteEntity(
        id = note.id,
        userId = userId,
        projectId = note.projectId,
        title = note.title.ifBlank { "Untitled Note" },
        body = note.body,
        noteType = note.noteType.label,
        folder = note.folder.ifBlank { "General" },
        tagsCsv = note.tags.joinToString(","),
        priority = note.priority.id,
        colorHex = note.colorHex,
        pinned = note.pinned,
        archived = note.archived,
        linkedNodeId = note.linkedNodeId,
        linkedNodeName = note.linkedNodeName,
        sourceUrl = note.sourceUrl,
        checklistJson = GraphJsonCodec.encodeChecklist(note.checklist),
        wordCount = words,
        updatedAt = System.currentTimeMillis(),
        createdAt = note.createdAt,
      )
    dao.upsertNote(entity)
  }

  suspend fun deleteNote(userId: String, noteId: String) {
    dao.deleteNote(noteId, userId)
  }

  private fun StudioNoteEntity.toDomain(): StudioNote =
    StudioNote(
      id = id,
      projectId = projectId,
      title = title,
      body = body,
      noteType = NoteType.fromLabel(noteType),
      folder = folder,
      tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
      priority = NotePriority.fromId(priority),
      colorHex = colorHex,
      pinned = pinned,
      archived = archived,
      linkedNodeId = linkedNodeId,
      linkedNodeName = linkedNodeName,
      sourceUrl = sourceUrl,
      checklist = GraphJsonCodec.decodeChecklist(checklistJson),
      wordCount = wordCount,
      updatedAt = updatedAt,
      createdAt = createdAt,
    )
}
