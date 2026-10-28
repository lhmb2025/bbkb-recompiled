package dev.bbkb.ime.core.engine.dlm

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.shared.Logger

/**
 * Moving the whole learned model in and out of the engine, with the safety the engine's own
 * import lacks: the data is read by [LearnedModelStream] first, so nothing the engine would
 * refuse reaches it; the current model is exported before the import and put back if the engine
 * refuses after all (its import resets the model before it checks the data); and the
 * spell-checker's engine, which maps the same model file, is re-attached afterwards so it sees
 * the new model rather than its own stale caches.
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

    /** The engine that holds the learned model, when it can export and import it. */
    private fun engine(): NuanceSDK? {
        if (!NuanceSDKManager.isDLMReady()) return null
        return NuanceSDKManager.peekInstance()?.takeIf { it.isDLMTransferAvailable() }
    }

    /** Whether export and import can work right now. */
    fun isAvailable(): Boolean = engine() != null

    /** The current model as the engine's export stream, or null when unavailable. */
    fun export(): ByteArray? = engine()?.exportDLM()

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
    fun importParsed(model: LearnedModelStream.Model): Outcome {
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

    /** The spell-checker's engine maps the same file; re-attach it so it reads the new model. */
    private fun reattachSecondary() {
        val secondary = NuanceSDKManager.peekSecondary() ?: return
        if (!secondary.isDLMAttached) return
        if (!secondary.loadDLMFromFile()) Logger.warn(TAG, "the spell checker's engine did not re-attach the model")
    }
}
