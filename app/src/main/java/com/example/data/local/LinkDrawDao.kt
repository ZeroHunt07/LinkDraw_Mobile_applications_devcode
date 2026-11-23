package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDrawDao {

  @Query("SELECT * FROM user_accounts ORDER BY createdAt DESC")
  fun observeAccounts(): Flow<List<UserAccountEntity>>

  @Query("SELECT * FROM user_accounts WHERE LOWER(email) = LOWER(:email) LIMIT 1")
  suspend fun findAccountByEmail(email: String): UserAccountEntity?

  @Query("SELECT * FROM user_accounts WHERE id = :id LIMIT 1")
  suspend fun findAccountById(id: String): UserAccountEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAccount(account: UserAccountEntity)

  @Query("SELECT * FROM graph_projects WHERE userId = :userId ORDER BY updatedAt DESC")
  fun observeProjectsForUser(userId: String): Flow<List<GraphProjectEntity>>

  @Query("SELECT * FROM graph_projects WHERE id = :projectId AND userId = :userId LIMIT 1")
  suspend fun getProjectById(projectId: String, userId: String): GraphProjectEntity?

  @Query("SELECT COUNT(*) FROM graph_projects WHERE userId = :userId")
  suspend fun countProjectsForUser(userId: String): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertProject(project: GraphProjectEntity)

  @Query("DELETE FROM graph_projects WHERE id = :projectId AND userId = :userId")
  suspend fun deleteProject(projectId: String, userId: String)

  @Query(
    "SELECT * FROM studio_notes WHERE userId = :userId ORDER BY pinned DESC, updatedAt DESC"
  )
  fun observeNotesForUser(userId: String): Flow<List<StudioNoteEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertNote(note: StudioNoteEntity)

  @Query("DELETE FROM studio_notes WHERE id = :noteId AND userId = :userId")
  suspend fun deleteNote(noteId: String, userId: String)
}
