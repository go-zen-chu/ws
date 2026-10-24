package com.gozenchu.ws

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {
    private val tabs = listOf(TabState("https://github.com", "GitHub"), TabState("", ""))

    @Test
    fun roundTripsNamedAndUnnamedSessions() {
        val named = Session("ws", tabs, 1, 42L)
        assertEquals(named, Session.decode(Session.encode(listOf(named))).single())
        val unnamed = named.copy(name = null)
        assertEquals(unnamed, Session.fromJson(unnamed.toJson()))
    }

    @Test
    fun upsertReplacesByNameNewestFirst() {
        val a = Session("a", tabs, 0, 1L)
        val b = Session("b", tabs, 0, 2L)
        val newerA = a.copy(savedAt = 3L, activeIndex = 1)
        assertEquals(listOf(newerA, b), Session.upsert(listOf(b, a), newerA))
    }

    @Test
    fun roundTripsSites() {
        assertEquals(DEFAULT_SITES, Site.decode(Site.encode(DEFAULT_SITES)))
    }
}
