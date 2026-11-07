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
├── ai/                 AiCoach(인터페이스), ClaudeAiCoach, LocalCoach, Prompts, Transcriber/OpenAiTranscriber
├── alarm/              AlarmScheduler, AlarmReceiver, BootReceiver, Notifications, ReminderWorker
├── data/
│   ├── db/             Room 엔티티 11개, AppDao, AppDatabase
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
   ├─ ClaudeAiCoach.evaluate (켜져 있을 때만, 실패 시 로컬 결과 유지)
   ├─ attempts 저장 (metricsJson, feedbackJson, feedbackSource)
   ├─ daily_logs 갱신 (실발화 시간)
   ├─ 스크립트 MasteryRules + SrsScheduler 갱신
   └─ error_patterns 누적
   ▼
결과 카드 인라인 표시 (화면 전환 없음) → 다시 말하기
```

## 3. 데이터베이스 (Room v2, 스키마: `app/schemas/`, v1→v2 마이그레이션 포함)

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
| `exams` | epochDay, type, resultGrade, note, topicsSeen, stuckPoints, feeling, selfLevelUsed, debriefAt | 시험 결과, 복기, 재응시 계산 (v2에서 복기 컬럼 추가) |
| `stories` | id(S1~S7), titleKo, summary, reusableFor | 개인 경험 스토리 뱅크 |
| `questions` | topic, text, type, source(USER_LIST/AI_GENERATED) | 질문 은행 |

설정(DataStore): 시험일, 학습량, 알람(시간/소리/진동/정확), 저녁 리마인더, Survey, Self Assessment, 캡처 방식, TTS 속도, AI(사용/모델/텍스트 동의), 클라우드 전사(사용/음성 동의), 오디오 보관일, seed 버전, 모의시험 집중 주제.

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
| `ClaudeAiCoach` | 제공자 Claude + 키 + AI 사용 + 텍스트 동의 | 공식 `com.anthropic:anthropic-java`. effort: 개선/평가 MEDIUM, 채팅 LOW. JSON은 프롬프트로 지정하고 첫 `{...}`를 추출해 kotlinx.serialization으로 파싱. 401/403/429/5xx/네트워크/refusal/파싱 실패를 한국어 메시지로 변환 |
| `LocalCoach` | 그 외 | 규칙 기반. Short/Keywords만 생성, 채팅은 오프라인 질문·오류·취약 주제 응답 |

`Transcriber` 인터페이스: `OpenAiTranscriber`(whisper-1, 필러를 남기도록 프롬프트 지정). 서버 중계 구현으로 교체할 수 있습니다.

## 5. 음성 모듈

| 클래스 | 역할 |
|---|---|
| `TtsManager` | Android TTS, 영어(US) 지원 여부 탐지, 속도 조절, 완료 대기 |
| `LiveRecognizer` | SpeechRecognizer 자동 재시작 세션, 부분 결과, dB 타임라인, 오류 코드 한국어 변환 |
| `PcmRecorder` | AudioRecord 16kHz mono → WAV, 20ms RMS, 10분 상한, finally에서 해제 |
| `AnswerCapture` | 두 캡처 방식을 하나의 start/stop API로 묶음 (Speaking·Mock 공용) |
| `AudioPlayer` | MediaPlayer 1개만 유지, 0.75x 재생 |
| `SpeechCapabilities` | 인식 서비스·온디바이스 인식 지원 여부 실행 시 탐지 |

## 6. 학습 엔진

- **SRS**: 고정 간격 [1, 2, 4, 7, 14]일. 성공 시 한 단계 위, 실패 시 1일. 시험 전에는 다음 복습일이 D-1을 넘지 않도록 제한.
- **마스터리**: NEW → LEARNING → NO_SCRIPT_OK(Blind/다른 표현 70%+) → VARIATION_OK(응용 60점+) / 하락 시 NEEDS_REVIEW.
- **계획**: 매일 "남은 일수"로 다시 계산. 7일 이상이면 템플릿, 그보다 적으면 D-1 가벼운 복습과 D-2 모의시험을 고정하고 나머지는 우선순위(자유 발화 > 과거·비교 > 롤플레이 > 핵심 > 진단)로 채움. 시험 후에는 12월까지 7일 주기 장기 훈련.
- **완료율**: 발화 시간 60% + 아침 루틴 20% + 복습 20%. 앱을 켜 둔 시간은 세지 않음.
- **다음 시험 추천 (`RetestAdvisor`)**: 입력은 두 가지입니다.
  1. 달력 제약: 재응시 간격(기본 25일, 미검증 표시), 결과 발표까지 일수(기본 10일), 등급 마감일(기본 12/31), 사내 회차(선택), 접수 마감 리드타임(기본 7일)
  2. 준비도 조건 5개: 연습량(10일 또는 150분), 자유 발화 평균 90단어, 긴 무음 답변당 1회 이하, 학습 점수 65점, 시험 후 모의시험 1회 60점
  판정: 첫 시험 전에는 잠정 계획, 조건 4개 이상 통과 시 가장 빠른 날짜, 부족하면 필요한 일수만큼 늦추되 마감 전 결과가 나올 수 없으면 가장 빠른 날짜로 신청을 권합니다. 시험 직후 복기에서 고른 "막힌 주제"는 자유 발화·모의시험 우선 주제로 들어갑니다.
