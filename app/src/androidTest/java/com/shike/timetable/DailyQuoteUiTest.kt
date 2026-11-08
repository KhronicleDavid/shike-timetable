package com.shike.timetable

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.shike.timetable.data.ScheduleStore
import com.shike.timetable.quotes.*
import com.shike.timetable.theme.AppearanceStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

class DailyQuoteUiTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldTheme="minimal"
    private var oldVoice=true
    private val clean=object: ExternalResource() {
        override fun before() {
            oldTheme=AppearanceStore(context).themeId
            oldVoice=AppearanceStore(context).voiceEnabled
            AppearanceStore(context).themeId="minimal"
            context.getSharedPreferences(DailyQuoteStore.PREFERENCES,Context.MODE_PRIVATE).edit().clear().commit()
        }
        override fun after() { InstrumentationRegistry.getInstrumentation().runOnMainSync { com.shike.timetable.theme.CompanionVoicePlayback.cancel(context) }; AppearanceStore(context).voiceEnabled=oldVoice; DailyQuoteStore(context).enabled=false; DailyQuoteScheduler.cancel(context); AppearanceStore(context).themeId=oldTheme }
    }
    @get:Rule val rules: TestRule=RuleChain.outerRule(clean).around(compose)
    private fun line()=compose.onNodeWithTag("daily_quote_text",useUnmergedTree=true).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
    private fun clipboard(): String {
        var value=""
        compose.runOnIdle { value=context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        return value
    }
    @Test fun dailyCardKeepsMascotAndChangingDailyQuoteNeverInterruptsRoleVoice() {
        val themeStore=com.shike.timetable.theme.ExternalThemeStore(context)
        if (themeStore.installed().none { it.id=="himari" }) {
            val archive=ThemeTestContent.archive(context,InstrumentationRegistry.getInstrumentation().context,"himari")
            try { themeStore.install(themeStore.prepare(archive)) } finally { archive.delete() }
        }
        AppearanceStore(context).apply { themeId="himari";voiceEnabled=true;showCompanion=true }
        DailyQuoteStore(context).enabled=true
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("daily_quote_text",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("吉祥物：皆原阳茉莉").assertIsDisplayed()
        val first=line()
        compose.onNodeWithTag("companion_portrait_shuffle").performClick()
        compose.waitUntil(15000) { com.shike.timetable.theme.CompanionVoicePlayback.sessions.value?.phase==com.shike.timetable.theme.VoicePhase.Playing }
        val token=com.shike.timetable.theme.CompanionVoicePlayback.sessions.value!!.token
        compose.onNodeWithTag("companion_portrait_shuffle").assertIsNotEnabled()
        compose.onNodeWithTag("daily_quote_text",useUnmergedTree=true).performClick()
        assertNotEquals(first,line())
        assertEquals(token,com.shike.timetable.theme.CompanionVoicePlayback.sessions.value!!.token)
        compose.onNodeWithTag("daily_quote_copy").performClick();assertEquals(line(),clipboard())
        compose.waitUntil(30000) { com.shike.timetable.theme.CompanionVoicePlayback.sessions.value==null }
        compose.onNodeWithTag("companion_portrait_shuffle").assertIsEnabled()
    }
    @Test fun optionalCardSwitchTapLongPressCopyAndRecreationPreserveSchedule() {
        val before=ScheduleStore(context).read()
        compose.onNodeWithTag("daily_quote_card").assertDoesNotExist()
        compose.onNodeWithText("留一点时间，做喜欢的事。",substring=true).assertDoesNotExist()
        compose.onNodeWithText("把时间留给热爱").assertDoesNotExist()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("daily_quote_enabled").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("课表").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("daily_quote_text",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        val first=line()
        compose.onNodeWithTag("daily_quote_text",useUnmergedTree=true).performClick()
        val second=line();assertNotEquals(first,second)
        compose.onNodeWithTag("daily_quote_copy").performClick()
        assertEquals(second,clipboard());assertEquals(second,line())
        compose.onNodeWithTag("daily_quote_text",useUnmergedTree=true).performTouchInput { longClick() }
        assertEquals(second,clipboard());assertEquals(second,line())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("daily_quote_text",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(second,line())
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("daily_quote_enabled").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("daily_quote_card").assertDoesNotExist()
        assertEquals(before,ScheduleStore(context).read())
    }
}
