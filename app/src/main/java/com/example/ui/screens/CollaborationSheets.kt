package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BoardComment
import com.example.model.BoardPresence
import com.example.model.CollabBoard
import com.example.model.GraphNode
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun JoinBoardDialog(
  busy: Boolean,
  onDismiss: () -> Unit,
  onJoin: (String) -> Unit,
) {
  var code by remember { mutableStateOf("") }
  val cleaned = code.trim().uppercase()
  val valid = cleaned.matches(Regex("^[A-Z0-9]{6,12}$"))

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Join Shared Board") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Enter the 6-character invite code shared by a teammate to collaborate live on their LinkDraw canvas.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = code,
          onValueChange = { raw ->
            code = raw.uppercase().filter { it.isLetterOrDigit() }.take(12)
          },
          label = { Text("Invite Code") },
          placeholder = { Text("e.g. LD7K9M") },
          singleLine = true,
          leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
          modifier = Modifier.fillMaxWidth().testTag("join_invite_code_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onJoin(cleaned) },
        enabled = valid && !busy,
        modifier = Modifier.testTag("confirm_join_board"),
      ) {
        if (busy) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
          Text("Join Live")
        }
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardCollaboratorsSheet(
  board: CollabBoard,
  currentUserId: String,
  presence: List<BoardPresence>,
  onCopyToast: (String) -> Unit,
  onRenameBoard: (String, String) -> Unit,
  onRemoveMember: (String) -> Unit,
  onLeaveBoard: () -> Unit,
  onDeleteBoard: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val isOwner = board.ownerId == currentUserId
  var editTitle by remember(board.title) { mutableStateOf(board.title) }
  var editDesc by remember(board.description) { mutableStateOf(board.description) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text("Live Collaboration", style = MaterialTheme.typography.titleLarge)
          Text(
            "${board.memberUids.size}/20 collaborators • Real-time sync active",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Invite Code Card
      ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors =
          CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
          ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Board Invite Code", style = MaterialTheme.typography.labelMedium)
            Text(
              text = board.inviteCode,
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
            )
          }
          FilledTonalIconButton(
            onClick = {
              clipboard.setText(AnnotatedString(board.inviteCode))
              onCopyToast("Copied invite code ${board.inviteCode}")
            },
            modifier = Modifier.testTag("copy_invite_code_button"),
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Invite Code")
          }
          Spacer(Modifier.width(4.dp))
          FilledTonalIconButton(
            onClick = {
              val send =
                Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_SUBJECT, "Join LinkDraw Board: ${board.title}")
                  putExtra(
                    Intent.EXTRA_TEXT,
                    "Join my live LinkDraw collaborative board \"${board.title}\" using Invite Code: ${board.inviteCode}",
                  )
                }
              context.startActivity(Intent.createChooser(send, "Share Invite Code"))
            }
          ) {
            Icon(Icons.Default.Share, contentDescription = "Share Invite Code")
          }
        }
      }

      // Online Now
      Text("Online Right Now (${presence.size})", style = MaterialTheme.typography.labelLarge)
      if (presence.isEmpty()) {
        Text(
          "Connecting presence...",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          presence.take(6).forEach { p ->
            val dotColor = parseHexColor(p.colorHex, MaterialTheme.colorScheme.primary)
            AssistChip(
              onClick = {},
              leadingIcon = {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
              },
              label = {
                val suffix = if (p.uid == currentUserId) " (You)" else ""
                Text("${p.displayName.take(14)}$suffix")
              },
            )
          }
        }
      }

      // Member Roster
      Text("Board Members", style = MaterialTheme.typography.labelLarge)
      board.memberUids.forEach { uid ->
        val name = board.memberNames[uid] ?: if (uid == board.ownerId) board.ownerName else "Collaborator"
        val isRowOwner = uid == board.ownerId
        val isOnline = presence.any { it.uid == uid }
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier =
                Modifier.size(10.dp)
                  .clip(CircleShape)
                  .background(if (isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (uid == currentUserId) "$name (You)" else name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                text = if (isRowOwner) "Owner" else "Collaborator",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (isOwner && !isRowOwner) {
              IconButton(onClick = { onRemoveMember(uid) }) {
                Icon(
                  Icons.Default.PersonRemove,
                  contentDescription = "Remove $name",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }

      if (isOwner) {
        HorizontalDivider()
        Text("Board Settings", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = editTitle,
          onValueChange = { editTitle = it.take(120) },
          label = { Text("Board Title") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = editDesc,
          onValueChange = { editDesc = it.take(300) },
          label = { Text("Description") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = { onRenameBoard(editTitle, editDesc) },
            enabled = editTitle.trim().isNotEmpty(),
            modifier = Modifier.weight(1f),
          ) {
            Text("Save Info")
          }
          OutlinedButton(
            onClick = { showDeleteConfirm = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete Board")
          }
        }
      } else {
        OutlinedButton(
          onClick = {
            onLeaveBoard()
            onDismiss()
          },
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.ExitToApp, null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Leave Shared Board")
        }
      }
    }
  }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Delete Shared Board?") },
      text = {
        Text("This permanently removes \"${board.title}\" and its invite code for all collaborators.")
      },
      confirmButton = {
        Button(
          onClick = {
            showDeleteConfirm = false
            onDeleteBoard()
            onDismiss()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardCommentsSheet(
  board: CollabBoard,
  comments: List<BoardComment>,
  selectedNode: GraphNode?,
  currentUserId: String,
  onSendComment: (text: String, linkSelectedNode: Boolean) -> Unit,
  onDeleteComment: (String) -> Unit,
  onFocusNode: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var draft by remember { mutableStateOf("") }
  var linkNode by remember(selectedNode?.id) { mutableStateOf(selectedNode != null) }
  val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = 320.dp, max = 560.dp)
          .padding(horizontal = 20.dp)
          .padding(bottom = 24.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text("Team Comments", style = MaterialTheme.typography.titleLarge)
          Text(
            "${comments.size} notes on ${board.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      if (comments.isEmpty()) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "No comments yet. Pin feedback to a node or message your collaborators.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(comments, key = { it.id }) { c ->
            val canDelete = c.authorId == currentUserId || board.ownerId == currentUserId
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = c.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                  )
                  val timeLabel = c.createdAt?.toDate()?.let { fmt.format(it) } ?: "now"
                  Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (canDelete) {
                    IconButton(
                      onClick = { onDeleteComment(c.id) },
                      modifier = Modifier.size(28.dp),
                    ) {
                      Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete comment",
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  }
                }
                Spacer(Modifier.height(4.dp))
                Text(c.text, style = MaterialTheme.typography.bodyMedium)
                if (!c.linkedNodeId.isNullOrBlank()) {
                  Spacer(Modifier.height(6.dp))
                  AssistChip(
                    onClick = {
                      onFocusNode(c.linkedNodeId)
                      onDismiss()
                    },
                    leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(16.dp)) },
                    label = { Text("Node: ${c.linkedNodeLabel ?: c.linkedNodeId}") },
                  )
                }
              }
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      if (selectedNode != null) {
        FilterChip(
          selected = linkNode,
          onClick = { linkNode = !linkNode },
          leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(16.dp)) },
          label = { Text("Pin to \"${selectedNode.text.take(24)}\"") },
        )
        Spacer(Modifier.height(6.dp))
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it.take(500) },
          placeholder = { Text("Write a team comment...") },
          singleLine = true,
          modifier = Modifier.weight(1f).testTag("board_comment_input"),
        )
        FilledIconButton(
          onClick = {
            if (draft.isNotBlank()) {
              onSendComment(draft, linkNode && selectedNode != null)
              draft = ""
            }
          },
          enabled = draft.isNotBlank(),
          modifier = Modifier.testTag("send_board_comment_button"),
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Comment")
        }
      }
    }
  }
}
