package com.clauder.reader

import android.app.Application
import com.clauder.reader.data.SettingsStore

class ClauderApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
