// 逐行复刻 Protocol.kt 的 frame() 与 Pattern.kt 的 parse()，用来验证 Kotlin 逻辑正确
// （本机无 Android SDK / kotlinc，无法编译 Kotlin，故用等价复刻 + 对照已实测的期望值）

const HEAD = 0xAA;
const GROUP_MODE = 0x01;
const GROUP_SHAKE = 0x02;
const CMD_PAUSE = 0x00;
const CMD_START = 0x01;
const CMD_SHAKE = 0x12;

function frame(group, cmd, param = null) {
  const body = [HEAD, group & 0xFF, cmd & 0xFF];
  if (param !== null) body.push(param & 0xFF);
  let sum = 0;
  for (const b of body) sum += b;
  body.push(sum & 0xFF);
  return body;
}

const frameMode = (c) => frame(GROUP_MODE, c);
const frameStart = () => frame(GROUP_MODE, CMD_START);
const framePause = () => frame(GROUP_MODE, CMD_PAUSE);
const frameStrength = (v) => frame(GROUP_SHAKE, CMD_SHAKE, Math.max(0, Math.min(255, v)));
const frameHeartbeat = () => [0xAA, 0xAA, 0xAA, 0xAA, 0xA8];

const valid = (f) => {
  if (f.length < 4) return false;
  if (f[0] !== HEAD) return false;
  let s = 0;
  for (let i = 0; i < f.length - 1; i++) s += f[i];
  return (s & 0xFF) === f[f.length - 1];
};

const hex = (a) => a.map((b) => b.toString(16).padStart(2, '0').toUpperCase()).join(' ');

// MODES 表（与 Protocol.kt 完全一致）
const MODES = [
  ['微风', 0x02], ['心跳', 0x03], ['冲击', 0x04], ['拍打', 0x05], ['深度', 0x06],
  ['宇宙', 0x07], ['海啸', 0x08], ['瀑布', 0x09], ['火山', 0x10], ['神风', 0x11],
];

// 期望值来自真机实测 + Python 实现 + 原始小程序 JS 三方一致的结果
const EXPECTED = {
  'heartbeat': 'AA AA AA AA A8',
  'pause': 'AA 01 00 AB',
  'start': 'AA 01 01 AC',
  '微风': 'AA 01 02 AD',
  '心跳': 'AA 01 03 AE',
  '冲击': 'AA 01 04 AF',
  '拍打': 'AA 01 05 B0',
  '深度': 'AA 01 06 B1',
  '宇宙': 'AA 01 07 B2',
  '海啸': 'AA 01 08 B3',
  '瀑布': 'AA 01 09 B4',
  '火山': 'AA 01 10 BB',
  '神风': 'AA 01 11 BC',
  'str35': 'AA 02 12 23 E1',
  'str50': 'AA 02 12 32 F0',
  'str60': 'AA 02 12 3C FA',
  'str70': 'AA 02 12 46 04',
  'str100': 'AA 02 12 64 22',
  'str55': 'AA 02 12 37 F5',
};

let pass = 0, fail = 0;
function check(name, got) {
  const exp = EXPECTED[name];
  const ok = got === exp;
  if (ok) pass++; else { fail++; }
  console.log(`${ok ? 'OK  ' : 'FAIL'}  ${name.padEnd(10)} got=${got.padEnd(18)} exp=${exp}`);
}

console.log('=== Protocol.kt frame() 复刻验证 ===');
check('heartbeat', hex(frameHeartbeat()));
check('pause', hex(framePause()));
check('start', hex(frameStart()));
for (const [name, cmd] of MODES) check(name, hex(frameMode(cmd)));
for (const v of [35, 50, 60, 70, 100, 55]) check('str' + v, hex(frameStrength(v)));

console.log('\n=== isValid() 自检 ===');
const allFrames = [
  frameHeartbeat(), framePause(), frameStart(),
  ...MODES.map(([, c]) => frameMode(c)),
  ...[35, 50, 60, 70, 100, 55].map(frameStrength),
];
const allValid = allFrames.every(valid);
console.log('全部帧 isValid =', allValid);
// 故意破坏一帧，确认能被检出
const broken = frameMode(0x02); broken[broken.length - 1] ^= 0xFF;
console.log('破坏后的帧 isValid =', valid(broken), '(应为 false)');

console.log('\n=== 强度边界（0..255 全覆盖）校验和自洽 ===');
let rangeOk = true;
for (let v = 0; v <= 255; v++) {
  if (!valid(frameStrength(v))) { rangeOk = false; console.log('  bad at', v); }
}
console.log('力度 0..255 全部 256 条帧校验和自洽 =', rangeOk);

// ---------------------------------------------------------------- Pattern.parse 复刻
const DEFAULT_DURATION_MS = 300;

function parsePattern(text) {
  const steps = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const lineNo = i + 1;
    const line = lines[i].split('#')[0].trim();
    if (line === '') continue;
    const parts = line.split(/[\s,]+/).filter((s) => s !== '');
    if (parts.length === 0) continue;
    const strength = /^-?\d+$/.test(parts[0]) ? parseInt(parts[0], 10) : null;
    if (strength === null) return { error: `第 ${lineNo} 行：力度「${parts[0]}」不是整数` };
    let duration = DEFAULT_DURATION_MS;
    if (parts.length >= 2) {
      if (!/^-?\d+$/.test(parts[1])) return { error: `第 ${lineNo} 行：时长「${parts[1]}」不是整数` };
      duration = parseInt(parts[1], 10);
    }
    if (strength < 0 || strength > 255) return { error: `第 ${lineNo} 行：力度 ${strength} 超出范围` };
    if (duration < 0) return { error: `第 ${lineNo} 行：时长不能为负` };
    if (duration > 600000) return { error: `第 ${lineNo} 行：单步时长过大` };
    steps.push([strength, duration]);
  }
  if (steps.length === 0) return { error: '脚本为空，至少需要一步' };
  return { steps };
}

console.log('\n=== Pattern.parse 复刻验证 ===');
const cases = [
  ['基本解析', '40 400\n60 400\n100 1500', [[40, 400], [60, 400], [100, 1500]]],
  ['省略时长默认300', '40\n60', [[40, 300], [60, 300]]],
  ['注释与空行', '# 注释\n\n40 400\n\n# 又一段\n0 200', [[40, 400], [0, 200]]],
  ['行尾注释', '40 400 # 慢速爬升\n0 200', [[40, 400], [0, 200]]],
  ['逗号分隔', '40,400\n60,300', [[40, 400], [60, 300]]],
  ['制表符分隔', '40\t400', [[40, 400]]],
  ['内置 ramp', '40  400\n55  400\n70  500\n85  600\n100 1500\n70  400\n50  300\n0   200',
    [[40, 400], [55, 400], [70, 500], [85, 600], [100, 1500], [70, 400], [50, 300], [0, 200]]],
];
for (const [name, text, expected] of cases) {
  const r = parsePattern(text);
  if (r.error) { console.log(`FAIL  ${name}: ${r.error}`); fail++; continue; }
  const ok = JSON.stringify(r.steps) === JSON.stringify(expected);
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'OK  ' : 'FAIL'}  ${name.padEnd(16)} -> ${JSON.stringify(r.steps)}`);
}

console.log('\n=== 非法输入应被拒绝 ===');
const badCases = [
  ['力度非数字', 'abc 400'],
  ['力度越界', '300 400'],
  ['负力度', '-5 400'],
  ['时长非数字', '40 xyz'],
  ['负时长', '40 -100'],
  ['空脚本', '# 只有注释\n\n'],
];
for (const [name, text] of badCases) {
  const r = parsePattern(text);
  const ok = !!r.error;
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'OK  ' : 'FAIL'}  ${name.padEnd(14)} -> ${r.error || '(未报错!)'}`);
}

console.log(`\n============================`);
console.log(`通过 ${pass}  失败 ${fail}`);
console.log(fail === 0 ? '全部通过 —— Kotlin 协议层逻辑验证正确' : '存在失败项，需要修正');
