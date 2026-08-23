package com.personal.smsforwarder.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.smsforwarder.model.AppIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These have to run on a device. The alias name is a string built in Kotlin that must
 * match an `<activity-alias>` in the manifest, and nothing on the JVM can tell you whether
 * those two agree. Get it wrong and the icon silently never changes — or, in the worst
 * ordering, every launcher entry ends up disabled and the app vanishes from the home
 * screen with no way back in.
 */
@RunWith(AndroidJUnit4::class)
class AppIconManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = AppIconManager(context)

    @After
    fun restore() {
        manager.apply(AppIcon.Blue)
    }

    @Test
    fun untouchedInstallReportsTheDefaultIcon() {
        assertEquals(AppIcon.Blue, manager.current())
        assertEquals(listOf(AppIcon.Blue), manager.enabled())
    }

    /** Proves every alias in the enum actually resolves to a component in the manifest. */
    @Test
    fun everyVariantCanBeAppliedAndReadBack() {
        AppIcon.entries.forEach { icon ->
            manager.apply(icon)
            assertEquals("$icon did not become the enabled alias", icon, manager.current())
        }
    }

    /**
     * The invariant that keeps the app launchable: exactly one alias enabled after a
     * switch, never zero and never two.
     */
    @Test
    fun exactlyOneLauncherAliasIsEnabledAfterEverySwitch() {
        AppIcon.entries.forEach { icon ->
            manager.apply(icon)
            assertEquals("wrong alias set enabled for $icon", listOf(icon), manager.enabled())
        }
    }

    /** Reapplying the icon already in use must not disturb anything. */
    @Test
    fun applyingTheCurrentIconIsANoOp() {
        manager.apply(AppIcon.Light)
        manager.apply(AppIcon.Light)
        assertEquals(listOf(AppIcon.Light), manager.enabled())
    }
}
