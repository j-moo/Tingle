// Run from any directory with Node.js. The handoff and catalogue are read-only.
import fs from 'node:fs';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {questions, featureLabels, domainDefs} from '../reference/survey-20261021/mockup/dist/data100.mjs';
import model from '../reference/survey-20261021/mockup/dist/initial-profiles.mjs';

const root = new URL('../../', import.meta.url);
const output = new URL('../app/personal_analysis/data/', import.meta.url);
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const sources = [
  'docs/contracts/survey/questionnaire-SIS-Q-1.1-100.json',
  ...['data100', 'engine100', 'initial-profiles', 'type-recommendations100', 'wording100', 'age100']
    .map(name => `docs/handoffs/survey-20261021/mockup/dist/${name}.mjs`),
];
const localSource = path => path
  .replace('docs/contracts/survey/', 'ai-server/reference/survey-20261021/')
  .replace('docs/handoffs/survey-20261021/mockup/', 'ai-server/reference/survey-20261021/mockup/');
const artifacts = Object.fromEntries(sources.map(path => [path, hash(fs.readFileSync(new URL(localSource(path), root)))]));
const previous = new URL('provenance.json', output);
if (fs.existsSync(previous) && JSON.stringify(JSON.parse(fs.readFileSync(previous)).artifacts) !== JSON.stringify(artifacts)) {
  throw new Error('Pinned sources changed. Review a new calculation version before exporting again.');
}
const reference = JSON.stringify({model, questions, featureLabels, domainDefs}, null, 2) + '\n';
const catalogue = fs.readFileSync(new URL(localSource(sources[0]), root));
fs.mkdirSync(output, {recursive: true});
fs.writeFileSync(new URL('reference.json', output), reference);
fs.writeFileSync(new URL('questionnaire-SIS-Q-1.1-100.json', output), catalogue);
fs.writeFileSync(new URL('provenance.json', output), JSON.stringify({
  sourceCommit: 'f5224c9aad434e4e1b54df8f3f17d6f84eb48897',
  artifacts,
  packaged: {
    'reference.json': hash(reference),
    'questionnaire-SIS-Q-1.1-100.json': hash(catalogue),
  },
}, null, 2) + '\n');
console.log(`Exported reference data to ${fileURLToPath(output)}`);
