// Independent oracle: import original handoff modules, never Python outputs.
import fs from 'node:fs';
import crypto from 'node:crypto';
import {questions, validAnswer, featureLabels, domainDefs} from '../../docs/handoffs/survey-20261021/mockup/dist/data100.mjs';
import {model, demoAnswers, profileFromAnswers, classifyAnswers, classifyProfile, readings, preferenceGaps, wishFit} from '../../docs/handoffs/survey-20261021/mockup/dist/engine100.mjs';
import {typeRecommendations} from '../../docs/handoffs/survey-20261021/mockup/dist/type-recommendations100.mjs';

const baseline = JSON.parse(fs.readFileSync(new URL('../../docs/handoffs/survey-20261021/mockup/fixtures/wording-baseline.json', import.meta.url)));
const base = baseline.fixtures[9];
const copy = value => structuredClone(value);
const cases = baseline.fixtures.map((answers, i) => ({name: `prototype-${model.prototypes[i].type}`, answers}));
for (const prototype of model.prototypes) cases.push({name: `variant-${prototype.type}`, answers: demoAnswers(prototype.type, 1)});
cases.push({name: 'normal', answers: copy(base)});
cases.push({name: 'all-unknown', answers: Object.fromEntries(questions.map(q => [q.id, 'unknown']))});
cases.push({name: 'partial-score', answers: {...copy(base), 1: 'unknown'}});
cases.push({name: 'neutral-unknown', answers: {...copy(base), ...Object.fromEntries([4,9,14,19,21,22,23,24,25,26,27,28,29,48,49,50,52,54,55,56,57,58,60].map(id => [id, 'unknown']))}});
cases.push({name: 'self-similarity', answers: {...copy(base), ...Object.fromEntries(Array.from({length:35}, (_,i) => [61+i, 'any']))}});
cases.push({name: 'preference-only', answers: {...copy(base), ...Object.fromEntries(Array.from({length:60}, (_,i) => [1+i, 'unknown']))}});
const mid = Object.fromEntries(questions.map(q => [q.id, q.id<=60 && q.options.every(o => typeof o.value === 'number') ? 3 : 'unknown']));
cases.push({name: 'tied', answers: mid});
// A fixed valid ordinal snapshot with a single winner close to another type.
let borderline;
for (const id of questions.filter(q => q.id<=60 && q.options.every(o => typeof o.value==='number')).map(q=>q.id)) {
  for (const v of [1,2,3,4,5]) {
    const answers = {...mid, 30:4, 31:2, 32:2, [id]:v};
    if (classifyAnswers(answers).status==='borderline') {borderline=answers;break;}
  }
  if (borderline) break;
}
if (!borderline) throw new Error('Borderline fixture unavailable');
cases.push({name:'borderline', answers:borderline});
for (const tolerance of ['close','some','wide','unknown']) cases.push({name:`tolerance-${tolerance}`, answers:{...copy(base),98:tolerance,65:1,69:5,75:['active','walk'],89:1}});
for (const flags of [['age'], ['age','smoking'], ['religion','tattoo']]) cases.push({name:`strict-${flags.join('-')}`,answers:{...copy(base),97:flags}});

// Seeded, valid synthetic coverage across all options/missingness and text branches.
let seed=106;
const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
for(let index=0;index<100;index++) {
  const answers={};
  for(const q of questions) {
    const r=random();
    if(r<.08) answers[q.id]='unknown';
    else if(q.allowAny && r<.25) answers[q.id]='any';
    else if(q.kind==='multi') {
      const options=[...q.options];
      const count=1+Math.floor(random()*Math.min(q.max??options.length,options.length));
      const start=Math.floor(random()*options.length);
      answers[q.id]=Array.from({length:count},(_,i)=>options[(start+i)%options.length].value);
    } else answers[q.id]=q.options[Math.floor(random()*q.options.length)].value;
  }
  cases.push({name:`seeded-${index}`,answers});
}
for(const item of cases) {
  if(!questions.every(q=>validAnswer(q.id,item.answers[q.id]))) throw new Error(`Invalid synthetic fixture ${item.name}`);
  item.result={profile:profileFromAnswers(item.answers),classification:classifyAnswers(item.answers),readings:readings(item.answers),gaps:preferenceGaps(item.answers),references:typeRecommendations(item.answers)};
}

const neutral=profileFromAnswers(mid), target=profileFromAnswers({...mid,30:4,31:2,32:2});
const gap=classifyProfile(target).gap;
const profiles=[{name:'neutral',features:neutral}];
for(const desiredGap of [3-1e-8,3,3+1e-8]) {
  const ratio=desiredGap/gap;
  const features=Object.fromEntries(Object.entries(neutral).map(([k,v])=>[k,typeof v==='number'?v+ratio*(target[k]-v):v]));
  profiles.push({name:`gap-${desiredGap}`,features});
}
for(const ratio of [1e-11,1e-7]) {
  const features=Object.fromEntries(Object.entries(neutral).map(([k,v])=>[k,typeof v==='number'?v+ratio*(target[k]-v):v]));
  profiles.push({name:`tie-epsilon-${ratio}`,features});
}
// Exact 0.8 coverage can be formed with existing fixed weights.
const active=Object.keys(model.weights);
let coverageFeatures;
for(let mask=0;mask<(1<<12);mask++) {
  const removed=active.slice(0,12).filter((_,i)=>mask&(1<<i));
  const weight=active.filter(k=>!removed.includes(k)).reduce((s,k)=>s+model.weights[k],0);
  if(Math.abs(weight-.8)<1e-12) {coverageFeatures={...model.prototypes[9].features,...Object.fromEntries(removed.map(k=>[k,null]))};break;}
}
if(!coverageFeatures) throw new Error('Coverage boundary fixture unavailable');
profiles.push({name:'coverage-at-minimum',features:coverageFeatures});
const remaining=active.find(k=>coverageFeatures[k]!==null);
profiles.push({name:'coverage-below-minimum',features:{...coverageFeatures,[remaining]:null}});
for(const item of profiles) item.result=classifyProfile(item.features);
const wishCases=[];
for(const id of Array.from({length:35},(_,i)=>i+61)) {
  for(const tolerance of ['close','some','wide','unknown']) {
    const wishes=['unknown','any',...questions[id-1].options.map(o=>questions[id-1].kind==='multi'?[o.value]:o.value)];
    const actuals=id<=64?[0,30,40,50,60,70,100,null]:[...questions[id-36].options.map(o=>questions[id-36].kind==='multi'?[o.value]:o.value),null];
    for(const wish of wishes)for(const actual of actuals)wishCases.push({id,wish,actual,tolerance,result:wishFit(id,wish,actual,tolerance)});
  }
}
const sourceRoot=new URL('../../',import.meta.url);
const provenance=JSON.parse(fs.readFileSync(new URL('../app/personal_analysis/data/provenance.json',import.meta.url)));
const hashes=Object.fromEntries(Object.keys(provenance.artifacts).map(path=>[path,crypto.createHash('sha256').update(fs.readFileSync(new URL(path,sourceRoot))).digest('hex')]));
process.stdout.write(JSON.stringify({cases,profiles,wishCases,reference:{model,questions,featureLabels,domainDefs},hashes}));
