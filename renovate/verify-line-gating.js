/*
 * Proves that the Renovate preset of this repository keeps a consumer inside its release
 * line, instead of asserting it in prose. The VanillaBP Camunda 8 adapter carries the same
 * check for its own preset, and both have to hold: an application reads the adapter and
 * this extension, and a line is a statement about the cluster, not about a number.
 *
 * Two things are checked:
 *
 *  - Maven orders 0.10.0-8.8 ABOVE 0.9.0-8.9, so "the newest version" crosses a line
 *    boundary under plain maven versioning. The regex versioning of
 *    'camunda8-lines.json' has to refuse that candidate.
 *  - Renovate's documentation says capture groups have to be numeric, which is about the
 *    version parts, while our compatibility value is the string "8.10" and contains a
 *    dot. This has to be accepted.
 *
 * Run it from the repository root, with the renovate package installed:
 *
 *   npm install --no-save renovate
 *   node renovate/verify-line-gating.js
 */

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { get } = require('renovate/dist/modules/versioning/index.js');

const presetFile = path.join(__dirname, 'camunda8-lines.json');
const preset = JSON.parse(fs.readFileSync(presetFile, 'utf8'));

const rule = preset.packageRules.find((candidate) => candidate.versioning);
assert.ok(rule, `no package rule of '${presetFile}' carries a versioning scheme`);

const api = get(rule.versioning);
assert.ok(api, `versioning scheme '${rule.versioning}' is unknown to Renovate`);

// every version this repository could ever publish, of every line
const published = [
  '0.9.0-8.8', '0.10.0-8.8', '1.0.0-8.8',
  '0.9.0-8.9', '0.10.0-8.9', '1.0.0-8.9',
  '0.10.0-8.10', '1.0.0-8.10-alpha1', '1.0.0-8.10-alpha2', '1.0.0-8.10',
];

function offeredTo(pinned) {
  return published
    .filter((version) => api.isValid(version))
    .filter((version) => api.isCompatible(version, pinned))
    .filter((version) => api.isGreaterThan(version, pinned))
    .sort((left, right) => api.sortVersions(left, right));
}

const expected = {
  '0.9.0-8.8': ['0.10.0-8.8', '1.0.0-8.8'],
  '0.9.0-8.9': ['0.10.0-8.9', '1.0.0-8.9'],
  '0.10.0-8.10': ['1.0.0-8.10-alpha1', '1.0.0-8.10-alpha2', '1.0.0-8.10'],
};

for (const [pinned, updates] of Object.entries(expected)) {
  const offered = offeredTo(pinned);
  assert.deepStrictEqual(
    offered,
    updates,
    `a consumer pinned to ${pinned} would be offered ${JSON.stringify(offered)}`);
  console.log(`ok   ${pinned} -> ${JSON.stringify(offered)}`);
}

// the trap the compatibility group exists for: newer by number, other line
assert.ok(
  api.isGreaterThan('0.10.0-8.8', '0.9.0-8.9'),
  '0.10.0-8.8 should still be the greater NUMBER, otherwise this check tests nothing');
assert.strictEqual(
  api.isCompatible('0.10.0-8.8', '0.9.0-8.9'),
  false,
  '0.10.0-8.8 must not be compatible with a consumer of line 8.9');
console.log('ok   0.10.0-8.8 is the greater number than 0.9.0-8.9 and still not offered to line 8.9');

// a compatibility value containing a dot is accepted
assert.ok(api.isValid('0.10.0-8.10'), 'a compatibility value with a dot must be valid');
console.log('ok   the compatibility value "8.10" is read although it contains a dot');

// a pre-release of a line ranks below that line's release, and in order among itself
assert.ok(api.isGreaterThan('1.0.0-8.10', '1.0.0-8.10-alpha2'));
assert.ok(api.isGreaterThan('1.0.0-8.10-alpha2', '1.0.0-8.10-alpha1'));
assert.strictEqual(api.isStable('1.0.0-8.10-alpha1'), false);
assert.strictEqual(api.isStable('1.0.0-8.10'), true);
console.log('ok   1.0.0-8.10-alpha1 < 1.0.0-8.10-alpha2 < 1.0.0-8.10, and the alphas are unstable');

console.log('\nline gating verified against renovate ' + require('renovate/package.json').version);
