package ai.rever.boss.components.auth.screens

import ai.rever.boss.components.auth.forms.*
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.passkey.PasskeyInfo
import ai.rever.boss.viewmodels.LoginViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PasskeySelectionScreen(
    email: String,
    viewModel: LoginViewModel,
    onPasskeySelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedCredentialId by remember { mutableStateOf<String?>(null) }

    // Collect passkeys from ViewModel (already set during login flow)
    val passkeys by viewModel.passkeyAuthViewModel.availablePasskeys.collectAsState()

    // Auto-select if only one passkey (shouldn't happen, but safety)
    LaunchedEffect(passkeys) {
        if (passkeys.size == 1) {
            selectedCredentialId = passkeys.first().credentialId
        }
    }

    // The scaffold brings the verticalScroll this screen never had - and it is the screen that needed
    // it most, since its content grows with the number of registered passkeys.
    AuthScaffold(title = "Choose Your Passkey") {
        PasskeySelectionCardContent(
            email = email,
            passkeys = passkeys,
            selectedCredentialId = selectedCredentialId,
            onSelect = { selectedCredentialId = it },
            onContinue = { selectedCredentialId?.let(onPasskeySelected) },
            onBack = onBack,
        )
    }
}

@Composable
private fun PasskeySelectionCardContent(
    email: String,
    passkeys: List<PasskeyInfo>,
    selectedCredentialId: String?,
    onSelect: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = BossTheme.colors
    Text("Signing in as:", fontSize = 14.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(8.dp))
    Text(email, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.signal, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(20.dp))

    if (passkeys.isNotEmpty()) {
        Text(
            "Select the device you want to use:",
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // A plain Column, not a LazyColumn, now that the scaffold scrolls this screen. The list was a
        // LazyColumn capped at 400dp, which is a scrollable inside a scrollable on the same axis: legal,
        // since the height bound satisfies Compose's constraint check, but a wheel gesture over the list
        // scrolled the inner one and never chained out to the page - on the one screen the scaffold's own
        // KDoc singles out as needing the outer scroll most. Laziness bought nothing here either; nobody
        // has enough registered passkeys for it to matter.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            passkeys.forEach { passkey ->
                PasskeyCard(passkey, selectedCredentialId == passkey.credentialId) { onSelect(passkey.credentialId) }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryActionButton(
            text = "Continue with Selected Passkey",
            onClick = onContinue,
            enabled = selectedCredentialId != null,
            isLoading = false,
        )
    } else {
        Text(
            "No passkeys available.",
            fontSize = 14.sp,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 20.dp),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    TextButton(onBack) {
        Text("Back to Sign In", fontSize = 14.sp, color = colors.signal, textDecoration = TextDecoration.Underline)
    }
}

@Composable
private fun PasskeyCard(
    passkey: PasskeyInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        RoundedCornerShape(8.dp),
        if (isSelected) BossTheme.colors.raised else BossTheme.colors.panel,
        elevation = 0.dp,
        border =
            BorderStroke(
                if (isSelected) 2.dp else 1.dp,
                if (isSelected) BossTheme.colors.signal else BossTheme.colors.line,
            ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = "Device",
                    tint = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textSecondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = passkey.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossTheme.colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatCredentialId(passkey.credentialId),
                        fontSize = 11.sp,
                        color = BossTheme.colors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) BossTheme.colors.ok else BossTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun formatCredentialId(credentialId: String): String {
    // Show first 4 and last 4 characters for unique identification
    return if (credentialId.length > 8) {
        "ID: ${credentialId.take(4)}...${credentialId.takeLast(4)}"
    } else {
        "ID: $credentialId"
    }
}
