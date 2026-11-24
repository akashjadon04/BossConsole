package ai.rever.boss.utils

/**
 * Where a `boss://` request came from.
 *
 * The `boss://` scheme is registered with the OS, so a link arriving at the app
 * is not evidence that the operator asked for anything: every program that can
 * ask the OS to open a URL produces the same input the operator's own shell
 * does, and by the time a link reaches a handler the two are indistinguishable.
 * Entry points therefore state which one they are, and handlers that would act
 * on the operator's behalf consult it.
 *
 * Absence is not neutral: anything that does not state an origin is treated as
 * [EXTERNAL], so a new caller that forgets to say gets the cautious answer.
 */
enum class DeepLinkOrigin {
    /**
     * BOSS's own CLI parsed the request out of this process's `argv`
     * ([ai.rever.boss.cli.createBossCLI]), which only happens for arguments the
     * operator passed to the BOSS executable themselves.
     */
    OPERATOR_CLI,

    /**
     * The request came in over a path any program can drive: the OS URL-open
     * handler, a `boss://` argument handed over by the registered protocol
     * handler, or a forward across the single-instance channel that labelled
     * itself this way. Also the default for an unstated origin.
     */
    EXTERNAL,
    ;

    /** True when the request is known to be the operator acting on their own machine. */
    val isOperatorInitiated: Boolean
        get() = this == OPERATOR_CLI

    companion object {
        /**
         * Reads an origin off the single-instance wire format, mapping anything
         * unrecognised — including a missing label — to [EXTERNAL].
         */
        fun fromWireLabel(label: String?): DeepLinkOrigin {
            val normalized = label?.trim()?.uppercase()
            return entries.firstOrNull { it.name == normalized } ?: EXTERNAL
        }
    }
}
