package ai.rever.boss.components.auth.forms

/**
 * Plays the sign-in screen's arrival sound: a single deep swell, four seconds, once per process.
 *
 * **A one-shot, not a loop.** Continuous background music on the screen someone has to pass through to use
 * the app is a lot to impose; a short swell says "you have arrived somewhere" and then gets out of the way.
 *
 * **Played from the JVM rather than the brand page, and that is not a stylistic choice.** The page tried
 * this with WebAudio and could not win: Chromium's autoplay policy leaves an `AudioContext` suspended until
 * a discrete user gesture, so a sound meant to accompany the screen *appearing* either never played or
 * waited for a click that had nothing to do with it. Playing from the app has no such gate.
 *
 * It also means the sound does not depend on the embedded browser: it plays when the brand page is off via
 * `BOSS_AUTH_BRAND_SITE=false`, and when the engine failed and the panel fell back to the drawn art.
 * `BOSS_AUTH_SOUND=false` silences it.
 *
 * The audio is synthesised, so there is no asset to ship and no licensed material compiled into the app.
 * Silent no-op on any machine without working audio output.
 */
internal expect fun startAuthTheme()

/**
 * Cuts the swell short if the screen goes before it has finished.
 *
 * The caller pairs it with [startAuthTheme] in a `DisposableEffect`. Four seconds is easily longer than a
 * saved session takes to sign in, so without this the sound would carry on over the app that follows -
 * which is the one outcome that would make it intolerable.
 */
internal expect fun stopAuthTheme()
