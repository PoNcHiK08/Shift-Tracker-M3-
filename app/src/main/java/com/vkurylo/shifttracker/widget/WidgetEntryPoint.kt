package com.vkurylo.shifttracker.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** How the widget (created by the system, not by Hilt) reaches the app's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetRepository(): WidgetRepository
    fun widgetUpdater(): WidgetUpdater
}
