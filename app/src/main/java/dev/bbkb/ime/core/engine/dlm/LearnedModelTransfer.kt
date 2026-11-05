package dev.bbkb.ime.core.engine.dlm

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.engine.EngineOps
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.shared.Logger
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException

/**
 * Moving the whole learned model in and out of the engine, with the safety the engine's own
 * import lacks: the data is read by [LearnedModelStream] first, so nothing the engine would
 * refuse reaches it; the current model is exported before the import and put back if the engine
 * refuses after all (its import resets the model before it checks the data); and the
 * spell-checker's engine, which maps the same model file, is re-attached afterwards so it sees
 * the new model rather than its own stale caches.
 *
 * Every entry point runs its engine work as one op on the loader thread ([EngineThreads.load])
 * and blocks its caller until that op is done, so it must not be called on the main thread
 * (debug builds throw). Called from the loader or the engine thread, it runs inline.
 */
object LearnedModelTransfer {

    private const val TAG = "LearnedModelTransfer"

    sealed class Outcome {
        /** The engine now holds the model; [words] is its word count. */
        class Imported(val words: Int) : Outcome()
        /** The data is not a learned model this app reads; [reason] is for the user. */
        class Rejected(val reason: String) : Outcome()
        /** No engine with a model attached, or the engine build the shim cannot reach. */
        object NotAvailable : Outcome()
        /** The engine refused the model after all; [restored] says whether the previous one is back. */
        class Refused(val status: Int, val restored: Boolean) : Outcome()
    }

    /**
     * Runs [block] on the loader thread and waits for it: inline when the caller is already one of
     * the engine's own threads (a nested call, or the probe), never on the main thread.
     */
    private fun <T> onLoader(block: () -> T): T {
        if (EngineThreads.onEngineCapableThread()) return block()
        EngineThreads.assertNotMain("LearnedModelTransfer")
        return try {
            EngineThreads.load(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The engine that holds the learned model, when it can export and import it. Loader thread. */
    private fun engine(): NuanceSDK? {
        val sdk = NuanceSDKManager.peekInstance() ?: return null
        if (!sdk.isDLMReady()) return null
        return sdk.takeIf { it.isDLMTransferAvailable() }
    }

    /** Whether export and import can work right now. */
    fun isAvailable(): Boolean = onLoader { engine() != null }

    /** The current model as the engine's export stream, or null when unavailable. */
    fun export(): ByteArray? = onLoader { engine()?.exportDLM() }

    /** The current model, parsed; null when unavailable or when the engine's own stream does not parse. */
    fun current(): LearnedModelStream.Model? {
        val bytes = export() ?: return null
        return try {
            LearnedModelStream.parse(bytes)
        } catch (e: LearnedModelStream.InvalidModelException) {
            Logger.warn(TAG, "the engine's own export does not parse: ${e.message}")
            null
        }
    }

    /** Replaces the learned model with [data], a stream from [export] (this phone's or another's). */
    fun importModel(data: ByteArray): Outcome {
        val model = try {
            LearnedModelStream.parse(data)
        } catch (e: LearnedModelStream.InvalidModelException) {
            return Outcome.Rejected(e.message ?: "not a learned model")
        }
        return importParsed(model)
    }

    /** [importModel] for a stream already read, such as one edited with [LearnedModelStream.withCounts]. */
    fun importParsed(model: LearnedModelStream.Model): Outcome = onLoader { importOnLoader(model) }

    private fun importOnLoader(model: LearnedModelStream.Model): Outcome {
        val sdk = engine() ?: return Outcome.NotAvailable
        val safety = sdk.exportDLM()
        val status = sdk.importDLM(model.bytes)
        if (status != NuanceSDK.DLM_IMPORT_OK) {
            val restored = safety != null && sdk.importDLM(safety) == NuanceSDK.DLM_IMPORT_OK
            Logger.warn(TAG, "engine refused the model: status $status, previous model restored=$restored")
            return Outcome.Refused(status, restored)
        }
        reattachSecondary()
        return Outcome.Imported(model.words.size)
    }

    /**
     * The spell-checker's engine maps the same file; re-attach it so it reads the new model. Only
     * when it is loaded (never creates it). Called from the import op on the loader, so it runs
     * inline there, right after the import, as before.
     */
    private fun reattachSecondary() {
        EngineOps.loadPeekSecondary<Unit>("LearnedModelTransfer.reattachSecondary") { secondary ->
            if (secondary.isDLMAttached && !secondary.loadDLMFromFile()) {
                Logger.warn(TAG, "the spell checker's engine did not re-attach the model")
            }
        }
    }
}
