package com.opicih.coach.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opicih.coach.AppContainer
import com.opicih.coach.data.db.PracticeMode
import com.opicih.coach.ui.screens.CoachScreen
import com.opicih.coach.ui.screens.ExamsScreen
import com.opicih.coach.ui.screens.FramesScreen
import com.opicih.coach.ui.screens.HomeScreen
import com.opicih.coach.ui.screens.ListenScreen
import com.opicih.coach.ui.screens.ImportScreen
import com.opicih.coach.ui.screens.MockScreen
import com.opicih.coach.ui.screens.MorningScreen
import com.opicih.coach.ui.screens.NextExamScreen
import com.opicih.coach.ui.screens.ProgressScreen
import com.opicih.coach.ui.screens.SailConversationScreen
import com.opicih.coach.ui.screens.ScriptDetailScreen
import com.opicih.coach.ui.screens.ScriptEditScreen
import com.opicih.coach.ui.screens.ScriptsScreen
import com.opicih.coach.ui.screens.SettingsScreen
import com.opicih.coach.ui.screens.SpeakingScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "홈", Icons.Filled.Home),
    Tab("scripts", "스크립트", Icons.AutoMirrored.Filled.MenuBook),
    Tab("speaking", "말하기", Icons.Filled.Mic),
    Tab("mock", "모의시험", Icons.Filled.Assignment),
    Tab("progress", "기록", Icons.Filled.Insights),
)

private val titles = mapOf(
    "script/{id}" to "스크립트", "scriptEdit/{id}" to "스크립트 편집", "import" to "스크립트 가져오기",
    "settings" to "설정", "nextExam" to "다음 OPIc 추천", "exams" to "시험 일정", "sail" to "SAIL 대화 연습", "morning" to "아침 표현 루틴", "coach" to "AI 코치",
    "listen" to "스크립트 듣기", "review/{id}" to "다시 말하기",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(startRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { route?.startsWith(it.route) == true && !route.startsWith("scripts/") }

    // Study time: only counted while the app is RESUMED on a study screen.
    val container = LocalContainer.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val studyRoute = isStudyRoute(route)
    LaunchedEffect(studyRoute) {
        if (!studyRoute) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = android.os.SystemClock.elapsedRealtime()
            try {
                while (true) {
                    delay(15_000)
                    val t = android.os.SystemClock.elapsedRealtime()
                    container.studyTime.add(t - last)
                    last = t
                }
            } finally {
                withContext(NonCancellable) { container.studyTime.add(android.os.SystemClock.elapsedRealtime() - last) }
            }
        }
    }

    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            when (startRoute) {
                "morning" -> nav.navigate("morning") { launchSingleTop = true }
                "speaking" -> nav.goTab("speaking")
                "nextExam" -> nav.navigate("nextExam")
                "listen" -> nav.navigate("listen") { launchSingleTop = true }
            }
            onRouteConsumed()
        }
    }

    Scaffold(
        topBar = {
            if (!isTab && route != "morning") CenterAlignedTopAppBar(
                title = { Text(titles[route] ?: "") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (isTab) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route?.startsWith(t.route) == true,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") {
                HomeScreen(
                    onSpeak = { nav.goTab("speaking") },
                    onMorning = { nav.navigate("morning") { launchSingleTop = true } },
                    onScript = { nav.navigate("script/$it") },
                    onMock = { nav.goTab("mock") },
                    onScripts = { nav.goTab("scripts") },
                    onCoach = { nav.navigate("coach") },
                    onSettings = { nav.navigate("settings") },
                    onNextExam = { nav.navigate("nextExam") },
                    onExams = { nav.navigate("exams") },
                    onSpeakingReview = { nav.navigate("review/$it") { launchSingleTop = true } },
                    onSpeakMode = { m ->
                        when (m) {
                            null -> nav.goTab("speaking")
                            PracticeMode.SAIL_CONVERSATION.name -> nav.navigate("sail")
                            else -> nav.navigate("speaking?mode=$m")
                        }
                    },
                )
            }
            composable("scripts") {
                ScriptsScreen(onOpen = { nav.navigate("script/$it") }, onNew = { nav.navigate("scriptEdit/0") }, onImport = { nav.navigate("import") }, onFrames = { nav.navigate("frames") }, onListen = { nav.navigate("listen") })
            }
            composable(
                "speaking?scriptId={scriptId}&mode={mode}",
                arguments = listOf(
                    navArgument("scriptId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e ->
                val id = e.arguments?.getLong("scriptId")?.takeIf { it > 0 }
                val mode = e.arguments?.getString("mode")?.let { PracticeMode.of(it) }
                SpeakingScreen(id, mode, onOpenFrames = { nav.navigate("frames") })
            }
            composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                SpeakingScreen(null, PracticeMode.FREE, reviewId = e.arguments!!.getLong("id"), onOpenFrames = { nav.navigate("frames") })
            }
            composable("frames") { FramesScreen() }
            composable("mock") { MockScreen() }
            composable("progress") { ProgressScreen(onNextExam = { nav.navigate("nextExam") }) }
            composable("script/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ScriptDetailScreen(
                    id = e.arguments!!.getLong("id"),
                    onPractice = { sid, m -> nav.navigate("speaking?scriptId=$sid&mode=${m.name}") },
                    onEdit = { nav.navigate("scriptEdit/$it") },
                    onDeleted = { nav.popBackStack() },
                )
            }
            composable("scriptEdit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ScriptEditScreen(e.arguments!!.getLong("id")) { saved ->
                    nav.popBackStack()
                    if (e.arguments!!.getLong("id") == 0L) nav.navigate("script/$saved")
                }
            }
            composable("import") { ImportScreen(onDone = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(onExams = { nav.navigate("exams") }) }
            composable("exams") { ExamsScreen() }
            composable("sail") { SailConversationScreen() }
            composable("coach") { CoachScreen() }
            composable("listen") { ListenScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("nextExam") { NextExamScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("morning") {
                MorningScreen(
                    onReviewScript = { nav.navigate("speaking?scriptId=$it&mode=${PracticeMode.BLIND.name}") },
                    onExit = { nav.popBackStack() },
                )
            }
        }
    }
}

/** Routes whose open time counts as study (not home / progress / settings / exams / nextExam / import / scriptEdit). */
internal fun isStudyRoute(route: String?): Boolean = route != null && (
    route == "scripts" || route == "script/{id}" || route == "review/{id}" ||
        listOf("speaking", "mock", "sail", "coach", "morning").any { route.startsWith(it) }
    )

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
