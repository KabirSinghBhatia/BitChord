package com.music.bitchord.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopMacFrameTest {

    @Test
    fun testDarkAquaAppearance() {
        if (!DesktopPlatform.isMac) return
        val objc = NativeLibrary.getInstance("objc")
        val getClass = objc.getFunction("objc_getClass")
        val registerName = objc.getFunction("sel_registerName")
        val msgSend = objc.getFunction("objc_msgSend")

        val nsAppearanceClass = getClass.invokePointer(arrayOf("NSAppearance"))
        val nsStringClass = getClass.invokePointer(arrayOf("NSString"))
        val selStringWithUtf8 = registerName.invokePointer(arrayOf("stringWithUTF8String:"))
        val selAppearanceNamed = registerName.invokePointer(arrayOf("appearanceNamed:"))

        val darkAquaStr = msgSend.invokePointer(arrayOf(nsStringClass, selStringWithUtf8, "NSAppearanceNameDarkAqua"))
        val darkAppearance = msgSend.invokePointer(arrayOf(nsAppearanceClass, selAppearanceNamed, darkAquaStr))

        println("Dark Aqua Appearance: $darkAppearance")
        assertTrue(darkAppearance != null && darkAppearance != Pointer.NULL, "NSAppearanceNameDarkAqua must exist")

        val nsAppClass = getClass.invokePointer(arrayOf("NSApplication"))
        val selSharedApp = registerName.invokePointer(arrayOf("sharedApplication"))
        val app = msgSend.invokePointer(arrayOf(nsAppClass, selSharedApp))
        val selSetAppearance = registerName.invokePointer(arrayOf("setAppearance:"))
        if (app != null && app != Pointer.NULL) {
            msgSend.invoke(arrayOf(app, selSetAppearance, darkAppearance))
            println("Successfully set appearance on NSApplication")
        }
    }

    @Test
    fun testVisualEffectViewWithDarkAqua() {
        if (!DesktopPlatform.isMac) return
        assertTrue(DesktopMacFrame.isObjcAvailable(), "ObjC runtime classes must be available on macOS")

        val objc = NativeLibrary.getInstance("objc")
        val getClass = objc.getFunction("objc_getClass")
        val registerName = objc.getFunction("sel_registerName")
        val msgSend = objc.getFunction("objc_msgSend")

        val effectClass = getClass.invokePointer(arrayOf("NSVisualEffectView"))
        val selAlloc = registerName.invokePointer(arrayOf("alloc"))
        val selInit = registerName.invokePointer(arrayOf("init"))
        val alloc = msgSend.invokePointer(arrayOf(effectClass, selAlloc))
        val view = msgSend.invokePointer(arrayOf(alloc, selInit))
        assertTrue(view != null && view != Pointer.NULL, "NSVisualEffectView could not be created")

        val nsAppearanceClass = getClass.invokePointer(arrayOf("NSAppearance"))
        val nsStringClass = getClass.invokePointer(arrayOf("NSString"))
        val selStringWithUtf8 = registerName.invokePointer(arrayOf("stringWithUTF8String:"))
        val selAppearanceNamed = registerName.invokePointer(arrayOf("appearanceNamed:"))
        val darkAquaStr = msgSend.invokePointer(arrayOf(nsStringClass, selStringWithUtf8, "NSAppearanceNameDarkAqua"))
        val darkAppearance = msgSend.invokePointer(arrayOf(nsAppearanceClass, selAppearanceNamed, darkAquaStr))

        val selSetAppearance = registerName.invokePointer(arrayOf("setAppearance:"))
        msgSend.invoke(arrayOf(view, selSetAppearance, darkAppearance))

        val selSetMaterial = registerName.invokePointer(arrayOf("setMaterial:"))
        msgSend.invoke(arrayOf(view, selSetMaterial, 7L)) // Sidebar material

        val selSetWantsLayer = registerName.invokePointer(arrayOf("setWantsLayer:"))
        msgSend.invoke(arrayOf(view, selSetWantsLayer, true))

        val selLayer = registerName.invokePointer(arrayOf("layer"))
        val layer = msgSend.invokePointer(arrayOf(view, selLayer))
        assertTrue(layer != null && layer != Pointer.NULL, "Layer must be present on NSVisualEffectView")

        val selSetCornerRadius = registerName.invokePointer(arrayOf("setCornerRadius:"))
        val selSetMasksToBounds = registerName.invokePointer(arrayOf("setMasksToBounds:"))
        msgSend.invoke(arrayOf(layer, selSetCornerRadius, 10.0))
        msgSend.invoke(arrayOf(layer, selSetMasksToBounds, true))
        println("Successfully tested NSVisualEffectView with Dark Aqua and corner radius 10.0")
    }

    @Test
    fun testPerformWindowDragSelectors() {
        if (!DesktopPlatform.isMac) return
        val objc = NativeLibrary.getInstance("objc")
        val registerName = objc.getFunction("sel_registerName")
        val selPerformDrag = registerName.invokePointer(arrayOf("performWindowDragWithEvent:"))
        val selCurrentEvent = registerName.invokePointer(arrayOf("currentEvent"))
        assertTrue(selPerformDrag != null && selPerformDrag != Pointer.NULL, "performWindowDragWithEvent: must be a valid selector")
        assertTrue(selCurrentEvent != null && selCurrentEvent != Pointer.NULL, "currentEvent must be a valid selector")
    }
}
