package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.PaymentScreenStructure

/** Use only the visible, read-only evidence region, excluding system bars and other windows. */
object PaymentVisualRegion {
    fun bounds(structure: PaymentScreenStructure?, imageWidth: Int, imageHeight: Int): Rect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val regions = structure?.nodes.orEmpty().filter { it.visible && it.labels.isNotEmpty() }
            .mapNotNull { node ->
                if (node.bounds.size != 4) return@mapNotNull null
                val (left, top, right, bottom) = node.bounds
                if (left < 0 || top < 0 || right > imageWidth || bottom > imageHeight ||
                    left >= right || top >= bottom) return@mapNotNull null
                Rect(left, top, right, bottom)
            }
        if (regions.isEmpty()) return null
        return Rect(regions.first()).apply { regions.drop(1).forEach(::union) }
    }
}
