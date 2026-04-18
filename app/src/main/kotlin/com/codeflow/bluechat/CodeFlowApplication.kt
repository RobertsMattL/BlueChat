package com.codeflow.bluechat

import android.app.Application

class CodeFlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CodeFlowLogger.init()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        CodeFlowLogger.flush()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CodeFlowLogger.flush()
    }
}
