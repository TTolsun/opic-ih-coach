# UX copy rules

The learner opens this app to speak, not to read. Every sentence on screen competes with the mic button.

## Before adding a sentence, classify it

| Kind | Example | Where it goes |
|---|---|---|
| Action now | "같은 질문 다시 말하기" | Button label. Verb first, at most ~12 characters. |
| Irreversible warning | "되돌릴 수 없습니다" | Confirm dialog only. |
| Trust / measurement caveat | "공식 등급 예측이 아닙니다", STT limits | `InfoTopics` in `ui/components/InfoSheet.kt`, shown behind an ⓘ button. Never inline. |
| Mode instruction | "키워드만 보고 말하기" | The mode's `hint` in `ui/screens/PracticeModeUi.kt`. One line. |
| How it works / why | Keystore, engine limits, unofficial tips | Do not put on screen. README or code comment. |

## Rules

1. One helper line per card at most. If a control needs explaining, change the control (for example, a "질문 보기" button instead of "tap here to see the question").
2. An empty state names the state in one short sentence; the action is a button.
3. Banners are one short sentence plus an action. No second sentence explaining the consequence.
4. Do not repeat the same caveat on several screens. Add a fact to `InfoTopics` once.
5. New practice modes: add a row to `PracticeModes.all` (label, stage, hint). Do not add per-mode explanation cards.
