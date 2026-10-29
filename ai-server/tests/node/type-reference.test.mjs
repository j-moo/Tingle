import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {typeRecommendations} from '../../reference/survey-20261021/mockup/dist/type-recommendations100.mjs';
import {demoAnswers,pair,classifyAnswers,profileFromAnswers,readings} from '../../reference/survey-20261021/mockup/dist/engine100.mjs';
const a=demoAnswers(),before=structuredClone(a),base=typeRecommendations(a),rank=r=>r.all.map(p=>[p.type,p.score]);
assert.equal(base.selected.length,3);
for(const flags of [['age'],['smoking','religion'],['children','tattoo']]){
 const changed={...a,97:flags};assert.deepEqual(rank(typeRecommendations(changed)),rank(base));
}
const lifestyles={...a,48:1,49:'half',50:'daily',52:['night'],54:5,55:'both',56:'muslim',57:5,58:'no',60:'visible',83:1,84:['half'],85:['daily'],87:['night'],89:5,90:['cigarette'],91:['muslim'],92:5,93:['no'],95:['visible'],97:['age','smoking']};
assert.deepEqual(rank(typeRecommendations(lifestyles)),rank(base));
const allAny=structuredClone(a);for(let id=61;id<=95;id++)allAny[id]='any';const fallback=typeRecommendations(allAny);assert.equal(fallback.mode,'self-similarity');assert.equal(fallback.selected.length,3);assert.equal(fallback.selected[0].type,classifyAnswers(allAny).primary.type);
assert.equal(typeRecommendations({}).selected.length,0);
const strict={...a,97:['age','smoking'],90:['none']},b={...demoAnswers(),55:'cigarette'};
assert.equal(typeRecommendations(strict).selected.length,3);assert.equal(pair(strict,b,{age:25,min:23,max:27},{age:31}).eligible,false);assert.deepEqual(pair(strict,b,{age:25,min:23,max:27},{age:31}).ab.excluded,['age','smoking']);
const unknownPair=pair({...a,97:['age']},b);assert.deepEqual(unknownPair.ab.unresolved,['age']);assert.equal(unknownPair.eligible,false);
assert.deepEqual(a,before);
const manifest=JSON.parse(fs.readFileSync(new URL('../fixtures/v14-source-manifest.json',import.meta.url)));
for(const name of ['data100.mjs','engine100.mjs','initial-profiles.mjs','age100.mjs','record100.mjs','wording100.mjs'])assert.equal(crypto.createHash('sha256').update(fs.readFileSync(new URL('../../reference/survey-20261021/mockup/dist/'+name,import.meta.url))).digest('hex'),manifest.files.find(f=>f.file===name).sha256,name+' changed');
assert.deepEqual(profileFromAnswers(a),profileFromAnswers(before));assert.deepEqual(readings(a),readings(before));
console.log('Type references independent of all real-person exclusions and neutral lifestyle inputs; labeled ANY fallback; missing-data behavior; unchanged actual-pair exclusions and six core source hashes; no answer mutation passed.');
