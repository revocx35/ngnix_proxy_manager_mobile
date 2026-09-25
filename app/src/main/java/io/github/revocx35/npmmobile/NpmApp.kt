package io.github.revocx35.npmmobile

import android.app.Application
import io.github.revocx35.npmmobile.data.AppGraph

open class NpmApp : Application() {
    lateinit var graph: AppGraph
        private set

    /** Tests override this (e.g. with a cipher that doesn't need the Android Keystore). */
    protected open fun createGraph(): AppGraph = AppGraph(this)

    override fun onCreate() {
        super.onCreate()
        graph = createGraph()
        graph.diagnostics.install()
        graph.session.start()
    }
}
