package io.github.revocx35.npmmobile

import io.github.revocx35.npmmobile.data.Cached
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CachedTest {
    @Test fun `a cancelled load does not leave the cache stuck in loading`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val cache = Cached<String>(CoroutineScope(Dispatchers.Unconfined)) {
            calls++
            if (calls == 1) {
                started.complete(Unit)
                CompletableDeferred<String>().await() // never answers
            } else {
                "fresh"
            }
        }
        val job = launch(Dispatchers.Default) { cache.load() }
        started.await()
        job.cancelAndJoin()

        assertFalse("loading must be reset after a cancelled load", cache.state.value.loading)
        cache.ensure() // would be skipped while `loading` is stuck
        assertEquals("fresh", cache.state.value.data)
    }
}
