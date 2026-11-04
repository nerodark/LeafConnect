package leafconnect

import okhttp3.Call
import java.util.concurrent.CopyOnWriteArrayList

class CancellationToken {
    @Volatile
    var isCancelled = false
        private set

    private val calls = CopyOnWriteArrayList<Call>()

    fun register(call: Call) {
        if (isCancelled) call.cancel() else calls.add(call)
    }

    fun cancel() {
        isCancelled = true
        calls.forEach { it.cancel() }
    }
}
