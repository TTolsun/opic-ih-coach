# 아키텍처

단일 `app` 모듈, 수동 DI(`AppContainer`), MVVM을 가볍게 적용했습니다. 1인용 앱이라 Hilt·멀티 모듈 같은 추가 추상화는 쓰지 않았습니다.

## 1. 패키지 구조

```
com.opicih.coach
├── App.kt              Application + AppContainer(수동 DI). 첫 실행 seed, 알람 예약, 오디오 정리
├── MainActivity.kt     Compose 호스트, 알림 딥링크(route=morning/speaking)
├── analysis/           순수 Kotlin, 단위 테스트 대상
│   ├── TextNormalizer      축약형, 숫자, 간이 lemma, 동의어, 문장 분할
│   ├── ScriptComparator    순서 무관 내용 일치율, 문장별 커버리지, 키워드, LCS 순서 유사도
│   ├── DiscourseAnalyzer   시제·연결 표현·경험 표지·필러·반복·어휘 다양성
│   ├── PauseAnalyzer       음량 타임라인 → 실발화/무음/2초+ 무음
│   └── FeedbackComposer    지표 → 잘한 점 2 / 고칠 점 ≤3 / 미션 / 학습용 점수
├── importer/           ScriptParser, QuestionClassifier, DuplicateLinker, DocxTextExtractor, ImportService(PDF/TXT/DOCX)
├── learning/           SrsScheduler, MasteryRules, PlanGenerator, RetestCalculator, ProgressCalculator,
│                       MorningRoutineBuilder, MockTestBuilder  (모두 순수 함수)
├── speech/             TtsManager, LiveRecognizer, PcmRecorder, AudioPlayer, AnswerCapture, SpeechCapabilities
├── ai/                 AiCoach(인터페이스), GeminiAiCoach, LocalCoach, Prompts, Transcriber/GeminiTranscriber
├── alarm/              AlarmScheduler, AlarmReceiver, BootReceiver, Notifications, ReminderWorker
├── data/
│   ├── db/             Room 엔티티 14개, AppDao, AppDatabase, MigrationSql
│   ├── prefs/          SettingsRepository (DataStore)
│   ├── secure/         ApiKeyStore (Android Keystore AES-GCM)
│   ├── repo/           ScriptRepository, PracticeRepository (분석·저장·SRS·로그·오류 노트를 한곳에서)
│   ├── seed/           SeedLoader (assets/seed/*.json)
│   └── backup/         BackupManager (JSON 백업/복원, 오디오 보관 관리)
└── ui/                 theme, components, AppNav(5탭 + 상세 라우트), screens/*
```

## 2. 핵심 데이터 흐름: 말하기 1회

```
SpeakingScreen
   │ 마이크 버튼
   ▼
SpeakingViewModel.start()
   │  AnswerCapture.start(LIVE_STT | RECORD_ONLY)
   ▼
(사용자 발화, 100ms마다 경과 시간·음량·부분 인식 표시)
   │ 다시 누름 / 3분 / 앱 백그라운드
   ▼
AnswerCapture.stop()
   ├─ LIVE_STT: 누적 텍스트 + onRmsChanged dB → PauseAnalyzer
   └─ RECORD_ONLY: WAV + 20ms RMS → PauseAnalyzer, (선택) Transcriber
   ▼
PracticeRepository.analyzeAndSave()
   ├─ DiscourseAnalyzer, ScriptComparator, FeedbackComposer (항상)
   ├─ GeminiAiCoach.evaluate (켜져 있을 때만, 실패 시 로컬 결과 유지)
   ├─ attempts 저장 (metricsJson, feedbackJson, feedbackSource)
   ├─ daily_logs 갱신 (실발화 시간)
   ├─ 스크립트 MasteryRules + SrsScheduler 갱신
   └─ error_patterns 누적
   ▼
결과 카드 인라인 표시 (화면 전환 없음) → 다시 말하기
```

## 3. 데이터베이스 (Room v3, 스키마: `app/schemas/`, v1→v2→v3 마이그레이션 포함)

| 테이블 | 주요 필드 | 용도 |
|---|---|---|
| `scripts` | topic, title, question, questionType, **original**, natural, ihTarget, shortVersion, keywords, activeVersion, koreanMeaning, source, masteryState, masteryScore, srsStep, dueEpochDay, linkedGroupId, storyIds, importId | 스크립트와 학습 상태. original은 자동으로 덮어쓰지 않음 |
| `script_revisions` | scriptId, version, text, reasonKo, origin(AI_CLAUDE/PREBUILT/USER/LOCAL), status(PENDING/APPROVED/REJECTED) | 승인 전 개선안 보관 |
| `import_sources` | fileName, mime, rawText, parsedCount | 가져온 파일 원문 보존 |
| `attempts` | scriptId, topic, questionText, mode, transcript, captureMode, audioPath, durationMs, speechMs, longPauseCount, wordCount, wpm, coverage, score, metricsJson, feedbackJson, feedbackSource, mockId | 모든 발화 기록 |
| `error_patterns` | key(unique), category, example, correction, count | 반복 오류 노트 |
| `chunks` | phrase, meaningKo, example, category, srsStep, dueEpochDay, lastResult, failCount | 아침 표현 |
| `daily_logs` | epochDay, speakingMs, attempts, scriptsReviewed, chunksReviewed, mockDone, morningDone | 완료율·연속일 |
| `mock_tests` | startedAt, finishedAt, questionCount, selfLevel, reportJson | 모의시험 |
| `exams` | epochDay, type, resultGrade, note, topicsSeen, stuckPoints, feeling, selfLevelUsed, debriefAt | v2 시험 결과와 복기. v3부터 일정은 `exam_registrations`가 담당하고, 이 테이블은 복기 데이터 보관용으로 유지 |
| `exam_registrations` (v3) | examType(OPIC/SAIL/UNKNOWN), dateLocal("2026-11-11" 또는 "2026-02"), datePrecision(DAY/MONTH), start/endTimeLocal, zoneId(Asia/Seoul), location, status(PLANNED/REGISTERED/COMPLETED/CANCELLED/NO_SHOW), resultGrade, waiverUsed(null=모름), sourceType(USER/EMAIL_IMPORT/OFFICIAL/MIGRATED), verifiedAt, needsReview, legacyExamId | 시험 일정의 단일 출처. 사실만 저장하고 추천은 저장하지 않음 |
| `exam_status_events` (v3) | registrationId, fromStatus, toStatus, effectiveDate, note | 상태 변경 이력. 취소 이력과 실제 응시를 구분 |
| `exam_training_sessions` (v3) | examType, kind, startedAt, finishedAt, turnCount, aiCalls, summaryJson | 시험별 묶음 훈련(A2 SAIL 대화에서 사용 예정). 각 턴은 `attempts.trainingSessionId`로 연결 |
| `stories` | id(S1~S7), titleKo, summary, reusableFor | 개인 경험 스토리 뱅크 |
| `questions` | topic, text, type, source(USER_LIST/AI_GENERATED) | 질문 은행 |

v2→v3 마이그레이션은 테이블과 nullable 컬럼만 추가합니다. 기존 `exams` 행은 `UNKNOWN + needsReview`로 복사하고, 결과나 복기가 있는 행만 COMPLETED로 둡니다. SQL은 `MigrationSql`에 문자열로 두어 JVM 테스트(`MigrationTest`)가 같은 SQL을 실제 SQLite에서 실행합니다.

앱 시작 시 `ExamRepository.bootstrapIfNeeded()`가 한 번만 실행됩니다 (`ExamBootstrap`, 순수 함수). 사용자가 확인한 사실(SAIL 11/11 접수, SAIL 11/18 11/03 취소, OPIc 2026-02 응시)을 같은 유형·날짜 기록이 없을 때만 추가하고, 예전 `Settings.examDate`는 같은 날 기록이 없으면 `UNKNOWN + PLANNED + needsReview`로 옮깁니다. 기존 기록은 덮어쓰지 않습니다.

설정(DataStore): 시험일(v2 값, v3부터 계획에 쓰지 않음), 학습량, 알람(시간/소리/진동/정확), 저녁 리마인더, Survey, Self Assessment, 캡처 방식, TTS 속도, AI(사용/모델/텍스트 동의), 클라우드 전사(사용/음성 동의), 오디오 보관일, seed 버전, 모의시험 집중 주제.

## 4. AI 모듈

```kotlin
interface AiCoach {
    val isRealAi: Boolean
    suspend fun improveScript(input: ScriptInput): Result<ScriptVersions>  // Natural / IH / Short / Keywords / 변경 이유 / 한국어 뜻
    suspend fun evaluate(input: EvalInput): Result<Feedback>              // 잘한 점 2, 고칠 점 ≤3, 예시 답변, 미션, 오류 패턴
    suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String>
}
```

| 구현 | 조건 | 비고 |
|---|---|---|
| `GeminiAiCoach` (기본) | 제공자 Gemini + 키 + AI 사용 + 텍스트 동의 | REST v1beta `generateContent`, systemInstruction + JSON 응답 모드(responseMimeType). 차단(blockReason/finishReason), 400/403/404/429를 한국어 메시지로 변환. `listModels`로 키에서 쓸 수 있는 모델 조회 |
| `LocalCoach` | 그 외 | 규칙 기반. Short/Keywords만 생성, 채팅은 오프라인 질문·오류·취약 주제 응답 |

`Transcriber` 인터페이스: `GeminiTranscriber`(WAV를 inline_data로 전송, 필러·반복·문법 실수를 그대로 받아 적도록 프롬프트 지정, temperature 0). AI 코치와 같은 Gemini 키와 모델을 씁니다.

## 5. 음성 모듈

| 클래스 | 역할 |
|---|---|
| `TtsManager` | Android TTS, 영어(US) 지원 여부 탐지, 속도 조절, 완료 대기 |
| `LiveRecognizer` | SpeechRecognizer 자동 재시작 세션, 부분 결과, dB 타임라인, 오류 코드 한국어 변환 |
| `PcmRecorder` | AudioRecord 16kHz mono → WAV, 20ms RMS, 10분 상한, finally에서 해제 |
| `AnswerCapture` | 두 캡처 방식을 하나의 start/stop API로 묶음 (Speaking·Mock 공용) |
| `AudioPlayer` | MediaPlayer 1개만 유지, 0.75x 재생 |
| `SpeechCapabilities` | 인식 서비스·온디바이스 인식 지원 여부 실행 시 탐지 |
| `ListenTts` | 스크립트 듣기 전용 TTS. 영어와 한국어를 단계마다 바꿔 읽고 끝날 때까지 기다립니다. 질문은 여성, 답변은 남성 음성을 쓰며 성별은 음성 이름으로 추정합니다(`VoiceGenders`) |

### 스크립트 듣기 (이슈 #22)

```
ListenScreen ──명령──▶ ListenService(Foreground, mediaPlayback) ──▶ ListenPlayer(상태 기계)
     ▲                    │ MediaSession · 오디오 포커스 · WakeLock · SilentKeepAlive │ ShuffleQueue → ListenCycleBuilder
     └────── state ───────┴────────────────────────────────────────────────────────┘          └▶ ListenSpeaker(ListenTts)
```

- 재생 순서는 질문(영·한) → 답변 전체(영·한) → 문단(영·한) → 문장(영·한)이고, 스크립트는 `ShuffleQueue`로 한 바퀴 동안 겹치지 않게 고릅니다. 문단과 문장은 스크립트 안에서 매번 무작위로 고릅니다.
- **영한 정렬 데이터(`script_segments`, DB v6):** 학습본을 `ScriptSegmenter`가 코드로 먼저 문장·문단으로 나누고(빈 줄이 없으면 한 문단에 문장 3개 이하로 균등 분할), 번역은 문장마다만 만듭니다. 문단과 전체 답변의 한국어는 속한 문장 번역을 이어 붙인 것이라 영어와 항상 같은 범위입니다. 질문·학습본의 해시(`sourceHash`)가 달라지면 그 행들은 낡은 것으로 보고 쓰지 않습니다. 백업(v6)에 포함됩니다.
- **번역을 채우는 두 경로:** (1) `assets/seed/segments_ko.json`을 앱 시작 때 `SegmentRepository.importBundled`가 가져옵니다(해시와 영어 문장 목록이 모두 맞는 스크립트만, 이미 있는 번역은 덮어쓰지 않음). (2) 재생 화면의 "번역 만들기"가 `AiCoach.translateToKorean`(Gemini)으로 번역이 없거나 낡은 스크립트를 만듭니다(시범 2개 먼저). 개수가 맞지 않는 응답은 저장하지 않습니다.
- 번역이 없으면 `koreanMeaning`을 쓰되, 지금 학습본의 번역이라고 믿을 수 있을 때만 재생합니다(`koreanMatchesStudyText`). 그것도 아니면 한국어 단계를 건너뜁니다.
- **목소리:** 질문은 여성, 답변은 남성 음성으로 읽습니다(영어·한국어 모두). 안드로이드가 성별을 알려 주지 않아 Google TTS 음성 이름의 코드로 추정하며, 화면에 "추정"으로 표시하고 미리듣기로 확인하게 합니다.
- **이어폰 버튼:** TTS 소리는 TTS 엔진 앱이 내서 시스템이 이 앱을 오디오 재생 앱으로 보지 않고, 그러면 미디어 버튼이 음악 앱 세션으로 갑니다. 그래서 재생 중에만 `SilentKeepAlive`가 무음 오디오를 이 프로세스에서 재생합니다.
- 모든 명령은 서비스를 거칩니다. 화면은 `ListenPlayer.state`를 읽기만 합니다. 전화·다른 앱이 오디오 포커스를 가져가거나 이어폰이 빠지면 일시정지하고, 자동으로 다시 재생하지 않습니다.
- 읽기에 3번 연속 실패하거나 읽을 단계가 3사이클 연속 없으면 스스로 멈춰서 무한 반복을 막습니다.

## 6. 학습 엔진

- **SRS**: 고정 간격 [1, 2, 4, 7, 14]일. 성공 시 한 단계 위, 실패 시 1일. 시험 전에는 다음 복습일이 D-1을 넘지 않도록 제한.
- **마스터리**: NEW → LEARNING → NO_SCRIPT_OK(Blind/다른 표현 70%+) → VARIATION_OK(응용 60점+) / 하락 시 NEEDS_REVIEW.
- **시험 기준 (`ExamSchedule`)**: 계획과 D-day의 기준은 "아직 끝나지 않은 가장 가까운 접수 완료(REGISTERED) 시험"입니다. 접수 예정(PLANNED)은 기준이 되지 않습니다. 시험 종료 시각(종료 시각, 없으면 시작+1시간, 없으면 그날 끝)이 지나면 다음 시험으로 넘어가고, 응시 여부는 사용자에게 묻습니다. 시각 비교는 휴대폰 시간대와 관계없이 KST로 합니다.
- **SAIL 계획 (`PlanGenerator.forExam`)**: D-8 이상 공통 기본기, D-7~D-4 즉답·추가 질문, D-3~D-2 실전 대화 연습, D-1 가벼운 복습(15분 이하), D-Day 워밍업(10분 이하). 30분 기준 표현 5 · 키워드 5 · 즉답+추가 질문 10 · 약점 교정 5 · 같은 질문 다시 5. OPIc 시험은 기존 스프린트를 그대로 씁니다.
- **SAIL 대화 (`SailConversation.kt`, `SailConversationViewModel`)**: 학습용 시뮬레이션입니다. 첫 질문은 내장 질문 15개 또는 SAIL 복기에 적은 실제 질문("Q: " 줄)에서 고릅니다. 추가 질문은 Claude(`AiCoach.followUp`)가 만들고, AI가 꺼져 있거나 실패하거나 `AiBudget` 상한(대화당 6회, 하루 `aiDailyCap`회)에 닿으면 `LocalFollowUp`이 만듭니다. `LocalFollowUp`은 키워드 규칙(문제 상황 → 좋아하는 것 → 과거 경험 → 계획 → 짧은 답 → 일반) 순서로 직전 답변의 구절을 되묻고, 한 대화에서 같은 템플릿을 반복하지 않습니다. 턴마다 `attempts`에 SAIL_CONVERSATION 모드로 저장하되 턴별 AI 평가는 하지 않고, 끝에 `ConversationScoring`(로컬) 결과와 선택적 Claude 종합 피드백 1회를 `exam_training_sessions.summaryJson`에 저장합니다. 답변 시작 시간은 마이크가 질문 음성 직후에 켜지므로 `PauseMetrics.leadingSilenceMs`로 잽니다. 직접 입력한 답변은 시간 지표에서 빠집니다.
- **계획**: 매일 "남은 일수"로 다시 계산. 7일 이상이면 템플릿, 그보다 적으면 D-1 가벼운 복습과 D-2 모의시험을 고정하고 나머지는 우선순위(자유 발화 > 과거·비교 > 롤플레이 > 핵심 > 진단)로 채움. 시험 후에는 12월까지 7일 주기 장기 훈련.
- **완료율**: 발화 시간 60% + 아침 루틴 20% + 복습 20%. 앱을 켜 둔 시간은 세지 않음.
- **재응시 규칙 (`RetakeEngine.kt`, `RetakeSummary.kt`)**: `ExamPolicy`가 시험별 제한 일수, Waiver 지원 여부, 재부여 일수, 제한 공유 그룹, 출처, 검증 상태를 따로 가집니다. OPIc은 공식 시험관리규정 제5조(D+26부터 응시, Waiver 150일 1회, `Verification.OFFICIAL`), SAIL은 설정값(기본 25일, Waiver `UNKNOWN`, 미검증)이고 "SAIL과 OPIc 제한 공유" 설정으로 같은 그룹이 됩니다. `WaiverLedger`는 잔여 수를 직접 줄이지 않고, 사용자가 입력한 보유 수(입력일 기준)에서 이후 `waiverUsed = true`인 등록을 빼고 150일이 지난 사용분은 되돌립니다. 결시·취소된 시험의 Waiver 복구는 규정에 없어 사용한 것으로 보수적으로 계산합니다. `RetakeEngine.paths`는 Waiver 없이 / Waiver 사용 두 경로의 가장 빠른 날과 마감 전 기회 수를 계산하며, 날짜는 규정상 가능일로만 표시합니다.
- **약점 기반 계획 (`WeaknessAnalyzer`, `WeaknessPlanner`)**: 최근 14일의 실제 기록에서 공통 역량(`SharedSpeakingCompetency`: 유창성, 답변 길이·연결, 반복 문법 오류, 과거 경험 시제, 즉답, 추가 질문 대응, 표현 반복)의 약점을 찾습니다. 규칙마다 최소 표본 3개가 필요하고, 근거 문장(예: "2초 이상 멈춤이 답변당 평균 2.0회")을 함께 저장합니다. 발음은 측정하지 않으므로 역량 목록에 없습니다. 가장 심한 약점 1개가 오늘 계획의 약점 교정 항목(없으면 마지막 말하기 항목)을 같은 시간으로 바꾸고, 시험이 없을 때는 장기 주기의 주제를 고릅니다. 시험 전날·당일과 모의시험 날은 바꾸지 않습니다. 홈의 "가장 부족한 역량" 카드와 아침 알림이 같은 결과를 씁니다.
- **시험 알림 (`ExamReminders`)**: 접수 완료이고 시작 시각이 있는 시험마다 전날 20:00과 당일 1회(SAIL은 입실 마감 30분 전, 그 밖에는 시작 1시간 전)만 예약합니다. 일정이 바뀌면 `App`이 `exam_registrations` Flow를 보고 다시 계산하고, 접수 완료가 아닌 시험의 알림은 취소합니다.
- **다음 시험 추천 (`RetestAdvisor`)**: 입력은 두 가지입니다.
  1. 달력 제약: OPIc 재응시 제한(공식 25일, 응시일 제외), "다음 OPIc 추천에 Waiver 사용" 설정, 결과 발표까지 일수(기본 10일), 등급 마감일(기본 12/31), 사내 회차(선택), 접수 마감 리드타임(기본 7일)
  2. 준비도 조건 5개: 연습량(10일 또는 150분), 자유 발화 평균 단어 수, 긴 무음 답변당 1회 이하, 말하기 점수, 시험 후 모의시험 1회 이상. 단어 수·점수·모의시험 기준은 다음 시험 목표 등급(`ReadinessCriteria`)에 따라 달라지며 IH 기준이 90단어, 65점, 60점입니다. 말하기 점수는 스크립트 암송 모드(Blind 등)를 제외한 즉석 답변만 집계합니다(`ScoreKind`).
  판정: 첫 시험 전에는 잠정 계획, 조건 4개 이상 통과 시 가장 빠른 날짜, 부족하면 필요한 일수만큼 늦추되 마감 전 결과가 나올 수 없으면 가장 빠른 날짜로 신청을 권합니다. 시험 직후 복기에서 고른 "막힌 주제"는 자유 발화·모의시험 우선 주제로 들어갑니다.
