package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.auth.GoogleSignInButton
import com.example.auth.attemptAutoSignIn

@Composable
fun AuthScreen(
  onAuthSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var authError by remember { mutableStateOf<String?>(null) }
  var checkingAutoSignIn by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = {
        checkingAutoSignIn = false
        onAuthSuccess()
      },
      onUnauthenticated = {
        checkingAutoSignIn = false
      },
      scope = scope,
    )
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
              MaterialTheme.colorScheme.background,
              MaterialTheme.colorScheme.surface,
            )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier =
        Modifier.widthIn(max = 480.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        modifier = Modifier.size(76.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary,
        tonalElevation = 6.dp,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Outlined.Hub,
            contentDescription = "LinkDraw Logo",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(42.dp),
          )
        }
      }

      Spacer(Modifier.height(16.dp))
      Text(
        text = "LinkDraw Studio",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "Collaborative Graph Intelligence, OSINT Lab & Live Analytics",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )

      Spacer(Modifier.height(22.dp))

      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
      ) {
        Column(
          modifier = Modifier.padding(22.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          FeatureHighlightRow(
            icon = Icons.Default.Groups,
            title = "Real-Time Multi-User Boards",
            subtitle = "Share invite codes to co-edit nodes, edges, and sticky notes live.",
          )
          FeatureHighlightRow(
            icon = Icons.Default.Sync,
            title = "Live Presence & Team Comments",
            subtitle = "See active teammates on the canvas and pin discussions to nodes.",
          )
          FeatureHighlightRow(
            icon = Icons.Default.Hub,
            title = "Full Graph Suite & Offline Personal Vault",
            subtitle = "14+ node shapes, shortest-path/centrality metrics, OSINT & Notes.",
          )

          HorizontalDivider()

          if (authError != null) {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = authError ?: "",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
              )
            }
          }

          if (checkingAutoSignIn) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(10.dp))
              Text(
                text = "Checking Google session...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          GoogleSignInButton(
            onAuthSuccess = {
              authError = null
              onAuthSuccess()
            },
            onAuthError = { msg -> authError = msg },
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = "Protected by Google Identity & Firestore Security Rules",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FeatureHighlightRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.size(36.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(20.dp),
        )
      }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
