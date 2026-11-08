package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.auth.AccountStore
import dev.sparkynox.sparkytube.homefeed.HomeFeedViewModel

/** Bottom sheet opened from the Home avatar: switch between saved accounts, add one, or remove one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(
    home: HomeFeedViewModel,
    nav: NavViewModel,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<AccountStore.Account?>(null) }
    val accounts = AccountStore.accounts

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Accounts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                accounts.forEach { a ->
                    val active = a.id == AccountStore.activeId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!active && AccountStore.switchTo(a.id)) {
                                    nav.resetForAccountChange()
                                    home.onAccountChanged()
                                }
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(a.photo, a.name.ifBlank { "?" }, 44)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.name.ifBlank { "YouTube account" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (a.handle.isNotBlank()) Text(
                                a.handle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (active) Icon(Icons.Default.Check, "Active", tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { removing = a }) {
                            Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (accounts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss(); onAddAccount() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Spacer(Modifier.width(14.dp))
                Text("Add account", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove account?") },
            text = { Text("${a.name.ifBlank { "This account" }} will be signed out of SparkyTube on this phone. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    val wasActive = a.id == AccountStore.activeId
                    AccountStore.remove(a.id)
                    removing = null
                    if (wasActive) {
                        nav.resetForAccountChange()
                        home.onAccountChanged()
                    }
                    if (AccountStore.accounts.isEmpty()) onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
