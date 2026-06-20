package com.example.epubreader

import android.app.Application
import com.example.epubreader.data.ReaderDatabase
import com.example.epubreader.data.ReaderRepository

class ReaderApplication : Application() {
    lateinit var repository: ReaderRepository
        private set

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        repository = ReaderRepository(this, ReaderDatabase.create(this).dao())
    }
}
