package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HomeStyleTest {
    @Test public void knownStylesAreKept() {
        assertEquals("turntable", Config.homeStyle("turntable"));
        assertEquals("bubbles", Config.homeStyle(" Bubbles "));
    }

    @Test public void anythingElseIsTheGrid() {
        assertEquals("grid", Config.homeStyle(null));
        assertEquals("grid", Config.homeStyle(""));
        assertEquals("grid", Config.homeStyle("grid"));
        assertEquals("grid", Config.homeStyle("carousel"));
    }
}
