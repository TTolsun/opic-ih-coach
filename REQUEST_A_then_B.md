# Claude Code 구현 요청 (재작성): OPIc + SAIL 통합 코치, A단계 → B단계

## 0. 이 요청이 원본과 다른 점

원본 요청은 Sprint 1~3 전체를 한 번에 요구했다. 이 재작성본은 다음 판단을 전제로 한다.

1. 오늘은 2026-11-07(토, KST)이고 SAIL 시험은 2026-11-11(수) 19:20이다. 남은 기간은 4일이다.
2. 이 4일 동안 가장 큰 위험은 기능 부족이 아니라, 개발 때문에 실제 말하기 훈련 시간이 줄어드는 것이다.
3. 따라서 작업을 두 단계로 나눈다.
   - **A단계 (11/11 이전 완료):** SAIL 시험에 실제로 도움이 되는 최소 기능만 만든다.
   - **B단계 (11/11 이후 시작):** OPIc 재응시와 Waiver 결정을 위한 정확한 규칙 엔진을 만든다.
4. 원본의 Sprint 3 항목(장기 분석, 연속 응시 시뮬레이션, 알림 묶기 등)은 이번 요청의 범위에서 제외한다.

각 단계와 하위 단계는 단독으로 빌드, 테스트, 설치가 가능한 상태로 끝나야 한다.

---

## 1. 작업 전 안전장치 (코드 수정 전에 반드시 수행)

1. 사용자에게 앱의 "백업 내보내기"를 실행하도록 요청하고, 백업 파일 위치를 확인받는다.
2. 기기가 연결되어 있으면 현재 v2 DB 사본을 확보한다. 이 사본을 v3 마이그레이션 실기기 검증에 사용한다.
3. 마이그레이션 전후로 다음 개수를 비교하는 확인 절차를 만든다: 스크립트 수(현재 23개), 개선안 수와 상태별 개수, 발화 기록 수, 오류 패턴 수, 청크 SRS 상태, 시험 기록 수.
4. API Key와 개인 데이터는 로그, 테스트 출력, 커밋에 포함하지 않는다.

---

## 2. 코드에서 이미 확인한 사실과 결함

작업 전에 아래 내용을 다시 확인하고, 사실과 다르면 보고한 뒤 계획을 조정한다.

| 위치 | 확인한 내용 | 처리 |
|---|---|---|
| `ui/screens/NextExamScreen.kt:115` | 추천 응시일을 `settings.examDate`에 덮어쓴다. 이 값은 `lastExamDate()` 계산에도 다시 들어간다. 추천일이 확정 시험일로 바뀌는 결함이다. | A단계에서 덮어쓰기를 제거한다. |
| `learning/RetestAdvisor.kt`, `learning/PlanGenerator.kt` 안의 `RetestCalculator` | 재응시 계산이 두 군데 중복되어 있다. `RetestCalculator`는 Waiver가 있으면 `lastExam.plusDays(1)`을 반환하는데, 이 동작은 근거가 확인되지 않았다. | B단계에서 하나의 엔진으로 통합한다. |
| `data/db/Entities.kt`의 `ExamEntity` | 시험 유형, 시각, 장소, 상태(취소/결시)가 없다. 날짜는 `epochDay` 하나뿐이다. | A단계에서 새 테이블을 추가하고, 기존 테이블은 삭제하지 않는다. |
| `data/prefs/SettingsRepository.kt`의 `Settings.examDate` | 시험일이 Room이 아니라 DataStore에 있다. 따라서 Room 마이그레이션만으로는 이 값을 옮길 수 없다. | 앱 시작 시 한 번만 실행되는 이전 단계를 별도로 만든다. |
| `data/backup/BackupManager.kt` | 스냅샷 `version = 1`이다. stories, questions, mock_tests, 설정 값은 백업 대상이 아니다. | 새 테이블을 추가하고 `version = 2`로 올린다. version 1 파일도 계속 복원할 수 있어야 한다. |
| `PlanGenerator.today(today, exam, ...)` | 시험 하나의 날짜만 입력받고, Focus 목록은 OPIc 전용이다. | A단계에서 "가장 가까운 시험"을 입력으로 받도록 확장한다. 기존 OPIc 동작과 기존 테스트는 유지한다. |

---

## 3. 데이터 설계 원칙 (A, B 공통)

### 3.1 날짜에는 정밀도가 있다

과거 OPIc 응시는 "2026년 2월"만 알고 있다. 임의의 날짜를 만들면 안 된다. 따라서 날짜는 다음처럼 저장한다.

- `dateLocal`: `"2026-02"` 또는 `"2026-11-11"` 형식의 문자열
- `datePrecision`: `MONTH` / `DAY`
- `startTimeLocal`: `"19:20"` 또는 null
- `zoneId`: 기본값 `"Asia/Seoul"`

정밀도가 `MONTH`인 시험으로 재응시 가능일을 계산할 때는 **해당 월의 마지막 날을 시험일로 가정**하고(가장 보수적인 값), 화면에 "정확한 응시일 미입력으로 보수적으로 계산함"이라고 표시한다.

### 3.2 사실, 가정, 추천은 다른 타입이다

UI 라벨로만 구분하지 말고 데이터 수준에서 분리한다.

- **사실:** 사용자가 등록했거나 메일로 확인한 예약, 실제 응시 이력. `ExamRegistration`에 저장한다.
- **가정:** 재응시 일수, Waiver 규칙, 결과 발표 기간처럼 검증되지 않은 정책 값. `ExamPolicy`에 `verification = UNVERIFIED / USER_CONFIRMED / OFFICIAL`로 저장한다.
- **추천:** 계산 결과. 저장하지 않고 매번 다시 계산한다. 사용자가 추천일을 채택하면 `status = PLANNED`인 등록이 새로 생기고, 이 등록은 접수 확인 전까지 확정 일정처럼 보이면 안 된다.

### 3.3 상태 변경 이력은 별도로 남긴다

등록의 현재 상태와 상태 변경 이력을 분리한다. 취소된 시험(예: 11/18 SAIL)은 이력으로는 남지만, 재응시 제한 계산의 기준이 되는 "실제 응시"로 취급하지 않는다.

---

## 4. A단계: D-4 실전 모드

**목표:** 11/11 SAIL 전에 실제 기기에서 쓸 수 있는 상태로 만든다. 설치를 두 번으로 나눈다.

### A1. 다중 시험 기반과 홈 화면 (목표: 11/08 저녁 설치)

1. **Room v3 마이그레이션**
   - 신규 테이블 `exam_registrations`: id, examType, dateLocal, datePrecision, startTimeLocal, zoneId, location, status(`PLANNED / REGISTERED / COMPLETED / CANCELLED / NO_SHOW`), resultGrade, resultPublishedAt, waiverUsed(nullable, null은 모름), memo, sourceType(`USER / EMAIL_IMPORT / OFFICIAL / MIGRATED`), verifiedAt, needsReview, legacyExamId, createdAt, updatedAt
   - 신규 테이블 `exam_status_events`: registrationId, fromStatus, toStatus, at, note
   - 신규 테이블 `exam_training_sessions` (A2에서 사용): id, examType, kind, startedAt, finishedAt, turnCount, aiCalls, summaryJson
   - `practice_attempts`에 nullable 컬럼 `trainingSessionId` 추가
   - 기존 `exams` 행은 삭제하지 않는다. 각 행을 `exam_registrations`로 복사하되 `examType = UNKNOWN`, `needsReview = true`, `sourceType = MIGRATED`로 표시하고 `legacyExamId`로 연결한다. 기존 복기 데이터는 `exams` 테이블에 그대로 둔다.
   - `MIGRATION_2_3`과 schema `3.json`을 추가하고, v1→v2→v3 연속 마이그레이션 테스트를 작성한다.

2. **DataStore 시험일 이전 (한 번만 실행)**
   - `Settings.examDate`가 기본값이 아니면 `status = PLANNED`, `examType = UNKNOWN`, `needsReview = true`인 등록을 만든다. 실제 응시 완료로 단정하지 않는다.
   - 실행 여부를 DataStore 플래그로 기록해서 두 번 실행되지 않게 한다.

3. **사용자 확인 데이터 등록 (한 번만 실행, 기존 데이터 덮어쓰기 금지)**
   - 같은 유형과 날짜의 등록이 이미 있으면 새로 만들지 않는다.
   - SAIL, `2026-11-11`, `19:20`, 종료 `20:20`, 장소 "전자(DS) 에듀센터 동탄Ⅱ B1층 어학검정장 A", `REGISTERED`, `EMAIL_IMPORT`, 메모 "시작 10분 전까지 입실"
   - SAIL, `2026-11-18`, `CANCELLED`, 상태 이력에 "2026-11-03 취소" 기록
   - OPIc, `2026-02`, 정밀도 `MONTH`, `COMPLETED`, `USER`
   - OPIc Waiver 잔여 1회는 A단계에서는 사용자 신고 값으로 표시만 한다. 계산은 B단계에서 한다.

4. **홈 화면 상단 카드**
   - 가장 가까운 미래의 `REGISTERED` 시험을 표시한다: 유형, `2026.11.11 (수) 19:20`, `D-4`, 장소, 입실 시각.
   - 아래 줄: "다음 OPIc 일정: 미정", "OPIc Waiver: 1회 (사용자 입력, 미검증)", "다음 SAIL 응시 가능일: 규정 검증 필요", "목표 IH 기한까지 N일".
   - `needsReview = true`인 등록이 있으면 "확인이 필요한 시험 기록 N건" 배너를 띄우고, 유형을 지정하는 간단한 화면으로 연결한다.
   - 시험 등록/수정/취소 화면은 단순한 목록과 입력 폼으로 만든다. 캘린더 뷰는 만들지 않는다.

5. **시험 중심 학습 계획**
   - `PlanGenerator`가 "가장 가까운 미래 시험(유형, 날짜)"을 입력으로 받는다. 기존 `today(today, exam: LocalDate, ...)` 시그니처는 OPIc 기본값으로 유지해서 기존 테스트를 깨지 않는다.
   - SAIL용 Focus를 추가한다: `SAIL_QUICK_RESPONSE`, `SAIL_FOLLOWUP`, `SAIL_MOCK_CONVERSATION`, `SAIL_LIGHT`, `SAIL_WARMUP`.
   - SAIL 일정 규칙: D-7~D-4는 즉답과 Follow-up 집중, D-3~D-2는 대화형 모의훈련, D-1은 가벼운 복습, D-Day는 10분 이내 워밍업.
   - 계획은 매일 다시 계산한다. 오늘 이미 완료한 항목(`DailyLogEntity`, 오늘의 `practice_attempts`)은 다시 강제하지 않는다. 하루를 건너뛰어도 밀린 항목을 몰아넣지 않는다.
   - 30분 예시 계획: 어제 틀린 표현 5분, 핵심 스크립트 키워드 5분, SAIL 즉답과 Follow-up 10분, 취약 문법·유창성 5분, 같은 질문 다시 답하기 5분.
   - 각 항목은 실제 화면으로 이동해야 한다. A2 전까지 SAIL 대화 항목은 기존 Speaking 화면의 자유 발화 모드로 연결한다.
   - SAIL 시험 시각이 지나면 다음 등록 시험으로, 등록 시험이 없으면 목표 기한(12/31) 기준의 OPIc 장기 주기로 자동 전환한다.

6. **(시간이 남으면) 시험 당일 알림 1개:** 11/11 18:40에 "19:10까지 입실" 알림. 기존 `AlarmScheduler`를 재사용한다.

7. **결함 수정:** `NextExamScreen.kt:115`의 `examDate` 덮어쓰기를 제거한다. "이 날짜로 계획" 버튼은 `PLANNED` 등록을 만들도록 바꾼다.

### A2. SAIL 대화 시뮬레이터 (목표: 11/09 저녁 설치)

1. **흐름:** 질문 TTS 재생 → 마이크 답변 → STT 변환 → 직전 답변을 근거로 Follow-up 생성 → 3~5턴 반복 → 종합 피드백.
2. **재사용:** `TtsManager`, `AnswerCapture`, `PauseAnalyzer`, `DiscourseAnalyzer`, `AiCoach`. 새 화면과 새 ViewModel을 만들되 녹음과 분석 로직은 복사하지 않는다.
3. **`AiCoach` 확장:** `followUp(history, profile)`와 `summarizeConversation(history, metrics)`를 추가한다. `ClaudeAiCoach`와 `LocalCoach`가 모두 구현한다.
4. **오프라인 Follow-up 규칙 (`LocalCoach`):** 답변에서 핵심 명사와 동사를 추출하고, 답변의 특징에 따라 템플릿을 고른다.
   - 답변이 15단어 미만이면: "Could you give me a specific example?"
   - 과거 시제가 있으면: 가장 어려웠던 점, 그 결과를 묻는다.
   - "like / enjoy / love + X"가 있으면: 계기, 빈도, 변화를 묻는다.
   - 답변이 비어 있거나 인식에 실패하면: 질문을 쉽게 바꿔서 다시 묻는다.
   - 같은 템플릿이 한 대화에서 반복되지 않게 한다.
   - 이 규칙은 실제 맥락 이해가 아니라 키워드 기반 응용이라는 점을 화면에 표시한다.
5. **AI 호출 조건과 상한:** `aiEnabled`와 `aiTextConsent`가 모두 켜져 있을 때만 호출한다. 대화 1회당 AI 호출은 최대 6회(Follow-up 5회, 요약 1회), 하루 상한은 설정에서 조정한다. 상한에 도달하거나 네트워크 오류가 나면 해당 턴부터 로컬 규칙으로 전환하고, 각 턴에 생성 출처(AI / 로컬)를 표시한다.
6. **턴별 측정값:** 응답 시작까지 걸린 시간(측정할 수 없으면 null로 두고 "측정 불가"로 표시), 실제 발화 시간, 2초 이상 침묵 횟수, 단어 수, 반복 표현, 연결어 사용. 각 턴은 `practice_attempts`에 `trainingSessionId`를 붙여 저장한다.
7. **정직성 규칙:**
   - "공식 SAIL 문항을 재현한다"고 표시하지 않는다. 화면 제목에 "학습용 시뮬레이션"을 명시한다.
   - STT 텍스트만으로 발음 점수나 억양 점수를 만들지 않는다.
   - 앱의 점수를 공식 등급처럼 표시하지 않는다.
8. **시험 직후 정찰 기록:** SAIL 복기 입력 항목(실제 질문, 답하지 못한 질문, 질문 템포, 체감 난이도)을 A2에 포함한다. 이 기록은 B단계와 이후 시뮬레이터 보정의 근거 자료가 된다.

### A단계 테스트

- v1→v2→v3 마이그레이션, 스크립트 23개와 개선안 상태 보존
- 기존 `exams` 행이 `UNKNOWN + needsReview`로 이전되는지
- 사용자 확인 데이터 등록이 두 번 실행되어도 중복이 생기지 않는지
- 취소된 11/18 SAIL이 "가장 가까운 시험"과 "마지막 응시"에서 제외되는지
- 11/07 기준 SAIL D-4, 11/10 D-1, 11/11 D-Day, 11/11 20:21 이후 다음 시험 전환
- 같은 날 서로 다른 시험 2개, 등록된 시험이 없는 경우
- 학습을 하루 건너뛴 다음 날 계획
- 오프라인 Follow-up이 직전 답변의 단어를 실제로 사용하는지
- AI 호출 상한 도달, 네트워크 오류, STT 실패 시 로컬 전환
- 백업 version 1 파일 복원, version 2 백업과 복원
- 실행 명령: `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`. 기존 테스트도 모두 통과해야 한다.

### A단계 완료 기준

1. 앱을 열면 11/11 SAIL의 시각, 장소, D-day가 표시된다.
2. 오늘의 계획이 SAIL 기준으로 생성되고, 각 항목에서 실제 연습 화면으로 이동한다.
3. 대화 시뮬레이터에서 직전 답변의 내용을 반영한 Follow-up이 나온다 (AI와 오프라인 모두).
4. 기존 스크립트, 개선안, 발화 기록, SRS 상태가 그대로 유지된다.
5. 실기기에서 확인한 항목과 확인하지 못한 항목을 구분해서 보고한다.

### A단계에서 하지 않는 것

캘린더 뷰, Waiver 계산 엔진, 재응시 규칙 엔진, 시험 유형 간 제한 공유, 알림 묶기, 장기 분석, 역량 자동 추출.

---

## 5. B단계: 재응시와 Waiver 규칙 엔진

**시작 시점:** 11/11 SAIL 응시 직후. 이 시점에 SAIL 실제 응시 이력이 생기고 OPIc 접수 결정이 필요해진다.

### B0. 사용자에게 먼저 받을 입력 (정답 데이터)

1. opic.or.kr 재응시 계산기에 날짜 3~4개(월말, 연말, 2월 포함)를 넣은 결과값. 이 값을 경계 테스트의 기준값으로 사용한다.
2. OPIc Waiver 규정 원문 또는 화면 캡처: 사용 조건, 재부여 기간, 취소·결시 시 복구 여부.
3. SAIL 재응시 규정(신청 확인 메일의 "25일 규정" 안내 원문)과 Waiver 존재 여부.

입력을 받지 못한 항목은 `UNVERIFIED`로 구현하고, 테스트 이름과 화면에 "가정"임을 표시한다. 입력 없이 공식 규정과 일치한다고 보고하지 않는다.

### B1. ExamPolicy

- 시험 유형별 정책: 재응시 제한 일수, 일수 계산 방식(`EXAM_DAY_IS_DAY_0` / `EXAM_DAY_IS_DAY_1`), Waiver 지원 여부(`YES / NO / UNKNOWN`), Waiver 재부여 일수, 제한 공유 그룹(OPIc 기본값 `ACTFL`, SAIL 기본값 `SAIL`, 설정에서 변경 가능), 결과 발표 기간, 출처, 검증 상태.
- 기본값: OPIc은 25일, Waiver 지원, 150일 재부여. SAIL은 25일(초기 가정), Waiver `UNKNOWN`.
- `UNKNOWN` Waiver는 응시 가능 경로 계산에서 사용 가능한 것으로 가정하지 않는다.

### B2. Waiver 원장 (ledger)

- 신규 테이블 `waiver_events`: examType, kind(`GRANTED / USED / RESTORED / EXPIRED`), at, registrationId, sourceType, verification.
- 잔여 횟수는 저장하지 않고 이벤트로부터 계산한다.
- 현재 보유 1회는 `GRANTED`, `USER`, `UNVERIFIED` 이벤트로 시작한다.
- 시뮬레이션은 메모리에서만 계산하고 원장에 기록하지 않는다.
- 취소·결시 시 복구 규칙이 검증되지 않았으면 "복구됨"과 "복구 안 됨" 두 경우를 함께 보여준다.

### B3. 재응시 엔진 통합

- `RetestAdvisor`와 `RetestCalculator`의 계산을 하나의 `RetakeEngine`으로 통합한다. `RetestAdvisor`의 준비도 게이트(Readiness gates)는 그대로 재사용한다.
- 기준일은 신청일이 아니라 `COMPLETED` 상태인 등록의 실제 응시일이다. `CANCELLED`는 제외하고, `NO_SHOW` 처리는 정책 값에 따른다.
- 출력은 세 가지 날짜 상태로 분리한다.
  - `RULE_ELIGIBLE`: 규정상 응시 가능한 가장 빠른 날
  - `LEARNING_RECOMMENDED`: 준비도 기준 추천일
  - `REGISTRATION_CONFIRMED`: 실제 접수가 확인된 날
- 함께 출력할 값: Waiver 미사용 경로와 사용 경로 비교, 12/31 전에 결과까지 받을 수 있는 응시 기회 수, 각 결과의 근거와 가정 목록.
- 사용자가 Waiver 사용 여부를 바꾸면 추천이 즉시 다시 계산된다.

### B4. 시험 유형별 복기와 계획 반영

- 기존 `NextExamScreen`의 복기를 시험 유형별로 나눈다. 입력 항목: 실제 질문, 답하지 못한 질문, 어려웠던 유형, 체감 난이도, 실제 취득 등급, 다음 개선 목표.
- 결과 등급을 입력하면 등록 상태가 갱신되고, 다음 날 계획이 다음 시험 기준으로 다시 계산된다.

### B5. 최소 알림

- 등록된 시험의 D-7, D-3, D-1, 입실 안내만 추가한다. 접수 마감일과 성적 발표일은 사용자가 입력한 경우에만 알린다.
- 같은 날 여러 알림은 하나로 묶고, 종류별로 끌 수 있게 한다.

### B단계 테스트

- 정답 데이터 기반 OPIc 25일 경계값 (B0 입력이 있을 때)
- 일수 계산 방식 두 가지의 차이
- Waiver 미사용과 사용 비교, Waiver 소진 후 다음 응시 가능일, 재부여일
- 시험 취소, 결시, 취소 후 재신청, 시험일 변경
- OPIc과 SAIL 독립 계산, 같은 제한 그룹으로 설정한 경우
- SAIL 규정 미확인 상태
- 정밀도 `MONTH`인 과거 응시 (2026-02)
- 12/31 연말 경계, 결과 발표 기간 때문에 기회 수가 줄어드는 경우
- 추천일을 채택해도 `REGISTERED`가 되지 않는지

---

## 6. 전체 금지 사항

- 추천일, 가정된 규정, 앱 점수를 확정 일정, 공식 규정, 공식 등급처럼 표시하지 않는다.
- 검증되지 않은 접수 마감일이나 성적 발표일을 만들어 알리지 않는다.
- 과거 응시일을 임의의 날짜로 만들지 않는다.
- Hilt 도입, 멀티 모듈 전환, 대규모 리팩토링을 하지 않는다.
- 실행하지 못한 테스트나 실기기 검증을 통과했다고 보고하지 않는다.

## 7. 각 단계 종료 시 보고 형식

1. 실제로 동작하게 된 것 (사용자가 바로 해볼 수 있는 동작 기준)
2. 변경한 파일 목록
3. 데이터 모델과 마이그레이션 변경 내용
4. 빌드와 테스트 결과 (실패가 있으면 출력 원문)
5. 실기기에서 확인한 항목과 확인하지 못한 항목
6. 가정으로 남아 있는 규정 목록
7. 다음 단계에서 할 일

`README.md`와 `docs/ARCHITECTURE.md`는 각 단계가 끝날 때 실제 구현 상태에 맞춰 갱신한다.
