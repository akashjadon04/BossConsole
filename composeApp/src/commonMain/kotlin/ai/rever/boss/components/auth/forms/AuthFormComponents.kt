package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the auth family's full-width actions.
 *
 * Taller than Material's intrinsic 36dp and specific to these screens, where a single action is the
 * whole point of the page. Named so the three buttons that share it cannot drift apart.
 */
internal val AuthButtonHeight: Dp = 48.dp

/**
 * Shared email input field component.
 *
 * The background is `ink`, not `panel`: this sits on the form pane, which IS `panel`, so a `panel`
 * field was invisible against its own container - and in Blueprint Light and Daylight, where `panel`
 * and `raised` are both pure white, no other surface token would separate either. `ink` reads as an
 * inset in the dark themes and as a faint grey well in the light ones.
 *
 * `focusedLabelColor` is `signalText` rather than `signal` because a label is a glyph, and the
 * design system splits those: `signal` for fills and borders, `signalText` for anything drawn as
 * text (Blueprint's `signal` only reaches 3.5:1 as text).
 */
@Composable
fun EmailField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = BossTheme.colors
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        // Guarded, because `requestFocus` throws if the node is not attached yet, and this composable is
        // reachable from screens that may compose it before layout has placed it. A sign-in screen that
        // crashed on the way to focusing a field would be a poor trade for saving one click.
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Email", style = BossTheme.type.body) },
        leadingIcon = {
            Icon(
                Icons.Default.Email,
                contentDescription = "Email",
                tint = colors.textSecondary,
            )
        },
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        shape = BossTheme.radius.inputShape,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Go,
            ),
        keyboardActions = keyboardActions,
        enabled = enabled,
        colors =
            TextFieldDefaults.outlinedTextFieldColors(
                textColor = colors.textPrimary,
                backgroundColor = colors.ink,
                focusedBorderColor = colors.signal,
                unfocusedBorderColor = colors.line,
                cursorColor = colors.signal,
                focusedLabelColor = colors.signalText,
                unfocusedLabelColor = colors.textSecondary,
            ),
    )
}

/**
 * Shared primary action button component.
 *
 * `contentColor` is `onSignal`, not white. `onSignal` is near-black in Daylight (`#2A1B05`) and
 * Operator (`#1A1206`), so a hardcoded white label was illegible on the amber fill in two of the five
 * themes - and it disagreed with the passkey button on the same screen, which already used the token.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val colors = BossTheme.colors
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(AuthButtonHeight),
        enabled = enabled && !isLoading,
        shape = BossTheme.radius.buttonShape,
        colors =
            ButtonDefaults.buttonColors(
                backgroundColor = colors.signal,
                contentColor = colors.onSignal,
                disabledBackgroundColor = colors.raised,
                disabledContentColor = colors.textMuted,
            ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = colors.onSignal,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = BossTheme.type.title,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Shared error message display component.
 */
@Composable
fun ErrorMessage(
    message: String?,
    modifier: Modifier = Modifier,
) {
    if (message != null) {
        Text(
            text = message,
            color = BossTheme.colors.alert,
            style = BossTheme.type.body,
            modifier = modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * Shared loading indicator component.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Int = 24,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        color = BossTheme.colors.signal,
        strokeWidth = 2.dp,
    )
}
