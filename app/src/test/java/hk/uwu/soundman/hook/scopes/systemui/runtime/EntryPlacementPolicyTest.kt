package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.model.EntryPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryPlacementPolicyTest {
    @Test
    fun insertIndexKeepsAnchorIndexAboveAndFollowsBelow() {
        assertEquals(2, EntryPlacementPolicy.insertIndex(2, EntryPosition.ABOVE))
        assertEquals(3, EntryPlacementPolicy.insertIndex(2, EntryPosition.BELOW))
    }

    @Test
    fun insertIndexBelowIsAppendWhenAnchorIsLast() {
        assertEquals(5, EntryPlacementPolicy.insertIndex(4, EntryPosition.BELOW))
    }

    /**
     * 回归：入口一旦被放到 wrap_content 父容器外面，父容器 + MATCH_PARENT 的音量条
     * 会一起被撑高，下一轮 anchor.bottom 又变大 —— 实测每轮 +（入口高+间距）。
     */
    @Test
    fun absolutePlacementOutsideParentIsRejectedToAvoidGrowingIt() {
        val parentHeight = 1114
        val entryHeight = 120
        val gap = 30

        // BELOW：anchor.bottom 就是父容器底边，入口必然落到容器外面。
        val below = EntryPlacementPolicy.frameTopMargin(
            anchorTop = 0,
            anchorBottom = parentHeight,
            entryHeight = entryHeight,
            gap = gap,
            position = EntryPosition.BELOW,
        )
        assertEquals(parentHeight + gap, below)
        assertFalse(EntryPlacementPolicy.fitsInsideParent(below, entryHeight, parentHeight))

        // ABOVE：落在容器顶部之内，可以放心用绝对定位。
        val above = EntryPlacementPolicy.frameTopMargin(
            anchorTop = 400,
            anchorBottom = parentHeight,
            entryHeight = entryHeight,
            gap = gap,
            position = EntryPosition.ABOVE,
        )
        assertEquals(250, above)
        assertTrue(EntryPlacementPolicy.fitsInsideParent(above, entryHeight, parentHeight))
    }

    @Test
    fun parentHeightIsIgnoredBeforeFirstMeasure() {
        assertTrue(EntryPlacementPolicy.fitsInsideParent(topMargin = 900, entryHeight = 120, parentHeight = 0))
        assertTrue(EntryPlacementPolicy.fitsInsideParent(topMargin = 900, entryHeight = 120, parentHeight = -1))
    }

    @Test
    fun placementExactlyFillingParentIsAccepted() {
        assertTrue(EntryPlacementPolicy.fitsInsideParent(topMargin = 994, entryHeight = 120, parentHeight = 1114))
        assertFalse(EntryPlacementPolicy.fitsInsideParent(topMargin = 995, entryHeight = 120, parentHeight = 1114))
    }

    @Test
    fun verticalMarginsPutOfficialGapOnOppositeSideOfPosition() {
        assertEquals(
            EntryMargins(top = 0, bottom = 6),
            EntryPlacementPolicy.verticalMargins(6, EntryPosition.ABOVE),
        )
        assertEquals(
            EntryMargins(top = 6, bottom = 0),
            EntryPlacementPolicy.verticalMargins(6, EntryPosition.BELOW),
        )
    }

    @Test
    fun verticalMarginsNeverDoubleCountOfficialGap() {
        val margins = EntryPlacementPolicy.verticalMargins(8, EntryPosition.BELOW)

        assertEquals(8, margins.top + margins.bottom)
    }

    @Test
    fun frameTopMarginAboveSubtractsEntryAndGap() {
        assertEquals(
            148,
            EntryPlacementPolicy.frameTopMargin(
                anchorTop = 200,
                anchorBottom = 320,
                entryHeight = 48,
                gap = 4,
                position = EntryPosition.ABOVE,
            ),
        )
    }

    @Test
    fun frameTopMarginBelowStartsAfterAnchorBottomPlusGap() {
        assertEquals(
            324,
            EntryPlacementPolicy.frameTopMargin(
                anchorTop = 200,
                anchorBottom = 320,
                entryHeight = 48,
                gap = 4,
                position = EntryPosition.BELOW,
            ),
        )
    }

    /**
     * 回归：实测间距一旦在「入口已插入」时被采信，就会把入口自身算进官方间距，
     * 于是每呼出一次音量条，按钮离音量条主体远一截（用户报的漂移）。
     */
    @Test
    fun gapStaysConstantAcrossShowDismissCycles() {
        var cached: Int? = null
        val fallback = 4
        val maxPx = 48

        fun nextGap(measured: Int, entryPresent: Boolean): Int {
            val resolution = EntryPlacementPolicy.resolveGap(
                officialMargin = 0,
                cached = cached,
                measured = measured,
                entryPresent = entryPresent,
                collapsed = true,
                fallback = fallback,
                maxPx = maxPx,
            )
            if (resolution.cacheable) cached = resolution.px
            return resolution.px
        }

        // 首次呼出：入口还没插进去，实测值就是官方间距，存下来。
        val official = nextGap(measured = 12, entryPresent = false)
        assertEquals(12, official)
        // 之后每一次呼出/重试，入口都在树里，实测距离被入口撑大 60px，
        // 但间距必须始终是同一个官方值。
        var measured = 12
        repeat(6) {
            measured += 60
            assertEquals(12, nextGap(measured = measured, entryPresent = true))
        }
    }

    @Test
    fun officialMarginAlwaysWinsOverMeasurement() {
        val resolution = EntryPlacementPolicy.resolveGap(
            officialMargin = 12,
            cached = 40,
            measured = 90,
            entryPresent = true,
            collapsed = true,
            fallback = 4,
            maxPx = 48,
        )

        assertEquals(12, resolution.px)
        assertFalse(resolution.cacheable)
    }

    @Test
    fun measuredGapIsRejectedWhileEntryIsInsertedOrExpanded() {
        val whileInserted = EntryPlacementPolicy.resolveGap(
            officialMargin = 0,
            cached = null,
            measured = 200,
            entryPresent = true,
            collapsed = true,
            fallback = 4,
            maxPx = 48,
        )
        assertEquals(4, whileInserted.px)
        assertFalse(whileInserted.cacheable)

        val whileExpanded = EntryPlacementPolicy.resolveGap(
            officialMargin = 0,
            cached = null,
            measured = 200,
            entryPresent = false,
            collapsed = false,
            fallback = 4,
            maxPx = 48,
        )
        assertEquals(4, whileExpanded.px)
        assertFalse(whileExpanded.cacheable)
    }

    @Test
    fun cleanMeasuredGapIsCacheableAndUnstableOnesFallBack() {
        val clean = EntryPlacementPolicy.resolveGap(
            officialMargin = 0,
            cached = null,
            measured = 12,
            entryPresent = false,
            collapsed = true,
            fallback = 4,
            maxPx = 48,
        )
        assertEquals(12, clean.px)
        assertTrue(clean.cacheable)

        assertEquals(
            4,
            EntryPlacementPolicy.resolveGap(0, null, null, false, true, 4, 48).px,
        )
        assertEquals(
            4,
            EntryPlacementPolicy.resolveGap(0, null, 0, false, true, 4, 48).px,
        )
    }

    /** 首次测量可能撞上呼出动画（官方内容带 scale/translation），超上限的值要截断。 */
    @Test
    fun measuredGapIsClampedToSaneMaximum() {
        val resolution = EntryPlacementPolicy.resolveGap(
            officialMargin = 0,
            cached = null,
            measured = 400,
            entryPresent = false,
            collapsed = true,
            fallback = 4,
            maxPx = 48,
        )

        assertEquals(48, resolution.px)
        assertTrue(resolution.cacheable)
    }

    @Test
    fun entryPositionFallsBackToAboveForUnknownStoredValues() {
        assertEquals(EntryPosition.ABOVE, EntryPosition.fromStored(null))
        assertEquals(EntryPosition.ABOVE, EntryPosition.fromStored(""))
        assertEquals(EntryPosition.ABOVE, EntryPosition.fromStored("above-invalid"))
        assertEquals(EntryPosition.ABOVE, EntryPosition.fromStored("BELOW"))
        assertEquals(EntryPosition.ABOVE, EntryPosition.fromStored("true"))
    }

    @Test
    fun entryPositionRoundTripsStoredValue() {
        EntryPosition.values().forEach { position ->
            assertEquals(position, EntryPosition.fromStored(position.storedValue))
        }
        assertEquals("above", EntryPosition.DEFAULT.storedValue)
    }
}
