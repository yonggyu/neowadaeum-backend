#!/usr/bin/env node
/*
 * close-linked-issues.yml 의 닫기 키워드 매처 회귀 테스트 (B-04-1 / #11)
 *
 *   node .github/scripts/test-closing-keywords.mjs
 *
 * 워크플로의 인라인 스크립트에서 `testable:start` ~ `testable:end` 구간을 잘라내 그대로 평가한다.
 * 로직을 복사해 두지 않는 이유는, 복사본은 원본과 갈라지는 순간 테스트가 아니라 거짓말이 되기 때문이다.
 *
 * B-04 에서 CI 잡에 연결한다. 그 전까지는 워크플로를 고칠 때 손으로 돌린다.
 */
import { readFileSync } from 'node:fs';

const WORKFLOW = new URL('../workflows/close-linked-issues.yml', import.meta.url);
const START = '// testable:start';
const END = '// testable:end';

const raw = readFileSync(WORKFLOW, 'utf8');
const startIndex = raw.indexOf(START);
const endIndex = raw.indexOf(END);

if (startIndex < 0 || endIndex < 0 || endIndex < startIndex) {
  console.error(`워크플로에서 ${START} ~ ${END} 구간을 찾지 못했다. 표식이 지워졌거나 옮겨졌다.`);
  process.exit(1);
}

// YAML 블록 스칼라라 공통 들여쓰기가 붙어 있다. 최소 들여쓰기를 걷어내야 JS 로 평가된다.
const lines = raw.slice(startIndex + START.length, endIndex).split('\n');
const indent = Math.min(...lines.filter((line) => line.trim()).map((line) => line.match(/^ */)[0].length));
const source = lines.map((line) => line.slice(indent)).join('\n');

// REFERENCE 가 참조하는 값. 워크플로에서는 context.repo 로 들어온다.
const owner = 'yonggyu';
const repo = 'neowadaeum-backend';

const findLinkedIssues = eval(`${source}\n; findLinkedIssues`);

const CASES = [
  // ── 키워드 형태 ─────────────────────────────────────────
  ['Closes #5', [5]],
  ['closes #5', [5]],
  ['Closed #5', [5]],
  ['fix #7', [7]],
  ['Fixes #7', [7]],
  ['fixed #7', [7]],
  ['resolve #9', [9]],
  ['Resolves #9', [9]],
  ['resolved #9', [9]],
  ['Closes: #11', [11]],
  ['closes GH-12', [12]],
  ['Fixes https://github.com/yonggyu/neowadaeum-backend/issues/13', [13]],
  ['closes https://www.github.com/yonggyu/neowadaeum-backend/issues/14', [14]],
  ['Closes #5\n\nfixes #6\nresolves GH-5', [5, 6]],
  ['Closes #5.', [5]],
  ['(Closes #5)', [5]],
  ['Closes #5, #6', [5]],
  ['Closes #5 and fixes #6', [5, 6]],

  // ── 키워드가 아닌 것 ────────────────────────────────────
  ['refs #5', []],
  ['Refs: R6.1, P6', []],
  ['관련 #5', []],
  ['prefixes #5', []],
  ['unclosed #5', []],
  ['Closes https://github.com/other/repo/issues/99', []],
  ['#5 만 단독', []],
  ['Closes #5x', []],
  ['closes #5-1', []],

  // ── 마크다운 문맥 (#11 회귀) ────────────────────────────
  // 이 축을 시험하지 않아 #9 가 잘못 닫혔다. GitHub 도 코드 문맥은 참조로 보지 않는다.
  ['`Closes #5`', []],
  ['`resolve #9` / `Resolves #9` / `resolved #9`', []],
  ['| `Closes #5` / `closes #5` | `refs #5` |', []],
  ['``Closes #5``', []],
  ['```\nCloses #5\n```', []],
  ['~~~\nCloses #5\n~~~', []],
  ['```js\n// Closes #5\nconst x = 1;\n```', []],
  ['<!-- Closes #5 -->', []],
  ['<!--\nfeat/* → backend\nCloses #5\n-->', []],

  // ── 섞인 본문: 실제 참조만 인식한다 ──────────────────────
  ['## 관련 이슈\n\nCloses #7\n\n| 예시 | `resolve #9` |', [7]],
  ['Closes #7\n\n```\nfixes #99\n```\n\n<!-- closes #98 -->', [7]],
  ['본문에 `Closes #7` 이라고 적었다', []],
];

let failed = 0;
for (const [text, expected] of CASES) {
  const actual = findLinkedIssues(text);
  const passed = JSON.stringify(actual) === JSON.stringify(expected);
  if (!passed) {
    failed += 1;
  }
  const label = JSON.stringify(text);
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${label.length > 64 ? label.slice(0, 61) + '..."' : label.padEnd(64)}`
    + `  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);
}

console.log(`\n${CASES.length - failed}/${CASES.length} 통과`);
process.exit(failed === 0 ? 0 : 1);
