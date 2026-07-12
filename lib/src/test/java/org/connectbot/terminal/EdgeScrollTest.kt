/*
 * ConnectBot Terminal
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeScrollTest {
    private fun direction(posY: Float, position: Int, maximum: Int) = edgeScrollDirection(posY, 1000f, position, maximum)

    @Test
    fun topAndBottomZonesRespectScrollLimits() {
        assertEquals(EdgeScroll.UP, direction(50f, 0, 100))
        assertEquals(EdgeScroll.NONE, direction(50f, 100, 100))
        assertEquals(EdgeScroll.DOWN, direction(950f, 50, 100))
        assertEquals(EdgeScroll.NONE, direction(950f, 0, 100))
        assertEquals(EdgeScroll.NONE, direction(500f, 50, 100))
    }

    @Test
    fun boundariesAreOutsideStrictEdgeZones() {
        assertEquals(EdgeScroll.NONE, direction(120f, 0, 100))
        assertEquals(EdgeScroll.UP, direction(119f, 0, 100))
        assertEquals(EdgeScroll.NONE, direction(880f, 50, 100))
        assertEquals(EdgeScroll.DOWN, direction(881f, 50, 100))
    }

    @Test
    fun velocityScalesWithFingerDepth() {
        assertEquals(1, edgeScrollRowsPerTick(119f, 1000f, EdgeScroll.UP))
        assertEquals(4, edgeScrollRowsPerTick(60f, 1000f, EdgeScroll.UP))
        assertEquals(8, edgeScrollRowsPerTick(0f, 1000f, EdgeScroll.UP))
        assertEquals(8, edgeScrollRowsPerTick(1000f, 1000f, EdgeScroll.DOWN))
    }

    @Test
    fun invalidOrInactiveViewportDoesNotScroll() {
        assertEquals(EdgeScroll.NONE, edgeScrollDirection(0f, 0f, 0, 100))
        assertEquals(0, edgeScrollRowsPerTick(0f, 0f, EdgeScroll.UP))
        assertEquals(0, edgeScrollRowsPerTick(500f, 1000f, EdgeScroll.NONE))
    }
}
