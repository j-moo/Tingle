import assert from 'node:assert/strict';
import fs from 'node:fs';
import {questions,questionnaireVersion} from './dist/data100.mjs';
import {profileFromAnswers,classifyAnswers,pair,readings,model} from './dist/engine100.mjs';
import {ageDecision} from './dist/age100.mjs';
const old=JSON.parse(fs.readFileSync('fixtures/wording-baseline.json'));
assert.equal(questionnaireVersion,'SIS-Q-1.1-100');assert.equal(questions.length,100);
for(const q of questions){const prior=old.questions.find(p=>p.id===q.id);assert.equal(q.kind,prior.kind);assert.equal(q.max,prior.max);assert.deepEqual(q.options.map(o=>o.value).filter(v=>v!=='age'),prior.options.map(o=>o.value));}
assert.deepEqual(old.fixtures.map(profileFromAnswers),old.profiles);assert.deepEqual(old.fixtures.map(classifyAnswers),old.classifications);
assert.deepEqual(old.fixtures.flatMap(a=>old.fixtures.map(b=>{const p=pair(a,b);return [p.score,p.eligible,p.ab.score,p.ba.score,p.ab.coverage,p.ba.coverage]})),old.pairs);
assert.deepEqual(old.fixtures.map(a=>readings(a).map(r=>[r.key,r.priority])),old.readingKeys);
const a=structuredClone(old.fixtures[0]),b=old.fixtures[1],before=pair(a,b);a[97]=['age'];
assert.equal(ageDecision({min:23,max:27},{age:23}),'accepted');assert.equal(ageDecision({min:23,max:27},{age:27}),'accepted');assert.equal(ageDecision({min:23,max:27},{age:28}),'excluded');assert.equal(ageDecision({min:27,max:23},{age:25}),'unresolved');
assert.deepEqual(pair(a,b).ab.unresolved,['age']);assert.ok(pair(a,b,{age:25,min:23,max:27},{age:28}).ab.excluded.includes('age'));
assert.equal(pair(a,b,{age:25,min:23,max:27},{age:26}).score,before.score);
const ab=pair(a,b,{age:25,min:23,max:27},{age:26}),ba=pair(b,a,{age:26},{age:25,min:23,max:27});assert.equal(ab.eligible,ba.eligible);assert.equal(ab.score,ba.score);
console.log('Wording preserves all old values, 16 profiles/types, 256 scores and reading branches; isolated optional age filter, inclusive bounds, missing/invalid age and symmetry passed.');
