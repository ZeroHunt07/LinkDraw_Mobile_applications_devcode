package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.model.*
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class CollaborationRepository(
  private val db: FirebaseFirestore,
  private val currentUserId: String,
  private val auth: FirebaseAuth = Firebase.auth,
) {

  constructor(
    context: Context,
    currentUserId: String,
  ) : this(
    db =
      FirebaseFirestore.getInstance(
        context.applicationContext.getString(R.string.firestore_database_id)
      ),
    currentUserId = currentUserId,
  )

  private fun requireSignedInUid(): String {
    val activeUid = auth.currentUser?.uid
    check(!activeUid.isNullOrBlank()) {
      "User must be signed in with Google before accessing collaboration boards."
    }
    check(activeUid == currentUserId) {
      "Active Google session changed; refusing cross-user Firestore operation."
    }
    return activeUid
  }

  private fun <T> Flow<T>.logAndRethrowFirestoreErrors(
    operationType: OperationType,
    path: String,
  ): Flow<T> = catch { error ->
    val firestoreError =
      generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val exceptionToLog = firestoreError ?: (error as? Exception)
    if (exceptionToLog != null && auth.currentUser?.uid == currentUserId) {
      handleFirestoreError(exceptionToLog, operationType, path)
    }
    throw error
  }

  fun observeMyBoards(): Flow<List<CollabBoard>> {
    if (currentUserId.isBlank()) return emptyFlow()
    val path = "collab_boards"
    return db.collection(path)
      .whereArrayContains("memberUids", currentUserId)
      .snapshots()
      .map { snap ->
        snap
          .toObjects(CollabBoard::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  fun observeBoard(boardId: String): Flow<CollabBoard?> {
    if (currentUserId.isBlank() || boardId.isBlank()) return flowOf(null)
    val path = "collab_boards/$boardId"
    return db.collection("collab_boards")
      .document(boardId)
      .snapshots()
      .map { snap ->
        if (snap.exists()) {
          snap.toObject(CollabBoard::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } else null
      }
      .logAndRethrowFirestoreErrors(OperationType.GET, path)
  }

  fun observeElements(boardId: String): Flow<List<BoardElement>> {
    if (currentUserId.isBlank() || boardId.isBlank()) return flowOf(emptyList())
    val path = "collab_boards/$boardId/elements"
    return db.collection("collab_boards")
      .document(boardId)
      .collection("elements")
      .snapshots()
      .map { snap ->
        snap.toObjects(BoardElement::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  fun observePresence(boardId: String): Flow<List<BoardPresence>> {
    if (currentUserId.isBlank() || boardId.isBlank()) return flowOf(emptyList())
    val path = "collab_boards/$boardId/presence"
    return db.collection("collab_boards")
      .document(boardId)
      .collection("presence")
      .snapshots()
      .map { snap ->
        snap
          .toObjects(BoardPresence::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          .sortedBy { it.displayName.lowercase() }
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  fun observeComments(boardId: String): Flow<List<BoardComment>> {
    if (currentUserId.isBlank() || boardId.isBlank()) return flowOf(emptyList())
    val path = "collab_boards/$boardId/comments"
    return db.collection("collab_boards")
      .document(boardId)
      .collection("comments")
      .snapshots()
      .map { snap ->
        snap
          .toObjects(BoardComment::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          .sortedBy { it.createdAt ?: Timestamp(0, 0) }
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  fun observePersonalNotes(): Flow<List<CollabNote>> {
    if (currentUserId.isBlank()) return emptyFlow()
    val path = "users/$currentUserId/notes"
    return db.collection("users")
      .document(currentUserId)
      .collection("notes")
      .snapshots()
      .map { snap ->
        snap
          .toObjects(CollabNote::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          .sortedWith(
            compareByDescending<CollabNote> { it.pinned }
              .thenByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
          )
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  fun observeBoardNotes(boardId: String): Flow<List<CollabNote>> {
    if (currentUserId.isBlank() || boardId.isBlank()) return flowOf(emptyList())
    val path = "collab_boards/$boardId/notes"
    return db.collection("collab_boards")
      .document(boardId)
      .collection("notes")
      .snapshots()
      .map { snap ->
        snap
          .toObjects(CollabNote::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          .sortedWith(
            compareByDescending<CollabNote> { it.pinned }
              .thenByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
          )
      }
      .logAndRethrowFirestoreErrors(OperationType.LIST, path)
  }

  suspend fun createSharedBoardFromDocument(
    title: String,
    description: String,
    ownerName: String,
    document: GraphDocument,
  ): Result<CollabBoard> {
    var path = "collab_boards"
    return try {
      val uid = requireSignedInUid()
      val cleanTitle = title.trim().ifBlank { "Shared Graph" }.take(120)
      val cleanDesc = description.trim().ifBlank { "Real-time LinkDraw workspace" }.take(300)
      val cleanOwner = ownerName.trim().ifBlank { "Analyst" }.take(80)
      val boardId = "brd_${UUID.randomUUID().toString().replace("-", "").take(16)}"
      val inviteCode = generateInviteCode()
      path = "collab_boards/$boardId"

      val boardRef = db.collection("collab_boards").document(boardId)
      val inviteRef = db.collection("board_invites").document(inviteCode)

      val boardPayload =
        mapOf(
          "ownerId" to uid,
          "ownerName" to cleanOwner,
          "title" to cleanTitle,
          "description" to cleanDesc,
          "inviteCode" to inviteCode,
          "memberUids" to listOf(uid),
          "memberNames" to mapOf(uid to cleanOwner),
          "gridSnap" to document.settings.gridSnap,
          "gridSize" to document.settings.gridSize.coerceIn(8, 96).toLong(),
          "flowAnimation" to document.settings.flowAnimation,
          "schemaVersion" to CollabElementMapper.CURRENT_SCHEMA_VERSION,
          "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      val invitePayload =
        mapOf(
          "boardId" to boardId,
          "ownerId" to uid,
          "title" to cleanTitle,
          "createdAt" to FieldValue.serverTimestamp(),
        )

      // Batch 1: Atomic creation of board + invite record (uses existsAfter/getAfter in rules)
      db.batch()
        .apply {
          set(boardRef, boardPayload)
          set(inviteRef, invitePayload)
        }
        .commit()
        .awaitTask()

      // Batch 2+: Populate initial graph elements (board exists for isBoardMember rule check)
      val entries = CollabElementMapper.documentElementEntries(document).entries.toList()
      val elemsCol = boardRef.collection("elements")
      for (chunk in entries.chunked(350)) {
        val batch = db.batch()
        chunk.forEach { (elemId, kindAndPayload) ->
          val (kind, payload) = kindAndPayload
          batch.set(
            elemsCol.document(elemId),
            mapOf(
              "kind" to kind,
              "payload" to payload,
              "updatedByUid" to uid,
              "updatedByName" to cleanOwner,
              "schemaVersion" to CollabElementMapper.CURRENT_SCHEMA_VERSION,
              "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
              "createdAt" to FieldValue.serverTimestamp(),
              "updatedAt" to FieldValue.serverTimestamp(),
            ),
          )
        }
        batch.commit().awaitTask()
      }

      val snap = boardRef.get().awaitTask()
      val createdBoard =
        snap.toObject(CollabBoard::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          ?: CollabBoard(
            id = boardId,
            ownerId = uid,
            ownerName = cleanOwner,
            title = cleanTitle,
            description = cleanDesc,
            inviteCode = inviteCode,
            memberUids = listOf(uid),
            memberNames = mapOf(uid to cleanOwner),
            gridSnap = document.settings.gridSnap,
            gridSize = document.settings.gridSize.coerceIn(8, 96).toLong(),
            flowAnimation = document.settings.flowAnimation,
          )
      Result.success(createdBoard)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  suspend fun joinBoardByInviteCode(rawCode: String, displayName: String): Result<CollabBoard> {
    val code = rawCode.trim().uppercase()
    var path = "board_invites/$code"
    return try {
      val uid = requireSignedInUid()
      require(code.matches(Regex("^[A-Z0-9]{6,12}$"))) {
        "Invite code must be 6-12 letters or numbers."
      }
      val cleanName = displayName.trim().ifBlank { "Collaborator" }.take(80)

      val inviteSnap = db.collection("board_invites").document(code).get().awaitTask()
      if (!inviteSnap.exists()) {
        return Result.failure(IllegalArgumentException("No shared board found for code \"$code\"."))
      }
      val boardId =
        inviteSnap.getString("boardId")?.takeIf { it.isNotBlank() }
          ?: return Result.failure(IllegalStateException("Invite record is missing a board reference."))

      path = "collab_boards/$boardId"
      val boardRef = db.collection("collab_boards").document(boardId)

      // Check if already a member (if not a member, get() fails with PERMISSION_DENIED)
      val alreadyMemberSnap =
        try {
          boardRef.get().awaitTask()
        } catch (e: FirebaseFirestoreException) {
          if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw e
        }

      if (alreadyMemberSnap != null && alreadyMemberSnap.exists()) {
        val board =
          alreadyMemberSnap.toObject(
            CollabBoard::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        if (board != null) return Result.success(board)
      }

      boardRef
        .update(
          mapOf(
            "memberUids" to FieldValue.arrayUnion(uid),
            "memberNames.$uid" to cleanName,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .awaitTask()

      val joinedSnap = boardRef.get().awaitTask()
      val joined =
        joinedSnap.toObject(CollabBoard::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          ?: return Result.failure(IllegalStateException("Joined board could not be loaded."))
      Result.success(joined)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun syncDocumentChanges(
    boardId: String,
    previousDoc: GraphDocument,
    nextDoc: GraphDocument,
    knownRemoteElementIds: Set<String>,
    updaterName: String,
  ): Result<Unit> {
    val path = "collab_boards/$boardId/elements"
    return try {
      val uid = requireSignedInUid()
      require(boardId.isNotBlank()) { "Board ID must not be blank." }
      val cleanName = updaterName.trim().ifBlank { "Collaborator" }.take(80)

      val prevEntries = CollabElementMapper.documentElementEntries(previousDoc)
      val nextEntries = CollabElementMapper.documentElementEntries(nextDoc)

      val toDelete =
        prevEntries.keys.filter { it !in nextEntries && it in knownRemoteElementIds } +
          knownRemoteElementIds.filter { it !in nextEntries && it !in prevEntries }
      val toUpsert =
        nextEntries.filter { (id, pair) ->
          val prev = prevEntries[id]
          prev == null || prev != pair || id !in knownRemoteElementIds
        }

      val boardRef = db.collection("collab_boards").document(boardId)
      val elemsCol = boardRef.collection("elements")

      val ops = mutableListOf<Pair<String, Pair<String, Map<String, Any>>?>>()
      toUpsert.forEach { (id, pair) -> ops += id to pair }
      toDelete.distinct().forEach { id -> ops += id to null }

      for (chunk in ops.chunked(350)) {
        val batch = db.batch()
        for ((id, kindPair) in chunk) {
          val docRef = elemsCol.document(id)
          if (kindPair == null) {
            batch.delete(docRef)
          } else {
            val (kind, payload) = kindPair
            val isExistingDoc =
              if (id in knownRemoteElementIds) true else docRef.get().awaitTask().exists()
            if (isExistingDoc) {
              batch.update(
                docRef,
                mapOf(
                  "payload" to payload,
                  "updatedByUid" to uid,
                  "updatedByName" to cleanName,
                  "schemaVersion" to CollabElementMapper.CURRENT_SCHEMA_VERSION,
                  "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
                  "updatedAt" to FieldValue.serverTimestamp(),
                ),
              )
            } else {
              batch.set(
                docRef,
                mapOf(
                  "kind" to kind,
                  "payload" to payload,
                  "updatedByUid" to uid,
                  "updatedByName" to cleanName,
                  "schemaVersion" to CollabElementMapper.CURRENT_SCHEMA_VERSION,
                  "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
                  "createdAt" to FieldValue.serverTimestamp(),
                  "updatedAt" to FieldValue.serverTimestamp(),
                ),
              )
            }
          }
        }
        batch.commit().awaitTask()
      }

      if (previousDoc.settings != nextDoc.settings) {
        boardRef
          .update(
            mapOf(
              "gridSnap" to nextDoc.settings.gridSnap,
              "gridSize" to nextDoc.settings.gridSize.coerceIn(8, 96).toLong(),
              "flowAnimation" to nextDoc.settings.flowAnimation,
              "updatedAt" to FieldValue.serverTimestamp(),
            )
          )
          .awaitTask()
      }

      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun updateBoardDetails(
    boardId: String,
    title: String,
    description: String,
  ): Result<Unit> {
    val path = "collab_boards/$boardId"
    return try {
      requireSignedInUid()
      val cleanTitle = title.trim().take(120)
      val cleanDesc = description.trim().take(300)
      require(cleanTitle.isNotBlank()) { "Board title must not be blank." }
      db.collection("collab_boards")
        .document(boardId)
        .update(
          mapOf(
            "title" to cleanTitle,
            "description" to cleanDesc,
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun removeMember(boardId: String, memberUid: String): Result<Unit> {
    val path = "collab_boards/$boardId"
    return try {
      val uid = requireSignedInUid()
      require(memberUid.isNotBlank() && memberUid != uid) {
        "Cannot remove board owner."
      }
      db.collection("collab_boards")
        .document(boardId)
        .update(
          mapOf(
            "memberUids" to FieldValue.arrayRemove(memberUid),
            "memberNames.$memberUid" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .awaitTask()
      runCatching {
        db.collection("collab_boards")
          .document(boardId)
          .collection("presence")
          .document(memberUid)
          .delete()
          .awaitTask()
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun leaveBoard(boardId: String): Result<Unit> {
    val path = "collab_boards/$boardId"
    return try {
      val uid = requireSignedInUid()
      runCatching {
        db.collection("collab_boards")
          .document(boardId)
          .collection("presence")
          .document(uid)
          .delete()
          .awaitTask()
      }
      db.collection("collab_boards")
        .document(boardId)
        .update(
          mapOf(
            "memberUids" to FieldValue.arrayRemove(uid),
            "memberNames.$uid" to FieldValue.delete(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
        .awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun deleteBoard(board: CollabBoard): Result<Unit> {
    val path = "collab_boards/${board.id}"
    return try {
      requireSignedInUid()
      val boardRef = db.collection("collab_boards").document(board.id)

      val elemIds = boardRef.collection("elements").get().awaitTask().documents.map { it.reference }
      val presIds = boardRef.collection("presence").get().awaitTask().documents.map { it.reference }
      val commIds = boardRef.collection("comments").get().awaitTask().documents.map { it.reference }
      val noteIds = boardRef.collection("notes").get().awaitTask().documents.map { it.reference }

      val allSubRefs = elemIds + presIds + commIds + noteIds
      for (chunk in allSubRefs.chunked(350)) {
        val batch = db.batch()
        chunk.forEach { batch.delete(it) }
        batch.commit().awaitTask()
      }

      db.batch()
        .apply {
          if (board.inviteCode.isNotBlank()) {
            delete(db.collection("board_invites").document(board.inviteCode))
          }
          delete(boardRef)
        }
        .commit()
        .awaitTask()

      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  suspend fun updatePresence(
    boardId: String,
    displayName: String,
    selectedNodeId: String?,
    activeTab: String,
    hasExistingPresence: Boolean,
  ): Result<Unit> {
    val path = "collab_boards/$boardId/presence/$currentUserId"
    return try {
      val uid = requireSignedInUid()
      val cleanName = displayName.trim().ifBlank { "Collaborator" }.take(80)
      val colorHex = CollabElementMapper.colorForUid(uid)
      val cleanNodeId = selectedNodeId?.takeIf { it.isNotBlank() }?.take(128)
      val presRef = db.collection("collab_boards").document(boardId).collection("presence").document(uid)

      val existsOnServer = hasExistingPresence || presRef.get().awaitTask().exists()
      if (existsOnServer) {
        val updateMap = mutableMapOf<String, Any>(
          "displayName" to cleanName,
          "colorHex" to colorHex,
          "activeTab" to activeTab,
          "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
        updateMap["selectedNodeId"] = cleanNodeId ?: FieldValue.delete()
        presRef.update(updateMap).awaitTask()
      } else {
        val createMap =
          mutableMapOf<String, Any?>(
              "uid" to uid,
              "displayName" to cleanName,
              "colorHex" to colorHex,
              "selectedNodeId" to cleanNodeId,
              "activeTab" to activeTab,
              "sourcePlatform" to CollabElementMapper.PLATFORM_ANDROID,
              "createdAt" to FieldValue.serverTimestamp(),
              "updatedAt" to FieldValue.serverTimestamp(),
            )
            .filterValues { it != null } as Map<String, Any>
        presRef.set(createMap).awaitTask()
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun clearPresence(boardId: String): Result<Unit> {
    val path = "collab_boards/$boardId/presence/$currentUserId"
    return try {
      val uid = auth.currentUser?.uid ?: return Result.success(Unit)
      db.collection("collab_boards")
        .document(boardId)
        .collection("presence")
        .document(uid)
        .delete()
        .awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  suspend fun addComment(
    boardId: String,
    authorName: String,
    text: String,
    linkedNodeId: String?,
    linkedNodeLabel: String?,
  ): Result<Unit> {
    val commentId = "cmt_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    val path = "collab_boards/$boardId/comments/$commentId"
    return try {
      val uid = requireSignedInUid()
      val cleanText = text.trim()
      require(cleanText.isNotEmpty() && cleanText.length <= 500) {
        "Comment must be 1 to 500 characters."
      }
      val cleanAuthor = authorName.trim().ifBlank { "Collaborator" }.take(80)
      val payload =
        mutableMapOf<String, Any?>(
            "authorId" to uid,
            "authorName" to cleanAuthor,
            "text" to cleanText,
            "linkedNodeId" to linkedNodeId?.takeIf { it.isNotBlank() }?.take(128),
            "linkedNodeLabel" to linkedNodeLabel?.takeIf { it.isNotBlank() }?.take(120),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
          .filterValues { it != null } as Map<String, Any>

      db.collection("collab_boards")
        .document(boardId)
        .collection("comments")
        .document(commentId)
        .set(payload)
        .awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  suspend fun deleteComment(boardId: String, commentId: String): Result<Unit> {
    val path = "collab_boards/$boardId/comments/$commentId"
    return try {
      requireSignedInUid()
      db.collection("collab_boards")
        .document(boardId)
        .collection("comments")
        .document(commentId)
        .delete()
        .awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  suspend fun upsertPersonalNote(note: StudioNote, updaterName: String): Result<Unit> {
    val cleanId = CollabElementMapper.sanitizeElementId(note.id, "note")
    val path = "users/$currentUserId/notes/$cleanId"
    return try {
      val uid = requireSignedInUid()
      val noteRef = db.collection("users").document(uid).collection("notes").document(cleanId)
      upsertNoteDocument(noteRef, note, uid, updaterName)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun deletePersonalNote(noteId: String): Result<Unit> {
    val cleanId = CollabElementMapper.sanitizeElementId(noteId, "note")
    val path = "users/$currentUserId/notes/$cleanId"
    return try {
      val uid = requireSignedInUid()
      db.collection("users").document(uid).collection("notes").document(cleanId).delete().awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  suspend fun upsertBoardNote(
    boardId: String,
    note: StudioNote,
    updaterName: String,
  ): Result<Unit> {
    val cleanId = CollabElementMapper.sanitizeElementId(note.id, "note")
    val path = "collab_boards/$boardId/notes/$cleanId"
    return try {
      val uid = requireSignedInUid()
      val noteRef = db.collection("collab_boards").document(boardId).collection("notes").document(cleanId)
      upsertNoteDocument(noteRef, note, uid, updaterName)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun deleteBoardNote(boardId: String, noteId: String): Result<Unit> {
    val cleanId = CollabElementMapper.sanitizeElementId(noteId, "note")
    val path = "collab_boards/$boardId/notes/$cleanId"
    return try {
      requireSignedInUid()
      db.collection("collab_boards").document(boardId).collection("notes").document(cleanId).delete().awaitTask()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  private suspend fun upsertNoteDocument(
    noteRef: com.google.firebase.firestore.DocumentReference,
    note: StudioNote,
    uid: String,
    updaterName: String,
  ) {
    val fields = CollabElementMapper.studioNoteToFields(note, uid, updaterName)
    val existing = noteRef.get().awaitTask()
    if (existing.exists()) {
      val updateMap = LinkedHashMap<String, Any>(fields)
      updateMap["updatedAt"] = FieldValue.serverTimestamp()
      if (!fields.containsKey("linkedNodeId") && existing.contains("linkedNodeId")) {
        updateMap["linkedNodeId"] = FieldValue.delete()
      }
      if (!fields.containsKey("linkedNodeName") && existing.contains("linkedNodeName")) {
        updateMap["linkedNodeName"] = FieldValue.delete()
      }
      if (!fields.containsKey("projectId") && existing.contains("projectId")) {
        updateMap["projectId"] = FieldValue.delete()
      }
      noteRef.update(updateMap).awaitTask()
    } else {
      fields["createdAt"] = FieldValue.serverTimestamp()
      fields["updatedAt"] = FieldValue.serverTimestamp()
      noteRef.set(fields).awaitTask()
    }
  }

  private fun generateInviteCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rng = SecureRandom()
    return buildString(6) {
      repeat(6) { append(alphabet[rng.nextInt(alphabet.length)]) }
    }
  }
}
