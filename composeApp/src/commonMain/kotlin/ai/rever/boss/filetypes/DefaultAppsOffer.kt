package ai.rever.boss.filetypes

import androidx.compose.runtime.Composable

/**
 * The one-time offer to make BOSS the default for links and code files.
 *
 * `expect` because everything behind it - Launch Services, the Windows registry,
 * `xdg-mime` - is `desktopMain`, while the window that has to show it is composed
 * from `commonMain`. Same shape as `AuthBrandSite` and
 * `rememberUpdateDialogOwnership`, which exist for the same reason.
 *
 * @param isFirstWindow only the first window offers. Two windows opening at once
 *   would otherwise each raise a dialog about the same machine-wide setting, and
 *   both would try to write it.
 */
@Composable
internal expect fun DefaultAppsOfferHost(isFirstWindow: Boolean)
