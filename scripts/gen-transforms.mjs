// Regenerates app/src/main/assets/japanese-transforms.json from Yomitan's japanese-transforms.js.
// Usage: node scripts/gen-transforms.mjs <empty-download-dir> [commit]
//   e.g. node scripts/gen-transforms.mjs C:\path\to\shiori-data\yomitan-src
// Downloads only the two Yomitan modules into the given directory, imports japanese-transforms.js
// (which itself only imports language-transforms.js) and writes plain data.
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL, fileURLToPath} from 'node:url';

const dir = path.resolve(process.argv[2] ?? '');
if (!process.argv[2]) { console.error('usage: node gen-transforms.mjs <download-dir> [commit]'); process.exit(1); }
let commit = process.argv[3];
if (!commit) {
    const r = await fetch('https://api.github.com/repos/yomidevs/yomitan/commits/master', {headers: {Accept: 'application/vnd.github.sha'}});
    commit = (await r.text()).trim();
}
if (!/^[0-9a-f]{40}$/.test(commit)) throw new Error('bad commit ' + commit);

const files = ['ext/js/language/ja/japanese-transforms.js', 'ext/js/language/language-transforms.js'];
for (const f of files) {
    const out = path.join(dir, f.replace('ext/js/language/', ''));
    if (!fs.existsSync(out)) {
        const r = await fetch(`https://raw.githubusercontent.com/yomidevs/yomitan/${commit}/${f}`);
        if (!r.ok) throw new Error(`download ${f}: ${r.status}`);
        fs.mkdirSync(path.dirname(out), {recursive: true});
        fs.writeFileSync(out, await r.text());
    }
}

const {japaneseTransforms: d} = await import(pathToFileURL(path.join(dir, 'ja', 'japanese-transforms.js')).href);

const literal = /^[^\^$.*+?()[\]{}|]+$/; // rules must be plain strings, not regexes
const conditions = {};
for (const [name, c] of Object.entries(d.conditions)) {
    conditions[name] = {isDictionaryForm: c.isDictionaryForm, subConditions: c.subConditions ?? []};
}
const transforms = Object.entries(d.transforms).map(([id, t]) => ({
    id,
    name: t.name,
    rules: t.rules.map((r) => {
        let inflected;
        if (r.type === 'suffix') inflected = r.isInflected.source.slice(0, -1);
        else if (r.type === 'wholeWord') inflected = r.isInflected.source.slice(1, -1);
        else throw new Error('unsupported rule type ' + r.type);
        if (!literal.test(inflected)) throw new Error('non-literal rule ' + inflected);
        const rule = {type: r.type, inflected, conditionsIn: r.conditionsIn, conditionsOut: r.conditionsOut};
        if (r.type === 'suffix') rule.deinflected = r.deinflected;
        else rule.deinflected = r.deinflect(inflected);
        return rule;
    }),
}));

const outFile = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'app', 'src', 'main', 'assets', 'japanese-transforms.json');
fs.writeFileSync(outFile, JSON.stringify({
    source: `yomidevs/yomitan ext/js/language/ja/japanese-transforms.js @ ${commit} (GPL-3.0)`,
    commit,
    conditions,
    transforms,
}, null, 1) + '\n');
console.log(`${transforms.length} transforms, ${transforms.reduce((n, t) => n + t.rules.length, 0)} rules, ${Object.keys(conditions).length} conditions -> ${outFile}`);
