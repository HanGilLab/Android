package io.github.hangillab.hangil.feature.map

import org.maplibre.android.maps.MapView

/** All style access stays in this View's Main callback and is checked again before acknowledgement. */
internal class MapSelectionApplier {
    private var appliedNonce: Long? = null

    fun apply(
        view: MapView,
        selection: MapSelection?,
        canApply: (Long) -> Boolean,
        onApplied: (Long) -> Unit,
        onFailed: () -> Unit,
    ) {
        val nonce = selection?.nonce ?: 0L
        if (appliedNonce == nonce || !canApply(nonce)) return
        view.getMapAsync { map ->
            if (view.isAttachedToWindow && canApply(nonce)) {
                try {
                    val style = checkNotNull(map.style) { "Missing ready preview style" }
                    check(style.isFullyLoaded) { "Preview style is not fully loaded" }
                    CoursePreviewLayers.select(style, selection?.pointId)
                    RoadPreviewLayers.select(style, selection?.roadId)
                    check(map.style === style) { "Preview style changed while applying selection" }
                    if (view.isAttachedToWindow && canApply(nonce)) {
                        appliedNonce = nonce
                        if (nonce > 0) onApplied(nonce)
                    }
                } catch (_: Exception) {
                    onFailed()
                } catch (_: LinkageError) {
                    onFailed()
                }
            }
        }
    }
}
