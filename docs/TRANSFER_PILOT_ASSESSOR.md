# 새 질문 적용 파일럿 진행자용 평가 문항

진행자와 채점자만 이 문서를 엽니다. 학습자에게는 해당 시도에서 영어 원문 한 개만 읽어 줍니다. 아래 접힌 영역에는 평가 문항과 채점 예시가 들어 있습니다. 학습을 시작하기 전에 진행 절차를 확인하려면 [파일럿 실행 안내](TRANSFER_PILOT.md)를 사용합니다.

## 노출 관리

1. 진행자가 문항 원문과 버전을 보관하고 학습자에게는 세션 일정과 녹음 안내만 전달합니다. 평가 문항은 질문 은행, 스크립트, 복습 목록, 메모 공유 및 AI 프롬프트에 넣지 않습니다. 공개 저장소의 이 파일과 변경 화면에는 원문이 있으므로 진행자는 학습자에게 해당 링크나 채점 예시를 전달하지 않습니다. 접힌 영역은 실수로 내용을 보는 일을 줄여 줍니다. 학습자가 파일이나 변경 화면을 열어 원문을 본 경우 진행자는 새 문항을 준비해 보관합니다.
2. 진행자는 해당 단계에서만 평가 문항을 열고 1회 읽습니다. 정해진 10초 준비와 최대 60초 답변을 사용합니다. 번역, 모범 답, 예시 소재, 문장 틀은 제시하지 않습니다. 첫 녹음을 닫은 뒤 정확한 문구나 사실상 같은 질문을 예전에 본 적이 있는지, 준비한 답을 그대로 쓸 수 있었는지 확인하고 노출과 친숙도를 기록합니다. 친숙도는 답할 소재를 쉽게 찾았으면 높음, 생각해서 찾았으면 보통, 소재가 없거나 주제가 낯설면 낮음으로 적습니다.
3. 즉시 적용은 연습일, 지연 미연습은 D+3의 첫 문항으로 제시합니다. 두 시도의 채점 예시나 답변 해설은 지연 세션을 마친 다음 공유합니다. 지연 재검사 전에 미리 열었거나 기존 답으로 사실상 같은 과업을 수행한 경우 해당 질문의 노출을 기록하고 적용 비교를 보류합니다. 다음 회차에 진행자가 같은 말하기 기능·조건 수·난이도의 새 문항을 준비하고 동일한 중복 검사를 합니다. 혼자 진행하면서 이미 원문이나 예시를 본 경우 해당 문항을 연습으로 표시합니다. 미노출 자료는 새 질문을 보관하고 제시해 줄 진행자와 확보합니다.
4. 구체적인 경험을 찾지 못하면 첫 시도에 그 사실을 남깁니다. 가상 예시로 다시 답할 경우에는 가상임을 밝히고 별도 시도로 저장합니다. 실제 경험으로 답한 문항과 묶어 유지 여부를 판정하지 않습니다. 학습자의 실제 경험을 질문 내용이나 예시에서 추정하지 않습니다.

## 문항 짝과 중복 검토

기준 커밋 `78a938c`의 질문 은행 81개, 스크립트 질문 23개, 14일 계획 문항, 파일럿 연습 문항을 대조했습니다. 문구를 대소문자·문장부호·공백에 영향을 받지 않도록 비교한 뒤, 같은 소재와 답변이 새 조건을 그대로 충족하는지 확인했습니다. 네 평가 ID와 영어 원문은 각각 고유합니다. 실행 전에는 진행자가 학습자에게 추가로 연습한 자료와 질문 노출을 확인합니다. [질문 은행](../app/src/main/assets/seed/content.json) · [내장 스크립트](../app/src/main/assets/seed/user_scripts_2026.json) · [14일 계획](14_DAY_OPIC_PLAN.md)

| 짝 | 같은 기능·형식과 조건 수 | 달라지는 난이도와 가까운 기존 문항 |
| --- | --- | --- |
| H-P1 ↔ H-P2a 또는 H-P2b | 개인의 과거·현재를 비교하는 직접 답변입니다. 과거 대상, 현재 대상, 같은 기준의 차이까지 3과업입니다. | 변형에서는 쓰임 또는 크기를 지정합니다. 원문에서 사용한 기준을 확인한 뒤 바꿉니다. 같은 소파 사실을 사용할 수 있습니다. |
| H-P1 ↔ H-I ↔ H-D | 같은 개인 비교 기능과 직접 답변, 과거·현재·차이 3과업을 유지합니다. | 두 새 문항은 비교 기준을 지정하며 어린 시절 대신 학창 시절 또는 청소년기를 기준으로 삼습니다. 인터넷 사용 변화·스마트폰 소통·음악 취향 변화·쇼핑 변화와 시간 구조가 비슷합니다. 요구하는 개인 정보원과 일정 기억 방식은 기존 주제의 답변으로 처리할 수 없습니다. `Appointments`의 기존 문항은 예약 과정이나 특정 사건을 다루며 두 시기의 일정 기억 방식을 비교하지 않습니다. 새 두 주제의 친숙도는 확인이 필요합니다. |
| T-P1 ↔ T-P2 | 과거 한 사건을 서술하는 직접 답변이며 문제·행동·결과 3과업입니다. | 변형은 첫 대응 뒤의 행동을 요구합니다. 같은 사건을 사용하며, 어느 조치의 결과를 설명하는지 구분해야 합니다. |
| T-P1 ↔ T-I ↔ T-D | 한 사건의 문제·첫 대응·결과 3과업을 유지합니다. 직접 답변과 과거 시간대도 동일합니다. | 극장의 소리·화면 문제와 일·학교의 문서 전달 오류는 소재 선택 부담이 있습니다. 영화의 ‘예상 밖 경험’ 스크립트와 ‘최근 영화 관람’ 문항은 영화가 겹치지만, 기존 배탈·결말 놓침이나 잘못 예약한 표로는 극장의 소리·화면 문제에 답할 수 없습니다. 질문 은행의 일반 업무 문제와 14일 계획 12일차의 잘못 가져온 물건은 문서 전달 오류를 지정하지 않습니다. 개인의 기존 업무 답변이 실제로 문서 전달 오류를 다뤘다면 해당 평가를 노출로 표시합니다. |

평가와 연습 문항은 시간대를 자연스럽게 유지하는 과제를 사용합니다. 조건 수를 맞췄더라도 경험 회상의 어려움, 선택할 수 있는 사례 수, 정해진 비교 기준이 다릅니다. 친숙도 한 단계 차이는 기록한 뒤 해석하고, 두 단계 차이 또는 실제 경험을 떠올리지 못한 경우에는 해당 대비를 보류합니다. 공통 표현을 기억했더라도 요구된 새 내용과 관계를 실제로 말한 경우에만 적용으로 기록합니다.

<details>
<summary>H-I · 즉시</summary>

**영어 원문**

> Think about when you were in school and how you get news now. Compare where you got news then with where you get it now. What is the main difference?

파일럿 작성 문항입니다. 주제는 뉴스 정보원입니다. 개인 비교를 요구하는 직접 답변입니다. 필수 과업은 ① 학창 시절 뉴스 출처 ② 현재 뉴스 출처 ③ 출처의 차이 또는 두 시기 출처가 같은지 명시입니다. 학창 시절과 현재를 구분합니다. 친숙도는 확인이 필요합니다. 무힌트이며 1회 제시, 10초 준비, 최대 60초 답변입니다.

- **관찰됨 예시.** “When I was in school, I got the news from TV at dinner. These days I get it from an app on my phone. I used to rely on TV, but now my phone is my main source.” 두 출처와 차이를 밝히고, 같은 기준으로 연결하며, 시간대를 구분합니다. 두 출처가 같아도 그렇게 명시하고 두 시점의 근거를 들려주면 비교로 인정합니다.
- **일부 또는 관찰되지 않음 예시.** “I watched the evening news at home. These days I read the news a lot because it's important.”는 과거 출처를 말하지만 현재 출처 대신 빈도와 이유를 말합니다. 시간 표지는 들리더라도 대응과 비교 기능은 일부입니다. “News helps us understand the world.”만 말하면 두 출처와 비교 기능이 관찰되지 않습니다.

</details>

<details>
<summary>H-D · 지연 미연습</summary>

**영어 원문**

> Think about when you were a teenager and how you keep track of your plans now. Compare how you remembered appointments then with how you remember them now. What is the main difference?

파일럿 작성 문항입니다. 주제는 개인 일정 관리입니다. 개인 비교를 요구하는 직접 답변입니다. 필수 과업은 ① 청소년기의 약속 기억 방법 ② 현재의 약속 기억 방법 ③ 기억 방법의 차이 또는 동일함을 명시입니다. 청소년기와 현재를 구분합니다. 친숙도는 확인이 필요합니다. 무힌트이며 1회 제시, 10초 준비, 최대 60초 답변입니다.

- **관찰됨 예시.** “As a teenager, I wrote appointments on a paper calendar. Now I put them in my phone and set alerts. Back then I had to check the calendar, but now my phone reminds me.” 두 방법과 차이를 같은 기준에서 밝히며 시간대를 유지합니다. 방법이 같으면 두 시기에 어떻게 썼는지와 같다는 근거를 들려줍니다.
- **일부 또는 관찰되지 않음 예시.** “I had fewer appointments as a teenager. Now I set alerts for all of them.”은 과거 약속 개수와 현재 기억 방법을 섞습니다. 시점은 유지하지만 대응·연결·비교 기능의 근거가 부족합니다. 예약 절차만 설명하면 목표 기능이 관찰되지 않습니다.

</details>

<details>
<summary>T-I · 즉시</summary>

**영어 원문**

> Think of a time when the sound or picture at a movie theater made it hard to follow the film. What was the problem, what was the first thing you did about it, and how did it turn out?

파일럿 작성 문항입니다. 주제는 영화 관람입니다. 과거 경험을 서술하는 직접 답변입니다. 필수 과업은 ① 극장에서 영화 내용을 따라가기 어렵게 한 소리나 화면 문제 ② 그 문제에 대한 첫 행동 ③ 행동 이후 결과입니다. 과거의 같은 관람 사건을 유지합니다. 친숙도와 실제 경험은 확인이 필요합니다. 무힌트이며 1회 제시, 10초 준비, 최대 60초 답변입니다.

- **관찰됨 예시.** “The speakers were too quiet, so I couldn't hear the dialogue. I first told an usher. The usher turned up the volume, and I could hear the rest of the movie.” 문제, 첫 행동, 들을 수 있게 된 결과가 같은 과거 사건으로 이어집니다. 불편이 해결되지 않았더라도 대응과 그 뒤 실제 결과가 이어지면 인정합니다.
- **일부 또는 관찰되지 않음 예시.** “The sound was too low, so I told an usher. I love that theater because the seats are comfortable.”는 과거 문제와 행동까지 들리지만 대응 결과가 없습니다. 소리 문제 없이 기존의 배탈과 영화 결말만 말하면 문항의 문제와 목표 기능은 관찰되지 않습니다.

</details>

<details>
<summary>T-D · 지연 미연습</summary>

**영어 원문**

> Think of a time when you received the wrong document for work or school. What problem did that cause, what was the first thing you did about it, and how did it turn out?

파일럿 작성 문항입니다. 주제는 학교 또는 일에서의 문서 전달입니다. 과거 경험을 서술하는 직접 답변입니다. 필수 과업은 ① 잘못 전달된 문서로 생긴 문제 ② 문제에 대한 첫 행동 ③ 그 뒤 결과입니다. 같은 과거 사건을 유지합니다. 친숙도와 실제 경험은 확인이 필요합니다. 무힌트이며 1회 제시, 10초 준비, 최대 60초 답변입니다.

- **관찰됨 예시.** “I got last month's schedule instead of the current one, so I couldn't tell which shift was mine. I first called the person who sent it. She sent the right schedule, and I found my shift.” 틀린 문서가 만든 문제, 조치, 결과를 한 사건으로 설명합니다.
- **일부 또는 관찰되지 않음 예시.** “I got the wrong file. I contacted my teammate, and then we had lunch.”는 첫 행동은 있지만 점심이 문서 문제의 결과로 연결되지 않습니다. 일반적인 문서 관리 습관을 현재형으로 나열하면 과거 사건과 목표 기능이 관찰되지 않습니다.

</details>

각 질문에서 발화가 들리면 빠진 과업과 근거를 기록합니다. 음성이 끊겨 내용을 확인할 수 없으면 영향을 받은 항목만 판단 불가로 기록합니다. 위 예문은 판정 연습용 가상 답변입니다. 실제 학습자는 자기 사례와 표현을 사용합니다.
