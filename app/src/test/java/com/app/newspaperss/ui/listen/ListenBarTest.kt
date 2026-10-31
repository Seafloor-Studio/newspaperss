package com.app.newspaperss.ui.listen

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.listen.ListenPage
import com.app.newspaperss.listen.ListenState
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ListenBarTest {
    @get:Rule val compose = createComposeRule()

    private val playing = ListenState(editionId = 1, editionTitle = "Edition", playing = true, pages = listOf(ListenPage("The ferry planner", "Rail Talk", 1.0)))

    /** Switching editions, Listen opens the new one with nothing but its id: the bar stays, saying so. */
    @Test
    fun whileAnEditionOpensTheBarStaysAndSaysSo() {
        val opening = ListenState(editionId = 2, loading = true, playing = true)
        assertTrue(showsListenBar(opening))
        var closed = 0
        compose.setContent { ListenBar(opening, onOpen = {}, onToggle = {}, onClose = { closed++ }) }
        compose.onNodeWithText("Opening the edition…").assertExists()
        // Nothing to pause yet; stopping still works.
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop listening").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun itGoesWhenNothingIsOpenHeardToTheEndOrBroken() {
        assertTrue(showsListenBar(playing))
        assertFalse(showsListenBar(null))
        assertFalse(showsListenBar(ListenState()))
        assertFalse(showsListenBar(playing.copy(finished = true)))
        assertFalse(showsListenBar(ListenState(editionId = 2, loading = false, playing = false, error = "This edition's file is gone, so it can't be read aloud.")))
    }
}
