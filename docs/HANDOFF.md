# OPIc IH Coach — 핸드오프 문서

> 작성: 2026-11-07, 클라우드 Claude Code 세션 → PC 로컬 세션(`C:\Users\baboe\git`)으로 이관
> 이 문서를 `C:\Users\baboe\git\opic-ih-coach\docs\HANDOFF.md`로 저장하고 로컬 세션에 **"docs/HANDOFF.md를 읽고 Sprint A부터 구현 시작"** 이라고 지시하면 된다.

---

## 0-A. 진행 현황 업데이트 (2026-11-07, 로컬 PC 세션)

| 항목 | 상태 |
|---|---|
| 앱 코드 | Sprint A 전체 + Sprint A+ 대부분 작성 (Kotlin 약 6,700줄). 패키지는 `com.opicih.coach` |
| Gradle 빌드 | **검증함**: `assembleDebug` 성공 (AGP 8.13.2, Kotlin 2.2.21, KSP 2.3.4, Compose BOM 2025.10.01, compileSdk/targetSdk 36) |
| 단위 테스트 | **검증함**: 43개 통과 |
| 실기기 | **미검증**: 이 PC에 기기·에뮬레이터 없음 |
| seed | Drive Google Doc 최신본 기준 23문항(7주제). 13장의 "25문항"은 실제 문서 기준 23문항이었음 |
| 15장과 달라진 점 | Compose BOM 2026.09는 AGP 9.1 필요 → 2025.10.01 사용. `security-crypto`(EncryptedSharedPreferences, deprecated) 대신 Android Keystore AES-GCM 직접 구현 |
| 사용자 결정 | 진행 방향 "A + B 핵심", AI 키는 앱에 직접 입력, GitHub 저장소는 private |

### 다음 할 일 (실기기 검증 순서)

1. APK 설치 → 첫 실행 seed(스크립트 23, 표현 41, 질문 81) 확인
2. Speaking: 마이크 권한 거부 → 재요청, 1분 이상 답변 시 인식 자동 재시작, 결과 카드
3. 설정 > 1분 뒤 테스트 알람 → 알림 액션 3개(지금 시작/바로 말하기/10분 후), 재부팅 후 알람 유지
4. Claude 키 입력 → 개선 요청, 답변 평가, 코치 채팅 (anthropic-java Android 동작 확인)
5. 녹음 우선 모드 → 다시 듣기, 0.75x, 앱 백그라운드 전환 시 자동 정지
6. 결과를 README "검증 현황"에 기록

## 0-B. 진행 현황 업데이트 (2026-11-07 오후, 실기기 검증 후)

- 첫 시험이 **2026-11-11(수) 19:20 SAIL, DS 에듀센터 동탄Ⅱ**로 바뀌어 앱 시험일을 변경함 (D-4).
- 실기기(Galaxy S25+, Android 17) 검증 결과는 README "검증 현황" 참고. 실시간 인식 버그(분할 세션 미사용, 종료 시 결과 유실)와 녹음 음량 기준을 수정함.
- 기본 AI 모델을 사용자 선택에 따라 Claude Sonnet 5.5로 변경. 키 입력과 텍스트 전송 동의는 사용자가 직접 해야 함.
- 다음 시험 추천 기능(방향 B: 준비도 게이트 + 시험 직후 복기) 추가. **SAIL 재응시 규칙은 공개 자료로 확인하지 못함** → 25일을 미검증 기본값으로 두고 설정에서 수정 가능. 사내 회차 날짜 입력 지원.
- 남은 확인: 실제 목소리 인식 정확도, Claude 호출, 실제 재부팅 후 알람.

## 0-C. 다중 시험 A1 (2026-11-07 저녁)

- 요청서: 저장소 루트 `REQUEST_A_then_B.md` (A단계: 11/11 SAIL 전, B단계: 재응시·Waiver 규칙 엔진).
- A1 완료: Room v3(`exam_registrations`, `exam_status_events`, `exam_training_sessions`, `attempts.trainingSessionId`), 사용자 확인 시험 데이터 1회 등록, 홈 다음 시험 헤더·시험 현황, 시험 일정 화면, SAIL D-day 계획, 백업 v2.
- 고친 결함: 다음 시험 화면이 추천일을 `Settings.examDate`에 덮어쓰던 문제. 이제 추천일은 OPIc "접수 예정 (미확정)" 기록으로만 추가됩니다.
- `Settings.examDate`는 더 이상 계획에 쓰지 않습니다. 모든 화면은 `ExamRepository.focus()`를 씁니다.
- A2 완료: SAIL 대화 연습 화면(`sail` 라우트, 홈의 SAIL 계획 항목에서 연결), 오프라인 추가 질문 규칙, Claude 추가 질문·종합 피드백(비용 상한), SAIL 시험 복기와 복기 질문 재사용.
- 에뮬레이터에서 찾아 고친 문제: (1) TTS 엔진이 처음 쓰일 때 초기화 중이라 첫 질문을 읽지 못함 → 최대 4초 대기. (2) "we had a problem"이 과거 경험 규칙에 걸려 어색한 질문이 나옴 → 문제 상황 규칙 우선.
- 아직 없음: 대화 결과의 취약 역량(`weakCompetencies`)을 다음 날 계획에 반영하는 연결(C단계), 시험 당일 입실 알림.
- 다음: B단계(정답 데이터 B0 먼저).

## 0. 현재 상태 (클라우드 세션 작성 당시)

| 항목 | 상태 |
|---|---|
| 요구사항 정의 | 완료 (원문 요청서 기준, 아래 1장에 요약) |
| 방향 결정 | 완료 — **A(시험 직결 스프린트) → C(완성형) 순차**, B의 평가 엔진 일부를 A에 포함 |
| 사용자 스크립트 분석 | 완료 — Drive `2026년 OPIc Script` (7주제/25문항), 13장 참고 |
| Gradle 설정 초안 | 작성했으나 **빌드 검증 안 됨** (클라우드에서 Maven Central 429로 의존성 다운로드 실패). 15장에 그대로 수록 |
| 앱 코드 | **미작성** (0줄) |
| 공식 규정 검증 | 일부 미검증 — 14장 "확인 필요" 표 참고 |

---

## 1. 목표와 제약

- 사용자: OPIc **IM2 → IH** 목표. 기준일 2026-11-07, 1차 시험 **2026-11-14(변경 가능)**, 12월 추가 응시 계획.
- 기기: Samsung Galaxy (Android 최신). 한국어 UI, 영어 학습 콘텐츠.
- 학습 시간: 평일 30~60분, 주말 60~90분.
- 산출물: 실제 설치해 매일 쓰는 Native Android 앱 + README + 아키텍처/DB/AI 모듈 문서 + 구현/미구현 구분 + Debug APK. GHE에 올릴 예정.
- 핵심 사이클: 스크립트 분석 → 개선 → 학습 → 암기 점검 → 실제 발화 → 분석 → 약점 → 복습 계획 자동 조정.
- 금지/주의
  - 공식 기출·빈출이라고 단정 금지 (공식 / 비공식 후기 / AI 생성 3단 구분)
  - AI 점수 ≠ ACTFL 공식 등급 (항상 "학습용 참고 점수" 표기)
  - STT 텍스트만으로 음소·억양을 측정했다고 주장 금지
  - 개선본이 원본을 자동 덮어쓰기 금지 (승인 후 학습본 저장)
  - API 키 하드코딩 금지, 비용 기능은 사용자 직접 활성화, 음성 외부 전송은 동의 필수
  - 검증 안 한 기능을 검증 완료라고 보고 금지

---

## 2. 결정 사항

| 결정 | 내용 | 이유 |
|---|---|---|
| 진행 순서 | **Sprint A(1~2일) → 11/14 시험 → Phase 2/3(C)** | 7일 안에 실사용 가치가 나와야 함 |
| 기술 | Kotlin, Compose, Material3, Room, DataStore, Coroutines/Flow, WorkManager, AlarmManager, SpeechRecognizer, TextToSpeech | 요청서 권장 그대로 |
| DI | **Hilt 미사용**, `AppContainer` 수동 DI | 단일 모듈·1인 앱, 과한 추상화 회피 |
| 구조 | 단일 `app` 모듈, 패키지로 계층 분리 (5장) | 유지보수 가능한 최소 구조 |
| minSdk / target | 26 / 35 | Galaxy 최신 + java.time 사용 |
| AI | **Claude API, 공식 Java SDK `com.anthropic:anthropic-java:3.3.0`** (Kotlin은 Java SDK 사용) | 공식 SDK 우선 원칙 |
| 기본 모델 | `claude-opus-5-5` (설정에서 변경 가능), `OutputConfig.Effort` 기본 `MEDIUM`, 코치 채팅은 `LOW` | Opus 5.5는 thinking 비활성 불가 → effort로 비용/지연 제어 |
| API 키 | 사용자가 설정 화면에서 입력 → `EncryptedSharedPreferences` 저장. 서버 중계는 인터페이스만(Phase 3) | 1인 사이드로드 앱에서 가장 현실적. 요청서의 "서버 중계 우선"과의 차이는 README에 명시 |
| STT(음성→텍스트) | 기본: Android `SpeechRecognizer`(무료). 녹음 후 정밀 전사는 선택적 외부 STT 인터페이스(미구현 시 "직접 듣고 비교") | Claude API는 오디오 입력 없음 |
| 초기 콘텐츠 | 사용자 Drive 스크립트(25문항)를 seed로 탑재 + 예제/질문 은행은 "AI 생성" 라벨 | 첫날부터 실제 콘텐츠로 연습 |

---

## 3. 전문가 관점 원칙 (앱 설계에 반영할 것)

1. **IH = 기능(function) 기준.** 문단 단위 연결 발화 유지, 과거 서사의 시제 일관성, Role-play 돌발 상황 해결. 앱 지표도 이에 맞춘다:
   - 실발화 시간, 2초 이상 침묵 횟수, 연결 문장 수(접속 표현), 과거 시제 비율, 구체적 경험 포함 여부
   - 발음 점수는 보조 지표
2. **스크립트 통암기 → 스토리 뱅크.** 개인 경험 5~7개를 여러 질문에 재조합. 통암기 티는 평가에 불리할 수 있음(비공식 경향).
3. **유창성 훈련 기법:** 4/3/2(같은 답을 점점 짧은 시간에 반복), Shadowing, Keyword → Blind 순 점진적 스캐폴딩 제거.
4. **Background Survey 전략:** 겹치는 주제 선택으로 준비 범위 축소(비공식 전략으로 표기).
5. **7일 시점에서는 도구보다 반복 횟수.** Sprint A는 "질문 듣기 → 큰 마이크 → 피드백 → 다시 말하기" 루프가 끊김 없이 돌아가는 것이 최우선.

## 4. 숨은 병목과 대응 설계

| 병목 | 대응 |
|---|---|
| SpeechRecognizer가 틀린 발화를 그럴듯하게 **자동 보정**, 필러(um/uh) 삭제 | 결과 화면에 "인식 텍스트는 보정되었을 수 있음" 표기. 필러/반복은 '측정 불가 가능' 표시. 정확도 과대평가 방지 |
| 약 2초 침묵 시 **인식 자동 종료** → 1~2분 답변 잘림 | 세션 활성 중 `onResults`/`ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` 시 **자동 재시작**, 세그먼트 누적 |
| 녹음(AudioRecord)과 인식 **마이크 동시 사용 충돌** | 캡처 모드 3종: ① 실시간 인식(기본, 재생 불가) ② 녹음 우선(재생 가능, 텍스트는 외부 STT 또는 없음) ③ 동시(실험적, 런타임 탐지: 우리 PCM이 무음이면/인식기가 오류면 '미지원' 저장 후 폴백) |
| 침묵·속도 측정 | ① 모드: `onRmsChanged` dB 타임라인으로 무음 구간 추정 ② 모드: PCM RMS 프레임(20ms)으로 정밀 측정. **이건 STT와 무관한 실제 음향 측정** |
| 온디바이스 인식 지원 여부 기기별 상이 | `SpeechRecognizer.isRecognitionAvailable`, API31+ `isOnDeviceRecognitionAvailable` 실행 시 탐지 → 설정 > 음성 진단 화면 |
| LLM 없으면 AI 코치가 키워드 매칭 수준 | 로컬 규칙 기반 분석을 **"로컬 분석(AI 아님)"** 라벨로 제공, AI는 선택 |
| Android 14 정확한 알람 기본 거부, 삼성 절전 앱 | `USE_EXACT_ALARM`(API33+, 사이드로드 앱 OK) + `SCHEDULE_EXACT_ALARM`(maxSdk 32). `canScheduleExactAlarms()` false면 `setAndAllowWhileIdle` 폴백 + WorkManager 리마인더. 설정에 배터리 최적화 예외 안내 |
| 재부팅/시간 변경 | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 수신 → 재예약 |
| Play 정책 | `USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`는 Play 배포 시 심사 대상 → README에 명시 |

---

## 5. 아키텍처 & 패키지 구조

```
com.opicih.coach
├── App.kt                      # Application, AppContainer 생성, PDFBox init, 알림 채널
├── AppContainer.kt             # 수동 DI (DB, repos, speech, ai)
├── MainActivity.kt             # Compose host, 알람 딥링크(route=morning), showWhenLocked
├── data/
│   ├── db/  (AppDatabase, Entities, Daos, Converters)
│   ├── prefs/SettingsRepository.kt     # DataStore
│   ├── secure/ApiKeyStore.kt           # EncryptedSharedPreferences
│   ├── repo/ (ScriptRepository, PracticeRepository, ChunkRepository, PlanRepository, ExamRepository)
│   ├── seed/SeedLoader.kt              # assets/seed/*.json 최초 1회 로드
│   └── backup/BackupManager.kt         # JSON export/import (SAF)
├── importer/  (ScriptParser, DocxTextExtractor, PdfTextExtractor, ImportService, DuplicateLinker)
├── speech/    (TtsManager, LiveRecognizer, PcmRecorder(WAV), AudioPlayer, SpeechCapabilities, PauseAnalyzer)
├── analysis/  (TextNormalizer, ScriptComparator, DiscourseAnalyzer, FeedbackComposer)  ← 순수 Kotlin, 단위테스트 대상
├── ai/        (AiCoach 인터페이스, ClaudeAiCoach, LocalCoach, Prompts, AiJson)
├── learning/  (SrsScheduler, MasteryState, PlanGenerator, RetestCalculator, MorningRoutineBuilder)
├── alarm/     (AlarmScheduler, AlarmReceiver, SnoozeReceiver, BootReceiver, ReminderWorker, Notifications)
└── ui/
    ├── theme/  (Dark 우선, 고급 타이포)
    ├── nav/    (5탭: Home, Scripts, Speaking, Mock Test, Progress + Coach/Settings/Morning 라우트)
    ├── home/ scripts/ speaking/ mock/ progress/ coach/ settings/ morning/ components/
```

원칙: `analysis`/`learning`/`importer` 핵심 로직은 Android 의존성 없는 순수 Kotlin → JVM 단위 테스트.

---

## 6. 데이터베이스 (Room)

| Entity | 주요 필드 |
|---|---|
| `ScriptEntity` | id, topic, questionType(DESCRIPTION/ROUTINE/PAST_EXPERIENCE/COMPARISON/ROLEPLAY/ISSUE/UNEXPECTED/SELF_INTRO), question, altQuestions(JSON), original, natural?, ihTarget?, short?, keywords?(JSON), activeVersion(ORIGINAL/IH_TARGET/…), changeNotesKo?, koreanMeaning?, keyExpressions(JSON), difficulty(1~5), estSeconds, masteryState, masteryScore(0~100), srsEase, srsIntervalDays, dueDate, lastReviewed, linkedGroupId?, storyIds(JSON), source(USER_IMPORT/SEED_USER/AI_SAMPLE), importId?, createdAt, updatedAt |
| `ScriptRevisionEntity` | id, scriptId, version, text, reasonKo, origin(AI_CLAUDE/PREBUILT/USER), status(PENDING/APPROVED/REJECTED), createdAt — **승인 전 개선본 보관** |
| `ImportSourceEntity` | id, fileName, mime, rawText(원본 보존), importedAt, parsedCount |
| `PracticeAttemptEntity` | id, scriptId?, questionId?, mode(READ_LISTEN/SHADOWING/FILL_BLANK/KEYWORD/BLIND/RANDOM/VARIATION/FREE/MOCK), questionText, transcript, audioPath?, captureMode, durationMs, speechMs, longPauseCount, wpm, wordCount, coverage?, metricsJson, feedbackJson, feedbackSource(LOCAL/AI), createdAt |
| `ErrorPatternEntity` | id, key(예: "past_tense_missing"), category(GRAMMAR/CONTENT/FLUENCY/VOCAB), example, correction, count, lastSeen |
| `ChunkEntity` | id, phrase, meaningKo, example, category, srsEase, intervalDays, dueDate, lastResult(OK/FAIL), lastReviewed, source |
| `DailyLogEntity` | date(PK), speakingMs, attempts, tasksDoneJson, minutesGoal, morningDone |
| `MockTestEntity` / `MockAnswerEntity` | 설정(survey, selfLevel, 12/15문항), 시작/종료, 리포트 JSON / 문항별 attempt 연결 |
| `ExamEntity` | id, date, type(FIRST/RETAKE), resultGrade?, note |
| `StoryEntity` | id, titleKo, summary, tags(JSON), reusableFor(JSON 질문유형) — 스토리 뱅크 |

DataStore 설정: 시험일, 알람(시간/on/소리/진동/정확알람), 학습 모드(Quick10/Standard30/Intensive60), survey 선택, self-assessment, 캡처 모드, TTS 속도, AI(사용 여부, 모델, effort, 텍스트 전송 동의, 음성 전송 동의), 오디오 보관일수(기본 14일), focusTopics(모의시험 결과 반영).

---

## 7. 학습 모드 (Speaking 화면 하나에서 전환, 화면 이동 없이 결과 인라인 표시)

| Mode | 동작 | 평가 |
|---|---|---|
| 1 Read & Listen | 스크립트 보며 TTS 듣고 따라 읽기 | 전체 비교 |
| 2 Shadowing | 문장 단위: TTS(1.0/0.75x) → 녹음 → 문장 비교 → 다음 | 문장별 일치율 |
| 3 Fill in the Blanks | 핵심 표현 가림, 전체 말하기 | 가린 표현 발화 여부 |
| 4 Keyword Speaking | 키워드 3~5개만 표시 | 키워드 커버리지 + 길이 + 연결 |
| 5 Blind | 질문만 TTS | 내용 단위 커버리지(순서 무관) |
| 6 Random Question | altQuestions/질문 은행의 다른 표현 | 관련성 + 커버리지 |
| 7 Variation | "같은 경험을 미래 계획으로", "다른 질문에 재활용", "과거 vs 현재 비교" | 요구 시제 사용 여부(DiscourseAnalyzer) |
| Free Speaking | 스크립트 없는 질문 | 구조/관련성/경험/시제/연결/어휘 |

결과 카드(공통): 잘한 점 2 / 우선 수정 ≤3 / 더 자연스러운 예시 / 다시 말하기 미션 / 이전 시도 대비 변화 / "학습용 참고 점수" 표기.

마스터리 상태: `NEW → LEARNING → NO_SCRIPT_OK(Blind 70%+) → VARIATION_OK(Variation 통과) ; NEEDS_REVIEW(하락 시)`.

---

## 8. 분석 엔진 (로컬, 오프라인)

- **TextNormalizer**: 소문자, 축약형 정규화(I'm→i am 등), 구두점 제거, 숫자 단어화, 간이 lemma(-s/-ed/-ing 제거).
- **ScriptComparator**: 문장 분할 → 문장별 내용어(불용어 제외) 집합 → 전사문 전체와 순서 무관 매칭(lemma 동일 시 인정) → 문장별 커버리지, 누락 핵심 내용, LCS 기반 단어 일치율. 동의 표현은 소규모 사전(really/very, a lot of/many 등) + AI 모드에서 의미 판단.
- **DiscourseAnalyzer** (휴리스틱, "참고용" 표기):
  - 시제: 과거(불규칙동사 목록 + -ed + was/were/used to), 미래(will/going to/plan to/hope to), 현재
  - 연결 표현: because, so, but, however, actually, after that, then, first of all, on the other hand, compared to …
  - 경험 표지: I remember, one time, last (year|summer|week), when I was
  - 문장 수, 평균 길이, 어휘 다양성(TTR), 반복 단어
- **PauseAnalyzer**: RMS 프레임 → 음성/무음 분할, 2초 이상 무음 수, 실발화 비율, WPM(=단어수/실발화분).
- **FeedbackComposer**: 위 지표 → 잘한 점 2 / 수정 ≤3 / 미션 생성 (규칙 테이블).

## 9. AI 모듈

```kotlin
interface AiCoach {
    suspend fun improveScript(s: ScriptInput): Result<ScriptVersions>       // Natural/IH/Short/Keywords + 변경이유(ko)
    suspend fun evaluate(q: String, transcript: String, metrics: Metrics, target: String?): Result<AiFeedback>
    suspend fun chat(history: List<ChatTurn>, context: CoachContext): Result<String>
}
// 구현: ClaudeAiCoach(anthropic-java), LocalCoach(규칙 기반). 실패 시 LocalCoach로 폴백하고 UI에 출처 표시.
```

Claude 호출(공식 Java SDK, 스킬 문서 기준 확인된 API):

```kotlin
val client = AnthropicOkHttpClient.builder().apiKey(keyFromEncryptedPrefs).build()
val params = MessageCreateParams.builder()
    .model(settings.model)                 // 기본 "claude-opus-5-5"
    .maxTokens(16000L)
    .system(SYSTEM_PROMPT_KO)              // JSON 스키마를 프롬프트에 명시
    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
    .addUserMessage(userPayload)
    .build()
val msg = withContext(Dispatchers.IO) { client.messages().create(params) }
// stopReason == "refusal" 체크 → stopDetails 표시. 텍스트 블록 합쳐 첫 {...} JSON 추출 후 kotlinx.serialization(lenient) 파싱
```

- Opus 5.5: `thinking` disabled/`budget_tokens` 사용 시 400 → thinking 파라미터 생략, effort로만 제어.
- 오류: `RateLimitException` / `AnthropicServiceException`(errorType) / 네트워크 예외 구분 → 재시도 버튼.
- **Android 호환성 미검증**: anthropic-java(OkHttp+Jackson)가 Android에서 정상 동작하는지 Sprint A 초기에 실기기로 확인. 문제 시 대안은 같은 인터페이스 뒤에서 교체.
- 개인정보: 텍스트 전송 동의 토글, 음성 원본은 전송 안 함(외부 STT 연동 시 별도 동의).

평가 프롬프트 핵심(시스템): "ACTFL IH 기능 기준(문단 수준 연결 발화, 주요 시제 서사, 복잡한 상황 대처)으로 학습용 피드백을 한국어로. 공식 등급 판정 금지. 출력 JSON: strengths[2], fixes[≤3]{issue,why,better}, betterAnswer, retryMission, tenseUse{past,present,future}, relevance(0-100), specificity(0-100), connectedness(0-100), errors[{key,category,example,correction}]".

---

## 10. 복습·계획 엔진

- **SRS(SM-2 단순화)**: 초기 간격 고정 [1,2,4,7,14]일, 성공 등급(커버리지 기준)에 따라 단계 이동, 실패 시 1일. **시험일 전 상한**: dueDate가 시험일을 넘지 않게 클램프(D-1에 전부 1회 이상 복습).
- **PlanGenerator** (순수 함수: today, examDate, mode분, 이력 → 오늘 태스크 목록)
  - D-7 진단+스크립트 분석 / D-6 핵심 주제 보완 / D-5 과거경험·비교·묘사 / D-4 Role-play·돌발 / D-3 자유발화·약점 / D-2 모의시험 / D-1 취약표현·가벼운 훈련
  - **놓친 날 처리**: 날짜 고정이 아니라 "남은 일수"로 매일 재계산 + 미완료 고우선 태스크(모의시험 1회, 약점 주제)를 남은 날에 우선 배치
  - 시험 후: 12월까지 주간 사이클(자유발화 길이, 돌발, 구조 다양화, 반복오류, 유창성)
  - 완료율 = 실제 기록(attempt, 발화 시간) 기반. 앱 켜둔 시간 아님
- **Morning Routine(5~10분)**: 어제 FAIL 청크 5 + 오늘 신규 청크 5 + 문장완성 3 + 발음(섀도잉) 2 + 오늘 복습 스크립트 안내.
- **RetestCalculator**: 마지막 응시일 + 25일 = 재응시 가능일(웨이버 사용 시 예외) — **규정은 14장 확인 필요, 최종 판단은 공식 사이트 안내 문구 필수**.

## 11. 알람

- 채널 2개: `morning_alarm`(IMPORTANCE_HIGH, 소리/진동, category ALARM) / `study_reminder`(기본).
- 정확 알람: `AlarmManager.setAlarmClock()` (권한 있을 때), 없으면 `setAndAllowWhileIdle` + 설정 화면 경고.
- 알림 액션: "지금 시작"(→ morning 라우트), "10분 후"(SnoozeReceiver → +10분 재예약). D-day 표시.
- fullScreenIntent는 `NotificationManager.canUseFullScreenIntent()`(API34) true일 때만.
- Android 13+ `POST_NOTIFICATIONS` 런타임 요청, 거부 시 홈 배너로 대체 안내.
- 저녁 리마인더: WorkManager 주기 작업(오늘 목표 미달 시만 알림).
- **AlarmScheduler.nextTrigger(now, hh, mm)는 순수 함수로 분리해 테스트.**

## 12. 스크립트 가져오기

- 지원: TXT, MD, PDF(텍스트 추출 가능 — `pdfbox-android`), DOCX(`word/document.xml`에서 `<w:t>` 수집, `</w:p>` 줄바꿈 — 라이브러리 불필요), 붙여넣기, 직접 작성.
- 크기 제한 5MB, `Dispatchers.Default`에서 파싱, 실패 시 원인별 메시지(암호화 PDF/이미지 PDF/인코딩).
- **ScriptParser 휴리스틱** (사용자 실제 형식 2종을 테스트 픽스처로):
  - 주제: `N. Title`(따옴표/물음표 없음), `## Topic: …`, `# …`
  - 질문: `Question:` 라벨, 따옴표로 감싼 문장, `?`로 끝나는 줄, `Label: "…"` 형태
  - 답변: `Answer:` 이후 / `*`·`■`·`-` 불릿 이후 다음 질문 전까지
  - 한국어 라벨(예: `4. 음악 취향 변화`)도 질문으로 허용, questionText 비어있으면 라벨 사용
- 중복 연결: 질문 토큰 Jaccard ≥ 0.6 또는 답변 ≥ 0.7 → 같은 `linkedGroupId`.
- 원본 텍스트는 `ImportSourceEntity.rawText`에 보존.

---

## 13. 사용자 콘텐츠 분석 (Drive `2026년 OPIc Script`, 2026-02 작성)

### 13.1 인벤토리 (7주제 / 25문항)
| 주제 | 문항 |
|---|---|
| Overseas Travel | Recent Trip, Memorable Experience |
| Domestic Travel | Favorite destination, Travel Routine, Travel Experience |
| Movies | Favorite Movies, Movie Routine, Memorable Experience |
| Vacation | Vacation at Home, Going outside, Memorable vacation |
| Home & Furniture | House description, Favorite furniture, Past vs Present, Childhood experience, Routine at home |
| Park & Music | Park description, Park/Music routine, Favorite musician, Music taste change, Memorable experience at park |
| Geography | Geography description, Neighboring countries |

관련 파일: Drive `2026년 OPIc Script`(Google Doc, 최신), 같은 이름 PDF/TXT 중간본 다수, `2024년 OPIc Script`, `2023년 OPIc 정기 기출 문제`(가구 돌발, 예약 등 질문 목록). seed는 Google Doc 최신본 기준으로 만들 것.

### 13.2 스토리 뱅크 (이미 존재하는 개인 경험)
| ID | 스토리 | 현재 사용처 |
|---|---|---|
| S1 | 오사카 여행: 제주항공, 3박4일, 오사카성, 무제한 맥주 호텔, 온천 | 해외여행, 휴가 |
| S2 | 강원도 사과따기: 사다리 위 풍경, 많이 먹고 배탈 | 국내여행 |
| S3 | 영화관 'Basketball': 클라이맥스에서 배탈로 화장실 → 결말 놓침 | 영화 |
| S4 | 어린 시절 소파 트램펄린 → 엄마께 새 소파 선물 | 가구 |
| S5 | 공원 조깅 중 음악에 빠져 여성과 충돌 | 공원 |
| S6 | 중국 출장: 고층 빌딩, 빈부격차 | 지리 |
| S7 | 거실 회색 소파 + 게임으로 스트레스 해소 | 집, 휴가, 루틴 |

### 13.3 코칭 인사이트 (앱의 "AI 코치 팁"과 개선안에 반영)
1. **"갑자기 배탈 → 약국 → 회복" 반전이 4개 답변(S1 타코야키, S2 사과, S3 영화, 휴가)에 반복.** 한 시험에서 여행+영화가 같이 나오면 암기 티가 날 위험 → 반전 요소 다양화 필요(예: 비행기 지연, 길 잃음, 예약 문제, 날씨).
2. **반복 문법 오류 후보** (ErrorPattern seed로 사용):
   - 시제 혼용: "After grabbing… I **found** my seat"(루틴인데 과거), "I **like** '비'"(과거 취향인데 현재)
   - 관사·고유명사: "japan", "Ganwon-do" 오타, "Tripe"
   - 어색한 표현: "the relationship is finally broken"→"they end up breaking up", "music list 'Butter'"→"music like 'Butter'", "When I was young, I went to the army"(시기 논리), "I usually don't reserve hotels to stay"
   - 비교 답변 도입부 "Honestly, I don't remember my childhood but I try to remember I guess…" → IM 신호. 명확한 비교 프레임 필요
3. **IH 갭:** 묘사/루틴 답변이 2~3문장으로 짧음(Home routine, Favorite furniture). 미래 시제 거의 없음. 비교 답변의 "과거→현재→이유→느낌" 구조 부족.
4. **강점:** 감정 표현("bittersweet", "rejuvenated", "lose track of time"), 청자 상호작용("Do you know them well?"), 구체적 디테일(3박4일, 6층, 회색 소파)은 IH에 유리 — 유지.

### 13.4 개선본 스타일 예시 (프리빌트 개선안의 기준, 승인 전 PENDING으로 저장)
**Home routine — Original:** "On my days off, I usually spend my time on the sofa playing video games on my big TV. …"
**IH Target (예시):**
> On my days off, I'm basically a homebody. I usually sleep in until around ten, and then I make a quick brunch. After that, I head straight to the big gray sofa in my living room and play video games on my TV. The graphics are so realistic that I completely lose track of time. In the evening, if the weather is nice, I go jogging in Central Park to get some fresh air. Honestly, this simple routine is the best way for me to recharge my batteries. Next weekend, I'm planning to try a new game that my friend recommended, so I'm really looking forward to it.

변경 이유(ko): 2문장→시간 흐름(아침-오후-저녁) 구조, 미래 시제 1문장 추가, 기존 표현(lose track of time, recharge my batteries) 재사용으로 암기 부담 최소화.
**Short:** "On my days off, I'm a homebody. I play video games on my big sofa and lose track of time. In the evening, I jog in the park to recharge."
**Keywords:** homebody · sofa + games · lose track of time · jogging · next weekend new game

→ 나머지 24문항도 같은 기준으로 IH Target/Short/Keywords/변경이유 작성해 `assets/seed/user_scripts_2026.json`에 포함(origin=PREBUILT, status=PENDING). Natural 버전은 AI 연결 시 생성.

### 13.5 추가로 필요한 seed 콘텐츠 (AI 생성 라벨)
- 질문 은행 ~80문항: 자기소개, 집/동네, 취미, 영화/음악/공연, 여행, 운동, 직장/업무, 비교, 기억에 남는 일, 돌발(재활용·은행·명절·기술·가전·식당·교통·건강), Role-play(질문하기/문제해결/관련 경험), 이슈 질문. 각 질문 `source=AI_GENERATED`.
- IH 청크 ~40개 (What I liked most was… / One thing I remember clearly is… / Compared to the past… / I wasn't expecting that at all. / Looking back, … / The thing is, … / To make a long story short, … 등) + 한국어 뜻 + 예문.
- 사용자가 GHE에 올릴 때 개인 스크립트 seed를 빼고 싶으면 `assets/seed/user_scripts_2026.json`만 제외(README에 안내).

---

## 14. 시험 정보 분류

| 구분 | 내용 | 상태 |
|---|---|---|
| 공식(웹 검색 요약 기준) | 상시 시험, 오리엔테이션 20분+본시험 40분, 등급 NL~AL 9단계, 성적 유효 2년(응시일 기준), 결과 시험일로부터 10일째 13시, 일반 정기 응시료 84,000원 | 2차 출처(블로그/커뮤니티) 요약 → **opic.or.kr에서 재확인 필요** |
| 사용자 제공 | 재응시 기본 25일 제한, Waiver | 이번 세션 검색으로 **확인 못함** → 앱은 "공식 사이트 확인" 문구와 함께 표시 |
| 비공식 경향 | Self Assessment 난이도 선택 전략, Survey 주제 겹치기, 통암기 감점 가능성, 15문항 구성(묘사-루틴-경험 콤보, 롤플레이 3연속, 마지막 비교/이슈) | 앱에서 "응시 후기 기반" 라벨 |
| AI 생성 | 질문 은행, 모의시험 문항 | "AI 생성 연습 문제" 라벨 |

ACTFL 기준: https://www.actfl.org (Proficiency Guidelines — IH 설명 확인해 평가 프롬프트에 인용 요약).

---

## 15. Gradle 설정 초안 (클라우드에서 작성, **빌드 미검증**)

`gradle/libs.versions.toml`
```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
navigation = "2.8.5"
room = "2.6.1"
datastore = "1.1.1"
work = "2.10.0"
serialization = "1.7.3"
coroutines = "1.9.0"
securityCrypto = "1.1.0-alpha06"
pdfbox = "2.0.27.0"
anthropic = "3.3.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
pdfbox-android = { group = "com.tom-roush", name = "pdfbox-android", version.ref = "pdfbox" }
anthropic-java = { group = "com.anthropic", name = "anthropic-java", version.ref = "anthropic" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`app/build.gradle.kts` 요점: namespace/applicationId `com.opicih.coach`, compileSdk 35, minSdk 26, targetSdk 35, Java/JVM 17, `buildFeatures { compose = true; buildConfig = true }`, `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`, packaging에서 `META-INF/DEPENDENCIES, LICENSE*, NOTICE*, INDEX.LIST` 제외(Jackson/pdfbox 충돌 대비), `testOptions.unitTests.isReturnDefaultValues = true`, 위 라이브러리 전부 implementation(+ room-compiler는 ksp).

> 로컬 PC에 hal-camera용 Android SDK/Android Studio가 있으면 그 JDK(17+)와 SDK 경로를 `local.properties`에 사용. 버전은 로컬 Android Studio가 제안하는 최신 호환 버전으로 올려도 됨.

Manifest 권한: `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`(maxSdkVersion=32), `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `WAKE_LOCK`, `USE_FULL_SCREEN_INTENT`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. `<queries>`에 `android.speech.RecognitionService` intent 추가(Android 11+ 패키지 가시성 — 없으면 인식기 탐지 실패).

---

## 16. 작업 순서 (로컬 세션 체크리스트)

### Sprint A — 목표: 11/8~9까지 실기기 설치·매일 사용 가능
1. [ ] 프로젝트 생성 + 15장 설정 + `./gradlew assembleDebug` 성공 (빈 Compose 화면)
2. [ ] Room/DataStore/AppContainer + seed 로더(사용자 25문항 + 프리빌트 개선안 PENDING + 청크 40 + 질문 은행)
3. [ ] **Speaking 화면** (최우선): 질문 TTS/재청취/0.75x → 큰 마이크 → LiveRecognizer(자동 재시작, RMS 무음 추정) → 결과 인라인(ScriptComparator + DiscourseAnalyzer + FeedbackComposer) → 다시 말하기 → 이전 시도 비교. 모드: Read&Listen, Shadowing, Keyword, Blind, Free
4. [ ] Scripts 탭: 목록/검색/편집/삭제, 원본 vs 개선본 나란히 + 변경 이유 + 승인 버튼, 가져오기(TXT/MD/DOCX/PDF/붙여넣기)
5. [ ] Home: D-day, 오늘 계획(PlanGenerator), 완료율, 연속 일수, 복습 예정, 취약 주제, "바로 말하기" 버튼
6. [ ] 아침 알람 + Morning Routine 화면 + 스누즈 + 재부팅 복구 + 권한 거부 폴백
7. [ ] 설정: 시험일/알람/학습모드/Survey/캡처모드/음성 진단/AI 키·동의/데이터 삭제
8. [ ] 단위 테스트: ScriptParser(사용자 실제 2개 형식), ScriptComparator, DiscourseAnalyzer, PauseAnalyzer(합성 PCM), SrsScheduler, PlanGenerator(놓친 날 재배치), AlarmScheduler.nextTrigger, DocxTextExtractor
9. [ ] Debug APK → 실기기 설치 → 실제 녹음/인식/알람 확인 후 결과를 README "검증 현황"에 기록

### Sprint A+ (11/10~13, 사용하면서)
- ClaudeAiCoach(개선/평가/채팅) + 실패 시 LocalCoach 폴백, ErrorPattern 누적
- Fill-blank, Random, Variation 모드, SRS 마스터리 상태 전이
- 간이 모의시험(15문항, 40분 타이머, 통합 리포트, focusTopics → 다음 3일 계획)

### Phase 2/3 (11/14 이후 ~ 12월)
- 녹음 우선/동시 캡처 모드 + 정밀 PauseAnalyzer, 오디오 보관기간/용량 관리
- 외부 STT/발음평가 인터페이스 구현(선택), 서버 중계 옵션
- Progress 장기 통계(차트), 재응시 일정 관리, 시험 결과 입력 → 계획 갱신
- 백업/복원(JSON, SAF), 안정성(녹음 중 종료, 권한 취소, 리소스 누수) 테스트

## 17. 검증 체크리스트 (보고 시 "검증함/미검증" 구분 필수)
- Gradle 빌드 / 단위 테스트 통과 수
- 실기기: 마이크 권한 거부→재요청, 인식기 미설치/네트워크 오류, 1분 이상 답변 자동 재시작, 녹음 중 앱 백그라운드/종료, TTS 엔진 없음
- 알람: 예약/취소/스누즈/재부팅 후/정확 알람 권한 거부 시 폴백
- 가져오기: 사용자 TXT·PDF·Google Doc→DOCX 내보내기 파일, 손상 파일, 5MB 초과
- AI: 키 없음/잘못된 키/429/오프라인 → LocalCoach 폴백 및 출처 표시
- 앱 재시작 후 학습 상태 유지

## 18. 환경 메모
- 클라우드 세션에서는 `dl.google.com` 허용을 추가했고 SDK 설치는 됐으나, Maven Central이 429를 반환해 빌드 불가였음. 로컬 PC에서는 해당 없음.
- 커밋/PR은 GHE 기준으로 로컬 세션에서 진행.
