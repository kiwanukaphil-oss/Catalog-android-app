package com.kline.pilot

import android.app.Application
import androidx.room.Room
import com.kline.pilot.data.*
import kotlinx.coroutines.sync.Mutex

class PilotApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, PilotDatabase::class.java, "pilot.db").addMigrations(PILOT_MIGRATION_1_2).build() }
    val sessions by lazy { SessionStore(this) }
    // Account changes and transfers share a lock, preventing another account from starting a saved request.
    val accountGate = Mutex()
}
