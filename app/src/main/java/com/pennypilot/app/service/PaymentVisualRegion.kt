package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.PaymentScreenStructure

/** Use only the visible, read-only evidence region, excluding system bars and other windows. */
object PaymentVisualRegion {
    fun bounds(structure: PaymentScreenStructure?, imageWidth: Int, imageHeight: Int,
        includePanel: Boolean = false, windowRelative: Boolean = false,
        windowBounds: List<Int> = emptyList(), receiptBounds: List<Int>? = null): Rect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val nodes = structure?.nodes.orEmpty()
        val root = nodes.firstOrNull { it.parentId == null }
        val origin = windowBounds.takeIf { it.size == 4 }?.takeIf { windowRelative &&
            it[2] - it[0] == imageWidth && it[3] - it[1] == imageHeight } ?: root?.bounds?.takeIf { windowRelative && it.size == 4 &&
            it[2] - it[0] == imageWidth && it[3] - it[1] == imageHeight }
        fun rect(bounds: List<Int>): Rect? {
            if (bounds.size != 4) return null
            val left = bounds[0] - (origin?.get(0) ?: 0)
            val top = bounds[1] - (origin?.get(1) ?: 0)
            val right = bounds[2] - (origin?.get(0) ?: 0)
            val bottom = bounds[3] - (origin?.get(1) ?: 0)
            if (left < 0 || top < 0 || right > imageWidth || bottom > imageHeight ||
                left >= right || top >= bottom) return null
            return Rect(left, top, right, bottom)
        }
        receiptBounds?.let { return rect(it) }
        val evidence = nodes.filter { it.visible && it.labels.isNotEmpty() }
        val regions = evidence.mapNotNull { rect(it.bounds) }
        if (regions.isEmpty()) return null
        val textRegion = Rect(regions.first()).apply { regions.drop(1).forEach(::union) }
        if (!includePanel || evidence.size < 2) return textRegion
        val byId = nodes.associateBy { it.id }
        fun ancestors(id: Int): List<Int> {
            val result = mutableListOf<Int>()
            var current = byId[id]?.parentId
            while (current != null && current !in result) {
                result += current
                current = byId[current]?.parentId
            }
            return result
        }
        // Only a shared interior result panel may expand the crop. The root can contain system
        // bars or unrelated content; an unlabeled root alone never grants a larger region.
        val shared = evidence.map { ancestors(it.id).toSet() }.reduce { a, b -> a.intersect(b) }
        val panel = ancestors(evidence.first().id).firstNotNullOfOrNull { id ->
            byId[id]?.takeIf { id in shared && it.parentId != null && it.visible }
                ?.let { rect(it.bounds) }?.takeIf { it.contains(textRegion) }
        }
        return panel ?: textRegion
    }

    /** Android 11–13 returns a display image; only the verified application window may be sent. */
    fun windowBounds(bounds: List<Int>, imageWidth: Int, imageHeight: Int, windowRelative: Boolean): Rect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (windowRelative) return Rect(0, 0, imageWidth, imageHeight)
        if (bounds.size != 4) return null
        val rect = Rect(bounds[0], bounds[1], bounds[2], bounds[3])
        return rect.takeIf { it.left >= 0 && it.top >= 0 && it.right <= imageWidth && it.bottom <= imageHeight &&
            it.width() > 0 && it.height() > 0 }
    }

}
