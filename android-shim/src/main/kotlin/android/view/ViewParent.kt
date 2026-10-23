package android.view

/**
 * Stand-in for android.view.ViewParent.
 *
 * Android returns this from [View.getParent], and every source in the extension tree immediately
 * casts it to [ViewGroup] before touching it (`webView.parent as? ViewGroup`), so the type only has
 * to exist and be implemented by [ViewGroup]. Android declares some forty methods here; none of
 * them are reachable through a `ViewParent` reference in that tree, and inventing them would mean
 * guessing behaviour no caller asks for.
 */
interface ViewParent
