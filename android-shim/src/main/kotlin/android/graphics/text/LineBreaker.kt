package android.graphics.text

/**
 * Stand-in for android.graphics.text.LineBreaker.
 *
 * Extensions only read the break-strategy constants to hand them to `StaticLayout.Builder`
 * (Dragon Ball Multiverse, Manhuarm), so just those are modelled, with the framework's values.
 */
class LineBreaker private constructor() {

    companion object {
        const val BREAK_STRATEGY_SIMPLE = 0
        const val BREAK_STRATEGY_HIGH_QUALITY = 1
        const val BREAK_STRATEGY_BALANCED = 2
    }
}
