package com.tunenagi.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Tunenagi application entry point — owned.
 *
 * Hilt annotation is the only runtime wiring the app needs at the top level for the
 * initial phase. Feature modules extend this with their own `@Module` contributions
 * as they are added; do not centralize all bindings here.
 */
@HiltAndroidApp
class TunenagiApp : Application()
