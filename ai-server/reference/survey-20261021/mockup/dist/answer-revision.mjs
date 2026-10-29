import {questionnaireVersion,semanticChanges,retiredIncompatible} from './revised-data.mjs';
import {sanitizeAnswers,validAnswer} from './engine.mjs';
const archiveOf=x=>x&&typeof x==='object'&&!Array.isArray(x)&&x.answers?{sourceVersion:String(x.sourceVersion??'이전 설문').slice(0,120),answers:sanitizeAnswers(x.answers)}:null;
export function normalizeRecord(record){
 const answers=sanitizeAnswers(record?.answers);
 const current=record?.questionnaireVersion===questionnaireVersion||/^SIS-0\.3(?:$|[ /])/.test(record?.version??'');
 let legacyArchive=archiveOf(record?.legacyArchive);
 if(!current&&Object.keys(answers).length){
  legacyArchive={sourceVersion:String(record?.version??'SIS-0.2').slice(0,120),answers:{...answers}};
  for(const id of [...semanticChanges,...retiredIncompatible])delete answers[id];
 }
 const reviewIds=semanticChanges.filter(id=>legacyArchive?.answers[id]!==undefined&&!validAnswer(id,answers[id]));
 return {answers,legacyArchive,reviewIds,questionnaireVersion};
}
