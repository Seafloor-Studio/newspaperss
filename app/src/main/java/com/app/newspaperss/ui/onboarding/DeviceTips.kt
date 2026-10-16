package com.app.newspaperss.ui.onboarding

import com.app.newspaperss.settings.Device
import com.app.newspaperss.ui.edition.MARK_NOT_SENT

object DeviceTips {
    fun tip(device: Device): String = when (device) {
        Device.KINDLE ->
            "Install the Kindle app from the Play Store and sign in with the same Amazon account as your Kindle. " +
                "When an edition is ready, tap Send and choose the Kindle app (“Send to Kindle”). " +
                "In its form, turn off “Add to your library” and pick your Kindle to send it straight to the device " +
                "(it won't sync to your other devices); otherwise it only goes to your library. " +
                "Changing Author to NewspapeRSS keeps your editions together. " +
                "On the Kindle, it's in Library \u203a Docs once the Kindle is on Wi-Fi."
        Device.KOBO ->
            "Once, on your Kobo: More \u203a Settings \u203a Dropbox \u203a Link, and sign in. Then, when an edition " +
                "is ready, tap Send, choose Dropbox and save it in Apps \u203a Rakuten Kobo. It appears on your Kobo " +
                "when it syncs."
        Device.BOOX ->
            "Install NewspapeRSS on the Boox itself and tap Read to read each edition in its reader, " +
                "or send editions over with BooxDrop."
        Device.POCKETBOOK ->
            "Tap Send, choose your email app and send to your @pbsync.com address (Send-to-PocketBook). " +
                "Add the address you'll send from as a trusted sender in your PocketBook account first."
        Device.KOREADER ->
            "Pick a folder that syncs to your e-reader, for example with Syncthing, and new editions are " +
                "saved there automatically. Without one, tap Send to share each edition."
        Device.OTHER -> "Tap Send to share the EPUB with any app. To read it on this phone instead, it's Open on this phone in the edition's ⋮ menu."
    }

    /**
     * When the app takes an edition as sent, so its articles don't come again, and what to do if
     * it never arrives: a send the app saw can still fail on the way to the e-reader.
     *
     * @param byEmail a Kindle that's emailed its editions rather than sent them with the Kindle app.
     */
    fun delivered(device: Device, byEmail: Boolean = false): String {
        val sent = when (device) {
            Device.KINDLE -> if (byEmail) "your mail app opens with it" else "you choose the Kindle app"
            Device.KOBO -> "you choose Dropbox"
            Device.BOOX -> "you tap Read"
            Device.POCKETBOOK -> "you choose your email app"
            Device.KOREADER -> "it's saved to your folder, or you choose an app after Send"
            Device.OTHER -> "you choose an app after Send"
        }
        return "An edition counts as sent once $sent, and its articles won't come again. " +
            "If it never arrives, open it and choose “$MARK_NOT_SENT” in its menu."
    }
}
