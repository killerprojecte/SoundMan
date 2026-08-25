package hk.uwu.soundman.ui.basic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import hk.uwu.soundman.ui.components.LiquidBottomTab
import hk.uwu.soundman.ui.components.LiquidBottomTabs
import hk.uwu.soundman.ui.components.LiquidTopBarButton
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

class TopBarFallbackInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyTopBarStyleRendersWithoutBackdrop() {
        composeRule.setContent {
            MiuixTheme {
                Column {
                    TopBarStyle.entries.forEach { style ->
                        AppTopBar(
                            title = "style-${style.name}",
                            style = style,
                            backdrop = null,
                        )
                    }
                }
            }
        }

        TopBarStyle.entries.forEach { style ->
            composeRule.onNodeWithText("style-${style.name}").assertIsDisplayed()
        }
    }

    @Test
    fun liquidNavigationControlsRenderWithoutBackdrop() {
        composeRule.setContent {
            MiuixTheme {
                Column {
                    LiquidTopBarButton(
                        onClick = {},
                        backdrop = null,
                        icon = Icons.Rounded.Home,
                        contentDescription = "fallback-top-bar-action",
                    )
                    Row {
                        LiquidBottomTabs(
                            selectedTabIndex = { 0 },
                            onTabSelected = {},
                            backdrop = null,
                            tabsCount = 2,
                            modifier = Modifier.width(240.dp),
                        ) {
                            LiquidBottomTab(onClick = {}) { Text("fallback-tab-one") }
                            LiquidBottomTab(onClick = {}) { Text("fallback-tab-two") }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("fallback-top-bar-action").assertIsDisplayed()
        composeRule.onNodeWithText("fallback-tab-one").assertIsDisplayed()
        composeRule.onNodeWithText("fallback-tab-two").assertIsDisplayed()
    }
}
