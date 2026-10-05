package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Color
import java.awt.Toolkit
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS native window frame and vibrancy bridge via AppKit and Objective-C runtime (JNA).
 * Attaches an NSVisualEffectView to provide authentic macOS frosted glass materials
 * behind the window chrome.
 */
internal object DesktopMacFrame {

    private val installed = AtomicBoolean(false)
    private var windowPtr: Pointer? = null
    private var effectViewPtr: Pointer? = null
    @Volatile
    private var currentBackdropKind: Int = 0

    private interface LibObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
    }

    private interface ActionCallback : Callback {
        fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer)
    }

    private val objc: LibObjC? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", LibObjC::class.java) }.getOrNull()
    }

    private val msgSend by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }

    // Keep strong references to callbacks so they are not garbage collected by JNA
    private val callbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                applyNativeBackdropOnMainThread(currentBackdropKind)
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame main thread error: ${it.message}")
            }
        }
    }

    private val helperInstance: Pointer? by lazy {
        initHelper()
    }

    private val selPerformOnMain: Pointer? by lazy {
        objc?.sel_registerName("performOnMainThread:on:withObject:waitUntilDone:")
    }

    private val selApplyBackdrop: Pointer? by lazy {
        objc?.sel_registerName("applyBackdrop:")
    }

    private val threadUtilities: Pointer? by lazy {
        Toolkit.getDefaultToolkit() // Ensure AWT native libs are loaded
        objc?.objc_getClass("ThreadUtilities")
    }

    private fun initHelper(): Pointer? {
        val o = objc ?: return null
        val send = msgSend ?: return null
        val nsObjectClass = o.objc_getClass("NSObject") ?: return null
        val clsName = "BitChordMacFrameHelper"
        var cls = o.objc_getClass(clsName)
        if (cls == null) {
            cls = o.objc_allocateClassPair(nsObjectClass, clsName, 0L) ?: return null
            val sel = o.sel_registerName("applyBackdrop:")
            o.class_addMethod(cls, sel, callbackRef, "v@:@")
            o.objc_registerClassPair(cls)
        }
        val selAlloc = o.sel_registerName("alloc")
        val selInit = o.sel_registerName("init")
        val alloc = send.invokePointer(arrayOf(cls, selAlloc)) ?: return null
        return send.invokePointer(arrayOf(alloc, selInit))
    }

    private fun dispatchToMainThread() {
        val tu = threadUtilities ?: return
        val helper = helperInstance ?: return
        val selPerform = selPerformOnMain ?: return
        val selAction = selApplyBackdrop ?: return
        val send = msgSend ?: return
        send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, false))
    }

    internal fun isObjcAvailable(): Boolean {
        val o = objc ?: return false
        val nsWindowClass = o.objc_getClass("NSWindow")
        val nsEffectClass = o.objc_getClass("NSVisualEffectView")
        return nsWindowClass != null && nsEffectClass != null
    }

    private fun isNSWindow(ptr: Pointer?): Boolean {
        if (ptr == null || ptr == Pointer.NULL) return false
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsWindowClass = o.objc_getClass("NSWindow") ?: return false
        val selIsKindOfClass = o.sel_registerName("isKindOfClass:")
        return runCatching {
            send.invoke(Boolean::class.java, arrayOf(ptr, selIsKindOfClass, nsWindowClass)) as? Boolean
        }.getOrDefault(false) ?: false
    }

    private fun toNSWindow(handle: Pointer): Pointer {
        if (isNSWindow(handle)) return handle
        val o = objc ?: return handle
        val send = msgSend ?: return handle

        val selNsWindow = o.sel_registerName("nsWindow")
        val winFromNsWindow = runCatching {
            send.invokePointer(arrayOf(handle, selNsWindow))
        }.getOrNull()
        if (winFromNsWindow != null && isNSWindow(winFromNsWindow)) return winFromNsWindow

        val selWindow = o.sel_registerName("window")
        val winFromWindow = runCatching {
            send.invokePointer(arrayOf(handle, selWindow))
        }.getOrNull()
        if (winFromWindow != null && isNSWindow(winFromWindow)) return winFromWindow

        return handle
    }

    private fun findAppKitWindow(): Pointer? {
        val o = objc ?: return null
        val send = msgSend ?: return null
        val nsAppClass = o.objc_getClass("NSApplication") ?: return null
        val selSharedApp = o.sel_registerName("sharedApplication")
        val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp)) ?: return null

        val selKeyWindow = o.sel_registerName("keyWindow")
        val keyWin = send.invokePointer(arrayOf(app, selKeyWindow))
        if (keyWin != null && isNSWindow(keyWin)) return keyWin

        val selMainWindow = o.sel_registerName("mainWindow")
        val mainWin = send.invokePointer(arrayOf(app, selMainWindow))
        if (mainWin != null && isNSWindow(mainWin)) return mainWin

        val selWindows = o.sel_registerName("windows")
        val windows = send.invokePointer(arrayOf(app, selWindows)) ?: return null
        val selCount = o.sel_registerName("count")
        val count = (send.invoke(Long::class.java, arrayOf(windows, selCount)) as? Number)?.toLong() ?: 0L
        val selObjAtIndex = o.sel_registerName("objectAtIndex:")
        for (i in 0 until count) {
            val win = send.invokePointer(arrayOf(windows, selObjAtIndex, i)) ?: continue
            if (isNSWindow(win)) return win
        }
        return null
    }

    private fun getWindowPointer(window: Window): Pointer? {
        try {
            val method = window.javaClass.getMethod("getWindowHandle")
            val handle = method.invoke(window) as? Long
            if (handle != null && handle != 0L) {
                return Pointer.createConstant(handle)
            }
        } catch (_: Throwable) {}

        // Fallback: search AppKit NSApplication windows
        findAppKitWindow()?.let { return it }

        // Fallback: AWT peer reflection
        return try {
            val peer = window.javaClass.getMethod("getPeer").invoke(window)
            val platformWindow = peer.javaClass.getMethod("getPlatformWindow").invoke(peer)
            var targetClass: Class<*>? = platformWindow.javaClass
            var field: java.lang.reflect.Field? = null
            while (targetClass != null && field == null) {
                field = runCatching { targetClass.getDeclaredField("ptr") }.getOrNull()
                targetClass = targetClass.superclass
            }
            if (field != null) {
                field.isAccessible = true
                val ptr = field.getLong(platformWindow)
                if (ptr != 0L) Pointer.createConstant(ptr) else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Prepares the window for macOS transparency and vibrancy. */
    fun install(window: Window): Boolean {
        if (!DesktopPlatform.isMac) return false

        // Clear AWT background layers immediately on the caller thread
        window.background = Color(0, 0, 0, 0)
        (window as? javax.swing.RootPaneContainer)?.let { rpc ->
            rpc.contentPane?.background = Color(0, 0, 0, 0)
            (rpc.contentPane as? javax.swing.JComponent)?.isOpaque = false
            rpc.rootPane?.isOpaque = false
            rpc.rootPane?.background = Color(0, 0, 0, 0)
            rpc.rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
            rpc.rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
        }

        val handle = getWindowPointer(window) ?: run {
            DesktopTrackLog.log("DesktopMacFrame: failed to obtain window pointer")
            return false
        }
        val nsWindow = toNSWindow(handle)
        if (!isNSWindow(nsWindow)) {
            DesktopTrackLog.log("DesktopMacFrame: pointer is not an NSWindow: $nsWindow")
            return false
        }
        windowPtr = nsWindow
        installed.set(true)

        applyDarkAppearance(nsWindow, null)
        dispatchToMainThread()
        DesktopTrackLog.log("DesktopMacFrame: installed successfully on NSWindow $nsWindow")
        return true
    }

    /** Applies or removes the NSVisualEffectView native backdrop blur. */
    fun setBackdrop(nativeKind: Int): Boolean {
        if (!DesktopPlatform.isMac) return false
        currentBackdropKind = nativeKind
        dispatchToMainThread()
        return true
    }

    /** Adjusts the vibrancy view corner radius to match the Compose window placement. */
    fun updateCornerRadius(maximized: Boolean) {
        if (!DesktopPlatform.isMac) return
        val o = objc ?: return
        val send = msgSend ?: return
        val view = effectViewPtr ?: return
        val selLayer = o.sel_registerName("layer")
        val layer = send.invokePointer(arrayOf(view, selLayer)) ?: return
        val selSetCornerRadius = o.sel_registerName("setCornerRadius:")
        send.invoke(arrayOf(layer, selSetCornerRadius, if (maximized) 0.0 else 10.0))
    }

    private fun applyDarkAppearance(nsWindow: Pointer, effectView: Pointer?) {
        val o = objc ?: return
        val send = msgSend ?: return
        runCatching {
            val nsAppearanceClass = o.objc_getClass("NSAppearance") ?: return
            val nsStringClass = o.objc_getClass("NSString") ?: return
            val selStringWithUtf8 = o.sel_registerName("stringWithUTF8String:")
            val selAppearanceNamed = o.sel_registerName("appearanceNamed:")
            val darkAquaStr = send.invokePointer(arrayOf(nsStringClass, selStringWithUtf8, "NSAppearanceNameDarkAqua")) ?: return
            val darkAppearance = send.invokePointer(arrayOf(nsAppearanceClass, selAppearanceNamed, darkAquaStr)) ?: return

            val selSetAppearance = o.sel_registerName("setAppearance:")

            // 1. Enforce dark mode for the entire application process
            val nsAppClass = o.objc_getClass("NSApplication")
            if (nsAppClass != null) {
                val selSharedApp = o.sel_registerName("sharedApplication")
                val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp))
                if (app != null && app != Pointer.NULL) {
                    send.invoke(arrayOf(app, selSetAppearance, darkAppearance))
                }
            }

            // 2. Enforce dark mode on the window
            send.invoke(arrayOf(nsWindow, selSetAppearance, darkAppearance))

            // 3. Enforce dark mode on the vibrancy view
            if (effectView != null && effectView != Pointer.NULL) {
                send.invoke(arrayOf(effectView, selSetAppearance, darkAppearance))
            }
            DesktopTrackLog.log("DesktopMacFrame: enforced NSAppearanceNameDarkAqua")
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: failed to apply dark appearance: ${it.message}")
        }
    }

    private fun applyNativeBackdropOnMainThread(nativeKind: Int) {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsWindow = windowPtr ?: return

        // 1. Transparent window & drop shadow
        val selSetOpaque = o.sel_registerName("setOpaque:")
        send.invoke(arrayOf(nsWindow, selSetOpaque, false))

        val nsColorClass = o.objc_getClass("NSColor")
        if (nsColorClass != null) {
            val selClearColor = o.sel_registerName("clearColor")
            val clearColor = send.invokePointer(arrayOf(nsColorClass, selClearColor))
            if (clearColor != null) {
                val selSetBackgroundColor = o.sel_registerName("setBackgroundColor:")
                send.invoke(arrayOf(nsWindow, selSetBackgroundColor, clearColor))
            }
        }

        val selSetHasShadow = o.sel_registerName("setHasShadow:")
        send.invoke(arrayOf(nsWindow, selSetHasShadow, true))

        val selInvalidateShadow = o.sel_registerName("invalidateShadow")
        send.invoke(arrayOf(nsWindow, selInvalidateShadow))

        applyDarkAppearance(nsWindow, effectViewPtr)

        // 2. NSVisualEffectView setup
        val selContentView = o.sel_registerName("contentView")
        val contentView = send.invokePointer(arrayOf(nsWindow, selContentView)) ?: run {
            DesktopTrackLog.log("DesktopMacFrame: contentView is null")
            return
        }

        val selSuperview = o.sel_registerName("superview")
        val superview = send.invokePointer(arrayOf(contentView, selSuperview)) ?: contentView

        val existing = effectViewPtr

        if (nativeKind == 0) { // OFF
            if (existing != null) {
                val selRemove = o.sel_registerName("removeFromSuperview")
                send.invoke(arrayOf(existing, selRemove))
                effectViewPtr = null
            }
            DesktopTrackLog.log("DesktopMacFrame: removed NSVisualEffectView")
            return
        }

        // macOS Materials:
        // MICA (2) -> NSVisualEffectMaterialSidebar (7)
        // ACRYLIC (3) -> NSVisualEffectMaterialHUDWindow (13)
        val material = if (nativeKind == 2) 7L else 13L

        val effectView = existing ?: run {
            val effectClass = o.objc_getClass("NSVisualEffectView") ?: run {
                DesktopTrackLog.log("DesktopMacFrame: NSVisualEffectView class not found")
                return
            }
            val selAlloc = o.sel_registerName("alloc")
            val selInit = o.sel_registerName("init")
            val alloc = send.invokePointer(arrayOf(effectClass, selAlloc)) ?: return
            val view = send.invokePointer(arrayOf(alloc, selInit)) ?: return

            val selSetTranslates = o.sel_registerName("setTranslatesAutoresizingMaskIntoConstraints:")
            send.invoke(arrayOf(view, selSetTranslates, false))

            // NSVisualEffectBlendingModeBehindWindow = 0
            val selSetBlending = o.sel_registerName("setBlendingMode:")
            send.invoke(arrayOf(view, selSetBlending, 0L))

            // NSVisualEffectStateActive = 1
            val selSetState = o.sel_registerName("setState:")
            send.invoke(arrayOf(view, selSetState, 1L))

            // Mask corners to match Compose's 10.dp rounded corners
            val selSetWantsLayer = o.sel_registerName("setWantsLayer:")
            send.invoke(arrayOf(view, selSetWantsLayer, true))
            val selLayer = o.sel_registerName("layer")
            val layer = send.invokePointer(arrayOf(view, selLayer))
            if (layer != null && layer != Pointer.NULL) {
                val selSetCornerRadius = o.sel_registerName("setCornerRadius:")
                val selSetMasksToBounds = o.sel_registerName("setMasksToBounds:")
                val radius = if (DesktopWindowMode.maximized.value) 0.0 else 10.0
                send.invoke(arrayOf(layer, selSetCornerRadius, radius))
                send.invoke(arrayOf(layer, selSetMasksToBounds, true))
            }

            // Add behind AWTView (in superview if available, else below contentView)
            val selAddSubview = o.sel_registerName("addSubview:positioned:relativeTo:")
            if (superview != contentView) {
                send.invoke(arrayOf(superview, selAddSubview, view, -1L, contentView))
            } else {
                send.invoke(arrayOf(contentView, selAddSubview, view, -1L, null))
            }

            pinConstraints(view, contentView)

            effectViewPtr = view
            applyDarkAppearance(nsWindow, view)
            view
        }

        val selSetMaterial = o.sel_registerName("setMaterial:")
        send.invoke(arrayOf(effectView, selSetMaterial, material))
        applyDarkAppearance(nsWindow, effectView)

        DesktopTrackLog.log("DesktopMacFrame: applied material $material behind AWTView (nativeKind $nativeKind)")
    }

    private fun pinConstraints(view: Pointer, target: Pointer) {
        val o = objc ?: return
        val send = msgSend ?: return

        val selLeading = o.sel_registerName("leadingAnchor")
        val selTrailing = o.sel_registerName("trailingAnchor")
        val selTop = o.sel_registerName("topAnchor")
        val selBottom = o.sel_registerName("bottomAnchor")
        val selConstraintEqualTo = o.sel_registerName("constraintEqualToAnchor:")
        val selSetActive = o.sel_registerName("setActive:")

        listOf(selLeading, selTrailing, selTop, selBottom).forEach { anchorSel ->
            val vAnchor = send.invokePointer(arrayOf(view, anchorSel)) ?: return@forEach
            val tAnchor = send.invokePointer(arrayOf(target, anchorSel)) ?: return@forEach
            val constraint = send.invokePointer(arrayOf(vAnchor, selConstraintEqualTo, tAnchor)) ?: return@forEach
            send.invoke(arrayOf(constraint, selSetActive, true))
        }
    }
}
