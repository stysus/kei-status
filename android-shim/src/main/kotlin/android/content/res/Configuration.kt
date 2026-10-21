package android.content.res

/**
 * Stand-in for android.content.res.Configuration.
 *
 * `uiMode` is a field rather than a property because compiled code reaches it with `getfield`, as
 * on Android. Nothing in a headless run has a theme, so the night bits stay undefined, which reads
 * as "light" to a source that picks a captcha theme from them.
 */
class Configuration {

    @JvmField
    var uiMode: Int = UI_MODE_NIGHT_UNDEFINED

    companion object {
        const val UI_MODE_NIGHT_MASK = 0x30

        const val UI_MODE_NIGHT_NO = 0x10

        const val UI_MODE_NIGHT_YES = 0x20

        const val UI_MODE_NIGHT_UNDEFINED = 0x00
    }
}
