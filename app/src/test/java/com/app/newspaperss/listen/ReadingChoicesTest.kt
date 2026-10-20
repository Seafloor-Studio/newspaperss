package com.app.newspaperss.listen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingChoicesTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun edition(name: String) = runBlocking {
        val file = File(app.filesDir, "editions/$name.epub").apply { parentFile!!.mkdirs(); writeText("epub") }
        app.container.db.editions().insert(EditionEntity(title = name, status = EditionStatus.DELIVERED, fileName = file.name))
    }

    @Test
    fun anEditionsChoicesGoWithItsBook() {
        val gone = edition("monday")
        val kept = edition("tuesday")
        val choices = StoredReadingChoices(app)
        choices.setVoiceInstead(gone, 2, true)
        choices.setVoiceInstead(gone, 5, true)
        choices.setVoiceInstead(kept, 2, true)

        runBlocking { app.container.editions.delete(gone) }

        idleUntil { !choices.voiceInstead(gone, 2) && !choices.voiceInstead(gone, 5) }
        assertTrue(choices.voiceInstead(kept, 2))
    }
}
