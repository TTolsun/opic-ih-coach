package com.opicih.coach.ui

import androidx.compose.foundation.layout.padding
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import com.opicih.coach.ui.screens.ExamDebriefScreen
import com.opicih.coach.ui.screens.ExamsScreen
import com.opicih.coach.ui.screens.FramesScreen
import com.opicih.coach.ui.screens.HomeScreen
import com.opicih.coach.ui.screens.SpeakingDiagnosticScreen
import com.opicih.coach.ui.screens.HotelRoleplayScreen
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
import java.time.LocalDate

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "홈", Icons.Filled.Home),
    Tab("scripts", "스크립트", Icons.AutoMirrored.Filled.MenuBook),
    Tab("speaking", "말하기", Icons.Filled.Mic),
    Tab("progress", "기록", Icons.Filled.Insights),
)

private const val SCRIPT_REVIEW = "scriptReview/{ids}/{index}/{mode}"

private val titles = mapOf(
    SCRIPT_REVIEW to "내 대본 복습", "mockReport/{id}" to "모의시험 결과", "mockRestart/{count}" to "모의시험",
    "speakingHotel" to PracticeMode.HOTEL_ROLEPLAY.label, "frames" to "문장 틀",
    "script/{id}" to "스크립트", "scriptEdit/{id}" to "스크립트 편집", "import" to "스크립트 가져오기",
    "settings" to "설정", "nextExam" to "다음 OPIc 추천", "exams" to "시험 일정", "sail" to "SAIL 대화 연습", "morning" to "아침 표현 루틴", "coach" to "AI 코치",
    "mock" to "모의시험", "recall?scriptId={scriptId}&question={question}" to "질문 듣고 답하기", "listen" to "스크립트 듣기", "review/{id}" to "다시 말하기", "diagnostic" to PracticeMode.DIAGNOSTIC.label,
    "pendingRevisions" to "승인 대기 개선안", "examDebrief/{day}/{registrationId}" to "시험 복기",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(startRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    fun recall(id: Long, question: String) {
        nav.navigate("recall?scriptId=$id&question=${android.net.Uri.encode(question)}")
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabRoute = route?.substringBefore('?')
    val isTab = tabs.any { it.route == tabRoute }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

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
                navigationIcon = { IconButton(onClick = { backDispatcher?.onBackPressed() ?: nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (isTab) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tabRoute == t.route,
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
                    onRecall = { nav.navigate("recall") },
                    onSpeak = { nav.goTab("speaking") },
                    onMorning = { nav.navigate("morning") { launchSingleTop = true } },
                    onScript = { nav.navigate("script/$it") },
                    onMock = { nav.navigate("mock") },
                    onRecentMock = { mock ->
                        nav.navigate(if (mock.reportJson != null) "mockReport/${mock.id}" else "mockRestart/${mock.questionCount}")
                    },
                    onScriptReview = { ids, mode ->
                        if (ids.isNotEmpty()) nav.navigate("scriptReview/${ids.joinToString(",")}/0/${mode.name}") { launchSingleTop = true }
                    },
                    onReviewRevisions = { nav.navigate("pendingRevisions") },
                    onCoach = { nav.navigate("coach") },
                    onSettings = { nav.navigate("settings") },
                    onNextExam = { nav.navigate("nextExam") },
                    onDebrief = { date, registrationId -> nav.navigate("examDebrief/${date.toEpochDay()}/${registrationId ?: 0L}") },
                    onExams = { nav.navigate("exams") },
                    onDiagnostic = { nav.navigate("diagnostic") { launchSingleTop = true } },
                    onSpeakingReview = { nav.navigate("review/$it") { launchSingleTop = true } },
                    onSpeakMode = { m ->
                        when (m) {
                            null -> nav.goTab("speaking")
                            PracticeMode.HOTEL_ROLEPLAY.name -> nav.navigate("speakingHotel")
                            PracticeMode.DIAGNOSTIC.name -> nav.navigate("diagnostic")
                            PracticeMode.SAIL_CONVERSATION.name -> nav.navigate("sail")
                            else -> nav.navigate("speaking?mode=$m")
                        }
                    },
                )
            }
            listOf("scripts", "pendingRevisions").forEach { listRoute ->
                composable(listRoute) {
                    ScriptsScreen(
                        onOpen = { nav.navigate("script/$it") },
                        onNew = { nav.navigate("scriptEdit/0") },
                        onImport = { nav.navigate("import") },
                        onFrames = { nav.navigate("frames") },
                        onListen = { nav.navigate("listen") },
                        pendingOnly = listRoute == "pendingRevisions",
                    )
                }
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
                SpeakingScreen(
                    id, mode, onQuestion = ::recall,
                    onOpenFrames = { nav.navigate("frames") },
                    onHotelRoleplay = { nav.navigate("speakingHotel") },
                    onDiagnostic = { nav.navigate("diagnostic") },
                )
            }
            composable(SCRIPT_REVIEW, arguments = listOf(navArgument("index") { type = NavType.IntType })) { e ->
                val args = requireNotNull(e.arguments)
                val ids = requireNotNull(args.getString("ids")).split(",").map(String::toLong)
                val index = args.getInt("index")
                val mode = PracticeMode.of(args.getString("mode"))
                SpeakingScreen(
                    ids[index], mode, reviewPosition = "${index + 1} / ${ids.size}",
                    nextLabel = if (index == ids.lastIndex) "복습 마치기" else "다음 복습 대본",
                    onReviewNext = {
                        if (nav.currentBackStackEntry == e) {
                            if (index == ids.lastIndex) nav.popBackStack()
                            else nav.navigate("scriptReview/${ids.joinToString(",")}/${index + 1}/${mode.name}") {
                                popUpTo(e.destination.id) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                SpeakingScreen(
                    null, PracticeMode.FREE, reviewId = e.arguments!!.getLong("id"), onQuestion = ::recall,
                    onOpenFrames = { nav.navigate("frames") },
                    onHotelRoleplay = { nav.navigate("speakingHotel") },
                    onDiagnostic = { nav.navigate("diagnostic") },
                )
            }
            composable(
                "recall?scriptId={scriptId}&question={question}",
                arguments = listOf(
                    navArgument("scriptId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("question") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                SpeakingScreen(entry.arguments?.getLong("scriptId"), PracticeMode.VARIATION,
                    recall = true, prompt = entry.arguments?.getString("question"),
                    onMemorize = { nav.navigate("speaking?scriptId=$it&mode=${PracticeMode.READ_LISTEN.name}") },
                    onScripts = { nav.goTab("scripts") })
            }
            composable("frames") { FramesScreen() }
            // An upgrade can restore a back stack that still points to the former catalogue.
            composable("stories") {
                LaunchedEffect(Unit) {
                    nav.navigate("scripts") {
                        popUpTo("stories") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable("mock") { MockScreen() }
            composable("mockReport/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                MockScreen(reportId = e.arguments!!.getLong("id"))
            }
            composable("mockRestart/{count}", arguments = listOf(navArgument("count") { type = NavType.IntType })) { e ->
                MockScreen(restartCount = e.arguments!!.getInt("count"))
            }
            composable("speakingHotel") { HotelRoleplayScreen() }
            composable("diagnostic") { SpeakingDiagnosticScreen() }
            composable("progress") { ProgressScreen(onNextExam = { nav.navigate("nextExam") }, onExams = { nav.navigate("exams") }) }
            composable("script/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ScriptDetailScreen(
                    id = e.arguments!!.getLong("id"),
                    onQuestion = ::recall,
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
            composable("settings") { SettingsScreen() }
            composable("exams") { ExamsScreen { date, id -> nav.navigate("examDebrief/${date.toEpochDay()}/$id") } }
            composable("sail") { SailConversationScreen() }
            composable("coach") { CoachScreen() }
            composable("listen") { ListenScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("nextExam") {
                NextExamScreen(onOpenSettings = { nav.navigate("settings") }, onOpenExams = { nav.navigate("exams") })
            }
            composable(
                "examDebrief/{day}/{registrationId}",
                arguments = listOf(navArgument("day") { type = NavType.LongType }, navArgument("registrationId") { type = NavType.LongType }),
            ) { e ->
                val args = requireNotNull(e.arguments)
                ExamDebriefScreen(LocalDate.ofEpochDay(args.getLong("day")), args.getLong("registrationId").takeIf { it > 0L })
            }
            composable("morning") {
                MorningScreen(
                    onReviewScript = { nav.navigate("scriptReview/$it/0/${PracticeMode.READ_LISTEN.name}") },
                    onExit = { nav.popBackStack() },
                )
            }
        }
    }
}

/** Routes whose open time counts as study (not home / progress / settings / exams / nextExam / import / scriptEdit). */
internal fun isStudyRoute(route: String?): Boolean = route != null && (
    route == SCRIPT_REVIEW || route.startsWith("recall?") || route == "scripts" || route == "pendingRevisions" || route == "script/{id}" || route == "review/{id}" || route == "diagnostic" ||
        listOf("speaking", "mock", "sail", "coach", "morning").any { route.startsWith(it) }
    )

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
