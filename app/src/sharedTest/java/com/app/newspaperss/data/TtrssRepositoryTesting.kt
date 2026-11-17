package com.app.newspaperss.data

/** Signs in as the app does, returning the error to show the reader or null: how most tests get an account. */
suspend fun TtrssRepository.connect(address: String, user: String, password: String): String? =
    (signIn(address, user, password) as? TtrssRepository.SignIn.Failed)?.message
