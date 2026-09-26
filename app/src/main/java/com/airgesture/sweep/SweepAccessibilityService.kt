package com.airgesture.sweep

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 无障碍服务 —— 本项目的心脏，也是**唯一一个能同时满足以下四点的地方**：
 *   1. 拿得到相机（无障碍服务不受"后台不能用相机"限制，因此不需要前台服务）
 *   2. 能常驻
 *   3. 能派发系统级手势（`dispatchGesture`）—— 这是唯一的注入手段（见 SwipeInjector）
 *   4. 能读当前窗口的包名（`canRetrieveWindowContent`）—— 只用来判"是不是桌面"
 * 这套架构直接照搬 Google 官方 Project Gameface 安卓版（见可行性报告 10.1）。
 *
 * ============================ 关于图像通道（重要） ============================
 * v0.1 装到手机上"相机在跑但永远识别不出手"，根因在这里：
 * `MediaImageBuilder.build()` 的源码是
 *     `return new MPImage(new MediaImageContainer(mediaImage), ...)`
 * —— 它**只是把 android.media.Image 包了一层，不拷贝像素**，注释还写着
 * "passed in, to keep data integrity you shouldn't modify content in it"。
 * 而我们在 detectAsync 之后立刻 `proxy.close()`（照抄官方示例的写法），
 * MediaPipe 是异步流水线，等它真正去读像素时 Image 已经失效 → 永远拿不到手。
 *
 * 现在的默认通道是 **RGBA**：CameraX 直接出 RGBA_8888，我们在分析线程里
 * **同步拷进自己持有的 Bitmap**，再交给 MediaPipe。Bitmap 由我们持有且不被回收，
 * 所以无论 MediaPipe 什么时候读都是有效数据。
 * 零拷贝通道（IMG_MEDIA）还留着代码，但已经**没有界面开关**了 —— 万一某些机型 RGBA
 * 有 stride 问题，把 `Prefs.IMAGE_PATH` 的默认值改成 `IMG_MEDIA` 重新编译即可。
 * ============================================================================
 */
class SweepAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var executor: ExecutorService? = null
    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var detector: SweepDetector = SweepDetector()
    private val filter = OneEuro2D.forHand()

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null

    // 状态（只用于显示与诊断）
    private var lastTs = 0L
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fps = 0.0
    private var lastPublish = 0L
    private var lastDir: String? = null
    private var lastInjectInfo = ""
    private var message = "正在启动…"
    private var handPresent = false
    private var together = Double.NaN
    /** 四指伸出根数的可读描述（悬浮窗显示）。只在根数变化时重建。 */
    private var fingerInfo = "—"
    private var lastFingerInfoCount = -1
    /** 四指伸出的根数。 */
    private var extendedCount = 0
    /** 复用的关键点缓冲（每帧 30 次，别 new —— 见 onResult 里的说明）。 */
    private val xsBuf = FloatArray(21)
    private val ysBuf = FloatArray(21)
    private val lmBuf = Array(21) { DoubleArray(3) }
    private var cameraReady = false
    private var lastAspect = 4.0 / 3.0
    private var cameraImagePath = ""
    private var prevState = SweepDetector.State.IDLE

    /**
     * 最近一次读到的**屏幕旋转（度）**（0/90/180/270）。每帧刷新一次，见 [analyze]。
     *
     * 用途（v2.3.0 横屏支持）：
     *   · 方向映射的旋转补偿 —— [ScreenOrientation.angleOffsetDeg]（每帧喂判定器前用）；
     *   · 判断"是不是横屏" —— 横屏只保留上下（[SwipeInjector.perform] 里拦左右）；
     *   · 悬浮窗显示，真机验收时一张截图就能确认当前读到的是哪个方向。
     */
    @Volatile
    private var displayRotationDeg = ScreenOrientation.DEG_0

    /** 最近一帧的 `ImageInfo.rotationDegrees`（**帧旋转**，和屏幕旋转不是一回事）。仅显示用。 */
    @Volatile
    private var frameRotationDeg = 0

    /**
     * 自然竖屏下的方向角锚点（= 手机上已存的 `angle_offset_deg`，默认 90°）。
     * 服务启动时读一次；横屏时的偏移由它推出（见 [ScreenOrientation]）。
     */
    private var angleAnchorDeg = Prefs.DEFAULT_ANGLE_OFFSET.toDouble()

    /** 已经下发给 CameraX 的 targetRotation（度）；-1 = 还没设过。见 [syncTargetRotation]。 */
    private var appliedTargetRotationDeg = -1

    // ---- 三路诊断计数（用来区分"管道断了"和"阈值太高"）----
    /** 相机交来的总帧数（含被跳过的）。 */
    private var framesSeen = 0L
    /** 真正送去推理的帧数。 */
    private var framesIn = 0L
    private var resultsOut = 0L
    private var handsSeen = 0L
    private var delegateInfo = "未加载"
    private var strideInfo = ""

    // ---- 复用的 Bitmap 池：避免每帧分配 1.2MB 造成 GC 抖动 ----
    private val frameBitmaps = arrayOfNulls<Bitmap>(2)
    private var frameBitmapIdx = 0

    /**
     * 降载：相机全程取流，但只对每 N 帧做推理（见 [ANALYSE_EVERY_NTH_FRAME]）。
     * 计数器本身在纯逻辑 [FrameSkipper] 里并有单测覆盖。
     */
    private val skipper = FrameSkipper(ANALYSE_EVERY_NTH_FRAME)

    /**
     * 因"不在相机作用域内"（桌面 / 不在白名单 / **息屏**）而主动关掉相机的标志。
     * 与 idle-downshift 的待机区别：这个跟"有没有手"无关，只看当前前台 App 与屏幕状态。
     */
    private var cameraOff = false
    private var cameraOffReason: String? = null

    /**
     * 屏幕是否亮着。
     *
     * ★ 息屏必须关相机，理由有二：
     *   1. 用户明确期望"灭屏后软件自动停止调用摄像头"；
     *   2. 隔夜功耗的大头就在这里 —— 实测"应用内、相机取流"约 50% 单核，
     *      若整夜息屏还开着相机，8 小时耗电会非常难看。
     * 用广播而不是轮询 `PowerManager.isInteractive`：广播是**即时**的，
     * 而看门狗是 10 秒一轮 —— 屏幕一黑立刻松手，不用等下一轮。
     */
    @Volatile
    private var screenOn = true

    private var screenReceiver: android.content.BroadcastReceiver? = null

    /**
     * 上一次通过无障碍事件看到的前台包名。
     *
     * 用途：窗口事件会**重复上报同一个包**，用它去重，避免同一个前台被反复判定。
     * 注意它只是"去重用的缓存"，真正的判定仍以 `SwipeInjector.foregroundPackage()`
     * 为准 —— 那条路径在取不到节点时会返回 null，而我们的 CameraScope 对 null 是放行的。
     */
    private var lastForegroundPkg: String? = null

    /**
     * 前台是不是桌面。**每帧都要看**，所以刻意用一个轻量布尔缓存，
     * 由窗口事件（[onAccessibilityEvent]）与作用域轮询（[onScopeTick]）更新，
     * 而不是在每帧里调 `rootInActiveWindow`（那是跨进程取节点树，不能放在热路径上）。
     */
    @Volatile
    private var onLauncher = false

    /**
     * 最近一次从无障碍窗口事件里看到的**可信前台包名**。
     *
     * ★ 为什么需要它（本会话排查出来的关键）：
     *   `SwipeInjector.foregroundPackage()` 走的是 `rootInActiveWindow?.packageName`。
     *   在华为桌面上这个调用**返回 null** → 前台包名变成 null →
     *   白名单判定里 null 是"放行"（为了避免失灵）→ **桌面上被判成"允许开相机"**，
     *   于是相机不会随"退出白名单 App"立刻关掉，只能等接近光窗口过期（最长 8 秒）。
     *   用户实测到的"回桌面后有 5~6 秒降帧"、"要等一段才断"就是这个。
     *
     *   而窗口事件（TYPE_WINDOW_STATE_CHANGED）**自带 packageName**，
     *   不需要读节点树，所以在桌面/其它 App 上都拿得到。
     *   把它缓存下来，用作判定时"权威但可能缺失"的前台信息的**回退来源**。
     */
    @Volatile
    private var lastEventPkg: String? = null

    /** [lastEventPkg] 的时刻，用来判断这条"事件包名"是否还新鲜。 */
    private var lastEventPkgAtMs = 0L

    /**
     * 事件包名的新鲜期。超过它就不再采信，回退到 `rootInActiveWindow`。
     *
     * 为什么不无限期采信：事件包名只在**刚切换**那一刻可靠
     * （全屏判定也挡不住"输入法以全屏方式弹出"这类少数情况）。
     * 给 5 秒窗口：足够覆盖"按 HOME 后立刻判出桌面"这个真实场景，
     * 又不会让一条陈旧的事件长期压过权威来源。
     */
    private val lastEventFreshMs = 5_000L

    /** 相机作用域判定的节流时间戳与最小间隔（见 onScopeTick 的说明）。 */
    private var lastScopeTickMs = 0L

    /**
     * 接近光触发（用户提出的设计）。
     *
     * `armed` = 窗口开着，相机可以连；过期后必须再贴近一次。
     * `proximityValue` / `proximityMax` 纯粹为诊断 —— 悬浮窗要能显示
     * "传感器在哪、贴近时读数多少"，否则用户不知道往哪贴。
     */
    @Volatile
    private var proximityArmed = false
    private var proximityArmedAtMs = 0L
    private var proximitySensor: android.hardware.Sensor? = null
    private var proximityListener: android.hardware.SensorEventListener? = null
    @Volatile
    private var proximityValue = Float.NaN
    private var proximityMaxRange = 5f
    /** 累计触发次数（诊断：能看到"贴近"到底有没有被识别到）。 */
    private var proximityHits = 0L

    /**
     * 最近几次手势**实际注入**的方向（可读中文，新的在后）。
     *
     * 记录的是 `ScreenOrientation` 极性映射之后的值 —— 也就是"屏幕上真正会往哪滑"，
     * 这正是判断方向对错要看的东西。横屏下被判成左右（不注入）的会写成"忽略左/右"。
     * 上限见 [SweepBus.Status.GESTURE_HISTORY_MAX]。
     */
    private val gestureHistory = ArrayDeque<String>()

    companion object {
        private const val TAG = "SweepService"
        private const val DIAG = "SweepDiag"
        private const val MODEL_ASSET = "hand_landmarker.task"
        private const val BITMAP_POOL = 2

        /** 两次相机作用域判定之间至少隔这么久，避免窗口事件密集时反复解绑/重绑。 */
        private const val SCOPE_TICK_MIN_INTERVAL_MS = 250L

        /**
         * 兜底作用域检查的周期（**不是**主判据）。
         *
         * ★ v1.0.0 的设计：**事件驱动为主，定时器只兜底**。
         *   用户质疑得对："判定退出白名单应用不是应用退出那一刻你能检测到吗，
         *   你还用一直四百毫秒去检测在不在白名单应用里面吗" ——
         *   窗口切换时系统本来就会发无障碍事件（本服务的配置里一直注册着
         *   `typeWindowStateChanged`），没必要自己高频去问系统"我在哪个 App"。
         *
         *   所以主路径是 [onAccessibilityEvent]：窗口一变立刻判定（通常 <100ms）。
         *   这个 1 秒定时器只是**安全网** —— 万一某个 OEM 不投窗口事件
         *   （本项目在荣耀/华为上都见过类似限制），也不至于完全失效。
         *
         *   v0.17.16 曾把它收紧到 400ms；那等于每秒 2.5 次
         *   `rootInActiveWindow` IPC，是没必要的负担，已回退。
         */
        private const val T_SCOPE_TICK_MS = 1_000L

        // ---------------------------------------------------------------- 空闲降频
        /*
         * 背景（真机 v0.16.1 实测，数据见 docs 里的功耗记录）：
         * 手不在画面里时，本进程持续占用 ~100% 单核 —— 每帧推理约 21%、ART GC 约 21%、
         * CameraX + 相机 HAL 约 15%。手势一天用不了几次，却 24 小时付这个钱。
         *
         * 策略（用户确认的"自适应分档"）：
         *   ACTIVE（连续取流）--连续 T_IDLE_DOWN_MS 没见到手--> STANDBY
         *   STANDBY = 解绑相机 -> 睡 sleep -> 醒来 T_BURST_MS 探一次手 -> 没探到就继续睡
         *   探到手的瞬间立刻回到 ACTIVE（连续取流）
         *   睡的时长按"距上次在 ACTIVE 见到手"的间隔自适应（见 T_TIER1_AFTER_IDLE_MS）：
         *     刚停手 30 秒内 -> 密集档（响应快）；之后 -> 稀疏档（省电优先）。
         *
         * 为什么选"解绑相机"而不是"降分辨率"：线程级实测显示大头是**每帧推理 + 随之而
         * 来的 GC**，不是单帧像素量；降分辨率救不了这部分，只有"不取流"才真的省。
         *
         * ★ 判定链路为什么不会被这时序改坏（重要，改动前必读）：
         *   SweepDetector 判"手丢了"用的是 `t - lastSeen > lostMs`（300ms），而 lastSeen
         *   **只在真正喂帧时前进**。睡眠期间没有 update() 调用，所以状态不会因为"帧间隔
         *   变长"而误重置；只会在下一次真正看到帧时才发现手不见了。两种模式下帧间隔都
         *   远小于 300ms，所以判定行为与 v0.16.1 完全一致。
         */
        /**
         * 每多少帧做一次推理（降载的核心参数）。
         *
         * 1 = 每帧推理（v0.16.1 行为，空闲约 97% 单核）
         * 4 = 每 4 帧推理一次 -> 推理与随之而来的 GC 开销降到约 1/4，
         *     最坏响应 = 4 x 帧间隔（30fps 时约 133ms，满足用户要求的 ≤0.3s）
         *
         * ★ 判定链路对此安全：`SweepDetector` 用 `t - lastSeen > lostMs`(300ms) 判断
         *   手丢失，而 lastSeen 只在**真正喂帧**时前进；帧间隔从 33ms 变成 133ms
         *   仍远小于 300ms，所以状态机不会因此误重置。
         */
        const val ANALYSE_EVERY_NTH_FRAME = 4

        /** 连续多久没见到手就进入待机（解绑相机）。 */
        private const val T_IDLE_DOWN_MS = 5_000L
        /**
         * 自适应分档（用户确认）：停手后先密集探测一阵子（"刚用完，可能马上再用"），
         * 超过这个时长就切成稀疏探测（长时间闲置，优先省电）。
         *
         * 为什么需要分档：实测空闲是 ~100% 单核，而"探一次手"必须跑完整推理，
         * 所以探得越勤越费电 —— 响应速度与省电在数学上互斥。分档是按用户真实
         * 用法（一天用不了几次）在两者之间取的一个折中。
         */
        private const val T_TIER1_AFTER_IDLE_MS = 30_000L
        /** 密集档：睡多久。平均响应 = (睡 + 探)/2 = (600+1200)/2 = 900ms < 1s（已单测钉住）。 */
        private const val T_SLEEP_TIER1_MS = 600L
        /**
         * 稀疏档：睡多久（长时间闲置）。
         *
         * 30 秒是**被单测反推出来的下限**：再短就达不到"占空比下降一个量级"
         * （14 秒只有 7.9%，是密集档 60% 的 1/7.6，不够）。代价是平均响应 ~15.6 秒，
         * 但这一档只在"停手超过 30 秒"之后才生效，而那种时刻本来就不该期待抬手即用。
         */
        private const val T_SLEEP_TIER2_MS = 30_000L
        /** 待机时醒来探多久（要够相机重新绑定并出帧，实测约 400~600ms 起步）。 */
        private const val T_BURST_MS = 1_200L
        /**
         * 空闲降频总开关。
         *
         * ★ **已关闭（false）** —— 这条路线被真机否决，别再打开：
         *   它能做到空闲 CPU ~12%，但代价是"伸手后最坏等 30 秒"，用户明确不接受
         *   （要求 ≤0.3s），而且 v0.17.0 的实现在"睡醒后探测"这一步有 bug，
         *   导致相机永不重绑、手势彻底失效。
         *
         *   之所以保留代码而不是删掉：它是"我们试过什么、为什么放弃"的凭据，
         *   而且 [IdleDownshift] 那套决策函数有完整单测。要重新启用必须先解决
         *   "重绑延迟 vs 探测间隔"这个根本矛盾。
         *
         * 现在生效的降载手段是 [ANALYSE_EVERY_NTH_FRAME]（相机常绑 + 跳帧推理）。
         */
        const val ENABLE_IDLE_DOWNSHIFT = false

        /**
         * 帧陈旧看门狗。
         *
         * 为什么必须有：`bindToLifecycle(...)` 只是**排队**一个打开相机的请求，
         * 它返回时不代表相机真的出帧了 —— 相机被别的应用占用、HAL 出错等情况下，
         * 它可能静默失败：`cameraReady` 是 true、一帧都不来，而且**再没有任何人**
         * 会去调用 `onFrameModeTick`（那个函数只在帧回调里跑）。
         * 结果是服务**永久卡死**，悬浮窗还显示"就绪"。
         *
         * 所以用一个独立的定时器（不依赖帧）来检查"该出帧却没出"，并按当前模式重试。
         * 间隔取得比探测周期长，避免和正常的待机睡眠互相打架。
         */
        private const val T_WATCHDOG_MS = 10_000L
        /** 连续这么久没有任何帧回调，就认为相机链路断了。 */
        private const val T_FRAME_STALE_MS = 4_000L

        /** 当前存活的实例，供 MainActivity 直接改参数（同一进程）。 */
        @Volatile
        var instance: SweepAccessibilityService? = null
            private set
    }

    /** 取流模式：ACTIVE = 连续取流（v0.16.1 原有行为）；LOW = 空闲待机（解绑相机 + 定时唤醒）。 */
    enum class Mode { ACTIVE, LOW }

    /** 当前模式。 */
    var mode = Mode.ACTIVE
        private set

    private var lastHandSeenMs = 0L
    private var lastHandPresentMs = 0L
    /** 最后一帧回调（onResult）到达的时刻，供帧陈旧看门狗使用。 */
    private var lastFrameMs = 0L
    /** 看门狗触发重新绑定的次数（诊断）。 */
    private var watchdogRearms = 0L
    /** 当前档位实际睡的时长（诊断/自愈兜底用）。 */
    private var sleepNowMs = T_SLEEP_TIER1_MS
    /** 诊断计数：进入待机的次数 / 待机探测窗口内取到的帧数。
     *  注意 [burstFrames] 只在**探测窗口**内增长 —— 睡眠期间相机是解绑的，一帧都没有。
     *  所以"待机时它不怎么涨"正是正常现象，不代表相机坏了。 */
    private var standbyEntries = 0L
    private var burstFrames = 0L
    /**
     * 绑定代次。每次"解绑"都自增，用来作废还在飞行中的异步绑定。
     *
     * 为什么必须有：`provider.bindToLifecycle(...)` 是异步的（要等相机 HAL 打开，
     * 实测几百 ms）。如果这期间 SleepTask 或 enterStandby 先跑了，那次绑定会
     * **在解绑之后才落地**，把相机偷偷留在打开状态 —— 恰恰是我们要消灭的耗电状态。
     * 绑定回调里比对代次，过期就立刻解绑（续上自增，让更早的也一并作废）。
     *
     * 只在主线程读写（bindToLifecycle 与这些回调都在 mainExecutor 上）。
     */
    private var bindGeneration = 0L

    // ------------------------------------------------------------------ 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        landmarker = null
        cameraReady = false
        framesIn = 0
        resultsOut = 0
        handsSeen = 0
        // 空闲降频状态复位：启动瞬间按"刚刚还在用手势"处理，避免模型/首帧还没就绪
        // 就直接掉进待机（那样会看起来像"服务没起来"）。
        mode = Mode.ACTIVE
        lastHandSeenMs = SystemClock.uptimeMillis()
        lastHandPresentMs = 0L
        standbyEntries = 0L
        burstFrames = 0L
        bindGeneration = 0L
        // 看门狗的宽限起点：从"服务起来的那一刻"开始算，这样它不会在模型/首帧
        // 还没就绪时就误判为"无帧"。真正的帧到达后会被 onResult 覆盖。
        lastFrameMs = SystemClock.uptimeMillis()
        watchdogRearms = 0L
        // 同样做一次参数迁移：用户可能先开了无障碍服务、后打开 App 设置页
        Prefs.migrateOnce(this)
        detector = Prefs.buildDetector(this)
        // 方向角锚点 = 自然竖屏（ROTATION_0）下实测有效的那个值。横屏时的偏移由它 + 屏幕旋转推出，
        // 所以**竖屏行为与旧版本逐位相同**（见 ScreenOrientation 顶部推导）。
        angleAnchorDeg = Prefs.angleOffset(this).toDouble()
        filter.reset()
        message = "初始化…"

        overlay = OverlayController(this).also { it.show() }
        executor = Executors.newSingleThreadExecutor()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        createLandmarker()
        startCamera()
        startWatchdog()
        // 息屏/亮屏广播：屏幕一黑立刻松手，不等下一个看门狗周期
        screenOn = isInteractiveNow()
        registerScreenReceiver()
        // 接近光触发（用户设计）：手贴近传感器才连相机
        registerProximityListener()
        // 快速作用域判定（1 秒）：保证"返回桌面后相机尽快解绑"
        main.removeCallbacks(scopeTick)
        main.postDelayed(scopeTick, T_SCOPE_TICK_MS)
    }

    private fun isInteractiveNow(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    } catch (_: Exception) {
        true
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                when (i?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        // 息屏时把接近光窗口也清掉：亮屏后必须**重新贴近一次**才连相机，
                        // 否则"息屏前开的窗口"会在亮屏后自动把相机拉起来。
                        proximityArmed = false
                        Log.i(DIAG, "screen OFF -> release camera")
                        onScopeTick()
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        Log.i(DIAG, "screen ON -> consider camera")
                        onScopeTick()
                    }
                    android.content.Intent.ACTION_USER_PRESENT -> {
                        // 解锁也算"人回来了"，确保能恢复
                        screenOn = isInteractiveNow()
                        onScopeTick()
                    }
                }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(r, f)
            screenReceiver = r
        } catch (e: Exception) {
            Log.w(TAG, "注册屏幕广播失败", e)
        }
    }

    private fun unregisterScreenReceiver() {
        val r = screenReceiver ?: return
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
        }
        screenReceiver = null
    }

    // ------------------------------------------------------------ 接近光触发

    /**
     * 注册接近光监听。
     *
     * 只监听 `android.sensor.proximity`（标准传感器，任何 App 可用）。
     * 这台机上还有厂商专用的 `finger_sense` / `full_screen_aod`，但它们是
     * 自有 HAL 类型（`qti.sensor.*`），第三方 App 用不了，试也是白试。
     *
     * 用 `SENSOR_DELAY_NORMAL`：接近光是 on-change 型传感器，没有"采样率"概念，
     * 只在状态变化时回调，所以这一步的耗电可以忽略 —— 这正是它适合当开关的原因。
     */
    private fun registerProximityListener() {
        if (proximityListener != null) return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
            if (s == null) {
                Log.i(DIAG, "no proximity sensor on this device")
                return
            }
            proximitySensor = s
            proximityMaxRange = s.maximumRange
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent?) {
                    val v = e?.values?.firstOrNull() ?: return
                    proximityValue = v
                    val near = ProximityTrigger.isNear(v, proximityMaxRange)
                    if (near) {
                        proximityHits++
                        armProximityWindow()
                    }
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sm.registerListener(l, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            proximityListener = l
            Log.i(DIAG, "proximity listener on, maxRange=${s.maximumRange}")
        } catch (e: Exception) {
            Log.w(TAG, "注册接近光失败", e)
        }
    }

    private fun unregisterProximityListener() {
        val l = proximityListener ?: return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            sm.unregisterListener(l)
        } catch (_: Exception) {
        }
        proximityListener = null
    }

    /** 开窗（或续期）。 */
    private fun armProximityWindow() {
        val was = proximityArmed
        proximityArmed = true
        proximityArmedAtMs = SystemClock.uptimeMillis()
        if (!was) {
            Log.i(DIAG, "proximity ARMED #$proximityHits value=$proximityValue")
            // 立刻重新评估：相机会在这一刻连上
            main.post { onScopeTick() }
        }
    }

    override fun onDestroy() {
        // 先撤掉待机/探测回调，否则服务正在销毁时它们还可能把相机拉起来
        main.removeCallbacks(probeTask)
        main.removeCallbacks(endBurstTask)
        main.removeCallbacks(resumeRunnable)
        main.removeCallbacks(frameWatchdog)
        main.removeCallbacks(scopeTick)
        unregisterScreenReceiver()
        unregisterProximityListener()
        stopCamera()
        if (instance === this) instance = null
        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null
        executor?.shutdown()
        executor = null
        overlay?.hide()
        overlay = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        message = "已停止"
        publish(force = true)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // ★ 这个回调以前是空的 —— 但服务的配置里一直注册着 `typeWindowStateChanged`
        //   （见 res/xml/accessibility_service_config.xml），也就是说系统本来就在
        //   把"前台窗口换了"告诉我们，我们只是没用。
        //
        //   现在用它做**事件驱动的相机作用域判定**，取代"每 10 秒轮询一次"：
        //   用户从 App 返回桌面时，相机应该立刻解绑，而不是最多再空转 10 秒。
        //   （用户反馈："处于激活状态的时候返回桌面…这段时间手机会掉帧" ——
        //     相机在桌面上继续取流+推理会跟桌面动画抢资源，识别越慢掉帧越明显。）
        //
        //   注意取包名用的是 **event 自带的 packageName**，不调 rootInActiveWindow ——
        //   后者每次要跨进程取节点树，在窗口切换频繁时开销很可观。
        if (event?.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // ★ 只把**全屏**窗口事件当作"前台 App 换了"的证据。
        //
        //   为什么需要这个闸门：窗口事件里的包名**不一定是前台 App** ——
        //   输入法弹出、系统小窗、通知栏都会发 TYPE_WINDOW_STATE_CHANGED。
        //   v0.17.14 就是因为无条件相信事件包名，在白名单 App 里被输入法事件
        //   误判成"不在白名单"而把相机关掉（真机症状：进 App 约 1 秒就断）。
        //
        //   而 App 切换/返回桌面产生的是**全屏**窗口，输入法/小窗不是 ——
        //   用它当判据，既拿到事件驱动的即时性，又不会被输入法误伤。
        //
        //   拿不到 isFullScreen 信息时（少数 OEM 不填）保守地**不采信**，
        //   交给 1 秒兜底轮询，宁可慢一点也不误断。
        val looksLikeAppSwitch = event.isFullScreen
        if (looksLikeAppSwitch) {
            lastEventPkg = pkg
            lastEventPkgAtMs = SystemClock.uptimeMillis()
        }
        if (pkg == lastForegroundPkg) return          // 同一个包重复上报，忽略
        lastForegroundPkg = pkg
        // 顺手更新"是否在桌面"这个每帧都要读的标志：当前这个事件就带着包名，
        // 不需要再跨进程去取节点树。
        onLauncher = (pkg == SwipeInjector.homePackage(this))
        onScopeTick()
    }

    override fun onInterrupt() {}

    /** 设置变更后调用（MainActivity 通过同一进程直接调用）。 */
    fun reloadSettings() {
        detector = Prefs.buildDetector(this)
        filter.reset()
        // 图像通道变了就必须重建 ImageAnalysis ——
        // RGBA 的帧不能喂给零拷贝(YUV)通道，反之亦然。所以干脆重启相机。
        if (cameraReady && Prefs.imagePath(this) != cameraImagePath) {
            message = "图像通道变了，正在重启相机…"
            publish(force = true)
            stopCamera()
            startCamera()
            return
        }
        message = "参数已更新"
        publish(force = true)
    }

    // ------------------------------------------------------------------ MediaPipe

    private fun createLandmarker() {
        val ex = executor ?: return
        ex.execute {
            var made = tryCreate(Delegate.GPU)
            var info = "GPU"
            if (made == null) {
                made = tryCreate(Delegate.CPU)
                info = "CPU"
            }
            val created = made
            val finalInfo = if (created == null) "加载失败" else info
            main.post {
                landmarker = created
                delegateInfo = finalInfo
                Log.i(DIAG, "HandLandmarker delegate=$finalInfo loaded=${created != null}")
                message = when {
                    created == null -> "模型加载失败（logcat 看 $TAG）"
                    !cameraReady -> "模型就绪($finalInfo)，等待相机…"
                    else -> "运行中"
                }
                publish(force = true)
            }
        }
    }

    private fun tryCreate(delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.3f)
            .setMinHandPresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .setResultListener { result: HandLandmarkerResult, _: MPImage -> onResult(result) }
            .build()
        HandLandmarker.createFromOptions(this, options)
    } catch (e: Throwable) {
        // GPU delegate 在个别机型/驱动上起不来，必须能回退 CPU（可行性报告环节 1）
        Log.w(TAG, "HandLandmarker 用 $delegate 创建失败: ${e.message}")
        null
    }

    private fun onResult(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis() / 1000.0
        lastFrameMs = SystemClock.uptimeMillis()
        countFps()
        resultsOut++

        // ★★ 桌面（启动器）上**从源头不进判定**（用户要求，取代"到了弹提示才判断"）。
        //
        //   为什么要在这一层拦：桌面本来就不注入手势（见 SwipeInjector 的桌面判定），
        //   所以为它跑完整判定链是纯浪费 —— 并拢度、指尖质心、四指伸展、OneEuro 滤波、
        //   状态机推进，全是白算。用户的原话是"能不能让隔空手势从底层上就不在桌面上触发，
        //   这样能省一个计算步骤"。
        //
        //   放在这里而不是放在弹提示那里，还有一个副作用是对的：状态机**不会**被桌面上的
        //   动作推进，所以回到 App 时不会残留一个"已经锁定姿势"的中间态。
        //
        //   注意：`onLauncher` 由窗口事件/看门狗刷新（每帧读它只是一次 volatile 读，很便宜），
        //   而它在桌面上通常也是 false —— 因为接近光触发模式下相机在桌面时根本是关的，
        //   走不到这里。真正会走到的是"激活状态中返回桌面"这短暂的一段。
        if (onLauncher) {
            handPresent = false
            onFrameModeTick(false)
            publish()
            return
        }

        val hands = result.landmarks()
        if (hands.isEmpty()) {
            handPresent = false
            together = Double.NaN
            fingerInfo = "—"
            extendedCount = 0
            detector.update(now, false, 0.0, 0.0, 1.0, 0)
            // ★ 空闲降频：每帧结果都通知一次"本帧有没有手"。
            //   放在判定之后、publish 之前 —— 模式切换只影响相机生命周期，
            //   不参与任何判定，所以对跨语言向量/单测结果零影响。
            onFrameModeTick(false)
            publish()
            return
        }

        val hand = hands[0]
        if (hand.size < 21) {
            detector.update(now, false, 0.0, 0.0, 1.0, 0)
            onFrameModeTick(false)
            return
        }
        handsSeen++
        onFrameModeTick(true)
        if (handsSeen == 1L) Log.i(DIAG, "first hand detected, landmarks=${hand.size}")

        // ★ 缓冲区**复用**（不要每帧 new）：这一段每秒跑 30 次，
        //   每帧分配 2 个 FloatArray + 21 个 DoubleArray 会白白喂 GC。
        //   这些缓冲只在本次回调内同步使用，不会被异步引用，所以复用是安全的。
        for (i in 0 until 21) {
            xsBuf[i] = hand[i].x()
            ysBuf[i] = hand[i].y()
            // z 也要取：手指伸展判定与 Windows 版一样用 (x, y, z) 算关节夹角，
            // 只给 x/y 的话角度会和那边对不上。
            lmBuf[i][0] = hand[i].x().toDouble()
            lmBuf[i][1] = hand[i].y().toDouble()
            lmBuf[i][2] = hand[i].z().toDouble()
        }

        handPresent = true
        together = HandFeatures.togetherRatio(xsBuf, ysBuf)
        val raw = HandFeatures.ftipCentroid(xsBuf, ysBuf, lastAspect)

        // ★ "手指根数"判据（用户实测：一根手指也能触发滑动）：
        //   并拢度只量"四个指尖靠得近不近"，不问手指有没有伸出来，单指时会被骗过。
        //   这里逐根判伸展，把根数交给判定器当"姿势闸门"。
        val extended = HandFeatures.extendedCount(lmBuf)
        extendedCount = extended
        // 悬浮窗那行文字只在根数变化时重建 —— 每帧拼字符串同样是无谓的分配
        if (extended != lastFingerInfoCount) {
            fingerInfo = HandFeatures.describeFingers(HandFeatures.fingerStates(lmBuf))
            lastFingerInfoCount = extended
        }

        // ★ 镜像必须在**坐标层面**做（把 x 取反），不能只在最后翻方向标签。
        //   原因（用户实测逼出来的）：前置摄像头画面本身是左右镜像的，再叠上传感器旋转，
        //   "镜像 ∘ 旋转"是一个**反射**，而反射不是旋转 —— 单靠一个方向角偏移永远修不好：
        //   把上下修对了左右必然反过来。先在坐标上镜像一次，剩下的才是纯旋转，
        //   于是一个偏移量就能把四个方向同时对齐。
        val cx = if (Prefs.mirrorX(this)) -raw[0] else raw[0]

        // One Euro 滤波：手慢速微调时强去抖，快速扫动时几乎不延迟
        val (fx, fy) = filter.apply(cx, raw[1], now)

        // ★ 方向映射的**旋转补偿**：每次喂判定器之前按当前屏幕旋转刷新一次偏移量
        //   （横竖屏切换立刻生效，不需要重启服务）。推导见 ScreenOrientation：
        //   竖屏 offset == 锚点（手机上已存的值，默认 90°），横屏 = 锚点 ± 90°。
        //   横屏的两个方向本身相差 180°，所以这里必须是"算出来的"、不能写死。
        detector.angleOffsetDeg =
            ScreenOrientation.angleOffsetDeg(displayRotationDeg, angleAnchorDeg)

        val dir = detector.update(now, true, fx, fy, together, extended)
        val st = detector.state
        if (dir != null) {
            val outDir = ScreenOrientation.applyPolarity(dir, Prefs.invertVertical(this))
            lastDir = outDir
            // ★ 横屏只处理上下：左右扫**识别出来了但不注入**（真正拦在 SwipeInjector.perform）。
            //   历史里用"忽略×"标出来 —— 悬浮窗上一眼能区分"识别成左右但没注入"和"根本没识别到"，
            //   真机验收横屏时就靠这个读数。
            val landscape = ScreenOrientation.isLandscape(displayRotationDeg)
            val allowed = ScreenOrientation.allowsDirection(outDir, landscape)
            // 记进累积历史：这是"方向对不对"唯一能靠一张截图判定的读数
            gestureHistory.addLast(
                if (allowed) SweepBus.directionCn(outDir) else "忽略${SweepBus.directionCn(outDir)}"
            )
            while (gestureHistory.size > SweepBus.Status.GESTURE_HISTORY_MAX) {
                gestureHistory.removeFirst()
            }
            val disp = detector.dbgDisp
            val angle = detector.dbgAngle
            Log.i(DIAG, "SWEEP raw=$dir out=$outDir disp=$disp angle=$angle together=$together " +
                "fingers=$extended/$4 screen=${if (landscape) "landscape" else "portrait"}" +
                "${displayRotationDeg}deg allowed=$allowed")
            main.post {
                // 双保险：相机那一层已经拦了签名，但"注入"才是真正产生效果的那一步，
                // 这里再查一次，杜绝任何绕过自检的路径。
                if (!SignatureGuard.isTrusted(this)) {
                    lastInjectInfo = "签名校验未通过：手势已禁用"
                    publish(force = true)
                    return@post
                }
                lastInjectInfo = SwipeInjector.perform(
                    this, outDir, Prefs.swipeMs(this),
                    Prefs.swipeFracVertical(this), Prefs.swipeFracHorizontal(this),
                )
                publish(force = true)
            }
        } else if (st != prevState && st == SweepDetector.State.WATCH) {
            // ★ SETTLE -> WATCH = "动作1（摆好姿势）完成、姿势已锁定"。用户的流程是：
            //     动作1（摆好姿势）→ 弹窗「请滑动」→ 动作2（扫动）→ 滑动 → 冷静期 → 新一轮
            //   这条提示是**动作1做完后的出发信号**，每一轮都会出现。
            //   ★ 一闪即隐（约 0.9 秒自动消失）：用户明确不要"一直挂着"的那种。
            //
            //   ★★ 桌面上不弹（用户要求："桌面我们是不调度手势的所以在桌面『请滑动』不用弹出"）。
            //      桌面本来就不注入手势（见 SwipeInjector.perform 的桌面判定），
            //      所以那个出发信号在桌面上没有意义，弹了只是视觉干扰。
            if (!SwipeInjector.isOnLauncher(this)) {
                // ★ 横屏只认上下，所以提示语也换成"请上下滑动" —— 让用户在扫之前就知道
                //   横屏下左右扫不会有反应（用户要求横屏只处理上下）。
                overlay?.showHint(
                    if (ScreenOrientation.isLandscape(displayRotationDeg)) "请上下滑动" else "请滑动",
                    900,
                )
            }
            // 姿势刚锁定：把**滤波器状态**也一并重置，让这一轮从干净状态开始量
            // （消除上一轮遗留的速度/位置平滑记忆）
            filter.reset()
        }
        prevState = st
        publish()
    }

    /**
     * 把**屏幕旋转**同步给 CameraX（`ImageAnalysis.setTargetRotation`）。
     *
     * ★ 它管的是"模型看到的画面正不正"，**不管方向映射** —— 映射由 [ScreenOrientation]
     *   用屏幕旋转自己推（那边刻意不依赖这里的设置是否生效）。
     *
     * 为什么必须有：
     *   · `setRotationDegrees` 只影响送进模型的图；侧着喂进去，掌/手检测的识别率会打折；
     *   · CameraX **不会自动跟随**屏幕旋转（官方文档要求应用自己调，且"不需要重建 use case"），
     *     而本项目 v2.3.0 之前一次都没设过 —— 默认值停在 use case 创建那一刻的方向。
     *
     * 只在旋转真的变了时才设置（`setTargetRotation` 会让 CameraX 重新计算输出旋转，
     * 没必要每帧调）。分析线程调用 → 投到主线程执行。
     */
    private fun syncTargetRotation(displayDeg: Int) {
        if (displayDeg == appliedTargetRotationDeg) return
        appliedTargetRotationDeg = displayDeg
        val surfaceRotation = surfaceRotationOf(displayDeg)
        main.post {
            try {
                imageAnalysis?.setTargetRotation(surfaceRotation)
            } catch (e: Exception) {
                Log.w(TAG, "setTargetRotation 失败: ${e.message}")
            }
        }
    }

    /** 屏幕旋转**度数** → `Surface.ROTATION_*` 枚举值（CameraX 要的是后者）。 */
    private fun surfaceRotationOf(deg: Int): Int = when (deg) {
        ScreenOrientation.DEG_90 -> android.view.Surface.ROTATION_90
        ScreenOrientation.DEG_180 -> android.view.Surface.ROTATION_180
        ScreenOrientation.DEG_270 -> android.view.Surface.ROTATION_270
        else -> android.view.Surface.ROTATION_0
    }

    // ------------------------------------------------------------------ 相机

    private fun startCamera() = startCameraImpl(standby = false)

    /**
     * 启动相机取流。
     *
     * [standby] = true 表示这是"空闲待机时醒来探测"的那一次：此时 `mode` 已经是 LOW，
     * 所以**不覆盖** mode（否则探测刚开始就把自己标回 ACTIVE 了）。
     * 其余行为与正常启动完全一致 —— 同一套分辨率/输出格式/分析器，保证"探测帧"与
     * "正常帧"喂给判定器的数据一模一样，判定结果不会因为模式不同而改变。
     */
    private fun startCameraImpl(standby: Boolean) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message = "缺少相机权限：请回到 App 里授权"
            publish(force = true)
            return
        }
        if (!standby) mode = Mode.ACTIVE
        cameraImagePath = Prefs.imagePath(this)
        val generation = ++bindGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // 过期绑定守卫：这期间如果发生过解绑（代次已前进），绑定必须作废，
            // 否则相机会在"已经进入待机"之后被重新打开，悄悄跑一整夜。
            // 注意要续上 ++bindGeneration，让更早的飞行中绑定也一并作废。
            if (generation != bindGeneration) {
                Log.i(DIAG, "stale bind dropped standby=$standby gen=$generation cur=$bindGeneration")
                publish(force = true)
                return@addListener
            }
            try {
                val provider = future.get()
                cameraProvider = provider

                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    // 只处理最新帧，避免积压导致延迟滚雪球（Gameface 也是这么做的）
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 输出格式必须与图像通道匹配：RGBA 走 Bitmap 拷贝，YUV 走零拷贝
                    .setOutputImageFormat(
                        if (cameraImagePath == Prefs.IMG_RGBA) {
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        } else {
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                        }
                    )
                    .setResolutionSelector(resolution)
                    .build()
                analysis.setAnalyzer(executor ?: return@addListener) { proxy -> analyze(proxy) }
                // ★ v2.3.0：新 use case 必须带上**当前**屏幕旋转。CameraX 的 targetRotation
                //   默认值是"创建 use case 那一刻"的屏幕方向，而且**不会自己跟随旋转**
                //   （官方文档明确要求应用在旋转时调 setTargetRotation，并说明"不需要重建 use case"）。
                //   本项目在 v2.3.0 之前一次都没设过 → 横屏时送进模型的画面是侧着的（识别率打折）。
                //   注意：它**只影响"模型看到的画面是否正立"**，方向映射不依赖它（见 ScreenOrientation）。
                val dispDeg = SwipeInjector.displayRotationDegrees(this)
                displayRotationDeg = dispDeg
                appliedTargetRotationDeg = dispDeg
                analysis.setTargetRotation(surfaceRotationOf(dispDeg))
                imageAnalysis = analysis

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                // bindToLifecycle 是异步的（要等 HAL 打开）。上面那次代次检查是在
                // getInstance 的 future 完成时做的，而 bind 本身还要再等一会儿 ——
                // 所以这里必须**再查一次**：若这期间已经解绑，立刻撤销这次绑定。
                if (generation != bindGeneration) {
                    Log.i(DIAG, "bind landed stale, unbinding gen=$generation cur=$bindGeneration")
                    try {
                        provider.unbindAll()
                    } catch (_: Exception) {
                    }
                    publish(force = true)
                    return@addListener
                }
                cameraReady = true
                if (standby) {
                    // 探测窗口：T_BURST_MS 后由 EndBurstTask 解绑（若这一窗探到了手，
                    // onResult 会把 mode 切回 ACTIVE，那个回调就变成空操作）
                    main.removeCallbacks(endBurstTask)
                    main.postDelayed(endBurstTask, T_BURST_MS)
                } else {
                    message = "运行中"
                }
                Log.i(DIAG, "camera bound standby=$standby mode=$mode gen=$generation imagePath=${Prefs.imagePath(this)}")
            } catch (e: Throwable) {
                message = "相机启动失败：${e.message}"
                Log.e(TAG, "相机启动失败", e)
            }
            publish(force = true)
        }, mainExecutor)
    }

    private fun stopCamera() {
        // 推进代次：作废任何还在飞行中的异步绑定（见 bindGeneration 的说明）
        bindGeneration++
        main.removeCallbacks(probeTask)
        main.removeCallbacks(endBurstTask)
        main.removeCallbacks(resumeRunnable)
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Exception) {
        }
        imageAnalysis = null
        cameraReady = false
        // 注意：**不再把 cameraProvider 置空**。它是进程级单例，待机/探测会反复
        // bind/unbind，置空会让后续以为需要重新 getInstance()。
        // startCameraImpl 内部本来就会先 unbindAll()，所以留着是安全的。
    }

    // ------------------------------------------------------------------ 空闲降频

    /**
     * 一次探测窗口结束时回到"睡"的状态。
     *
     * ★ 只在 `mode == Mode.LOW` 时才真的睡：如果这一窗探到了手，`onResult` 已经把 mode
     *   切回 ACTIVE，这个回调就是空操作 —— 用户手在画面里时相机是连续的，不会出现
     *   "手在动但相机在打盹"。
     *
     * 用具名类而不是 `object : Runnable {}`：匿名对象在 `val` 初始化器里引用自身
     * （`removeCallbacks(sleepRunnable)`）会让 Kotlin 的类型推断递归，编译期报
     * "Type checking has run into a recursive problem"。具名类 + 显式类型一次解决。
     */
    /**
     * 【探测周期性】待机睡眠结束 → **醒来探测一次**。
     *
     * ★ 这里曾经有一个致命 bug（v0.17.0 真机实测发现）：这个任务原本在解绑后
     *   `postDelayed(this, sleep)` 调**自己**，于是循环变成
     *   "解绑 → 等 → 又解绑 → 等…"，**永远不再绑定相机**。
     *   现象：相机指示灯亮一下就灭，之后手势完全失效（用户实测反馈）。
     *   根因是"睡醒后要探测"这一步被漏掉了。
     *
     * 现在职责拆开，两个任务各管一件事、互相接力：
     * ```
     *   enterStandby(): 解绑 -> 排入 ProbeTask(sleep)
     *   ProbeTask:      重新绑定 -> startCameraImpl(standby=true) 排入 EndBurstTask(burst)
     *   EndBurstTask:   解绑 -> 排入 ProbeTask(sleep)      <-- 循环在这里闭合
     * ```
     * 任何一条链路断了都不会再"静默停机"：探到手时 `enterActive()` 会把两个任务
     * 都撤掉并改为连续绑定。
     */
    private inner class ProbeTask : Runnable {
        override fun run() {
            // 只有仍在待机时才需要探测；探到手后 enterActive() 已把 mode 切回 ACTIVE
            if (mode != Mode.LOW) return
            startCameraImpl(standby = true)
        }
    }

    /** 探测窗口结束：解绑并排入下一次探测。 */
    private inner class EndBurstTask : Runnable {
        override fun run() {
            if (mode != Mode.LOW) return
            main.removeCallbacks(this)
            // 推进代次：作废可能还在飞行中的绑定（否则它落地后相机会一直开着）
            bindGeneration++
            try {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            imageAnalysis = null
            cameraReady = false
            publish(force = true)
            probeOnce()
        }
    }

    private val probeTask: Runnable = ProbeTask()
    private val endBurstTask: Runnable = EndBurstTask()

    /** 排入（或重排）一次"醒来探测"。 */
    private fun probeOnce() {
        main.removeCallbacks(probeTask)
        main.postDelayed(probeTask, currentSleepMs())
    }

    /**
     * 当前该睡多久 —— 交给 [IdleDownshift.sleepForIdle]（纯函数，有单测覆盖）。
     * 密集档 / 稀疏档按"距上次在 ACTIVE 模式下见到手"的时长切换。
     */
    private fun currentSleepMs(): Long = IdleDownshift.sleepForIdle(
        idleForMs = SystemClock.uptimeMillis() - lastHandSeenMs,
        tier1AfterIdleMs = T_TIER1_AFTER_IDLE_MS,
        sleep1Ms = T_SLEEP_TIER1_MS,
        sleep2Ms = T_SLEEP_TIER2_MS,
    )

    /** 探到手（或需要连续取流）后恢复连续绑定。 */
    private val resumeRunnable: Runnable = Runnable { startCameraImpl(standby = false) }

    // ------------------------------------------------------------------ 相机作用域

    /**
     * 按"当前前台 App 是否在相机作用域内"决定开/关相机。
     *
     * 这是继"跳帧"之后第二个降载手段，砍的是**取流**那一份开销
     * （线程级实测 CameraX + 相机 HAL 约 13~16% 单核，外加 GL mtrack ~80MB），
     * 而跳帧砍的是"算"那一份 —— 两者互补。
     *
     * 判定本身在纯逻辑 [CameraScope] 里（有单测），这里只执行副作用。
     *
     * ★ 调用时机（v1.0.0）：
     *   · **主路径**：[onAccessibilityEvent] —— 窗口一变就判定，通常 <100ms，
     *     这就是"从白名单 App 退出来 1~2 秒内断开相机"里那 1 秒以内的部分；
     *   · **兜底**：[ScopeTick] 每秒一次，防某个 OEM 不投窗口事件；
     *   · 另有息屏/亮屏广播、接近光触发也会立刻触发一次。
     *
     *   刻意不做高频轮询：判定要跨进程读一次节点树（`rootInActiveWindow`），
     *   每秒查很多次是没必要的负担 —— 系统在窗口切换时本来就会主动通知我们。
     */
    private fun onScopeTick() {
        // ★ 节流：窗口事件可能密集到达（返回桌面时系统会连报几个窗口变化），
        //   而"解绑/重绑相机"是几百毫秒级的重操作。不做节流的话，快速切 App
        //   会让相机反复解绑重绑 —— 那比少量卡顿严重得多。
        //   250ms 足够把同一批窗口事件合并成一次判定，又远小于人的操作间隔。
        val nowTick = SystemClock.uptimeMillis()
        if (nowTick - lastScopeTickMs < SCOPE_TICK_MIN_INTERVAL_MS) return
        lastScopeTickMs = nowTick

        val mode = CameraScope.parseMode(Prefs.cameraScope(this))
        val fg = SwipeInjector.foregroundPackage(this)
        val home = SwipeInjector.homePackage(this)
        // ★ 只取一次前台包名，再由它推出"是否在桌面"。
        //   以前这里先调 `isOnLauncher()`（内部会调一次 foregroundPackage）
        //   紧接着又调 `foregroundPackage()` —— **两次跨进程读节点树**，纯浪费。
        onLauncher = (fg != null && home != null && fg == home)
        val wl = Prefs.cameraWhitelist(this)
        // ★ 前台包名的可靠性分层（这里踩过两个方向的坑，别随便改回去）：
        //
        //   优先级：**新鲜的"全屏窗口事件"包名**  >  `rootInActiveWindow`  >  null
        //
        //   为什么新鲜事件包名优先：**华为桌面上 `rootInActiveWindow` 不可靠**
        //   （实测：按 HOME 后约 4 秒它才/才不返回桌面信息），只靠它会导致
        //   "退出白名单 App 后相机 4 秒才断"。而窗口事件在切换那一刻就带着正确包名。
        //
        //   为什么必须是"新鲜 + 全屏"的才采信：
        //     · **全屏**：输入法弹出、系统小窗、通知栏也会发窗口事件（且带自己的包名），
        //       拿它们比白名单会在白名单 App 里误判成"不在白名单"而关相机
        //       —— 这就是 v0.17.14 的真机 bug（进 App 约 1 秒被断）。
        //     · **新鲜**（超过 `lastEventFreshMs` 就不再采信）：事件包名只在刚切换那一刻可靠，
        //       无限期采信会让一条陈旧事件长期压过权威来源。
        //
        //   过期后回退到 `rootInActiveWindow`；两者都没有就只能放行（避免失灵）。
        //   判定规则本身在纯逻辑 [CameraScope.pickForegroundForWhitelist] 里（有单测）。
        val freshEventPkg = lastEventPkg
            ?.takeIf { nowTick - lastEventPkgAtMs <= lastEventFreshMs }
        val fgForWhitelist = CameraScope.pickForegroundForWhitelist(freshEventPkg, fg)
        if (!fgForWhitelist.isNullOrEmpty() && wl.isNotEmpty() && fgForWhitelist !in wl) {
            applyScopeDenied("不在白名单($fgForWhitelist)", fg, home, freshEventPkg)
            return
        }
        // ★ 接近光触发（用户设计）：打开后，相机**只能**由"手贴近传感器"开启，
        //   窗口过期就解绑。这是目前占空比最低的挡位。
        val proxOn = Prefs.proximityTrigger(this)
        // 测试旁路：测试机的接近光是虚拟的（永不触发），用它把窗口钉成"已开"
        val proxForce = proxOn && Prefs.proximityForceArmed(this)
        if (proxOn) {
            // 窗口过期 -> 关闭并解绑（下一次必须再贴近一次）
            if (ProximityTrigger.isExpired(
                    proximityArmed, SystemClock.uptimeMillis(), proximityArmedAtMs,
                    Prefs.PROXIMITY_WINDOW_MS)) {
                proximityArmed = false
                Log.i(DIAG, "proximity window expired -> camera off")
            }
            if (proxForce) proximityArmed = true
        } else {
            // 关掉这个功能时必须复位，否则会一直抱着一个"已开窗"的状态
            proximityArmed = false
        }

        // ★ 签名自检（v2.0.0）：不通过就**整个功能不工作** —— 不开相机、不注入滑动。
        //   用户选的强度是"只禁用隔空手势功能"：App 本身还能打开、状态区会写明原因。
        val sigOk = SignatureGuard.isTrusted(this)
        val allow = sigOk && CameraScope.shouldRunCamera(mode, fg, home, wl) && screenOn &&
            (!proxOn || proximityArmed)

        if (!allow) {
            val why = when {
                // 顺序即优先级：先说最"设计性"的原因，避免误报成故障
                !sigOk -> "签名校验未通过（手势已禁用）"
                !screenOn -> "息屏"
                proxOn && !proximityArmed -> "等贴近传感器"
                else -> CameraScope.offReason(mode, fg, home, wl) ?: "作用域外"
            }
            applyScopeDenied(why, fg, home, lastEventPkg)
            return
        }

        // 作用域允许：如果之前是关着的，恢复取流
        if (cameraOff) {
            cameraOff = false
            cameraOffReason = null
            Log.i(DIAG, "camera scope back ON fg=$fg mode=$mode")
            message = "运行中"
            publish(force = true)
            enterActive()
        }
    }

    /** 相机作用域判定为"不该开"时统一的收尾：解绑相机并把原因写进悬浮窗。 */
    private fun applyScopeDenied(why: String, fg: String?, home: String?, eventPkg: String?) {
        if (!cameraOff) {
            cameraOff = true
            cameraOffReason = why
            Log.i(DIAG, "camera scope OFF ($why) fg=$fg home=$home event=$eventPkg")
            // 撤掉所有相机相关回调，然后解绑
            main.removeCallbacks(probeTask)
            main.removeCallbacks(endBurstTask)
            main.removeCallbacks(resumeRunnable)
            bindGeneration++
            try {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            imageAnalysis = null
            cameraReady = false
            handPresent = false
            message = "已关相机（${why}）—— 省电"
            publish(force = true)
        } else {
            cameraOffReason = why
        }
    }

    /**
     * 帧陈旧看门狗：定期检查"该出帧却没出"，然后按当前模式重试。
     *
     * 两个必须成立的前提（否则会误伤正常行为）：
     *   1. `cameraReady` 为真 —— 待机睡眠中相机是**故意**解绑的，那不算故障。
     *   2. `lastFrameMs` 已经有过值 —— 服务刚启动时还没出帧，给 T_FRAME_STALE_MS 的宽限。
     *
     * 重试方式按模式区分：ACTIVE 就重新连续绑定；LOW 就当作一次探测唤醒。
     * 这个定时器**不依赖任何帧**，所以正好覆盖"帧链路彻底断掉"那种情况。
     */
    private inner class FrameWatchdog : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            try {
                // ① 只在"相机本该开着"的前提下，才把"无帧"当故障处理。
                //    cameraOff 时相机是**故意**关掉的，报无帧就是误报。
                // ② 作用域判定已经交给更快的 [ScopeTick]（1 秒），这里不再重复做。
                val now = SystemClock.uptimeMillis()
                val stale = !cameraOff && cameraReady &&
                    lastFrameMs != 0L &&
                    (now - lastFrameMs) > T_FRAME_STALE_MS
                if (stale) {
                    watchdogRearms++
                    val age = now - lastFrameMs
                    Log.w(DIAG, "frame watchdog: no frames for ${age}ms (mode=$mode), re-arming #$watchdogRearms")
                    message = "相机无帧 ${age}ms，正在重绑（#$watchdogRearms）"
                    publish(force = true)
                    // 作废在飞的绑定，清掉旧分析器，再按当前模式重新发起
                    bindGeneration++
                    try {
                        imageAnalysis?.clearAnalyzer()
                        cameraProvider?.unbindAll()
                    } catch (_: Exception) {
                    }
                    imageAnalysis = null
                    cameraReady = false
                    if (mode == Mode.LOW) {
                        probeOnce()
                    } else {
                        main.post(resumeRunnable)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "看门狗异常", e)
            } finally {
                // 无论发生什么都把自己排回去 —— 否则一次异常就让守护永久失效
                main.postDelayed(this, T_WATCHDOG_MS)
            }
        }
    }

    private val frameWatchdog: Runnable = FrameWatchdog()

    private fun startWatchdog() {
        main.removeCallbacks(frameWatchdog)
        main.postDelayed(frameWatchdog, T_WATCHDOG_MS)
    }

    /**
     * **快速作用域定时器**：1 秒一次，只做"前台是否还在作用域内"的判断。
     *
     * 为什么单独有一个快定时器：原来看门狗是 10 秒一轮，所以从 App 返回桌面后，
     * 相机最长还会继续取流 10 秒（实测用户看到 5~6 秒）—— 这段里相机 + 推理
     * 跟桌面动画抢 CPU，用户明确反馈"在桌面滑屏会降帧"。
     *
     * 为什么不直接用无障碍窗口事件（更省）：
     *   本会话试过用 [onAccessibilityEvent] 驱动，但实测在华为上没生效
     *   （窗口事件投递或 `rootInActiveWindow` 之一被 OEM 限制），
     *   而两台测试机的 logcat 都被隐藏、无法诊断。
     *   所以改用一个**不依赖 OEM 行为**的实现：自己每秒看一眼。
     *
     * 成本：每秒一次 [`SwipeInjector.isOnLauncher`]，它内部是
     *   `rootInActiveWindow?.packageName`（一次 IPC）+ 一个缓存过的包名比较。
     *   相比"相机多跑 5 秒"（约 50% 单核 + 持续推理），这个代价可以忽略。
     */
    private inner class ScopeTick : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            try {
                onScopeTick()
            } catch (e: Throwable) {
                Log.w(TAG, "作用域定时器异常", e)
            } finally {
                main.postDelayed(this, T_SCOPE_TICK_MS)
            }
        }
    }

    private val scopeTick: Runnable = ScopeTick()

    /**
     * 每帧调用：负责"有手 <-> 没手"的模式切换。
     *
     * 决策本身交给 [IdleDownshift.decide]（纯函数，有单测覆盖）—— 服务这边只负责
     * 维护两个时刻并执行副作用。**不要在服务里另写一套判断**，否则单测覆盖的就不是
     * 真正跑的代码了。
     *
     * 两个时刻的含义不同，别合并：
     *   · `lastHandPresentMs` —— 最后一帧**真的看到手**（目前仅用于诊断）。
     *   · `lastHandSeenMs` —— 上一次"在 ACTIVE 模式下见到手"。ACTIVE 期间由它决定
     *     什么时候该睡；待机期间**不更新**（由 [IdleDownshift.nextLastHandSeenMs] 保证），
     *     否则探测窗里的零星帧会让待机永远进不去。
     */
    private fun onFrameModeTick(handPresent: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (handPresent) lastHandPresentMs = now
        // ★ 接近光窗口"续期"：窗口内检测到手就重新计时，
        //   这样连续做几次手势不必反复去贴近传感器。
        if (handPresent && ProximityTrigger.shouldArm(proximityArmed, false, true)) {
            proximityArmedAtMs = now
        }
        if (mode == Mode.LOW) burstFrames++
        if (!ENABLE_IDLE_DOWNSHIFT) return

        val action = IdleDownshift.decide(
            mode = if (mode == Mode.LOW) IdleDownshift.Mode.LOW else IdleDownshift.Mode.ACTIVE,
            handPresent = handPresent,
            nowMs = now,
            lastHandSeenMs = lastHandSeenMs,
            idleDownMs = T_IDLE_DOWN_MS,
        )
        // ★ 先更新计时器再执行副作用：切回 ACTIVE 时必须已经重置，
        //   否则下一帧会立刻判定"超时"而再次进待机（抖动）。单测钉住了这一点。
        lastHandSeenMs = IdleDownshift.nextLastHandSeenMs(handPresent, now, lastHandSeenMs)

        when (action) {
            IdleDownshift.Action.ENTER_STANDBY -> enterStandby()
            IdleDownshift.Action.ENTER_ACTIVE -> enterActive()
            IdleDownshift.Action.NONE -> {}
        }
    }

    /** 掉进待机：解绑相机，睡一段（自适应档位）后由 [ProbeTask] 醒来探一次。 */
    private fun enterStandby() {
        main.removeCallbacks(probeTask)
        main.removeCallbacks(endBurstTask)
        main.removeCallbacks(resumeRunnable)
        mode = Mode.LOW
        standbyEntries++
        // 推进代次：作废任何还在飞行中的绑定（否则它落地后相机会留在打开状态）
        bindGeneration++
        try {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        imageAnalysis = null
        cameraReady = false
        // 睡眠期间手离开画面是"无声"的（没有帧就不会更新 lastSeen），所以这里主动把
        // "有手"显示复位，免得悬浮窗一直挂着一个过时的"有手"。
        handPresent = false
        val sleep = currentSleepMs()
        sleepNowMs = sleep
        val tier = if (sleep == T_SLEEP_TIER1_MS) "密集" else "稀疏"
        // ★ 模板后紧跟中文时必须写 ${tier}：Kotlin 的 $name 会把后面的字符一并
        //   当作标识符（"$tier档" 会被解析成标识符 "tier档" -> Unresolved reference）。
        message = "空闲待机（${tier}档）：相机已解绑，每 ${sleep + T_BURST_MS}ms 探一次"
        Log.i(DIAG, "enterStandby #$standbyEntries sleep=${sleep}ms tier=$tier")
        publish(force = true)
        // ★ 关键：这里排的是 ProbeTask（醒来探测），不是"再睡一次"。
        //   v0.17.0 的致命 bug 就是排成了自己，导致永远不再绑定相机。
        probeOnce()
    }

    /** 探到手（或任何需要连续取流的场合）：回到 ACTIVE。 */
    private fun enterActive() {
        main.removeCallbacks(probeTask)
        main.removeCallbacks(endBurstTask)
        main.removeCallbacks(resumeRunnable)
        if (mode != Mode.ACTIVE) {
            mode = Mode.ACTIVE
            Log.i(DIAG, "enterActive (hand found) standbyEntries=${standbyEntries}")
        }
        lastHandSeenMs = SystemClock.uptimeMillis()
        if (imageAnalysis == null) {
            // 正睡在待机里（相机已解绑）—— 重新连续绑定。
            // ★ 这条路径就是"重新唤醒延迟"的来源：从解绑到出首帧。
            main.post(resumeRunnable)
            // 自愈兜底：万一这次重新绑定失败（相机被别的应用抢走等），就没有任何帧会
            // 回来，也就没人再调 onFrameModeTick —— 会永久卡在"没有相机"。
            // 用一个备用探测保底：它到点会重新尝试绑定。
            main.removeCallbacks(probeTask)
            main.postDelayed(probeTask, T_BURST_MS + sleepNowMs)
        } else {
            message = "运行中"
            publish(force = true)
        }
    }

    // ------------------------------------------------------------------ 每帧

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            if (!Prefs.active(this)) return

            // ★ 降载：相机一直在取流（所以没有重绑延迟），但只有每 N 帧才真正推理。
            //   注意这一句必须在 buildRgbaImage 之前 —— 拷贝像素和交给 MediaPipe
            //   都是要花钱的，跳帧的目的就是把这些省掉。
            //   被跳过的帧由 finally 里的 proxy.close() 正常归还，不会泄漏。
            framesSeen++
            if (!skipper.shouldAnalyse()) return

            val rotation = proxy.imageInfo.rotationDegrees
            lastAspect = if (rotation == 90 || rotation == 270) {
                proxy.height.toDouble() / proxy.width.toDouble()
            } else {
                proxy.width.toDouble() / proxy.height.toDouble()
            }
            frameRotationDeg = rotation

            // ★ 屏幕旋转（v2.3.0）：方向映射要靠它把"手机转了多少"补回来（见 ScreenOrientation）。
            //
            //   成本：一次 DisplayManager 查询，只做在**真正推理的那一帧**上（跳帧后约 7.5 次/秒），
            //   而且只在取流期间（相机占空比 4.84%）。和同一帧里的位图拷贝 + 推理相比可忽略不计；
            //   刻意**不**放进 1 秒一次的 scope tick —— 那玩意是常驻的，反而更贵。
            val dispDeg = SwipeInjector.displayRotationDegrees(this)
            if (dispDeg != displayRotationDeg) {
                Log.i(DIAG, "display rotation changed -> ${dispDeg}deg (frameRotation=$rotation)")
                displayRotationDeg = dispDeg
            }
            syncTargetRotation(dispDeg)

            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts

            val mpImage: MPImage? = if (Prefs.imagePath(this) == Prefs.IMG_RGBA) {
                buildRgbaImage(proxy)
            } else {
                proxy.image?.let { MediaImageBuilder(it).build() }
            }
            if (mpImage == null) return

            val ipo = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            lm.detectAsync(mpImage, ipo, ts)
            framesIn++
            if (framesIn % 60L == 0L) {
                // ★ `cooldown` 打出来是有意的：定稿参数存在 SharedPreferences 里，
                //   历史上多次出现"改了代码但手机上没生效"（旧值盖掉新默认值）。
                //   正式包 run-as 读不了 prefs，这一行就是**不依赖调试包**验证参数的唯一通道。
                Log.i(DIAG, "framesIn=$framesIn resultsOut=$resultsOut handsSeen=$handsSeen " +
                    "delegate=$delegateInfo stride=$strideInfo mode=$mode cooldown=${detector.cooldownMs.toInt()} " +
                    "standbyEntries=$standbyEntries burstFrames=$burstFrames watchdogRearms=$watchdogRearms")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "analyze 异常", e)
            message = "取帧异常：${e.message}"
        } finally {
            proxy.close()
        }
    }

    /**
     * RGBA 通道：在分析线程里**同步**把像素拷进我们自己持有的 Bitmap。
     *
     * 这一步是整个修复的关键 —— 拷贝发生在这里，`proxy.close()` 之后再也不会有人去读
     * 那块已经失效的 Image。
     */
    private fun buildRgbaImage(proxy: ImageProxy): MPImage? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val w = proxy.width
        val h = proxy.height
        val rowBytes = w * 4
        val bmp = obtainBitmap(w, h)

        val buf = plane.buffer
        buf.rewind()
        try {
            if (plane.rowStride == rowBytes) {
                if (buf.remaining() < rowBytes * h) {
                    strideInfo = "buf短(${buf.remaining()}<${rowBytes * h})"
                    return null
                }
                bmp.copyPixelsFromBuffer(buf)
            } else {
                // 有行填充（少数机型）：逐行拷，慢一点但正确
                strideInfo = "rowStride=${plane.rowStride}(逐行拷)"
                val bytes = ByteArray(rowBytes * h)
                val dup = buf.duplicate()
                var off = 0
                for (row in 0 until h) {
                    val pos = row * plane.rowStride
                    if (pos + rowBytes > dup.limit()) break
                    dup.position(pos)
                    dup.get(bytes, off, rowBytes)
                    off += rowBytes
                }
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "拷贝 RGBA 失败", e)
            message = "拷贝帧失败：${e.message}"
            return null
        }
        if (strideInfo.isEmpty()) strideInfo = "rowStride=${plane.rowStride}(紧凑)"
        return BitmapImageBuilder(bmp).build()
    }

    /** 两个缓冲区轮换，避免 MediaPipe 还在读上一帧时被下一帧覆盖。 */
    private fun obtainBitmap(w: Int, h: Int): Bitmap {
        val i = frameBitmapIdx
        frameBitmapIdx = (frameBitmapIdx + 1) % BITMAP_POOL
        var b = frameBitmaps[i]
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBitmaps[i] = b
        }
        return b
    }

    // ------------------------------------------------------------------ 状态输出

    private fun countFps() {
        val now = SystemClock.uptimeMillis()
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            fpsFrames = 0
        }
        fpsFrames++
        val dt = now - fpsWindowStart
        if (dt >= 1000) {
            fps = fpsFrames * 1000.0 / dt
            fpsWindowStart = now
            fpsFrames = 0
        }
    }

    /** 自我诊断：三路计数能直接指出卡在哪一环。 */
    private fun selfDiagnosis(): String = when {
        landmarker == null -> "模型没加载成功"
        // ★ 相机被"作用域"主动关掉时（桌面 / 不在白名单），绝不能报故障 ——
        //   那是省电设计，不是坏了。悬浮窗是本机唯一能看到状态的地方（荣耀隐藏了 logcat），
        //   报错会让用户以为功能坏了。
        cameraOff -> "已关相机（${cameraOffReason ?: "作用域外"}）省电中"
        // ★ 待机（LOW）时相机**按设计**是解绑的，不能报"相机没起来"——
        //   否则悬浮窗会在空闲时一直挂着一条假的故障信息，用户会以为坏了。
        !cameraReady && mode == Mode.LOW -> ""
        !cameraReady -> "相机没起来"
        framesIn > 90 && resultsOut == 0L -> "推理没有回调 → 试试切换图像通道"
        // 帧链路断掉时看门狗会重绑；这里把发生过的次数显示出来，
        // 因为荣耀隐藏了 logcat，悬浮窗是唯一能看到这件事的地方。
        watchdogRearms > 0L -> "相机曾无帧，已自动重绑 ${watchdogRearms} 次"
        resultsOut > 30 && handsSeen == 0L -> "没检测到手 → 手放画面中间、光线亮些、离近点"
        handsSeen > 0L -> ""
        else -> "等待数据…"
    }

    private fun publish(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPublish < 150) return     // 最多 ~6 次/秒，别刷爆 UI
        lastPublish = now
        // 悬浮窗关掉时连 Status 都不用构造（构造里有字符串拼接）—— 省一层分配
        val ov = overlay ?: return
        if (!Prefs.debugOverlay(this)) {
            ov.hideForDisabled()
            return
        }

        val diag = selfDiagnosis()
        // 待机时相机是解绑的，但"服务整体是否就绪"对用户仍然是"就绪"（模型在、服务在），
        // 所以这里按 mode 区分，避免悬浮窗显示"未就绪"引发误解。
        // ★ 悬浮窗是本项目在真机上**唯一**可信的观察窗口（荣耀对 shell 隐藏了 logcat），
        //   所以档位必须显示出来，否则没法从手机上判断降频到底有没有生效。
        val ready = landmarker != null && (cameraReady || mode == Mode.LOW)
        val modeInfo = when {
            mode == Mode.ACTIVE -> "连续"
            sleepNowMs == T_SLEEP_TIER1_MS -> "待机/密集"
            else -> "待机/稀疏"
        }
        // 把生效的**方向参数**一并显示：重装会把 mirror_x / invert_vertical 清回默认，
        // 而这两个值决定四个方向对不对。显示出来才能一眼确认"当前到底用的哪套"，
        // 不必再去 run-as 翻 SharedPreferences（本会话为了查这个来回折腾过好几次）。
        //
        // ★ v2.3.0 增补：`角` 是**当前生效**的偏移（竖屏应等于锚点 90，横屏 90°→180°、270°→0°），
        //   后面跟"竖/横 + 屏幕旋转度数"，`帧` 是 CameraX 报的帧旋转，
        //   `屏` 是注入滑动时用的那对屏幕尺寸（`SwipeInjector` 读的就是它）。
        //   横屏验收时这几个读数就是判据：屏幕=横90 + 角180、屏幕=横270 + 角0 才算对；
        //   `屏` 在横屏时应变成"宽>高"，否则说明服务侧拿到的仍是竖屏尺寸（滑动幅度会偏大）。
        val screenMetrics = resources.displayMetrics
        val dirInfo = buildString {
            append("镜像").append(if (Prefs.mirrorX(this@SweepAccessibilityService)) "开" else "关")
            append("·上下").append(if (Prefs.invertVertical(this@SweepAccessibilityService)) "反" else "正")
            append("·角").append(detector.angleOffsetDeg.toInt())
            append("·").append(
                if (ScreenOrientation.isLandscape(displayRotationDeg)) "横" else "竖")
            append(displayRotationDeg)
            append("·帧").append(frameRotationDeg)
            append("·屏").append(screenMetrics.widthPixels).append("x").append(screenMetrics.heightPixels)
        }
        // 接近光状态：用户需要知道"往哪贴、贴上去有没有反应"。
        // 显示量程、当前读数、累计触发次数，以及窗口剩余秒数。
        val proxInfo = if (!Prefs.proximityTrigger(this)) {
            "近光关"
        } else {
            val v = if (proximityValue.isNaN()) "—" else String.format("%.1f", proximityValue)
            val left = if (proximityArmed) {
                ((Prefs.PROXIMITY_WINDOW_MS - (SystemClock.uptimeMillis() - proximityArmedAtMs)) / 1000)
                    .coerceAtLeast(0)
            } else 0
            "近光${v}/${String.format("%.0f", proximityMaxRange)}cm 触发${proximityHits} 窗${left}s"
        }
        val status = SweepBus.Status(
            serviceReady = ready,
            handPresent = handPresent,
            gated = detector.dbgGated,
            state = detector.state.name,
            fps = fps,
            disp = detector.dbgDisp,
            angle = detector.dbgAngle,
            together = together,
            fingerInfo = fingerInfo,
            extended = extendedCount,
            cooldownLeftMs = detector.dbgCooldownLeftMs,
            lastDir = lastDir,
            message = if (diag.isNotEmpty()) diag else message,
            injectInfo = lastInjectInfo,
            framesIn = framesIn,
            resultsOut = resultsOut,
            handsSeen = handsSeen,
            modelInfo = "$delegateInfo/${Prefs.imagePath(this)}/$modeInfo/$dirInfo/$proxInfo",
            gestureHistory = gestureHistory.joinToString(" "),
        )
        ov.update(status)
    }
}
