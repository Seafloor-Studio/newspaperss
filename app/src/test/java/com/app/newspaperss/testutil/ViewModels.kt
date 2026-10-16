package com.app.newspaperss.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.rules.ExternalResource

/**
 * View models a test makes itself, stopped when it ends, as their screen's owner would stop them.
 *
 * Left running, a view model's flows keep querying for five seconds after the screen has gone
 * (`WhileSubscribed(5_000)`), into a database the test has closed by then. Give this rule a higher
 * order than the one closing the database, so it stops them first.
 */
class ViewModels : ExternalResource() {
    private val made = mutableListOf<ViewModel>()

    fun <T : ViewModel> add(vm: T): T = vm.also { made += it }

    override fun after() = made.forEach { it.viewModelScope.cancel() }
}
