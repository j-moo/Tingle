import assert from 'node:assert/strict';
import {questions,validAnswer,answeredCount} from './dist/data100.mjs';
import {model,demoAnswers,classifyAnswers,classifyProfile,profileFromAnswers,pair,direction,wishFit,readings,recommendations} from './dist/engine100.mjs';
assert.equal(questions.length,100);
assert.equal(new Set(questions.map(q=>q.id)).size,100);
assert.deepEqual(questions.map(q=>q.id),Array.from({length:100},(_,i)=>i+1));
assert.equal(questions.filter(q=>q.id<=60).length,60);
const fixtures=model.prototypes.map(p=>demoAnswers(p.type));
for(let i=0;i<fixtures.length;i++){
 const a=fixtures[i];assert.equal(answeredCount(a),100);
 assert.ok(questions.every(q=>validAnswer(q.id,a[q.id])));
 assert.equal(classifyAnswers(a).primary?.type,model.prototypes[i].type);
 const changed={...a};for(let j=61;j<=100;j++)changed[j]='unknown';
 assert.deepEqual(classifyAnswers(changed),classifyAnswers(a));
 for(const b of fixtures){const ab=pair(a,b),ba=pair(b,a);assert.ok(Math.abs(ab.score-ba.score)<1e-9);assert.equal(ab.eligible,ba.eligible);}
}
const allMid=Object.fromEntries(questions.filter(q=>q.id<=60).map(q=>[q.id,q.options.every(o=>typeof o.value==='number')?3:'unknown']));
assert.equal(classifyAnswers(allMid).status,'tied');assert.equal(classifyAnswers(allMid).tied.length,16);
assert.equal(classifyAnswers({}).status,'insufficient');
const a=demoAnswers('STEADYSPARK'),b=structuredClone(a);b[55]='cigarette';a[90]=['none'];a[97]=['smoking'];
assert.equal(pair(a,b).eligible,false);assert.ok(direction(a,b).excluded.includes('smoking'));
const timeA=demoAnswers(),timeB=demoAnswers();timeA[52]=['morning'];timeA[87]=['evening'];timeB[52]=['evening'];
assert.equal(direction(timeA,timeB).rows.find(r=>r.id===87).fit,0);
assert.equal(wishFit(89,4,1,'close'),1,'lower drinking frequency satisfies an upper limit');
const any=demoAnswers();for(let id=61;id<=95;id++)any[id]='any';assert.equal(direction(any,fixtures[0]).score,null);assert.equal(recommendations(any).selected.length,0);
const unknown=demoAnswers();unknown[1]='unknown';assert.equal(profileFromAnswers(unknown).E,null);
const neutralChange=structuredClone(fixtures[0]);for(const q of [48,54,55,56,57,58,60])neutralChange[q]='unknown';for(let q=21;q<=29;q++)neutralChange[q]='unknown';
assert.deepEqual(classifyAnswers(neutralChange).ranking.map(r=>r.distance),classifyAnswers(fixtures[0]).ranking.map(r=>r.distance));
const p=classifyProfile(model.prototypes[9].features);assert.equal(p.primary.type,'STEADYSPARK');
assert.equal(new Set(readings(fixtures[9]).map(r=>r.key)).size,readings(fixtures[9]).length);
assert.notDeepEqual(readings(demoAnswers('STEADYSPARK',0)),readings(demoAnswers('STEADYSPARK',1)));
console.log('100-question schema, 16 type fixtures, neutral tie, preference invariance, pair symmetry (256 pairs), exclusions, actual time overlap, missingness and individual readings passed.');
