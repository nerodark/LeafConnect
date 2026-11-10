package leafconnect

import kotlin.concurrent.Volatile
import kotlinx.coroutines.Job

/**
 * Cancellation for one round of work (a refresh, a command).
 *
 * The old implementation held OkHttp `Call` references and cancelled them,
 * because `Thread.interrupt()` never aborted a blocking socket read. Requests
 * now run inside coroutines, so cancelling the round's Job aborts the Ktor
 * request on both platforms (OkHttp and NSURLSession honour cancellation).
 *
 * The Job is attached after `scope.launch` returns (passing a Job into a
 * coroutine builder is deprecated: it breaks structured concurrency). The flag
 * is authoritative — a Job finishing normally is not "canceled".
 */
class CancellationToken {

    @Volatile
    private var cancelled = false

    private var job: Job? = null

    val isCancelled: Boolean get() = cancelled

    fun attach(job: Job) {
        this.job = job
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }
}
