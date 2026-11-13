package dev.bbkb.ime.core

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.inputmethodservice.AbstractInputMethodService
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.PrintWriterPrinter
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorManager
import dev.bbkb.ime.core.contacts.ContactsLearningManager
import dev.bbkb.ime.core.device.config.model.DeviceQuirk
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.device.state.MetaKeyStateTracker
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.touch.KeypadTouchSources
import dev.bbkb.ime.core.device.touch.NativeTouchSource
import dev.bbkb.ime.core.device.touch.ShizukuTouchSource
import dev.bbkb.ime.core.device.touch.TouchSourceSelector
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.personaldictionary.OneTapAddWord
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector
import dev.bbkb.ime.core.gesture.ShakeGestureHandler
import dev.bbkb.ime.core.keyevent.CompositeKeyCharacterInterpreter
import dev.bbkb.ime.core.layout.PhysicalKeyResolver
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.KeyEventConverter
import dev.bbkb.ime.core.keyevent.KeyHoldHandler
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager
import dev.bbkb.ime.core.settings.ComposeSettingsActivity
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.suggestion.EmojibaseStripSuggestion
import dev.bbkb.ime.core.suggestion.SuggestionEngine
import dev.bbkb.ime.core.suggestion.SuggestionStripPresenter
import dev.bbkb.ime.core.suggestion.SuggestionUpdater
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.InputMethodHelper
import dev.bbkb.ime.core.textinput.InputSessionCoordinator
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.core.shared.CoordinateUtils
import dev.bbkb.ime.core.shared.WeakOwnerHandler
import dev.bbkb.ime.core.keyevent.CurrencyKeyHandler
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.settings.IntentUtils
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.shared.InAppEventBus
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.shared.StartupTiming
import dev.bbkb.ime.core.shared.ViewLayoutUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.receivers.ConnectivityAndScreenReceiver
import dev.bbkb.ime.core.receivers.LearningModelSaveReceiver
import dev.bbkb.ime.core.receivers.LocaleChangeReceiver
import dev.bbkb.ime.core.shared.CursorAnchorInfoUtils
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.shared.StringHelper.capitalizeFirstCodePoint
import dev.bbkb.ime.core.ime.CursorMovementListener
import dev.bbkb.ime.core.ime.InputView
import dev.bbkb.ime.core.textinput.CursorTracker
import dev.bbkb.ime.keyboard.auxbar.suggestions.FlickSuggestionView
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.internal.KeyboardId
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.state.CrossAxisRules
import dev.bbkb.ime.keyboard.state.KeyboardTransition
import dev.bbkb.ime.keyboard.MainKeyboardView
import dev.bbkb.ime.keyboard.auxbar.ArrowBarController
import dev.bbkb.ime.keyboard.auxbar.autofill.InlineAutofillManager
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiToneMemory
import dev.bbkb.ime.keyboard.auxbar.suggestions.CJKSuggestionGridView
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardHandler
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController
import dev.bbkb.ime.keyboard.internal.GestureEventProcessor
import dev.bbkb.ime.core.engine.EngineOps
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelController
import dev.bbkb.ime.keyboard.slideboard.QuickPhrasesController
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.languagepack.LanguagePackLocaleMonitor
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Locale
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.gesture.replay.GestureReplayReceiver
import dev.bbkb.ime.core.gesture.replay.SensorVizOverlay
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.locale.SubtypeState
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.ime.ControlModeController
import dev.bbkb.ime.core.ime.HardwareKeyBridge
import dev.bbkb.ime.core.ime.CkbGestureBridge
import dev.bbkb.ime.core.ime.ImeInsets
import dev.bbkb.ime.core.ime.ThemePrefsListener
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.ime.SwipeToDeleteAnimatorView
import dev.bbkb.ime.core.ime.VkbGestureListener
import dev.bbkb.ime.core.keyevent.KeyEventProcessor
import dev.bbkb.ime.core.keyevent.ModifierResetReason
import dev.bbkb.ime.core.keyevent.ModifierState
import dev.bbkb.ime.core.keyevent.MultitapEventHandler
import dev.bbkb.ime.core.keyevent.SoftwareMultitapHandler
import dev.bbkb.ime.core.keyevent.ModifierStatusBarUpdater

class BlackBerryIME : InputMethodService(),
    DictionaryLoader.DictionaryInitCallback,
    SettingsManager.OnSettingsChangeListener,
    CJKSuggestionGridView.Listener,
    FlickSuggestionView.Listener,
    SuggestionStripListener,
    CursorMovementListener,
    KeyboardActionListenerInterface,
    dev.bbkb.ime.keyboard.auxbar.AuxBarManager.AuxBarEventListener {

    companion object {
        /** How long the keyboard stays hidden before the emoji dataset is dropped (onTrimMemory). */
        private const val EMOJI_RELEASE_AFTER_HIDDEN_MS = 15L * 60L * 1000L

        /** Delay after a hide before free heap pages go back to the kernel; skips app-switch flicker. */
        private const val HEAP_PURGE_AFTER_HIDDEN_MS = 3_000L
        @JvmField
        val ASSERTIONS_DISABLED: Boolean = !BlackBerryIME::class.java.desiredAssertionStatus()

        @JvmField
        val LOG_TAG: String = BlackBerryIME::class.java.simpleName

        /** The two [PhysicalKeyboardStateTracker.getSymbolPageOrder] values that mean a symbol page is showing. */
        const val SYMBOL_PAGE_ORDER_FIRST = 1
        const val SYMBOL_PAGE_ORDER_SECOND = 3
    }

    // Cold-start anchor (debug STARTUP_TIMING). Must stay the first instance initialiser so the
    // logged cold start covers the property initialisers below. Phases:
    //   ime.constructor.start @ N ms  since process start
    //   anchor ime.service            reference point for every later mark
    //   ime.constructor = N ms        property initialisers + framework attach, up to onCreate
    //   ime.onCreate.* = N ms         onCreate's own steps
    //   engine.primaryInit = N ms     native library load + engine init (ImeStartup)
    //   ime.engine.join = N ms        wait of onCreate's first engine user (logged when >= 1 ms)
    private val constructorTiming: Long = StartupTiming.begin().also {
        StartupTiming.markSinceProcessStart("ime.constructor.start")
        StartupTiming.anchor("ime.service")
    }

    private var mCurrentNightMode: Int = -1

    private var themeChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Android 13+ `OnBackInvokedCallback`, kept to unregister in `onWindowHidden()`. Typed `Any?`
     * so the file compiles without API-33 stubs; the helpers cast it behind an SDK_INT gate.
     */
    private var backInvokedCallback: Any? = null

    var inputMethodImpl: AbstractInputMethodService.AbstractInputMethodImpl? = null

    var extractEditText: TextView? = null

    private val extractTextPreDrawListener: ViewTreeObserver.OnPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        this@BlackBerryIME.updateExtractedText()
        true
    }

    var isShowingWindow: Boolean = false

    var currentAlertDialog: AlertDialog? = null

    var isInputActive: Boolean = false

    var currentPackageName: String? = null

    private var isBlackBerryApp: Boolean = false

    var shouldShowOnFirstKeypress: Boolean = false

    var needsKeyboardReload: Boolean = false

    var wasBackPressed: Boolean = false

    var shouldInsertAsString: Boolean = false

    var isAltEnterPressed: Boolean = false

    var lastKeyEventTime: Long = 0L

    var lastCursorAnchorInfo: CursorAnchorInfo? = null

    var isCursorModeEnabled: Boolean = false

    private var lastBatchUpdateRequestTime = 0L

    // Views below are assigned by InputViewCoordinator.
    var rootInputView: View? = null


    var flickSuggestionView: FlickSuggestionView? = null

    var cjkSuggestionGridView: CJKSuggestionGridView? = null

    var swipeToDeleteAnimatorView: SwipeToDeleteAnimatorView? = null

    var arrowBarController: ArrowBarController? = null

    var auxBarManager: dev.bbkb.ime.keyboard.auxbar.AuxBarManager? = null

    var unifiedInputBoardHandler: UnifiedInputBoardHandler? = null

    var fccController: FccController? = null

    var clipboardController: ClipboardController? = null

    var numberPadController: NumberPadController? = null

    var voiceInputController: VoiceInputController? = null

    var quickPhrasesController: QuickPhrasesController? = null

    var numericSubpanelController: NumericSubpanelController? = null

    private val settingsManager: SettingsManager = SettingsManager.getInstance()

    private val subtypeManager: SubtypeManager = SubtypeManager.getInstance()

    private val keyboardSwitcher: KeyboardSwitcher = KeyboardSwitcher.getInstance()

    var richInputMethodManager: RichInputMethodManager? = null

    private val dictionaryLoader: DictionaryLoader = DictionaryLoader()

    var languagePackLocaleMonitor: LanguagePackLocaleMonitor? = null

    var contactsLearningManager: ContactsLearningManager? = null

    var dynamicLearningManager: DynamicLearningManager? = null


    var shakeGestureHandler: ShakeGestureHandler? = null

    private val currencyKeyHandler: CurrencyKeyHandler = CurrencyKeyHandler()

    private val subtypeState: SubtypeState = SubtypeState()

    @JvmField
    val keyEventConverters: SparseArray<KeyEventConverter> = SparseArray(1)

    private val cursorTracker: CursorTracker = CursorTracker(this)

    var vkbGestureListener: VkbGestureListener? = null

    var gestureDetector: MultiPointerGestureDetector? = null

    // Initialiser order matters below: later collaborators may read earlier fields.

    private val physicalKeyboardStateTracker: PhysicalKeyboardStateTracker = PhysicalKeyboardStateTracker(ModifierStatusBarUpdater(this))

    private val inputLogic: InputLogic = InputLogic(this, this, dictionaryLoader)

    @JvmField
    val uiUpdateHandler: UIUpdateHandler = UIUpdateHandler(this)

    /** The single funnel for all suggestion-strip update requests. */
    @JvmField
    val suggestionUpdater: SuggestionUpdater = SuggestionUpdater(this)

    @JvmField
    val multitapEventHandler: MultitapEventHandler =
        MultitapEventHandler(this, keyboardSwitcher.getKeyboardLayoutCallback())

    @JvmField
    val softwareMultitapHandler: SoftwareMultitapHandler = SoftwareMultitapHandler(this, keyboardSwitcher)

    private val uiCoordinator: InputViewCoordinator = InputViewCoordinator(this)

    /** Ctrl-shortcut / control-mode state machine; the host adapter is the only path to UI/editor. */
    private val controlMode = ControlModeController(object : ControlModeController.Host {
        override val isVkbControlModeEnabled: Boolean get() = settingsManager.getSettingsValues().isVkbControlModeEnabled
        override val controlModeSetting: Int get() = settingsManager.getSettingsValues().controlMode
        override val isAltActiveForCharacter: Boolean
            get() = physicalKeyboardStateTracker.getModifierState().isAltActiveForCharacter
        override val ctrlKeySendsNoKeyUp: Boolean
            get() = DeviceProfile.current().hasQuirk(DeviceQuirk.FN_NO_KEY_UP)
        override val isCtrlDoubleTapLockEnabled: Boolean get() = settingsManager.getSettingsValues().ctrlDoubleTapLock
        override val ctrlDoubleTapTimeoutMs: Long get() = MetaKeyStateTracker.getDoubleTapTimeout().toLong()
        // Modifier-KEY state only: the symbol board's own Alt page is not the user holding Alt,
        // and an Alt already spent (the Alt of an Alt+Sym) has left the span state.
        override val isPkbAltActiveForChord: Boolean
            get() = (physicalKeyboardStateTracker.getModifierState().modifierKeyMetaState and ModifierState.ALT_ANY_MASK) != 0
        override val isPkbShiftActiveForChord: Boolean
            get() = physicalKeyboardStateTracker.getModifierState().let { s ->
                (s.isShiftHeld || s.isShiftConsumed || s.isShiftSticky) && !s.isShiftLocked
            }
        override val isVkbShiftActiveForChord: Boolean get() = keyboardSwitcher.isShiftedForCtrlChord()
        override fun onPkbCtrlStateChanged() = physicalKeyboardStateTracker.onCtrlStateChanged()
        override fun setVkbCtrlLabel(active: Boolean) {
            keyboardSwitcher.getMainKeyboardView()?.setCtrlLabelActive(active)
        }
        override fun consumePkbModifiersAfterChord(keyCode: Int) =
            physicalKeyboardStateTracker.consumeModifiersAfterKey(keyCode, true)
        override fun consumeVkbShiftAfterChord() = keyboardSwitcher.releaseShiftAfterCtrlChord()
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) = inputLogic.sendKeyDownWithMeta(keyCode, metaState)
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) = inputLogic.sendKeyUpWithMeta(keyCode, metaState)
        override fun showControlModeUi() {
            // Suggestions and the bar stay visible; the ?123 label and status icon show Ctrl.
            sweepBoardsWhenUimBarIsOff()
            enableCursorMode(false)
        }
        override fun hideControlModeUi() {
            // Nothing to restore: showControlModeUi hides nothing.
        }
    })

    /** Accessibility-interceptor special keys, SYM / Alt+Sym, alt-character resolver, voice key. */
    private val hardwareKeys = HardwareKeyBridge(this)

    /** CKB gesture arbiter (classify → policy → action) and the cursor-mode drag. */
    private val ckbGestures = CkbGestureBridge(this)

    /**
     * Touch keypad delivered to this window's decor view (Titan 2 / Elite on Android 16 with the
     * OEM Scroll assistant on). Inert unless the device profile's `<touch-keypad>` selects it.
     */
    private val nativeTouch = NativeTouchSource(object : NativeTouchSource.Host {
        override fun decorView(): View? = window?.window?.decorView
        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            this@BlackBerryIME.onGenericMotionEvent(event)
        override fun selection(): TouchSourceSelector.Selection =
            DeviceProfile.current().touchSourceSelection
    })

    /**
     * Touch keypad read through Shizuku (a Titan whose pad the OS does not enumerate). Inert unless
     * the profile's `<touch-keypad>` selects it. Started on first show, kept for the IME's life,
     * grabbing the pad only while the window is shown.
     */
    private val shizukuTouch = ShizukuTouchSource(object : ShizukuTouchSource.Host {
        override fun context(): Context = this@BlackBerryIME
        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            this@BlackBerryIME.onGenericMotionEvent(event)
        override fun selection(): TouchSourceSelector.Selection =
            DeviceProfile.current().touchSourceSelection
        override fun touchKeypad(): TouchKeypadConfig? = DeviceProfile.current().touchKeypadConfig
    }, KeypadTouchSources.shizukuEngine())

    /** Bounded worker pool for cold-start warmups; shut down in `releaseResources`. */
    private val startupExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ImeStartup").apply { isDaemon = true }
        }

    /** Token for [uiUpdateHandler]-posted deferred startup work, so onDestroy can cancel it. */
    private val deferredInitToken = Any()

    /** Token of the delayed emoji-dataset release that a keyboard hide schedules (onTrimMemory). */
    private val emojiReleaseToken = Any()

    /** Token of the native-heap purge a keyboard hide schedules (onWindowHidden). */
    private val heapPurgeToken = Any()

    /** True once [registerNonCriticalReceivers] has actually registered the three receivers. */
    private var nonCriticalReceiversRegistered = false

    private val imeInsets = ImeInsets()

    private val disableCursorModeRunnable: Runnable = Runnable {
        this@BlackBerryIME.enableCursorMode(false)
    }

    // Constructed lazily: they capture collaborators that onCreate initialises after this
    // object's property initialisers run, and registration is deferred past onCreate anyway.
    private val connectivityAndScreenReceiver: BroadcastReceiver by lazy {
        ConnectivityAndScreenReceiver(this, subtypeManager, physicalKeyboardStateTracker)
    }

    private val learningModelSaveReceiver: BroadcastReceiver by lazy {
        LearningModelSaveReceiver(dynamicLearningManager!!)
    }

    private val localeChangeReceiver: BroadcastReceiver by lazy {
        LocaleChangeReceiver(this, subtypeManager, richInputMethodManager!!)
    }

    // Debug-only rigs (never constructed in release builds).
    private var gestureReplay: GestureReplayReceiver? = null

    private val sensorVizOverlay: SensorVizOverlay by lazy { SensorVizOverlay(this) }

    private val inputSessionCoordinator = InputSessionCoordinator(this)

    private val keyEventProcessor = KeyEventProcessor(this)

    @Throws(Resources.NotFoundException::class)
    override fun onCreate() {
        // First, before super.onCreate(): start the engine on its loader thread so it overlaps
        // everything below; NuanceSDKManager.getInstance() joins it. Not on startupExecutor: the
        // engine must only be called from threads that never exit (EngineThreads).
        NuanceSDKManager.startPrimaryAsync()
        StartupTiming.end("ime.constructor", constructorTiming)
        val onCreateToken = StartupTiming.begin()
        initializeStrictMode()
        InAppEventBus.getInstance().subscribe(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, languagePackChangedListener)

        // Prefs only start an async load and the first read blocks; finish both files on a
        // worker so the parse overlaps init below instead of stalling the main thread.
        PrefsManager.init(applicationContext)
        startupExecutor.execute {
            try {
                PrefsManager.getPrefs().contains(PrefsManager.Keys.KEYBOARD_THEME_MODE)
                getSharedPreferences("savestate", 0).contains("isManaged")
            } catch (_: Throwable) {}
        }

        // Must run before super.onCreate().
        var t = StartupTiming.begin()
        SettingsManager.initialize(applicationContext)
        StartupTiming.end("ime.onCreate.settingsManagerInitialize", t)
        PhysicalKeyResolver.init(applicationContext)
        // Engine key letters follow the letter layout; re-decided after the profile's KDB variant.
        startupExecutor.execute { dev.bbkb.ime.core.layout.LetterLayoutEngineSync.start(applicationContext) }
        settingsManager.addOnSettingsChangeListener(this)
        t = StartupTiming.begin()
        KeyboardSwitcher.initialize(this, uiCoordinator)
        StartupTiming.end("ime.onCreate.keyboardSwitcherInitialize", t)

        t = StartupTiming.begin()
        super.onCreate()
        StartupTiming.end("ime.onCreate.superOnCreate", t)

        // Debug self-check of libxt9kdb.so: parses a KDB asset and logs the model. Inert.
        if (BuildConfig.DEBUG) {
            startupExecutor.execute {
                try { com.blackberry.nuanceshim.Xt9Kdb.selfCheck(this, "athena") } catch (_: Throwable) {}
            }
        }

        // Debug only: replays CKB gesture traces into onGenericMotionEvent with original timing,
        // tagged with touchKeypadDeviceId so the isFromTouchKeypad gate passes as on hardware.
        if (BuildConfig.DEBUG) gestureReplay = GestureReplayReceiver(this).also { it.register() }

        // Explicit, or OEM heuristics decide whether Back reaches the IME or the activity.
        setBackDisposition(InputMethodService.BACK_DISPOSITION_DEFAULT)

        t = StartupTiming.begin()
        initializeNightModeTracking()
        StartupTiming.end("ime.onCreate.nightMode", t)
        t = StartupTiming.begin()
        initializeSingletons()
        StartupTiming.end("ime.onCreate.singletons", t)
        t = StartupTiming.begin()
        initializeColorManager()
        StartupTiming.end("ime.onCreate.colorManager", t)
        t = StartupTiming.begin()
        initializeLearningManagers()
        StartupTiming.end("ime.onCreate.learningManagers", t)

        // Receivers are not needed for the first input cycle, so they wait past cold start. The
        // token lets releaseResources cancel it: a service destroyed inside the window must not
        // register receivers that would hold the dead service. Controllers stay on the critical
        // path (setInputView's wiring needs them).
        uiUpdateHandler.postTokenized(deferredInitToken, 150L) { registerNonCriticalReceivers() }
        // Build every enabled keyboard's engine KDBs ahead of its first use (kept on storage).
        uiUpdateHandler.postTokenized(deferredInitToken, dev.bbkb.ime.keyboard.KdbPrebuilder.START_DELAY_MS) {
            dev.bbkb.ime.keyboard.KdbPrebuilder.start(applicationContext)
        }

        t = StartupTiming.begin()
        initializeControllers()
        StartupTiming.end("ime.onCreate.controllers", t)
        t = StartupTiming.begin()
        initializeBackgroundPreloading()
        StartupTiming.end("ime.onCreate.backgroundPreloading", t)
        StartupTiming.end("ime.onCreate.TOTAL", onCreateToken)
    }

    private fun initializeStrictMode() {
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build())
        }
    }

    private fun initializeNightModeTracking() {
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "onCreate()")

        val config = resources.configuration
        mCurrentNightMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    private fun initializeSingletons() {
        var t = StartupTiming.begin()
        RichInputMethodManager.init(this)
        richInputMethodManager = RichInputMethodManager.getInstance()
        StartupTiming.end("ime.singletons.richInputMethodManager", t)
        t = StartupTiming.begin()
        SubtypeManager.initialize(applicationContext)
        StartupTiming.end("ime.singletons.subtypeManager", t)
        t = StartupTiming.begin()
        AudioAndHapticFeedbackManager.init(this)
        InputMethodHelper.init(this as Context)
        DictionaryManager.shutdownInstance()
        uiUpdateHandler.initializeDelays()
        StartupTiming.end("ime.singletons.audioHelperDictHandler", t)

        // The XML parse and hardware scan run on startupExecutor; this only publishes the ticket.
        t = StartupTiming.begin()
        DeviceProfile.startInitialize(applicationContext, resources.configuration)
        StartupTiming.end("ime.singletons.deviceProfileStart", t)

        hardwareKeys.install()
    }

    private fun initializeColorManager() {
        PrefsManager.init(this)
        val prefs = PrefsManager.getPrefs()
        PrefsManager.migrateThemePrefs(prefs)
        KeyboardColorManager.init(
            this,
            KeyboardColorManager.Style.fromPref(
                prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern")),
            KeyboardColorManager.Scheme.fromPref(
                prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_MODE, "auto")),
            prefs.getBoolean(PrefsManager.Keys.KEYBOARD_USE_SYSTEM_COLORS, false),
        )

        themeChangeListener = ThemePrefsListener(this)
        prefs.registerOnSharedPreferenceChangeListener(themeChangeListener)
    }

    private fun initializeLearningManagers() {
        // First main-thread engine user: onCreate joins the background engine start here.
        val joinToken = StartupTiming.begin()
        dynamicLearningManager = DynamicLearningManager(applicationContext)
        StartupTiming.endIfOver("ime.engine.join", joinToken, 1L)
        languagePackLocaleMonitor = LanguagePackLocaleMonitor(applicationContext, uiUpdateHandler)
        contactsLearningManager = ContactsLearningManager(this)
        // Must stay on the critical path: many later paths dereference getSettingsValues()!!.
        loadSettings()
        // loadSettings -> DeviceProfile.fromSettings has just joined the profile load.
        Logger.info(LOG_TAG, "DeviceProfile initialized: ${DeviceProfile.current()}")
        // Its main-thread setup is not needed before onCreateInputView, so it runs after onCreate.
        uiUpdateHandler.postTokenized(deferredInitToken, 0L) { reloadDictionaryForSubtype() }
    }

    private fun initializeControllers() {
        shakeGestureHandler = ShakeGestureHandler(this, this)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            voiceInputController = VoiceInputController(this, uiCoordinator)
        }
        fccController = FccController(this, uiCoordinator)
        clipboardController = ClipboardController(this, uiCoordinator)
        numberPadController = NumberPadController(this, uiCoordinator)
        quickPhrasesController = QuickPhrasesController(this, settingsManager)
        numericSubpanelController = NumericSubpanelController(this, subtypeManager, settingsManager)
        uiCoordinator.initialize()
    }

    private fun initializeBackgroundPreloading() {
        // Warm the alt-mappings cache so the first keystroke does not pay the synchronous XML
        // parse on the main thread; AltMappingsParser.getCachedMappings is synchronized.
        startupExecutor.execute {
            try {
                val profile = DeviceProfile.current()
                if (profile.hasCustomAltMappings()) profile.getAltMappingsTable(applicationContext)
            } catch (e: Throwable) {
                Logger.warn(LOG_TAG, "Alt-mappings prewarm failed: ${e.message}")
            }
        }

        // Warm the lazily parsed language-pack catalogue before the first dictionary load.
        startupExecutor.execute {
            try {
                com.blackberry.nuanceshim.languagepack.LanguagePackManager
                    .getInstance(applicationContext).prewarmRegistry()
            } catch (e: Throwable) {
                Logger.warn(LOG_TAG, "Language-pack registry prewarm failed: ${e.message}")
            }
        }
    }

    private fun registerNonCriticalReceivers() {
        try {
            registerNotExported(connectivityAndScreenReceiver,
                "android.net.conn.CONNECTIVITY_CHANGE", "android.media.RINGER_MODE_CHANGED", "android.intent.action.SCREEN_OFF")
            registerNotExported(learningModelSaveReceiver,
                "android.intent.action.SCREEN_OFF", "android.intent.action.ACTION_POWER_CONNECTED")
            registerNotExported(localeChangeReceiver, "android.intent.action.LOCALE_CHANGED")
            // So releaseResources does not build the lazy receivers for a registration that never ran.
            nonCriticalReceiversRegistered = true
            Logger.debug(LOG_TAG, "Non-critical receivers registered")
        } catch (e: Exception) {
            Logger.warn(LOG_TAG, "Failed to register non-critical receivers: ${e.message}")
        }
    }

    private fun registerNotExported(receiver: BroadcastReceiver, vararg actions: String) {
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Drops the process-static emojibase dataset (735 KB asset, ~3700 objects) under memory
     * pressure, in the background, or after a long hide; it is re-parsed lazily on next use.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN arrives on every hide, so it only starts a long timer (each show cancels it);
        // releasing at once would re-parse the dataset on every show.
        uiUpdateHandler.removeCallbacksAndMessages(emojiReleaseToken)
        when {
            level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND -> {
                EmojibaseDataProvider.releaseShared()
                com.blackberry.nuanceshim.Xt9KdbVariant.purgeHeap()
            }
            level == TRIM_MEMORY_UI_HIDDEN -> uiUpdateHandler.postTokenized(
                emojiReleaseToken, EMOJI_RELEASE_AFTER_HIDDEN_MS) { EmojibaseDataProvider.releaseShared() }
        }
    }

    /**
     * onDestroy has begun. super.onDestroy still calls onFinishInputView/onFinishInput on an
     * active field; those return at once so they cannot rebuild what teardown just released.
     */
    private var teardownStarted = false

    override fun onDestroy() {
        teardownStarted = true
        InAppEventBus.getInstance().unsubscribe(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, languagePackChangedListener)
        if (themeChangeListener != null) {
            val prefs = PrefsManager.getPrefs()
            prefs.unregisterOnSharedPreferenceChangeListener(themeChangeListener)
        }

        gestureReplay?.unregister()
        gestureReplay = null
        // Quits the per-instance suggestion HandlerThread; before releaseResources() so no new
        // request reaches the engine during teardown.
        inputLogic.shutdown()
        releaseResources()
        DictionaryManager.getInstance().shutdown()
        enableCursorMode(false)
        dictionaryLoader.closeAndReset()
        super.onDestroy()
    }

    private fun releaseResources() {
        settingsManager.removeOnSettingsChangeListener(this)
        settingsManager.unregisteredListener()
        // First, so deferred startup work cannot register receivers after this point.
        uiUpdateHandler.removeCallbacksAndMessages(deferredInitToken)
        uiUpdateHandler.removeCallbacksAndMessages(emojiReleaseToken)
        uiUpdateHandler.removeCallbacksAndMessages(heapPurgeToken)
        // In-flight warmups may finish.
        startupExecutor.shutdown()
        ckbGestures.release()
        nativeTouch.stop()
        shizukuTouch.stop()
        if (nonCriticalReceiversRegistered) {
            nonCriticalReceiversRegistered = false
            try { unregisterReceiver(connectivityAndScreenReceiver) } catch (ignored: IllegalArgumentException) {}
            try { unregisterReceiver(learningModelSaveReceiver) } catch (ignored: IllegalArgumentException) {}
            try { unregisterReceiver(localeChangeReceiver) } catch (ignored: IllegalArgumentException) {}
        }
        // Both callbacks: a leftover all-keys callback keeps hardware Alt consumed system-wide
        // with a dead IME behind it (and leaks it through a static field).
        hardwareKeys.unregisterInterceptorCallbacks()
        physicalKeyboardStateTracker.onModifierListenerReset()
        languagePackLocaleMonitor!!.cleanup()
        flickSuggestionView?.init(null as FlickSuggestionView.Listener?, null as MainKeyboardView?)
        flickSuggestionView = null
        cjkSuggestionGridView?.setListener(null)
        cjkSuggestionGridView = null
        fccController?.destroy()
        fccController = null
        clipboardController?.destroy()
        clipboardController = null
        numberPadController?.destroy()
        numberPadController = null
        voiceInputController?.destroy()
        voiceInputController = null
        contactsLearningManager!!.unregister()
        dynamicLearningManager!!.destroy()
        keyboardSwitcher.destroy()
        InlineAutofillManager.destroyInstance()
        EmojibaseDataProvider.releaseShared()
        shakeGestureHandler!!.stop()
        quickPhrasesController?.hide()
        quickPhrasesController = null
        numericSubpanelController?.refresh()
        numericSubpanelController = null
        // The engine is not disposed: it lives for the process and the next instance reuses it
        // (see NuanceSDKManager.resetForTests for the crash disposing causes).
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        // Close all boards before any config change handling.
        uiCoordinator.hideAllInputUi()

        controlMode.resetAll()
        controlMode.clearControlState()
        uiUpdateHandler.resetOrientationState()
        val settingsValues = settingsManager.getSettingsValues()
        val zM4990a = SettingsManager.hasHardwareKeyboard(configuration)
        vkbGestureListener?.clearLastKeyEventTime()

        val newNightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mCurrentNightMode != -1 && mCurrentNightMode != newNightMode) {
            val prefs = PrefsManager.getPrefs()
            val themeMode = prefs.getString("pref_keyboard_theme_mode", "auto")

            if ("auto" == themeMode) {
                Logger.debug(LOG_TAG, "System theme changed (night mode: $newNightMode), reloading keyboard theme")
                loadSettings()

                // configChanges="uiMode" means Android 13+ no longer re-inflates the input view,
                // and the boards take their colours at inflate time, so recreate it as a Settings
                // theme change does (ThemePrefsListener).
                KeyboardColorManager.onConfigurationChanged()
                keyboardSwitcher.forceRecreateInputView()
            }
        }
        mCurrentNightMode = newNightMode

        if (settingsValues.displayOrientation != configuration.orientation) {
            Logger.debug(LOG_TAG, "orientation changed: newOrientation=${configuration.orientation}")
            quickPhrasesController?.hide()
            keyboardSwitcher.getSlideboardManager()?.hide()
            uiUpdateHandler.prepareForOrientationChange()
            inputLogic.commitComposingOrReset(settingsManager.getSettingsValues())
            // The rebuilt keyboard replaces the meta mask the old one installed.
            physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.KEYBOARD_RELOADED)
            updatePhysicalKeyboardFilter()
        }

        if (settingsValues.hasHardwareKeyboard != zM4990a) {
            onKeyboardConfigurationChanged(zM4990a, configuration.orientation)
        }

        KeyboardColorManager.onConfigurationChanged()

        // Keeps the keyboard above the nav bar in the new configuration.
        rootInputView?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }

        super.onConfigurationChanged(configuration)
    }

    /**
     * @param hasHardwareKeyboard true if a hardware keyboard is now attached
     * @param orientation the new [Configuration.orientation]
     */
    fun onKeyboardConfigurationChanged(hasHardwareKeyboard: Boolean, orientation: Int) {
        Logger.info(LOG_TAG, "Updating keyboard configuration settings")
        if (!hasHardwareKeyboard && orientation == 2 && !isInputViewShown() && hasActiveInputConnection() && !wasBackPressed) {
            richInputMethodManager!!.getInputMethodManager().showSoftInputFromInputMethod(window.window!!.attributes.token, 0)
        }
        loadSettings()
        if (settingsManager.getSettingsValues().hasHardwareKeyboard) {
            cleanupKeyboard()
        }
        enableCursorMode(false)
        fccController?.hideFcc()
        Logger.info(LOG_TAG, "Done updating keyboard configuration settings")
    }

    // No getSystemService override handing out a themed inflater: in gesture navigation (API 33+)
    // the IME nav bar's Back chevron only acts when its inflater context is this service. Themed
    // views wrap their own context. Pinned by ImeInflaterContextTest.

    override fun onCreateInputView(): View {
        Logger.debug(LOG_TAG, "onCreateInputView() - Creating keyboard view")
        return keyboardSwitcher.createInputView(false)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        uiCoordinator.setupInputView(view)
        ImeInsets.installNavBarInsetListener(view)
        (view as? InputView)?.setImeWindow(window.window)
    }

    override fun setExtractView(view: View?) {
        var textView: TextView? = null
        val textView2 = extractEditText
        super.setExtractView(view)
        if (view != null) {
            val viewFindViewById = view.findViewById<View>(android.R.id.inputExtractEditText)
            textView = if (viewFindViewById is TextView) viewFindViewById else null
        }
        if (textView2 === textView) {
            return
        }
        removeExtractTextPreDrawListener(textView2)
        extractEditText = textView
        extractTextObserver = textView?.viewTreeObserver
        extractTextObserver?.addOnPreDrawListener(extractTextPreDrawListener)
    }

    /** The observer [extractTextPreDrawListener] was last added to. */
    private var extractTextObserver: ViewTreeObserver? = null

    /**
     * Takes [extractTextPreDrawListener] off every observer it may be on. After a config change
     * the old view is detached and hands out a new, empty observer, while the listener has moved
     * to the window's; missing it there stacks a copy per change.
     */
    private fun removeExtractTextPreDrawListener(previous: TextView?) {
        fun remove(observer: ViewTreeObserver?) {
            if (observer != null && observer.isAlive) {
                observer.removeOnPreDrawListener(extractTextPreDrawListener)
            }
        }
        remove(extractTextObserver)
        remove(previous?.viewTreeObserver)
        remove(window?.window?.peekDecorView()?.viewTreeObserver)
        extractTextObserver = null
    }

    fun updateExtractedText() {
        if (!isFullscreenMode()) return
        val textView = extractEditText ?: return
        inputLogic.updateCursorAnchorInfo(CursorAnchorInfoUtils.getCursorAnchorInfo(textView))
    }

    /**
     * On PKB the input view is the suggestion strip / UIM, and super returns false whenever a
     * hardware keyboard is present, so PKB never defers to super (home-screen hiding is done in
     * `onShowInputRequested` and `onWindowHidden`). VKB defers so the keyboard hides with no editor.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        if (isShowingWindow) return true
        if (DeviceProfile.isVkbForcedForPackage(currentPackageName) || DeviceProfile.isForceVkbMode()) {
            return true
        }
        if (DeviceProfile.isPkb()) {
            val rotation = getDisplayRotation()
            if (rotation != 0) return isLandscapeOrientation()
            return true
        }
        return super.onEvaluateInputViewShown()
    }

    /**
     * Recomputes whether the on-screen keyboard is showing and publishes it to [DeviceProfile]
     * (the side effect is the point; callers that ignore the return value want the publish).
     */
    fun refreshOnScreenKeyboardShowing(): Boolean {
        // An input board open over the physical keys counts as the on-screen keyboard here, but is
        // published apart from a real one: "Show the suggestion bar" must stay off over a board.
        val typingKeyboard = super.onEvaluateInputViewShown() || DeviceProfile.isVkbForcedForPackage(currentPackageName) || DeviceProfile.isForceVkbMode() || isLandscapeOrientation()
        val z = typingKeyboard || isUimVisible()
        DeviceProfile.setOnScreenKeyboardShowing(z, typingKeyboard)
        return z
    }

    @SuppressWarnings("deprecation")
    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
    }

    fun isLandscapeOrientation(): Boolean {
        return !NuanceSDK.DEVICE_VENICE.equals(Build.DEVICE) && (resources.configuration.orientation == 2)
    }

    /**
     * PKB forces a show past super only with an editor bound and [onEvaluateInputViewShown]
     * agreeing, so transient show requests do not bring the IME up on the home screen.
     *
     * @param flags the show flags (bit 0 = implicit)
     * @param configChange true if this is for a configuration change
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        var zOnShowInputRequested = super.onShowInputRequested(flags, configChange)
        if (!zOnShowInputRequested && DeviceProfile.current().isPkbDevice()) {
            if (getCurrentInputBinding() != null && onEvaluateInputViewShown()) {
                zOnShowInputRequested = true
            }
        } else if (!zOnShowInputRequested && onEvaluateInputViewShown() && (flags and 1) == 0) {
            zOnShowInputRequested = DeviceProfile.isPkb()
        }
        Logger.debug(LOG_TAG, "onShowInputRequested(): flags=$flags configChange=$configChange show=$zOnShowInputRequested")
        return zOnShowInputRequested
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        if (DeviceProfile.current().isPkbDevice()) return false
        val zM5010d = SettingsManager.isFullscreenModeEnabled(resources)
        if (!super.onEvaluateFullscreenMode() || !zM5010d) {
            return false
        }
        val currentInputEditorInfo = getCurrentInputEditorInfo()
        return currentInputEditorInfo == null || (currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI) == 0
    }

    override fun updateFullscreenMode() {
        val window = window.window
        ViewLayoutUtils.updateLayoutHeightOf(window, -1)
        if (rootInputView != null) {
            val i = if (isFullscreenMode()) -2 else -1
            val viewFindViewById = window!!.findViewById<View>(android.R.id.inputArea)
            ViewLayoutUtils.updateLayoutHeightOf(viewFindViewById, i)
            ViewLayoutUtils.updateLayoutGravityOf(viewFindViewById, 80)
            ViewLayoutUtils.updateLayoutHeightOf(rootInputView, i)
        }
        super.updateFullscreenMode()
        inputLogic.setMoreKeysEnabled(isFullscreenMode())
    }

    override fun onComputeInsets(insets: InputMethodService.Insets) {
        val previousVisibleTop = insets.visibleTopInsets
        super.onComputeInsets(insets)
        val keyboardView = keyboardSwitcher.getActiveKeyboardView()
        val auxBarView = auxBarManager?.getAuxBarView()
        if (keyboardView == null || auxBarView == null) {
            if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: PREEMPT - no ${if (keyboardView == null) "keyboard view" else "aux bar"} (super insets left as-is)")
            return
        }
        val contentTop = imeInsets.compute(
            insets, rootInputView!!, keyboardView, auxBarView,
            isPkbDevice = DeviceProfile.current().isPkbDevice(),
            mainKeyboardShowing = keyboardSwitcher.isMainKeyboardShowing(),
        ) {
            // The CJK grid's visible-height bookkeeping has a side effect consumed elsewhere.
            cjkSuggestionGridView!!.setVisibleHeight(keyboardView.height)
        }
        if (contentTop != null && previousVisibleTop != contentTop) updateFlickMetrics()
    }

    /**
     * The two layout syncs a show posts. Fields so the framework's second showWindow per show
     * replaces the pending pair instead of queueing more engine-thread ops.
     */
    private val syncLayoutAfterShow = Runnable { keyboardSwitcher.syncKeyboardLayout() }
    private val syncLayoutAfterShowSettled = Runnable { keyboardSwitcher.syncKeyboardLayout() }

    /** @param showInput true to show the input view */
    override fun showWindow(showInput: Boolean) {
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "showWindow(): showInput=$showInput")
        Logger.debug(LOG_TAG, "showWindow(): showInput=$showInput")
        super.showWindow(showInput)
        if (showInput) {
            // The startInput sync runs before the view is laid out, and syncKeyboardLayout picks
            // the engine frame from whether MainKeyboardView is displayed; re-sync after layout.
            keyboardSwitcher.getMainKeyboardView()?.let { v ->
                v.removeCallbacks(syncLayoutAfterShow)
                v.removeCallbacks(syncLayoutAfterShowSettled)
                v.post(syncLayoutAfterShow)
                // Second pass after settling: the first can read a transient state (e.g. before
                // the pad-mode collapse), and a wrong frame would stick until the next layout.
                v.postDelayed(syncLayoutAfterShowSettled, 600)
            }
        }
    }

    override fun hideWindow() {
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "hideWindow() called")
        Logger.debug(LOG_TAG, "hideWindow()")
        keyboardSwitcher.cancelKeyTimers()
        DeviceProfile.setForceVkbMode(false)
        if (isAlertDialogShowing()) {
            currentAlertDialog?.dismiss()
            currentAlertDialog = null
        }
        super.hideWindow()
    }

    fun showImeWindow() {
        isShowingWindow = true
        showWindow(true)
        isShowingWindow = false
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The keyboard is back: keep the emoji dataset (see onTrimMemory) and the heap pages.
        uiUpdateHandler.removeCallbacksAndMessages(emojiReleaseToken)
        uiUpdateHandler.removeCallbacksAndMessages(heapPurgeToken)
        // Every show, so the keyboard sits above the nav bar after an IME switch-away-and-back.
        rootInputView?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        // A stale latch would suppress auto-show-on-key-press until a full editor reset.
        wasBackPressed = false
        // Re-assert the PKB modifier status icon: the system clears it on unbind or drops early
        // posts, and the updater only posts on a change.
        physicalKeyboardStateTracker.refreshModifierStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackInvokedCallback()
        }
        nativeTouch.attach()
        shizukuTouch.onWindowShown()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // The native heap never returns pages freed by boards, popups and language switches
        // (tens of MB); purge once the keyboard has been down a moment. onWindowShown cancels it.
        uiUpdateHandler.removeCallbacksAndMessages(heapPurgeToken)
        uiUpdateHandler.postTokenized(heapPurgeToken, HEAP_PURGE_AFTER_HIDDEN_MS) {
            val purged = com.blackberry.nuanceshim.Xt9KdbVariant.purgeHeap()
            if (BuildConfig.DEBUG) Logger.debug(LOG_TAG, "native heap purge after hide -> $purged")
        }
        nativeTouch.detach()
        shizukuTouch.onWindowHidden()
        resetUiState()
        fccController?.dismiss()
        clipboardController?.dismiss()
        // So a stale key press cannot re-summon the IME on the home screen.
        shouldShowOnFirstKeypress = false
        wasBackPressed = false
        isInputActive = false
        lastCursorAnchorInfo = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterBackInvokedCallback()
        }
    }

    /**
     * The IME window, not the service, owns the dispatcher. Needs the manifest's
     * `android:enableOnBackInvokedCallback="true"`.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackInvokedCallback() {
        if (backInvokedCallback != null) return
        val dispatcher = window?.window?.onBackInvokedDispatcher ?: return
        val cb = android.window.OnBackInvokedCallback { dismissKeyboard() }
        dispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            cb
        )
        backInvokedCallback = cb
        Logger.debug(LOG_TAG, "registered OnBackInvokedCallback")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackInvokedCallback() {
        val cb = backInvokedCallback as? android.window.OnBackInvokedCallback ?: return
        window?.window?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(cb)
        backInvokedCallback = null
        Logger.debug(LOG_TAG, "unregistered OnBackInvokedCallback")
    }

    override fun onExtractedTextClicked() {
        if (settingsManager.getSettingsValues().shouldShowLxxButton) {
            return
        }
        super.onExtractedTextClicked()
    }

    override fun onExtractedCursorMovement(dx: Int, dy: Int) {
        if (settingsManager.getSettingsValues().shouldShowLxxButton) {
            return
        }
        super.onExtractedCursorMovement(dx, dy)
    }

    override fun onCreateInputMethodInterface(): AbstractInputMethodService.AbstractInputMethodImpl {
        inputMethodImpl = super.onCreateInputMethodInterface()
        return inputMethodImpl!!
    }

    /**
     * Publishes whether the current keyboard's language asks for the on-screen keyboard
     * ([OnScreenKeyboardChoice]) and re-evaluates the input view.
     */
    fun refreshLanguageOnScreenKeyboard() {
        val forced = dev.bbkb.ime.core.layout.OnScreenKeyboardChoice.isOnForCurrentKeyboard(this)
        if (DeviceProfile.isForceVkbMode() != forced || forced) {
            DeviceProfile.setLanguageForceVkbMode(forced)
            updateInputViewShown()
        } else {
            DeviceProfile.setLanguageForceVkbMode(false)
        }
    }

    /** @param restarting true if the same editor is restarting */
    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        DeviceProfile.setLanguageForceVkbMode(dev.bbkb.ime.core.layout.OnScreenKeyboardChoice.isOnForCurrentKeyboard(this))
        Logger.info(LOG_TAG, "onStartInput(): restarting=$restarting, editorInfo=${if (editorInfo == null) "NULL" else "valid"}")
        // Debug probe (`setprop debug.et9.probe 1`) dumping the engine's ranking config. Here, not
        // in ...View, because PKB sessions create no input view; opt-in since it can SIGSEGV cold.
        if (BuildConfig.DEBUG) {
            // The main thread never calls the engine.
            EngineThreads.post { com.blackberry.nuanceshim.Et9Probe.probe(NuanceSDKManager.getInstance()) }
        }
        if (BuildConfig.DEBUG) sensorVizOverlay.syncActiveFromPrefs()
        if (BuildConfig.DEBUG) android.util.Log.d("KBD_LIFECYCLE", "onStartInput(): restarting=$restarting isInputViewShown=${isInputViewShown()} pkg=${editorInfo?.packageName ?: "null"}")

        // Every start, including a restart of the same editor.
        wasBackPressed = false

        if (editorInfo == null) {
            Logger.debug(LOG_TAG, "onStartInput() - EditorInfo is NULL, hiding UI elements")
            uiCoordinator.hideAllInputUi()
        }

        refreshSubtypeSwitcher()
        uiUpdateHandler.handleStartInput(editorInfo, restarting)
        isInputActive = hasActiveInputConnection()
        val str = editorInfo?.packageName
        var z2 = false
        if (!isInputActive || str == null || str != currentPackageName) {
            enableCursorMode(false)
        }
        currentPackageName = str
        physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.EDITOR_SWITCHED)

        sweepBoardsWhenUimBarIsOff()
        // Every input start, including PKB sessions that never show the board.
        clipboardController?.onStartInput()
        if (str != null && (str == "com.blackberry.help" || str == "com.blackberry.retaildemo")) {
            z2 = true
        }
        isBlackBerryApp = z2
        if (LocaleUtils.isCurrentSubtypeKorean() && str != null && str.lowercase(Locale.ROOT).startsWith("com.skmc.okcashbag")) {
            // OK Cashbag workaround: implicitly show the input view on a PKB-only device when the
            // field asks for NAVIGATE_NEXT or has either of action bits 0/2 set.
            val opts = editorInfo!!.imeOptions
            val wantsNextOrAction = (opts and EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0 ||
                (opts and (EditorInfo.IME_ACTION_NONE or EditorInfo.IME_ACTION_SEARCH)) != 0
            if (wantsNextOrAction && DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()) {
                inputMethodImpl!!.showSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, null)
            }
        }

        if (InlineAutofillManager.isSupported()) {
            InlineAutofillManager.getInstance(this).onInputStarted(editorInfo, restarting)
        }
    }

    /** @param restarting true if the same editor is restarting */
    @Throws(Resources.NotFoundException::class)
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        // The language-chip bar goes with the field it was for.
        auxBarManager?.hideLanguageChips()
        Logger.info(LOG_TAG, "onStartInputView()")
        // A re-show for a still-bound editor skips onStartInput, so heal the flag here.
        refreshInputActive()
        // Re-reads log.tag.XT9Input so diagnostics toggle via setprop without a restart.
        if (BuildConfig.DEBUG) InputPathDebug.refresh()
        uiUpdateHandler.handleStartInputView(editorInfo, restarting)
        // As in onWindowShown(): the system clears the status-bar slot between sessions.
        physicalKeyboardStateTracker.refreshModifierStatus()
        // Also on window show: the decor view's focus and listener do not survive every re-creation.
        nativeTouch.attach()
    }

    /** @param finishingInput true if input is finishing too */
    override fun onFinishInputView(finishingInput: Boolean) {
        if (teardownStarted) {
            Logger.info(LOG_TAG, "onFinishInputView() after teardown: ignored")
            return
        }
        Logger.info(LOG_TAG, "onFinishInputView(), finishingInput=$finishingInput")
        uiUpdateHandler.handleFinishInputView(finishingInput)
        controlMode.clearControlState()
    }

    override fun onFinishInput() {
        if (teardownStarted) {
            Logger.info(LOG_TAG, "onFinishInput() after teardown: ignored")
            return
        }
        Logger.info(LOG_TAG, "onFinishInput()")
        uiUpdateHandler.handleFinishInput()
        physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.FINISH_INPUT)
        isInputActive = false
        // About once a day, here because the engine is up to read learned words; no-op otherwise.
        dev.bbkb.ime.core.settings.backup.AutoBackup.refreshIfStale(this)

        if (InlineAutofillManager.isSupported()) {
            InlineAutofillManager.getInstance(this).onInputFinished()
        }
    }

    /** @param restarting true if the same editor is restarting */
    fun startInputInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        inputSessionCoordinator.onStartInputInternal(editorInfo, restarting)
    }

    /** @param restarting true if the same editor is restarting */
    @Throws(Resources.NotFoundException::class)
    fun startInputViewInternal(editorInfo: EditorInfo?, restarting: Boolean) {
        inputSessionCoordinator.onStartInputViewInternal(editorInfo, restarting)
    }

    fun finishInputInternal() {
        inputSessionCoordinator.onFinishInputInternal()
    }

    /** @param finishingInput true if input is finishing too */
    fun finishInputViewInternal(finishingInput: Boolean) {
        inputSessionCoordinator.onFinishInputViewInternal(finishingInput)
    }

    /**
     * Re-derives [isInputActive] from the live connection. A re-show for a still-bound editor
     * skips onStartInput, and a stale false fails every hardware key at KeyEventProcessor's
     * gate. Cannot undo the onFinishInput clear: getCurrentInputStarted() is false then.
     */
    fun refreshInputActive() {
        isInputActive = hasActiveInputConnection()
    }

    private fun hasActiveInputConnection(): Boolean {
        if (!getCurrentInputStarted()) return false
        val currentInputConnection = getCurrentInputConnection() ?: return false
        val currentInputBinding = getCurrentInputBinding()
        return currentInputBinding == null || currentInputBinding.connection != currentInputConnection
    }

    fun cleanupKeyboard() {
        keyboardSwitcher.cleanup()
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        // Teardown is the one place queued commits and batch results are dropped too.
        uiUpdateHandler.cancelPendingTextCommits()
        inputLogic.cancelInput()
        // Every finishInputView route passes here, including the deferred-cleanup flush.
        multitapEventHandler.resetMultitapState()
        softwareMultitapHandler.resetMultitapState()
    }

    /** Idempotent UI state reset shared by the lifecycle paths. */
    fun resetUiState() {
        keyboardSwitcher.getMainKeyboardView()?.closing()
        uiCoordinator.hideAllInputUi()
        enableCursorMode(false)
        physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.WINDOW_HIDDEN)
        shakeGestureHandler!!.stop()
    }

    fun dismissKeyboard() {
        Logger.info(LOG_TAG, "dismissKeyboard()")
        resetUiState()
        fccController?.dismiss()
        clipboardController?.dismiss()
        requestHideSelf(0)
    }

    /**
     * @param oldSelStart old selection start
     * @param oldSelEnd old selection end
     * @param newSelStart new selection start
     * @param newSelEnd new selection end
     * @param candidatesStart composing span start
     * @param candidatesEnd composing span end
     */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        val inputConnection = getCurrentInputConnection()
        if (inputConnection == null || !getCurrentInputStarted()) {
            Logger.debug(LOG_TAG, "onUpdateSelection() - No valid input connection, hiding UI elements")
            uiCoordinator.hideAllInputUi()
        }

        val settingsValues = settingsManager.getSettingsValues()
        val zM4467a = inputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, settingsValues)
        if (zM4467a) {
            resetKeyboardState()
        }
        if (physicalKeyboardStateTracker.getModifierState().isShiftHeld()) {
            physicalKeyboardStateTracker.consumeModifiersAfterKey(0, true)
        }
        if (keyboardSwitcher.isShiftKeyPressed() || keyboardSwitcher.isShiftKeyMomentary()) {
            keyboardSwitcher.onKeyReleaseExternal()
            keyboardSwitcher.onCodeInput(-21, true, getCurrentInputType(), getCurrentImeOptions())
        }
        if (zM4467a) {
            multitapEventHandler.commitMultitapIfActive()
            softwareMultitapHandler.commitMultitap()
        }
        if (newSelStart != newSelEnd && !isMetaKeyActive()) {
            enableCursorMode(false)
        }
        fccController?.onSelectionUpdate(newSelStart, newSelEnd)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        if (cursorAnchorInfo != null && isCursorModeEnabled) {
            val selectionStart = cursorAnchorInfo.selectionStart
            val selectionEnd = cursorAnchorInfo.selectionEnd
            if (selectionStart != selectionEnd) {
                if (!isMetaKeyActive()) {
                    enableCursorMode(false)
                } else {
                    cursorTracker.hide()
                }
            } else {
                if (!isMetaKeyActive()) {
                    if (cursorTracker.isShowing()) {
                        cursorTracker.updatePosition(cursorAnchorInfo)
                    } else {
                        cursorTracker.show(cursorAnchorInfo, rootInputView as ViewGroup?)
                    }
                }
            }
        }
        lastCursorAnchorInfo = cursorAnchorInfo
        if (!isFullscreenMode()) {
            inputLogic.updateCursorAnchorInfo(cursorAnchorInfo)
        }
    }

    /** @param focusChanged true if the click moved focus */
    override fun onViewClicked(focusChanged: Boolean) {
        Logger.debug(LOG_TAG, "onViewClicked, focusChanged=$focusChanged")
        super.onViewClicked(focusChanged)
        enableCursorMode(false)
        refreshUnifiedInputBoardState()
    }

    private fun refreshUnifiedInputBoardState() {
        val c1011iM6838p = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager()
        if (c1011iM6838p != null) {
            c1011iM6838p.hideOtherComponents(-11)
            c1011iM6838p.refresh()
        }
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!InlineAutofillManager.isSupported()) {
            return null
        }

        Logger.debug(LOG_TAG, "onCreateInlineSuggestionsRequest()")

        val maxWidth = maxWidth
        var maxHeight = ResourceConfigManager.getSuggestionsStripHeight(resources)

        if (maxHeight <= 0) {
            // InlinePresentationSpec sizes are pixels.
            maxHeight = (44 * resources.displayMetrics.density).toInt()
        }

        // Pass the live EditorInfo: this callback has no ordering guarantee against
        // onStartInput, so the manager must not rely on what onInputStarted last recorded.
        return InlineAutofillManager.getInstance(this)
            .createInlineSuggestionsRequest(maxWidth, maxHeight, currentInputEditorInfo)
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!InlineAutofillManager.isSupported()) {
            return false
        }

        Logger.debug(LOG_TAG, "onInlineSuggestionsResponse(): ${response.inlineSuggestions.size} suggestions")

        return InlineAutofillManager.getInstance(this).handleResponse(response)
    }

    @Throws(Resources.NotFoundException::class)
    override fun onCurrentInputMethodSubtypeChanged(inputMethodSubtype: InputMethodSubtype) {
        subtypeManager.onSubtypeChanged(inputMethodSubtype)
        refreshLanguageOnScreenKeyboard()
        updateLanguagePacksForSubtype(inputMethodSubtype)
        if (!ResourceLocaleUtils.isNonCjkLanguage(richInputMethodManager!!.getLastInputMethodSubtype()) && keyboardSwitcher.getUnifiedInputBoardManager() != null && !keyboardSwitcher.getUnifiedInputBoardManager()!!.isShowing()) {
            keyboardSwitcher.onFinishShiftLongPress()
        }
        inputLogic.fullReset(ResourceLocaleUtils.getConverterDescriptor(inputMethodSubtype), settingsManager.getSettingsValues())
        loadKeyboard()
        richInputMethodManager!!.recordSubtypeSwitch(inputMethodSubtype)
        cjkSuggestionGridView?.setVisible(false)
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        val settingsValues = settingsManager.getSettingsValues()
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.setVkbGestureHandlingEnabledByUser(settingsValues.isVkbGestureInputEnabledForLocale())
            mainKeyboardViewM6790T.setCkbGestureHandlingEnabledByUser(settingsValues.isCkbGestureInputEnabledForLocale())
        }
        numericSubpanelController?.onSubtypeChanged(inputMethodSubtype)
        quickPhrasesController?.show()
    }

    fun refreshSubtypeSwitcher() {
        val c0664aaM4239a = SubtypeManager.getInstance()
        if (!c0664aaM4239a.isInitialized()) return
        // Cached: the uncached form is a binder call per onStartInput.
        val listM4860a = RichInputMethodManager.getInstance().getMyEnabledInputMethodSubtypeList(true)
        if (listM4860a == null || listM4860a.size <= 0) return
        val localeM4259h = c0664aaM4239a.getCurrentSubtypeLocale()
        val setM4260i = c0664aaM4239a.getCurrentSubtypeAdditionalLocales()
        var z = false
        for (inputMethodSubtype in listM4860a) {
            val localeM5625c = ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype)
            val setM5634h = ResourceLocaleUtils.getAdditionalLocales(inputMethodSubtype)
            if (localeM4259h == localeM5625c && ((setM4260i != null && setM4260i == setM5634h) || (setM4260i == null && setM5634h == null))) {
                z = true
                break
            }
        }
        if (z) return
        onCurrentInputMethodSubtypeChanged(listM4860a[0])
    }

    fun getSettingsValues(): SettingsValues {
        return settingsManager.getSettingsValues()
    }

    /** @param settingsValues the new settings */
    override fun onSettingsValuesChanged(settingsValues: SettingsValues) {
    }

    fun loadSettings() {
        val localeM4259h = subtypeManager.getCurrentSubtypeLocale()
        val currentInputEditorInfo = getCurrentInputEditorInfo()
        settingsManager.loadSettings(applicationContext, localeM4259h,
            EditorCapabilities(
                currentInputEditorInfo,
                isFullscreenMode(),
                packageName,
                localeM4259h,
                SettingsManager.isForceSuggestionsEnabled(PrefsManager.getPrefs())
            )
        )
        val settingsValues = settingsManager.getSettingsValues()
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(settingsValues, this)
        if (!uiUpdateHandler.hasPendingAdditionalLocalesLoad()) {
            initDictionaryForLocale(localeM4259h)
        }
        vkbGestureListener?.updateSettings(settingsValues)
        if (currentInputEditorInfo != null) {
            DictionaryManager.getInstance().ensureEnglishCapitalISubstitution(localeM4259h)
        }
        contactsLearningManager?.setContactsDictEnabled(settingsValues.useContactsDicts)
        // "Suggest learned words": off leaves learned words out of the strip.
        inputLogic.mSuggestionEngine.setSuggestLearnedWords(settingsValues.usePersonalizedDicts)
        // Re-applies "Enable special key support" without a restart; idempotent, clears when off.
        hardwareKeys.registerInterceptorCallbacks()
        fccController?.hideIfDisabled()
        // The main thread never calls the engine; read the value here, apply on the engine thread.
        val useContactsDicts = settingsValues.useContactsDicts
        EngineOps.post("loadSettings.engineOptions") { sdk ->
            // Always off: strip emoji come from emojibase (EmojibaseStripSuggestion).
            sdk.setEmojiPredictionEnabled(false)
            // The engine half of "Suggest from contacts": the manager above only runs the sync,
            // and learned names stay active until the engine's contacts category is off.
            sdk.setContactsCategoryState(useContactsDicts)
        }
        if (settingsValues.isEmojiPredictionsEnabled) {
            // Parse off the UI thread so the first strip update does not pay for it; on the
            // executor because loadSettings runs on every field focus.
            EmojibaseDataProvider.preloadAsync(this, startupExecutor)
        }
    }

    @Throws(Resources.NotFoundException::class)
    fun loadKeyboard() {
        uiUpdateHandler.postLoadAdditionalLocales()
        loadSettings()
        sweepBoardsWhenUimBarIsOff()
        if (keyboardSwitcher.getMainKeyboardView() != null) {
            physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.KEYBOARD_RELOADED)
            keyboardSwitcher.startInput(getCurrentInputEditorInfo(), getCurrentInputType(), getCurrentImeOptions())
        }
    }

    /** @param settingsValues the current settings */
    fun applyAutoCorrectionSettings(settingsValues: SettingsValues) {
        val isPkb = DeviceProfile.current().hasShiftedSymbolKeyboard()
        val acMode = if (!isPkb) 1 else 0
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "applyAutoCorrectionSettings (updateAutoCorrection): isPkb=$isPkb" +
            " acEnabledPerUser=${settingsValues.isAutoCorrectionEnabledPerUserSettings}" +
            " acModeValue=${settingsValues.autoCorrectionMode}" +
            " willSetThreshold=${settingsValues.autoCorrectionMode} willSetMode=$acMode")
        }
        if (settingsValues.isAutoCorrectionEnabledPerUserSettings) {
            inputLogic.mSuggestionEngine.setAutoCorrectThreshold(settingsValues.autoCorrectionMode)
            inputLogic.mSuggestionEngine.setAutoCorrectMode(acMode)
        }
    }

    fun updateLanguagePacksForCurrentSubtype() {
        val current = subtypeManager.getCurrentSubtype()
        if (current != null) {
            updateLanguagePacksForSubtype(current)
        } else {
            Logger.warn(LOG_TAG, "Current subtype is null; skipping updateLanguagePacksForCurrentSubtype()")
        }
        // FIFO; the load op re-applies the latest layout when it ends
        // (KeyboardSwitcher.resyncLayoutAfterLanguageLoad) in case this ran first.
        KeyboardSwitcher.getInstance().syncKeyboardLayout()
    }

    fun updateLanguagePacksForSubtype(inputMethodSubtype: InputMethodSubtype) {
        // The extra value is a slash-joined list ("de/es", "fr_CA"); a single Locale from it
        // makes the engine refuse the whole set.
        val locales = ResourceLocaleUtils.getKeyboardLocales(
            ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype),
            ResourceLocaleUtils.getAdditionalLocales(inputMethodSubtype),
        )
        // On the loader thread; nothing waits for it. The load re-creates the engine core, so the
        // latest layout is re-applied at the front of the engine queue when it ends.
        val packs = LanguagePackManager.getInstance(applicationContext)
        val localeArray = locales.toTypedArray()
        EngineOps.load("updateLanguagePacksForSubtype") { sdk ->
            val loaded = packs.setLanguages(sdk, localeArray)
            KeyboardSwitcher.resyncLayoutAfterLanguageLoad()
            loaded
        }
    }

    /**
     * Re-reads the dictionary after the Language packs screen swapped the file behind the active
     * locale. The engine only sees a new pack when asked for the language again; with no live
     * service there is no subscriber and the next start picks the file up.
     */
    private val languagePackChangedListener = InAppEventBus.EventListener { _, _ ->
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "Language pack changed; reloading the dictionary")
        // Forced: the locale is unchanged, so an ordinary init would keep the old dictionary.
        reloadDictionaryForSubtype(forceReload = true)
    }

    @JvmOverloads
    fun reloadDictionaryForSubtype(forceReload: Boolean = false) {
        var setM4260i: Set<Locale>?
        var localeM4259h = subtypeManager.getCurrentSubtypeLocale()
        if (TextUtils.isEmpty(localeM4259h.toString())) {
            if (BuildConfig.DEBUG) Log.e(LOG_TAG, "System is reporting no current subtype.")
            localeM4259h = LocaleUtils.getConfigurationLocale(resources)
            setM4260i = null
        } else {
            setM4260i = subtypeManager.getCurrentSubtypeAdditionalLocales()
        }
        languagePackLocaleMonitor!!.onLocaleChanged(localeM4259h, setM4260i)
        initDictionaryForLocale(localeM4259h, forceReload)
        multitapEventHandler.refreshAltMultitapSupport()
        // Every language the keyboard types in, so extra languages' shortcuts and words load too.
        DictionaryManager.getInstance().initialiseOrSwitchLanguages(applicationContext,
            ResourceLocaleUtils.getKeyboardLocales(localeM4259h, setM4260i))
    }

    fun reloadAdditionalLocales() {
        dictionaryLoader.updateAdditionalLocales(subtypeManager.getCurrentSubtypeAdditionalLocales(), true, this)
    }

    private fun initDictionaryForLocale(locale: Locale, forceReload: Boolean = false) {
        val settingsValues = settingsManager.getSettingsValues()
        dictionaryLoader.initDictionary(this, locale, settingsValues.useContactsDicts, forceReload, this)
        applyAutoCorrectionSettings(settingsValues)
    }

    fun reinitDictionary() {
        val settingsValues = settingsManager.getSettingsValues()
        val localeM4644a = dictionaryLoader.getLocale()
        if (localeM4644a != null) {
            val additional = subtypeManager.getCurrentSubtypeAdditionalLocales()
            languagePackLocaleMonitor!!.onLocaleChanged(localeM4644a, additional)
            dictionaryLoader.initDictionary(this, localeM4644a, settingsValues.useContactsDicts, true, this)
            applyAutoCorrectionSettings(settingsValues)
            multitapEventHandler.refreshAltMultitapSupport()
            DictionaryManager.getInstance().initialiseOrSwitchLanguages(applicationContext,
                ResourceLocaleUtils.getKeyboardLocales(localeM4644a, additional))
        }
    }

    /** @param available true if the main dictionary loaded */
    override fun onDictionaryInitialized(available: Boolean) {
        keyboardSwitcher.getMainKeyboardView()?.setMainDictionaryAvailability(available)
        if (available) uiUpdateHandler.postDictionaryLoaded()
        // Restored words wait for the dictionary and engine model they are written into.
        if (available) dev.bbkb.ime.core.settings.backup.AutoBackup.applyPendingWordsIfReady(this)
        val hadTimeout = uiUpdateHandler.hasDictionaryLoadTimeout()
        if (hadTimeout) uiUpdateHandler.removeDictionaryLoadTimeout()
        if (available || hadTimeout) uiUpdateHandler.postUpdateShiftState(true, false)
    }

    override fun onLocalesUpdated() {
        uiUpdateHandler.postUpdateShiftState(true, false)
    }

    fun toggleCangjieMode() {
        if (dictionaryLoader != null) {
            val i = if (SettingsManager.getInstance().getSettingsValues().cangjieMode == 0) 1 else 0
            SettingsManager.setCangjieMode(PrefsManager.getPrefs(), i)
            val variant = if (i == 0) NuanceSDK.CANGJIE_VARIANT else NuanceSDK.QUICK_CANGJIE_VARIANT
            EngineOps.post("toggleCangjieMode") { it.setInputMethod(variant) }
        }
    }

    /**
     * "Change language": toggles the language-chip bar on the strip. Nothing with one language.
     *
     * @param inputSource where the request came from
     */
    fun switchToNextSubtype(inputSource: InputSource): Boolean {
        if (!richInputMethodManager!!.hasMultipleEnabledSubtypesInThisIme(false)) {
            return false
        }
        val chips = languageChips()
        if (chips.size < 2) return false
        val manager = auxBarManager ?: return false
        if (manager.isShowingLanguageChips()) {
            manager.hideLanguageChips()
            return true
        }
        manager.showLanguageChips(chips)
        return true
    }

    /** The chips in their order: the current language, then the switch history newest first, then the rest. */
    private fun languageChips(): List<dev.bbkb.ime.keyboard.auxbar.LanguageChip> {
        val enabled = richInputMethodManager!!.enabledSubtypesOfThisIme.filter { !it.isAuxiliary }
        val current = subtypeManager.currentSubtype
        val ordered = LinkedHashSet<InputMethodSubtype>()
        if (current != null && current in enabled) ordered += current
        for (recent in richInputMethodManager!!.subtypeSwitchHistory.asReversed()) {
            if (recent != null && recent in enabled) ordered += recent
        }
        ordered += enabled
        // The language's name only, not the subtype's (which may append the layout).
        return ordered.map { dev.bbkb.ime.keyboard.auxbar.LanguageChip(ResourceLocaleUtils.getSubtypeLocaleDisplayName(it.locale), it, it == current) }
    }

    override fun onLanguageChosen(subtype: InputMethodSubtype) {
        richInputMethodManager!!.setInputMethodAndSubtype(this, subtype)
    }

    override fun onLanguageSettingsClicked() {
        val intent = Intent(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity::class.java)
        intent.putExtra("screen", "languages_hub")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    /** @param inputSource where the request came from */
    fun updateSuggestionsFromSubtype(inputSource: InputSource): Boolean {
        if (!RichInputMethodManager.isInitialized()) {
            RichInputMethodManager.init(applicationContext)
        }
        if (!ASSERTIONS_DISABLED && window.window == null) {
            throw AssertionError()
        }
        subtypeState.switchSubtype(this, richInputMethodManager)
        return true
    }

    fun getCurrentLocale(): Locale {
        return subtypeManager.getCurrentSubtypeLocale()
    }

    /** Asked on every VKB fling ([VkbGestureListener.isBackwardSwipe]), so it must stay cheap. */
    fun isCurrentLanguageRtl(): Boolean =
        TextUtils.getLayoutDirectionFromLocale(subtypeManager.getCurrentSubtypeLocale()) ==
            View.LAYOUT_DIRECTION_RTL

    // Cached per input-method generation in RichInputMethodManager.
    fun shouldShowLanguageSwitchKey(): Boolean =
        richInputMethodManager!!.shouldOfferSwitchingToNextInputMethod(this)

    /** @param keyCode the key code */
    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "BlackBerryIME.onKeyDown: keyCode=$keyCode deviceId=${keyEvent.deviceId} source=0x${Integer.toHexString(keyEvent.source)} scanCode=${keyEvent.scanCode} flags=0x${Integer.toHexString(keyEvent.flags)}")
        // A firmware-synthesised swipe key (the Titan 2's 322 / 404) is a gesture, not typing:
        // handled and consumed before the typing guard or the key pipeline sees it.
        if (ckbGestures.handleFirmwareGestureKey(keyEvent)) return true
        ckbGestures.onHardwareKey(keyEvent.eventTime)
        return keyEventProcessor.onKeyDownInternal(keyCode, keyEvent)
    }

    /** @param keyCode the key code */
    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "BlackBerryIME.onKeyUp: keyCode=$keyCode deviceId=${keyEvent.deviceId} source=0x${Integer.toHexString(keyEvent.source)} scanCode=${keyEvent.scanCode} flags=0x${Integer.toHexString(keyEvent.flags)}")
        if (ckbGestures.handleFirmwareGestureKey(keyEvent)) return true
        ckbGestures.onHardwareKey(keyEvent.eventTime)
        return keyEventProcessor.onKeyUpInternal(keyCode, keyEvent)
    }

    /** Modifier/symbol state updates only, for keys the accessibility service sees with no field. */
    fun processKeyEventForState(event: KeyEvent) {
        keyEventProcessor.processKeyEventForState(event)
    }

    /**
     * Summons the hidden IME window for a physical key press (the SYM key's board needs it);
     * returns whether the input view is showing. The caller must know the press is physical.
     */
    fun requestShowOnKeyPress(): Boolean = keyEventProcessor.requestShowOnKeyPress()

    /**
     * @param codePoint the code point
     * @param keyCode the key code
     * @param isKeyRepeat true if this is a key repeat
     */
    fun processHardwareKeyPress(codePoint: Int, keyCode: Int, isKeyRepeat: Boolean) {
        val event = InputEvent.createHardwareKeyPress(codePoint, keyCode, null, isKeyRepeat, -1L)
        applyPostEventUpdates(inputLogic.processInputEvent(settingsManager.getSettingsValues(), event, physicalKeyboardStateTracker, InputSource.UNKNOWN, keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler), true)
        // Typing brings the strip back (AuxBarChoice).
        if (event.editsText()) uiCoordinator.onUserTyped(true)
    }

    /** @param keyCode the original key code */
    fun remapKeyEvent(keyCode: Int, ev: KeyEvent): KeyEvent {
        // ControlModeController owns Ctrl, the multifunction key's "held as Ctrl" latch and a
        // config-declared Ctrl-like modifier (the Titan 2's Fn).
        val profileRemapped = controlMode.remapProfileCtrlKey(ev)
        val mfRemapped = controlMode.remapMultifunctionCtrlKey(profileRemapped)
        return controlMode.remapModifierKeyEvent(mfRemapped.keyCode, mfRemapped)
    }

    /** @param deviceId the input device id */
    fun getOrCreateKeyEventConverter(deviceId: Int): KeyEventConverter {
        val interfaceC0916c = keyEventConverters.get(deviceId)
        if (interfaceC0916c != null) return interfaceC0916c
        val resolver = hardwareKeys.auxCharacterResolver()
        val c0917d = KeyEventConverter(deviceId, CompositeKeyCharacterInterpreter(currencyKeyHandler.asKeyCharacterInterpreter(), keyboardSwitcher.getKeyCharacterInterpreter(), multitapEventHandler.asKeyCharacterInterpreter()), this, resolver)
        keyEventConverters.put(deviceId, c0917d)
        return c0917d
    }

    fun updateMainKeyboardViewForKeyEvent(event: KeyEvent, isKeyDown: Boolean) {
        if (isInputViewShown() && isGestureInputReady()) {
            val mkv = keyboardSwitcher.getMainKeyboardView()!!
            if (isKeyDown) {
                mkv.onPhysicalKeyDown(event)
            } else {
                mkv.onPhysicalKeyUp(event)
            }
            if (PhysicalKeyboardStateTracker.isShiftKey(event.keyCode)) {
                mkv.updateGestureHandlingState(isMetaKeyActive())
            }
        }
    }

    fun updatePhysicalKeyboardFilter() {
        physicalKeyboardStateTracker.updateFilterAndAltGr(getCurrentInputType(), keyboardSwitcher.getKeyCharacterMap())
    }

    /**
     * A text key went down: close open boards through the coordinator so its active-board state
     * stays true (hiding views behind its back makes the next board key a dead press). Board keys
     * never reach here (KeyEventProcessor exempts them).
     */
    fun dismissBoardsForTextKey() {
        val uim = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager()
        val sv = SettingsManager.getInstance().getSettingsValues()
        val coordinator = uim?.getBoardCoordinator()

        // Emoji board text keys never get here: they go to its search query (EmojiQueryKeyRouting).
        val cursorBoard = CrossAxisRules.CURSOR_BOARD_KEY_CODE

        fccController?.let { fcc ->
            // FCC has its own mid-toggle exemption; reconcile the coordinator only if it hid.
            fcc.hideUnlessToggling()
            if (coordinator != null && coordinator.activeBoard() == cursorBoard && !fcc.isViewActive()) {
                coordinator.notifyBoardClosed()
            }
        }

        if (uim != null && sv != null && sv.isUimEnabled) {
            // Unless the table says the board survives a text key (e.g. the number pad, which
            // is typed from).
            val openBoard = coordinator?.activeBoard() ?: UnifiedBoardCoordinator.NO_BOARD
            if (coordinator != null && !CrossAxisRules.boardSurvivesTextKey(openBoard)) {
                coordinator.onTextKeyPressed()
            }
            // Sweeps components shown outside coordinator tracking (e.g. autofill); same exemptions.
            uim.hideComponentsExcept(CrossAxisRules.boardsExemptFromTextKey())
            uim.refresh()
        }
        if (sv == null || !keyboardSwitcher.isEmojiKeyboardShowing() || sv.isUimEnabled) return
        keyboardSwitcher.getUnifiedInputBoardManager()!!.closeActiveComponent()
    }

    /**
     * The on-screen Shift is releasing and every physical Shift is released. A Shift still down
     * after an Alt chord is consumed, not released (pinned by `ShiftLifecycleTest`).
     */
    fun isShiftChording(): Boolean =
        keyboardSwitcher.isShiftKeyReleasing() && physicalKeyboardStateTracker.getModifierState().isShiftReleased()

    fun isMetaKeyActive(): Boolean {
        // !isShiftReleased() && isShiftLockedForLayout() && !isAltUsedWithKey(), answered without
        // building a snapshot: the capacitive keypad asks on every touch sample.
        return physicalKeyboardStateTracker.isShiftHeldAndLockedForLayout()
    }

    fun getSymbolPageProvider(): SymbolPageProvider {
        return if (DeviceProfile.current().hasShiftedSymbolKeyboard()) physicalKeyboardStateTracker else keyboardSwitcher
    }

    /** @param isKeyRepeat true to leave the symbol shift alone */
    fun updateSymbolShift(isKeyRepeat: Boolean) {
        if (isKeyRepeat) return
        if (isInputViewShown()) {
            keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), false, true)
        } else {
            uiUpdateHandler.post {
                keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), false, true)
            }
        }
        cjkSuggestionGridView?.setVisible(false)
    }

    /**
     * @param primaryCode the key code
     * @param x touch x
     * @param y touch y
     * @param eventTime event time
     * @param isKeyRepeat true if this is a key repeat
     */
    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, eventTime: Long, isKeyRepeat: Boolean) {
        vkbGestureListener?.setLastKeyEventTime(eventTime)
        when (primaryCode) {
            -37 -> return
            -29 -> {
                InputMethodHelper.getInstance().switchToRapidInputIme(this)
                return
            }
            -27, -7 -> {
                // Not gated on isUimEnabled(): the voice view is its own ViewStub, not part of the UIM.
                val voice = voiceInputController
                if (voice != null) voice.show() else InputMethodHelper.getInstance().switchToVoiceIme(this)
                return
            }
        }
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()!!
        val keyX = mainKeyboardView.getKeyX(x)
        val keyY = mainKeyboardView.getKeyY(y)
        // Shift on a non-alphabet keyboard means "symbol shift" (-13).
        val code = if (primaryCode == -1 && keyboardSwitcher.getCurrentKeyboard()?.mId?.isAlphabetKeyboard() != true) -13 else primaryCode
        // With on-screen Ctrl on, the key already went to the app as a chord on press.
        val shouldProcessKey = !controlMode.suppressesSoftCodeInput(primaryCode) && ((primaryCode < 0) || (primaryCode > 0 && isInputViewShown()))
        if (shouldProcessKey) {
            val interpretation = softwareMultitapHandler.interpretCodePoint(code)
            val event = if (interpretation != null && interpretation.isModifier()) {
                createKeyPressEvent(interpretation.codePoint, keyX, keyY, eventTime, interpretation.isModifier(), interpretation.isShiftLocked())
            } else {
                createKeyPressEvent(code, keyX, keyY, eventTime, isKeyRepeat)
            }
            softwareMultitapHandler.onInputEvent(event)
            applyPostEventUpdates(inputLogic.processInputEvent(settingsManager.getSettingsValues(), event, keyboardSwitcher, InputSource.SOFTWARE, keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler), true)
            // Typing brings the strip back over a menu the user raised (AuxBarChoice).
            if (event.editsText()) uiCoordinator.onUserTyped(false)
            keyboardSwitcher.onInputCodeChanged(code, getCurrentInputType(), getCurrentImeOptions())
            // A PKB SYM board tap returns to the alphabet only after the commit above (see
            // KeyboardState.onCodeInput). No-op for function keys such as ABC (-3).
            keyboardSwitcher.onSoftwareSymbolCommitted(code, getCurrentInputType(), getCurrentImeOptions())
        }
        // Not for ABC / ?123 (-3): its release (onSymbolKeyRelease) reads the current mode, and
        // ending the PKB Sym entry first would open the symbols page instead of the alphabet.
        if (!Constants.isLetterCode(code) && code != -3) handleSymbolKeyLongPress()
    }

    /** A key code <= 0 is a function key (no code point); > 0 is a code point with no key code. */
    private fun splitCode(code: Int): Pair<Int, Int> = if (code <= 0) Pair(-1, code) else Pair(code, 0)

    /**
     * @param code key code (<= 0) or code point
     * @param x touch x
     * @param y touch y
     * @param eventTime event time
     * @param isKeyRepeat true if this is a key repeat
     */
    fun createKeyPressEvent(code: Int, x: Int, y: Int, eventTime: Long, isKeyRepeat: Boolean): InputEvent {
        val (codePoint, keyCode) = splitCode(code)
        return InputEvent.createKeyPress(codePoint, keyCode, x, y, eventTime, isKeyRepeat)
    }

    /**
     * Modifier key press; other params as above.
     *
     * @param isShiftLocked true if Shift is locked
     */
    fun createKeyPressEvent(code: Int, x: Int, y: Int, eventTime: Long, isKeyRepeat: Boolean, isShiftLocked: Boolean): InputEvent {
        val (codePoint, keyCode) = splitCode(code)
        return InputEvent.createModifierKeyPress(codePoint, keyCode, x, y, eventTime, isKeyRepeat, isShiftLocked)
    }

    /**
     * @param text the text to commit
     * @param eventTime event time
     */
    override fun onTextInput(text: String, eventTime: Long) {
        var z = false
        val strM4443a0 = inputLogic.stripLeadingDot(text)
        val strM4443a = if (getCurrentInputType() == android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) capitalizeFirstCodePoint(strM4443a0, getCurrentLocale()) else strM4443a0
        val length = strM4443a.length
        val iArr = IntArray(length)
        var iCharCount = 0
        var i = 0
        while (iCharCount < length) {
            val iCodePointAt = strM4443a.codePointAt(iCharCount)
            iArr[i] = iCodePointAt
            iCharCount += Character.charCount(iCodePointAt)
            i++
        }
        val settingsValues = settingsManager.getSettingsValues()
        for (i2 in 0 until i) {
            if (settingsValues.isWordSeparator(iArr[i2]) || Character.getType(iArr[i2]) == Character.OTHER_SYMBOL.toInt()) {
                z = true
                break
            }
        }
        // Emoji sequences with skin tone modifiers, hair components, ZWJ or VS16.
        if (!z && i > 1) {
            for (i2 in 0 until i) {
                val codepoint = iArr[i2]
                if ((codepoint in 0x1F3FB..0x1F3FF) ||
                    (codepoint in 0x1F9B0..0x1F9B3) ||
                    codepoint == 0x200D ||
                    codepoint == 0xFE0F) {
                    z = true
                    break
                }
            }
        }
        val c0920gM4442a: InputEventContext
        if (z) {
            c0920gM4442a = inputLogic.commitVoiceInput(settingsValues, InputEvent.createTextInputEvent(strM4443a, 0), keyboardSwitcher.getSymbolPageOrder(), shouldInsertAsString, uiUpdateHandler)
            shouldInsertAsString = false
        } else {
            var c0914aM5909a: InputEvent? = null
            for (i3 in i - 1 downTo 0) {
                c0914aM5909a = InputEvent.createHardwareKeyEvent(iArr[i3], 0, eventTime, c0914aM5909a)
            }
            if (c0914aM5909a == null) return
            c0920gM4442a = inputLogic.processInputEvent(settingsValues, c0914aM5909a, getSymbolPageProvider(), InputSource.INTERNAL, keyboardSwitcher.getSymbolPageOrder(), uiUpdateHandler)
        }
        vkbGestureListener?.setLastKeyEventTime(eventTime)
        applyPostEventUpdates(c0920gM4442a, true)
        keyboardSwitcher.onInputCodeChanged(-4, getCurrentInputType(), getCurrentImeOptions())
    }

    /**
     * @param primaryCode the key code
     * @param repeatCount repeat count (0 for the first press)
     * @param isSinglePointer true if this is the only active pointer
     */
    override fun onPressKey(primaryCode: Int, repeatCount: Int, isSinglePointer: Boolean) {
        if (controlMode.handleSoftKeyDown(primaryCode)) return
        if (swipeToDeleteAnimatorView?.isAnimating() == true) {
            swipeToDeleteAnimatorView?.endAnimation()
        }
        keyboardSwitcher.onCodeInput(primaryCode, isSinglePointer, getCurrentInputType(), getCurrentImeOptions())
        multitapEventHandler.commitMultitapIfActive()
        inputLogic.onSoftKeyDown(primaryCode)
        playKeyFeedback(primaryCode, repeatCount)
        enableCursorMode(false)
        if (LocaleUtils.isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale()) && isInputViewShown() && (primaryCode == -40 || primaryCode == -39)) {
            toggleCangjieMode()
        }
        if (primaryCode == -1) {
            inputLogic.sendKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        // The disable-then-restore cycle guards the UIM bar while typing; Shift toggles are not
        // typing and would flash the emoji/voice keys disabled.
        if (primaryCode != -1 && primaryCode != -2) {
            unifiedInputBoardHandler?.scheduleKeyStateRestore()
        }
    }

    /**
     * @param primaryCode the key code
     * @param withSliding true if released during sliding input
     */
    override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
        if (controlMode.handleSoftKeyUp(primaryCode)) return
        keyboardSwitcher.onCodeRelease(primaryCode, withSliding, getCurrentInputType(), getCurrentImeOptions())
        inputLogic.onSoftKeyUp(primaryCode)
        if (primaryCode == -1) {
            inputLogic.sendKeyUp(59)
        }
    }

    override fun onStartBatchInput() {
        val typedWord = if (inputLogic.mComposingTracker.isComposing()) inputLogic.mComposingTracker.getComposingText() else ""
        inputLogic.commitTypedWord(settingsManager.getSettingsValues(), "", InputSource.INTERNAL)
        if (typedWord.isNotEmpty()) ckbGestures.onSwipeStartCommittedTypedWord(typedWord)
        // Reset ET9's active-input buffer per swipe, or the trace record ring (traceObj+0x49000)
        // and composing-start index (+0x39c) never advance and earlier touches bleed in
        // ("my" -> "how are you"). FIFO keeps it between this swipe's sent and later samples.
        EngineOps.post("onStartBatchInput.clear") { it.clear() }
        inputLogic.onSuggestionStripUpdate(settingsManager.getSettingsValues(), getSymbolPageProvider().getSymbolPageOrder(), uiUpdateHandler)
        val c0919fM4047Z = physicalKeyboardStateTracker
        keyboardSwitcher.getMainKeyboardView()!!.setGestureInputStartsBeforeRunningShift(true)
        c0919fM4047Z.cancelModifierTimers()
    }

    override fun onUpdateBatchInput() {
        // Throttled: arrives per touch event, and each request rewrites the engine's context
        // buffer; unthrottled writes poison the DLM into ranking recent commits above trace
        // shape. Not skipped: these lookups show the converging word while the finger is down.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBatchUpdateRequestTime < 120) return
        lastBatchUpdateRequestTime = now
        suggestionUpdater.requestSuggestionsNow()
    }

    /** @param inputSource HARDWARE for a keypad swipe, SOFTWARE for an on-screen one */
    override fun onEndBatchInput(inputSource: InputSource) {
        suggestionUpdater.requestPredictionsNow(inputSource)
        // A swipe is typing (AuxBarChoice).
        uiCoordinator.onUserTyped(inputSource == InputSource.HARDWARE)
    }

    override fun onCancelBatchInput() {
        inputLogic.disableSuggestions(uiUpdateHandler)
    }

    override fun onFinishSlidingInput() {
        keyboardSwitcher.onMomentaryStateFinish(getCurrentInputType(), getCurrentImeOptions())
    }

    /** @param requestCode the request code (1 = switch language) */
    override fun onCustomRequest(requestCode: Int): Boolean {
        if (!isAlertDialogShowing() && requestCode == 1) {
            return switchToNextSubtype(InputSource.SOFTWARE)
        }
        return false
    }

    override fun onMoreKeysKeyTyped() {
        sendPrivateCommand("dev.bbkb.ime.TYPED_ALT_CHAR_VKB", null)
    }

    override fun onCancelInput() {
    }

    /**
     * @param inputEventContext the result of processing the event
     * @param delaySuggestionUpdate true to post the suggestion update delayed instead of now
     */
    fun applyPostEventUpdates(inputEventContext: InputEventContext, delaySuggestionUpdate: Boolean) {
        when (inputEventContext.getUiUpdateMode()) {
            1 -> resetKeyboardState()
            2 -> uiUpdateHandler.scheduleShiftStateUpdate()
        }
        if (inputEventContext.shouldUpdateSuggestions()) {
            val reason = when {
                inputEventContext.event.isSuggestionPicked() -> SuggestionUpdater.Reason.AFTER_MANUAL_PICK
                inputEventContext.event.isGestureEvent() -> SuggestionUpdater.Reason.AFTER_GESTURE
                else -> SuggestionUpdater.Reason.AFTER_KEYSTROKE
            }
            if (delaySuggestionUpdate) {
                suggestionUpdater.requestDelayedLocaleAware(reason)
            } else if (LocaleUtils.isCurrentSubtypeJapanese()) {
                requestSuggestionUpdateAsync(reason.legacyCode)
            } else {
                requestSuggestionUpdateSync(reason.legacyCode)
            }
        }
        if (inputEventContext.isKeyHandled()) {
            subtypeState.setCurrentSubtypeHasBeenUsed()
        }
    }

    /**
     * @param code the key code
     * @param repeatCount repeat count
     */
    fun playKeyFeedback(code: Int, repeatCount: Int) {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        if (mainKeyboardViewM6790T == null || !mainKeyboardViewM6790T.isInDraggingFinger()) {
            if (repeatCount <= 0 || ((code != -5 || inputLogic.mRichInputConnection.hasCursorPosition()) && repeatCount % 2 != 0)) {
                val c0662aM4226a = AudioAndHapticFeedbackManager.getInstance()
                if (repeatCount == 0 && code != -27 && code != -29 && code != -23) {
                    c0662aM4226a.performHapticFeedback(mainKeyboardViewM6790T)
                }
                c0662aM4226a.performAudioFeedback(code)
            }
        }
    }

    private fun handleSymbolKeyLongPress() {
        keyboardSwitcher.onSymbolKeyLongPress(getCurrentInputType(), getCurrentImeOptions())
    }

    fun resetKeyboardState() {
        updatePhysicalKeyboardFilter()
        keyboardSwitcher.setAlphabetKeyboard(getCurrentInputType(), getCurrentImeOptions())
    }

    fun getCurrentInputType(): Int {
        return inputLogic.getCapsMode(settingsManager.getSettingsValues())
    }

    fun getCurrentImeOptions(): Int {
        return inputLogic.getConfigParserResult()
    }

    /** @param codePoints code points to locate on the current keyboard */
    fun getKeyCoordinates(codePoints: IntArray): IntArray {
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
            ?: return CoordinateUtils.newCoordinateArray(codePoints.size, -1, -1)
        return c0965eM6834l.getCoordinatesForCodes(codePoints)
    }

    override fun onGenericMotionEvent(motionEvent: MotionEvent): Boolean {
        // With the native touch source attached one pad event can arrive twice (decor-view
        // listener and here); the second gets the first's answer. No-ops when not attached.
        nativeTouch.duplicateResult(motionEvent)?.let { return it }
        val handled = handleGenericMotionEvent(motionEvent)
        nativeTouch.recordResult(motionEvent, handled)
        return handled
    }

    private fun handleGenericMotionEvent(motionEvent: MotionEvent): Boolean {
        if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
            val profile = DeviceProfile.current()
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent ACTION_DOWN: deviceId=${motionEvent.deviceId}" +
                " source=0x${Integer.toHexString(motionEvent.source)}" +
                " hasTouchKeypad=${profile.hasTouchKeypad()}" +
                " touchKeypadDeviceId=${profile.touchKeypadDeviceId}" +
                " isFromTouchKeypad=${profile.isFromTouchKeypad(motionEvent)}")
        }
        if (DeviceProfile.current().isFromTouchKeypad(motionEvent)) {
            if (motionEvent.actionMasked != MotionEvent.ACTION_MOVE) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: CKB event received action=${motionEvent.actionMasked}, deviceId=${motionEvent.deviceId}, source=0x${Integer.toHexString(motionEvent.source)}")
            }
            if (!isInputViewShown()) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: inputView NOT shown, returning false")
                return false
            }
            // Calibration: the raw unsnapped sensor point (touchStart gets grid-snapped coords on a
            // firm press). Use light touches; view with adb logcat -s XT9RAW:I
            if (InputPathDebug.on()) {
                val a = motionEvent.actionMasked
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                    val pi = motionEvent.actionIndex
                    android.util.Log.i("XT9RAW", "down raw=(%.1f, %.1f)".format(motionEvent.getX(pi), motionEvent.getY(pi)))
                } else if (a == MotionEvent.ACTION_MOVE) {
                    android.util.Log.i("XT9RAW", "move raw=(%.1f, %.1f)".format(motionEvent.x, motionEvent.y))
                }
            }
            // Debug visualizer swallows raw samples before the engine (works without a KDB).
            if (BuildConfig.DEBUG && sensorVizOverlay.forwardIfActive(motionEvent)) return true
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN ||
                motionEvent.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                ckbGestures.onContactStart(motionEvent.eventTime)
            }
            val pointerId = motionEvent.getPointerId(motionEvent.actionIndex)
            val gestureProcessor = GestureEventProcessor.getGestureEventProcessor(pointerId)
            if (isCursorModeEnabled) {
                // Cursor (FCC) mode: drag moves the cursor, a tap exits, always consumed. Only a
                // contact's first sample has engine tracking to cancel.
                if (ckbGestures.cancelEngineTrackingOncePerContact(motionEvent)) gestureProcessor.cancelTracking()
                ckbGestures.handleCursorModeDrag(motionEvent)
                return true
            }
            // The gesture arbiter drives all CKB gestures; a consumed contact cancels any flow,
            // anything else (Tap / FlowTrace / pre-UP) falls through to the key/flow path.
            if (ckbGestures.feed(motionEvent)) {
                // Once per contact, as in cursor mode above.
                if (ckbGestures.cancelEngineTrackingOncePerContact(motionEvent)) gestureProcessor.cancelTracking()
                return true
            }
            val ag = isGestureInputReady()
            val asCursorMode = isCursorModeEnabled
            val isMove = motionEvent.actionMasked == MotionEvent.ACTION_MOVE
            if (!isMove) {
                val typeBySwipingForLocale = settingsManager.getSettingsValues().isCkbGestureInputEnabledForLocale
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent gate: action=${motionEvent.actionMasked} isGestureInputReady=$ag isCursorModeEnabled(cursor mode)=$asCursorMode | settings: type_by_swiping_ckb(locale-checked)=$typeBySwipingForLocale")
            }
            if (!ag || asCursorMode) {
                if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: BLOCKED (gate failed) — KeyDetector will NOT receive this event")
                return true
            }
            if (isMetaKeyActive() && !isGestureHandlingActive()) {
                if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: BLOCKED (isMetaKeyActive && !isGestureHandlingActive)")
                return true
            }
            if (!isMove && InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: PASS — dispatching to KeyDetector.processMotionEvent action=" + motionEvent.action + " x=" + motionEvent.x + " y=" + motionEvent.y)
            gestureProcessor.processMotionEvent(motionEvent)
            // So KeyDetector.onDownEvent's noise filter does not judge by a stale key timestamp.
            GestureEventProcessor.updateLastKeyTime(motionEvent.eventTime)
            return true
        } else {
            if (motionEvent.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "onGenericMotionEvent: event NOT from touch keypad, falling through to super. deviceId=${motionEvent.deviceId} source=0x${Integer.toHexString(motionEvent.source)} action=${motionEvent.actionMasked}")
            }
        }
        return super.onGenericMotionEvent(motionEvent)
    }

    /** Lazy getter for the (forked, multi-pointer) GestureDetector shared by the VKB views. */
    fun getOrCreateGestureDetector(): MultiPointerGestureDetector? {
        if (gestureDetector == null) {
            vkbGestureListener = VkbGestureListener(this, settingsManager.getSettingsValues())
            gestureDetector = MultiPointerGestureDetector(this, vkbGestureListener, null, this)
            gestureDetector!!.setIsLongpressEnabled(false)
        }
        return gestureDetector
    }

    /** @param gestureCode the swipe code: -18 delete, -19 down, -17 prediction pick */
    fun handleSwipeGesture(gestureCode: Int, motionEvent: MotionEvent, endEvent: MotionEvent) {
        val actionIndex = motionEvent.actionIndex
        var str = ""
        var zM5299a = false
        if (gestureCode == -18) {
            controlMode.clearControlState()
            val rawX = motionEvent.rawX
            val y = motionEvent.getY(actionIndex)
            val rawX2 = endEvent.rawX
            if (inputLogic.hasSelectionOrCursor()) {
                updateSwipeToDelete(rawX, y, rawX2)
                if (onSwipeDelete()) {
                    str = "dev.bbkb.ime.SWIPE_DELETE_VKB"
                    zM5299a = true
                } else {
                    Logger.warn("IMEGesture", "Delete gesture at valid position did nothing!")
                }
            }
        } else if (gestureCode == -19) {
            val swipeSettings = settingsManager.getSettingsValues()
            run {
                if (swipeSettings.isVkbSwipeDownEnabled) {
                    Logger.info(LOG_TAG, "Swipe-down dismiss: isCkb=false")
                    controlMode.clearControlState()
                    dismissKeyboard()
                    str = "dev.bbkb.ime.SWIPE_DISMISS_VKB"
                    zM5299a = true
                } else {
                    controlMode.clearControlState()
                    cjkSuggestionGridView?.setVisible(false)
                    str = "dev.bbkb.ime.SWIPE_SYMBOLS_VKB"
                    if (zM5299a) {
                        physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.ALT_PAGE_LEFT)
                    }
                    zM5299a = keyboardSwitcher.onSymbolShiftToggle(getCurrentInputType(), getCurrentImeOptions(), true, zM5299a)
                }
            }
        } else if (gestureCode == -17) {
            controlMode.clearControlState()
            val flick = flickSuggestionView
            if (flick != null && flick.isShown) {
                val x2 = motionEvent.getX(actionIndex)
                val y3 = motionEvent.getY(actionIndex)
                Logger.debug(LOG_TAG, "Swipe for prediction selection: starting at x $x2, y $y3")
                zM5299a = flick.handleFlick(x2, y3, settingsManager.getSettingsValues().inLetterMaxSwipeToWordDistance)
            }
            str = "dev.bbkb.ime.SWIPE_PREDICT_VKB"
        }
        if (zM5299a) AudioAndHapticFeedbackManager.getInstance().performAudioFeedback(gestureCode)
        if (str.isNotEmpty()) sendPrivateCommand(str, null)
    }

    fun onSwipeDelete(): Boolean {
        if (!inputLogic.mComposingTracker.isComposing() && inputLogic.mEventDispatcher.isRevertEligible()) {
            // COORD_SWIPE_DELETE_REVERT makes the backspace revert path treat it as revert-eligible.
            onCodeInput(-5, InputEvent.COORD_SWIPE_DELETE_REVERT, InputEvent.COORD_SWIPE_DELETE_REVERT, -1L, false)
            return false
        }
        val zM4481b = inputLogic.handleSwipeDelete(settingsManager.getSettingsValues())
        if (zM4481b) {
            resetKeyboardState()
            suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_CURSOR_MOVE)
        }
        return zM4481b
    }

    /** Keypad swipe-delete; the arbiter gives X as a fraction of the pad width, scaled to the view here. */
    fun onCkbSwipeDelete(startXFraction: Float, endXFraction: Float): Boolean {
        if (inputLogic.hasSelectionOrCursor()) {
            val width = animationWidth()
            updateSwipeToDelete(startXFraction * width, 0f, endXFraction * width)
        }
        return onSwipeDelete()
    }

    /** The width the swipe-delete icon travels across: the keyboard view, else the input view. */
    private fun animationWidth(): Int {
        val keyboardWidth = keyboardSwitcher.getMainKeyboardView()?.width ?: 0
        return if (keyboardWidth > 0) keyboardWidth else (rootInputView?.width ?: 0)
    }

    /**
     * @param startX start x
     * @param startY y
     * @param endX end x
     */
    fun updateSwipeToDelete(startX: Float, startY: Float, endX: Float) {
        val mainKeyboardView = keyboardSwitcher.getMainKeyboardView()
        swipeToDeleteAnimatorView?.setCurrentKeyboardHeight(mainKeyboardView?.height ?: 0)
        val auxBarShown = auxBarManager != null && auxBarManager!!.getAuxBarView() != null && (auxBarManager!!.getAuxBarView()?.isShowing() ?: false)
        swipeToDeleteAnimatorView?.setSuggestionStripShown(auxBarShown)
        swipeToDeleteAnimatorView?.setCurrentKeyboardWidth(animationWidth())
        swipeToDeleteAnimatorView?.startSwipeAnimation(startX, startY, endX)
    }

    fun shouldHandleGestureEvent(motionEvent: MotionEvent): Boolean {
        val isFromTouchKeypad = DeviceProfile.current().isFromTouchKeypad(motionEvent)
        return (isFromTouchKeypad && SettingsManager.getInstance().getSettingsValues().isCkbGestureInputEnabledForLocale() && isGestureInputReady()) ||
            (!isFromTouchKeypad && SettingsManager.getInstance().getSettingsValues().isVkbGestureInputEnabledForLocale() && isVkbGestureAvailable())
    }

    fun isGestureInputReady(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        val mkvNonNull = mainKeyboardViewM6790T != null
        val mkvC = mainKeyboardViewM6790T?.isGestureKeyboard() ?: false
        // One locale read: this runs on every touch sample.
        val locale = SubtypeManager.getInstance().getCurrentSubtypeLocale()
        val notLocaleA = !LocaleUtils.isChinese(locale)
        val notLocaleD = !LocaleUtils.isJapanese(locale)
        val result = mkvNonNull && mkvC && notLocaleA && notLocaleD
        if (!result) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isGestureInputReady returned FALSE | mkv!=null=$mkvNonNull mkv.isGestureKeyboard=$mkvC !LocaleUtils.a=$notLocaleA !LocaleUtils.d=$notLocaleD")
        }
        return result
    }

    fun isVkbGestureAvailable(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        return mainKeyboardViewM6790T != null && mainKeyboardViewM6790T.isGestureInputEnabled() && !LocaleUtils.isCurrentSubtypeChinese() && !LocaleUtils.isCurrentSubtypeJapanese()
    }

    fun isGestureHandlingActive(): Boolean {
        val mainKeyboardViewM6790T = keyboardSwitcher.getMainKeyboardView()
        return mainKeyboardViewM6790T != null && mainKeyboardViewM6790T.isGestureHandlingActive()
    }

    /** @param action the private command action */
    fun sendPrivateCommand(action: String, bundle: Bundle?) {
        if (!isBlackBerryApp) return
        val currentInputConnection = getCurrentInputConnection() ?: return
        currentInputConnection.performPrivateCommand(action, bundle)
    }

    /** @param count number of steps */
    override fun moveRight(count: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorRight(settingsManager.getSettingsValues(), count, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_RIGHT_CKB", null)
    }

    /** @param count number of steps */
    override fun moveLeft(count: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorLeft(settingsManager.getSettingsValues(), count, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_LEFT_CKB", null)
    }

    /** @param count number of steps */
    override fun moveUp(count: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorUp(count, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_UP_CKB", null)
    }

    /** @param count number of steps */
    override fun moveDown(count: Int) {
        clearStuckMetaState()
        inputLogic.moveCursorDown(count, isMetaKeyActive())
        sendPrivateCommand("dev.bbkb.ime.MOVE_DOWN_CKB", null)
    }

    private fun clearStuckMetaState() {
        if (!isMetaKeyActive()) {
            if (isCursorModeEnabled) physicalKeyboardStateTracker.cancelModifierTimers()
            physicalKeyboardStateTracker.consumeModifiersAfterKey(0, true)
        }
        if (isUnifiedInputBoardShowing()) return
        scheduleCursorModeDisable()
    }

    /** @param enable true to enable cursor mode */
    fun enableCursorMode(enable: Boolean) {
        applyCursorModeState(enable, true, false)
    }

    fun toggleCursorMode() {
        val z = !isCursorModeEnabled
        Logger.debug("ARROW_BAR_DIAG", "toggleCursorMode: toggling FCC to enable=$z (isCursorModeEnabled was ${isCursorModeEnabled})")
        applyCursorModeState(z, true, false)
    }

    /**
     * @param enable true to enable cursor mode
     * @param requestShiftUpdate on exit, true to re-derive the shift state
     * @param forced true for a forced entry (FCC reconciling a state it already arranged)
     */
    fun applyCursorModeState(enable: Boolean, requestShiftUpdate: Boolean, forced: Boolean) {
        if (BuildConfig.DEBUG) Logger.debug("ARROW_BAR_DIAG", "applyCursorModeState: enable=$enable forced=$forced isUnifiedInputBoardShowing()=${isUnifiedInputBoardShowing()} → will proceed=${forced || !isUnifiedInputBoardShowing()}")
        if (forced || !isUnifiedInputBoardShowing()) {
            if (enable) {
                if (isCursorModeEnabled) return
                Logger.debug("IMEGesture", "Cursor mode enabled")
                // A CrossAxisRules row: the arrow bar replaces open boards and suggestion views,
                // except on a forced entry, where the table leaves the other axes alone.
                keyboardSwitcher.transitions().apply(KeyboardTransition.enterCursorMode(forced))
                val currentInputConnection = getCurrentInputConnection()
                if (currentInputConnection != null && lastCursorAnchorInfo != null && !isMetaKeyActive() && (!forced || !inputLogic.mRichInputConnection.hasSelection())) {
                    if (lastCursorAnchorInfo!!.selectionEnd != lastCursorAnchorInfo!!.selectionStart || inputLogic.mRichInputConnection.hasSelection()) {
                        currentInputConnection.performPrivateCommand("dev.bbkb.ime.FCC_ON", null)
                    } else {
                        cursorTracker.show(lastCursorAnchorInfo!!, rootInputView as ViewGroup?)
                    }
                    currentInputConnection.performPrivateCommand("dev.bbkb.ime.FCC_OFF", null)
                }
                if (!forced) scheduleCursorModeDisable()
                if (isGestureInputReady()) {
                    keyboardSwitcher.getMainKeyboardView()!!.cancelAllGestureTracking()
                }
                return
            }
            if (isCursorModeEnabled) {
                Logger.debug("IMEGesture", "Cursor mode disabled")
                // The EXIT_CURSOR_MODE row; the cursor window and auto-disable timer are
                // cursor-axis internals and stay here.
                keyboardSwitcher.transitions().apply(KeyboardTransition.exitCursorMode(requestShiftUpdate))
                cursorTracker.hide()
                uiUpdateHandler.removeCallbacks(disableCursorModeRunnable)
                requestSuggestionsForCursorPosition()
            }
        }
    }

    // The cursor axis' mechanism, as KeyboardStateCoordinator.Axes reaches it.

    /** Cursor (FCC) mode on: tell FCC, then set the flag (order matters: FCC reads the flag). */
    fun cursorModeOn(forced: Boolean) {
        fccController?.onFccEnabled(forced)
        isCursorModeEnabled = true
    }

    fun cursorModeOff() {
        isCursorModeEnabled = false
    }

    /** Guards the whole `ENTER_CURSOR_MODE` bar+board effect: with the bar showing no step runs. */
    fun canRaiseArrowBar(): Boolean {
        val arrowBar = arrowBarController
        Logger.debug("ARROW_BAR_DIAG", "canRaiseArrowBar: arrowBarController=" +
            (if (arrowBar == null) "NULL" else "non-null") + " isShowing=" + arrowBar?.isShowing())
        return arrowBar != null && !arrowBar.isShowing()
    }

    /**
     * Before the arrow bar goes up: cancel the composing word (text pipeline, not in the
     * cross-axis table) and hide the suggestion views.
     */
    fun prepareForArrowBar() {
        inputLogic.cancelComposingAndTouchEvent()
        uiCoordinator.hideSuggestionViews()
    }

    /**
     * `ENTER_CURSOR_MODE`'s board column, `CLOSE_ALL`: every board goes, exemptions included. A
     * user-raised menu choice (and the show-menu key's override) survives, so the cursor-mode
     * exit restores that menu.
     */
    fun sweepAllBoardsClosed() {
        // getBarChoice() is a Java getter (platform type); the JVM harnesses mock the coordinator.
        val choice: dev.bbkb.ime.keyboard.state.AuxBarChoice? = uiCoordinator.getBarChoice()
        val menuChosen = choice?.isMenuChosen() == true
        val menuRequestedByKey = SettingsValues.isInputMenuRequestedByKey()
        hideInputBoard()
        if (menuChosen) choice?.chooseMenu()
        if (menuRequestedByKey) SettingsValues.setInputMenuRequestedByKey(true)
    }

    /** The last step of `ENTER_CURSOR_MODE`'s bar column. */
    fun raiseArrowBar() {
        Logger.debug("ARROW_BAR_DIAG", "raiseArrowBar: calling arrowBarController.show()")
        arrowBarController?.show()
    }

    fun lowerArrowBarRestoringStrip(requestShiftUpdate: Boolean) = hideArrowBar(requestShiftUpdate)

    /** The `RESTORE_STRIP_OR_UIM` bar effect: whichever of the two this editor gets. */
    fun restoreStripOrUimBar() {
        uiCoordinator.showSuggestionStripOrUim()
    }

    /**
     * On every cursor-mode exit, makes the same request an editor tap does
     * (`postUpdateShiftState(true, true)`), since performRecorrection ignores selection updates
     * while in cursor mode and `(false, false)` alone leaves the strip blank. The [isInputActive]
     * check keeps teardown exits from queuing work.
     */
    private fun requestSuggestionsForCursorPosition() {
        if (!isInputActive) return
        uiUpdateHandler.postUpdateShiftState(true, true)
    }

    private fun scheduleCursorModeDisable() {
        uiUpdateHandler.removeCallbacks(disableCursorModeRunnable)
        if (isMetaKeyActive()) return
        uiUpdateHandler.postDelayed(disableCursorModeRunnable, 4000L)
    }

    /** @param requestShiftUpdate true to re-derive the shift state after the strip returns */
    private fun hideArrowBar(requestShiftUpdate: Boolean) {
        val arrowBar = arrowBarController
        if (arrowBar == null || !arrowBar.isShowing()) return
        arrowBar.hide()
        if (hasAuxBarView() || hasCjkSuggestionGrid()) {
            restoreSuggestionStrip(false, requestShiftUpdate)
        }
    }

    /**
     * @param animate unused
     * @param requestShiftUpdate true to re-derive the shift state (skipped with a selection)
     */
    fun restoreSuggestionStrip(animate: Boolean, requestShiftUpdate: Boolean) {
        // RESTORE_BAR row; the shift re-derivation is the text pipeline and stays here.
        keyboardSwitcher.transitions().apply(KeyboardTransition.restoreBar())
        if (!requestShiftUpdate || inputLogic.mRichInputConnection.hasSelection()) return
        uiUpdateHandler.postUpdateShiftState(false, false)
    }

    /** @param inputStyle the update reason code ([SuggestionUpdater.Reason.legacyCode]) */
    fun requestSuggestionUpdateSync(inputStyle: Int) {
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        inputLogic.updateSuggestionsSync(settingsManager.getSettingsValues(), inputStyle)
    }

    /** @param inputStyle the update reason code */
    fun requestSuggestionUpdateAsync(inputStyle: Int) {
        uiUpdateHandler.cancelPendingSuggestionUpdates()
        inputLogic.updateSuggestionsAsync(settingsManager.getSettingsValues(), inputStyle)
    }

    /**
     * @param inputStyle the update reason code
     * @param suggestionCallback receives the suggestions
     */
    fun runSuggestionRequest(inputStyle: Int, suggestionCallback: SuggestionEngine.SuggestionCallback) {
        // A main-thread request carries its proximity grid (and shift state) in its snapshot,
        // so the worker reads no keyboard state.
        val snapshotGrid = InputLogic.snapshotProximityGrid(suggestionCallback)
        if (snapshotGrid != null) {
            inputLogic.requestSuggestionsWithContext(this, settingsManager.getSettingsValues(), snapshotGrid, 0, inputStyle, suggestionCallback)
            return
        }
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
        if (BuildConfig.DEBUG) Log.d("SUGG", "runSuggestionRequest: keyboard=${c0965eM6834l != null} thread=${Thread.currentThread().name}")
        if (c0965eM6834l == null) {
            suggestionCallback.onSuggestionsReady(SuggestedWords.EMPTY)
        } else {
            inputLogic.requestSuggestionsWithContext(this, settingsManager.getSettingsValues(), c0965eM6834l.getProximityGrid(), getSymbolPageProvider().getSymbolPageOrder(), inputStyle, suggestionCallback)
        }
    }

    /** Display side of the suggestion pipeline (the request side is [suggestionUpdater]). */
    private val suggestionStrip = SuggestionStripPresenter(this)

    /** @param suggestedWords the words to show */
    override fun showSuggestionStrip(suggestedWords: SuggestedWords) = suggestionStrip.showOrNeutral(suggestedWords)

    /**
     * @param suggestedWords the words to show
     * @param isGesture true if the words come from a gesture
     */
    fun displaySuggestions(suggestedWords: SuggestedWords, isGesture: Boolean) = suggestionStrip.display(suggestedWords, isGesture)

    fun updateFlickMetrics() = suggestionStrip.updateFlickMetrics()

    fun updateFlickAndFilter() {
        updateFlickMetrics()
        updatePhysicalKeyboardFilter()
    }

    fun hideFlickSuggestions() {
        flickSuggestionView?.hide()
    }

    fun clearSuggestions() = suggestionStrip.clear()

    override fun setNeutralSuggestionStrip() = suggestionStrip.showNeutral()

    /** @param word the word to offer */
    override fun showAddToDictionaryHint(word: String) {
        flickSuggestionView?.clearSuggestions(word)
    }

    override fun isShowingMoreSuggestions(): Boolean {
        return false
    }

    override fun dismissMoreSuggestions() {
        // AuxBarView handles this.
    }

    override fun onSuggestionPicked(suggestedWordInfo: SuggestedWords.SuggestedWordInfo, inputSource: InputSource) {
        if (suggestedWordInfo.isKind(EmojibaseStripSuggestion.KIND_EMOJI) && !keyboardSwitcher.isNoPersonalizedLearning()) {
            EmojiToneMemory.onEmojiPicked(PrefsManager.getPrefs(this), suggestedWordInfo.word)
        }
        applyPostEventUpdates(inputLogic.handleManualPick(settingsManager.getSettingsValues(), suggestedWordInfo, getSymbolPageProvider(), keyboardSwitcher.getKeyboardElementId(), uiUpdateHandler, inputSource), true)
    }

    override fun onCjkSuggestionSelected(suggestedWordInfo: SuggestedWords.SuggestedWordInfo) {
        onSuggestionPicked(suggestedWordInfo, InputSource.UNKNOWN)
    }

    fun isSuggestionStripActive(): Boolean {
        return settingsManager.getSettingsValues().isPredictionsEnabled && isInputActive && isInputViewShown()
    }

    fun refreshSuggestionStripVisibility() {
        uiCoordinator.updateSuggestionStripVisibility()
    }

    fun commitTouchEventText() {
        inputLogic.commitTouchEventText()
    }

    fun isAltMultitapSupported(): Boolean {
        return multitapEventHandler.isAltMultitapSupported()
    }

    /** @param insertAsString true to commit the next text input as one string */
    fun setInsertAsString(insertAsString: Boolean) {
        shouldInsertAsString = insertAsString
    }

    /**
     * The add-to-dictionary plus: adds the word (also teaching the DLM and the system user
     * dictionary) and re-runs the editor's spell check; if refused, opens BBKB's add-word dialog.
     *
     * @param word the word to add
     */
    fun addWordToUserDictionary(word: String) {
        val added = OneTapAddWord.add(DictionaryManager.getInstance(), word, dictionaryLoader.getLocale())
        if (added) {
            inputLogic.mRichInputConnection.requestSpellCheck()
        } else {
            dev.bbkb.ime.personaldictionary.AddWordDialog.start(this, word, dictionaryLoader.getLocale())
        }
        inputLogic.dismissMoreKeys()
    }

    fun isOnScreenKeyboardVisible(): Boolean {
        return DeviceProfile.isOnScreenKeyboardVisible()
    }

    fun isUnifiedInputBoardShowing(): Boolean {
        val c0979i = keyboardSwitcher
        return c0979i.isUnifiedInputBoardShowing()
    }

    fun isUimEnabled(): Boolean = uiCoordinator.isUimEnabled()

    override fun shouldShowUim(): Boolean = uiCoordinator.shouldShowUim()

    private fun isUimVisible(): Boolean {
        // A view question: whether the emoji board is open belongs to EmojiBoardController.
        return !(!isUnifiedInputBoardShowing() || keyboardSwitcher.peekEmojiPalettesView()?.isPalettesVisible() == true)
    }

    private fun hideInputBoard() {
        uiCoordinator.hideUnifiedInputBoard()
    }

    /** With the UIM bar switched off, open boards have no bar to sit in, so they go. No-op when on. */
    fun sweepBoardsWhenUimBarIsOff() {
        if (keyboardSwitcher.getUnifiedInputBoardManager() == null || isUimEnabled()) return
        keyboardSwitcher.transitions().apply(KeyboardTransition.sweepBoards())
    }

    fun refreshUnifiedInputBoard() {
        if (isOnScreenKeyboardVisible()) return
        val c1011iM6838p = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager() ?: return
        if (!c1011iM6838p.isShowing()) return
        c1011iM6838p.refresh()
    }

    /** @param handler the UIM board handler */
    fun attachInputBoardHandler(handler: UnifiedInputBoardHandler) {
        unifiedInputBoardHandler = handler
    }

    fun hasAuxBarView(): Boolean {
        return auxBarManager != null && auxBarManager!!.getAuxBarView() != null
    }

    fun hasFlickSuggestionView(): Boolean = flickSuggestionView != null

    fun hasCjkSuggestionGrid(): Boolean = cjkSuggestionGridView != null

    fun hasFccController(): Boolean = fccController != null

    fun hasVoiceInputController(): Boolean = voiceInputController != null

    fun hasClipboardController(): Boolean = clipboardController != null

    fun getAuxBarView(): dev.bbkb.ime.keyboard.auxbar.AuxBarView? {
        return auxBarManager?.getAuxBarView()
    }


    fun showInputOptions() {
        if (isAlertDialogShowing()) return
        showInputOptionsMenu()
    }

    private fun showInputOptionsMenu() {
        val string = getString(R.string.english_ime_input_options)
        val string2 = getString(R.string.language_selection_title)
        val settingsString = try {
            val activityInfo = packageManager.getActivityInfo(android.content.ComponentName(this, ComposeSettingsActivity::class.java), 0)
            if (activityInfo != null) getString(activityInfo.labelRes) else "Settings"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "Settings"
        }
        val charSequenceArr: Array<CharSequence> = arrayOf(string2, settingsString)
        val onClickListener = DialogInterface.OnClickListener { dialogInterface, i ->
            dialogInterface.dismiss()
            when (i) {
                0 -> {
                    val intentM5786a = IntentUtils.getInputLanguageSelectionIntent(richInputMethodManager!!.getInputMethodIdOfThisIme(), 337641472)
                    intentM5786a.putExtra("android.intent.extra.TITLE", string2)
                    startActivity(intentM5786a)
                }
                1 -> openSettings()
            }
        }
        val builder = dev.bbkb.ime.core.settings.ui.BbkbDialogs.builder(this)
        builder.setItems(charSequenceArr, onClickListener).setTitle(string)
        val alertDialogCreate = builder.create()
        alertDialogCreate.setCancelable(true)
        alertDialogCreate.setCanceledOnTouchOutside(true)
        showAlertDialog(alertDialogCreate)
    }

    private fun showAlertDialog(alertDialog: AlertDialog) {
        val windowToken = keyboardSwitcher.getMainKeyboardView()?.windowToken ?: return
        val window = alertDialog.window
        val attributes = window!!.attributes
        attributes.token = windowToken
        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.attributes = attributes
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        currentAlertDialog = alertDialog
        alertDialog.show()
    }

    private fun isAlertDialogShowing(): Boolean {
        val alertDialog = currentAlertDialog
        return alertDialog != null && alertDialog.isShowing
    }

    private fun createSettingsIntent(): Intent {
        inputLogic.commitTypedWord(settingsManager.getSettingsValues(), "", InputSource.INTERNAL)
        requestHideSelf(0)
        keyboardSwitcher.getMainKeyboardView()?.closing()
        val intent = Intent()
        intent.setClass(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION
        intent.putExtra("show_home_as_up", false)
        return intent
    }

    fun openSettings() {
        startActivity(createSettingsIntent())
    }

    fun openSlideboardSettings() {
        val intent = Intent(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity::class.java)
        intent.putExtra("screen", "slideboard_settings")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    override fun onSuggestionSelected(wordInfo: SuggestedWords.SuggestedWordInfo, inputSource: InputSource) {
        onSuggestionPicked(wordInfo, inputSource)
    }

    override fun onSuggestionLongPressed(wordInfo: SuggestedWords.SuggestedWordInfo) {
        onCjkSuggestionSelected(wordInfo)
    }

    override fun onAutofillSelected(position: Int) {
        if (BuildConfig.DEBUG) android.util.Log.d("INLINE_AUTOFILL_DEBUG", "[IME] onAutofillSelected() pos=$position - hiding bar")
        if (InlineAutofillManager.isSupported()) {
            val autofillManager = InlineAutofillManager.getInstance(this)
            autofillManager.onSelectionMade()
        }
    }

    override fun onHamburgerMenuClicked() {
        uiCoordinator.showUnifiedInputMenuFromSuggestionStrip()
    }

    override fun onExpandSuggestionsClicked() {
        handleSymbolKeyLongPress()
    }

    override fun onAuxBarStateChanged(
        oldState: dev.bbkb.ime.keyboard.auxbar.AuxBarState,
        newState: dev.bbkb.ime.keyboard.auxbar.AuxBarState
    ) {
        Logger.debug(LOG_TAG, "AuxBar state changed: $oldState -> $newState")
    }

    override fun getMainKeyboard(): dev.bbkb.ime.keyboard.Keyboard? {
        return keyboardSwitcher.getCurrentKeyboard()
    }

    override fun onAccentSelected(accentChar: String) {
        if (accentChar.isNotEmpty()) {
            inputLogic.replaceLastCharWithAccent(accentChar)
            Logger.debug(LOG_TAG, "Accent committed: $accentChar")
        }
    }

    override fun letterAnAccentWouldReplace(): String = inputLogic.letterAnAccentWouldReplace()

    override fun isInSymbolMode(): Boolean {
        val symbolPageOrder = physicalKeyboardStateTracker.getSymbolPageOrder()
        return symbolPageOrder == SYMBOL_PAGE_ORDER_FIRST || symbolPageOrder == SYMBOL_PAGE_ORDER_SECOND
    }

    override fun getMoreKeysForScanCode(scanCode: Int): String? {
        return keyboardSwitcher.getMoreKeysForScanCode(scanCode)
    }

    override fun getAuxCharacterResolver(): dev.bbkb.ime.core.keyevent.AuxCharacterResolver {
        return hardwareKeys.auxCharacterResolver()
    }

    // Accessors and super bridges for the Java collaborators.

    fun getSettingsManager(): SettingsManager = settingsManager

    fun getInputLogic(): InputLogic = inputLogic

    fun getUiCoordinator(): InputViewCoordinator = uiCoordinator

    fun getSubtypeManager(): SubtypeManager = subtypeManager

    fun getUiUpdateHandler(): UIUpdateHandler = uiUpdateHandler

    fun getDictionaryLoader(): DictionaryLoader? = dictionaryLoader

    fun getCursorTracker(): CursorTracker = cursorTracker

    fun getPhysicalKeyboardStateTracker(): PhysicalKeyboardStateTracker = physicalKeyboardStateTracker

    fun getKeyboardSwitcher(): KeyboardSwitcher = keyboardSwitcher

    fun getMultitapEventHandler(): MultitapEventHandler = multitapEventHandler

    fun getDisableCursorModeRunnable(): Runnable = disableCursorModeRunnable

    fun getSymbolPageOrder(): Int = physicalKeyboardStateTracker.getSymbolPageOrder()

    fun getControlMode(): ControlModeController = controlMode

    fun getHardwareKeys(): HardwareKeyBridge = hardwareKeys

    fun clearLastCursorAnchorInfo() { lastCursorAnchorInfo = null }

    fun superOnStartInput(ei: EditorInfo, r: Boolean) { super.onStartInput(ei, r) }

    fun superOnStartInputView(ei: EditorInfo, r: Boolean) { super.onStartInputView(ei, r) }

    /** @param finishingInput true if input is finishing too */
    fun superOnFinishInputView(finishingInput: Boolean) { super.onFinishInputView(finishingInput) }

    fun superOnFinishInput() { super.onFinishInput() }

    /** @param keyCode the key code */
    fun superOnKeyDown(keyCode: Int, ev: KeyEvent): Boolean = super.onKeyDown(keyCode, ev)

    /** @param keyCode the key code */
    fun superOnKeyUp(keyCode: Int, ev: KeyEvent): Boolean = super.onKeyUp(keyCode, ev)

    override fun dump(fileDescriptor: FileDescriptor, printWriter: PrintWriter, args: Array<String>) {
        super.dump(fileDescriptor, printWriter, args)
        val printWriterPrinter = PrintWriterPrinter(printWriter)
        printWriterPrinter.println("BlackBerryIME state :")
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            printWriterPrinter.println("  VersionCode = ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)}")
            printWriterPrinter.println("  VersionName = ${packageInfo.versionName}")
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            printWriterPrinter.println("  VersionCode = 0")
            printWriterPrinter.println("  VersionName = ")
        }
        val c0965eM6834l = keyboardSwitcher.getCurrentKeyboard()
        printWriterPrinter.println("  Keyboard mode = ${if (c0965eM6834l != null) c0965eM6834l.mId.mMode else -1}")
        val settingsValues = settingsManager.getSettingsValues()
        printWriterPrinter.println(settingsValues.dumpSettings())
    }

}
