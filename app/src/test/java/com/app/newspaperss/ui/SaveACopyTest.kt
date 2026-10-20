package com.app.newspaperss.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/** The edition's ⋮ › Save a copy…: the book itself, kept where the reader picks. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SaveACopyTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val dbRule = DbRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val viewModels = ViewModels()

    private val book = "the edition's book".toByteArray()

    private fun pickSaveACopy(): Intent {
        val dir = tmp.newFolder("editions")
        File(dir, "tuesday.epub").writeBytes(book)
        val id = runBlocking {
            dbRule.db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.DELIVERED, fileName = "tuesday.epub"))
        }
        val vm = viewModels.add(EditionDetailViewModel(EditionRepository(dbRule.db, dir), id) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        idleUntil { vm.detail.value?.file != null }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Save a copy…").performClick()
        return shadowOf(compose.activity).nextStartedActivityForResult?.intent ?: Intent()
    }

    /** The toast comes from an effect, which runs on the Compose rule's own dispatcher: idle that as well as the looper. */
    private fun waitForToast(text: String) = idleUntil { compose.waitForIdle(); ShadowToast.getTextOfLatestToast() == text }

    private fun answer(asked: Intent, uri: Uri) =
        shadowOf(compose.activity).receiveResult(asked, Activity.RESULT_OK, Intent().setData(uri))

    @Test
    fun theBookIsSavedWhereTheReaderPicks() {
        val asked = pickSaveACopy()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.action)
        assertEquals("application/epub+zip", asked.type)
        assertEquals("Tuesday Morning Edition.epub", asked.getStringExtra(Intent.EXTRA_TITLE))

        val picked = File(tmp.root, "picked.epub")
        answer(asked, Uri.fromFile(picked))

        waitForToast("Saved a copy.")
        assertEquals(book.toList(), picked.readBytes().toList())
    }

    @Test
    fun aCopyAnsweredAfterTheAppWasEndedIsStillSaved() {
        val dir = tmp.newFolder("editions")
        File(dir, "tuesday.epub").writeBytes(book)
        val id = runBlocking {
            dbRule.db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.DELIVERED, fileName = "tuesday.epub"))
        }
        // Made again after Android ended the app: the save dialog's answer arrives before anything
        // has read the edition.
        val vm = viewModels.add(EditionDetailViewModel(EditionRepository(dbRule.db, dir), id) {})
        val picked = File(tmp.root, "picked.epub")
        vm.saveCopy(compose.activity.contentResolver, Uri.fromFile(picked))

        idleUntil { vm.copied.value != null }
        assertEquals("Saved a copy.", vm.copied.value)
        assertEquals(book.toList(), picked.readBytes().toList())
    }

    @Test
    fun aDeviceWithNoSaveDialogSaysSoRatherThanClosing() {
        // E-reader firmware can leave out Android's documents screen.
        shadowOf(compose.activity.application).checkActivities(true)
        pickSaveACopy()
        assertEquals("This device has no way to pick where to save a file. Try Send another way.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aCopyThatCantBeWrittenSaysSo() {
        val asked = pickSaveACopy()
        answer(asked, Uri.fromFile(File(tmp.root, "no-such-folder/picked.epub")))
        waitForToast("Couldn't save the copy.")
    }
}
