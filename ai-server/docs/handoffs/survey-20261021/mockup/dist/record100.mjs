import {questionnaireVersion,sanitizeAnswers,validAnswer} from './data100.mjs';
export const previousVersion='SIS-Q-1.0-100';
export const revisedIds=[56,60,91,95];
export const supportedVersions=[previousVersion,questionnaireVersion];
export function validPriorAnswer(id,value){if(!revisedIds.includes(Number(id)))return validAnswer(Number(id),value);return value==='unknown'||(Number(id)>60&&value==='any')||(Number.isInteger(value)&&value>=1&&value<=5);}
export function normalizeRecord(record){
 const previousResponses=Array.isArray(record?.previousResponses)?record.previousResponses:[];
 if(!record||!supportedVersions.includes(record.questionnaireVersion))return {answers:{},previousResponses,migratedIds:[],supported:!record};
 if(record.questionnaireVersion===questionnaireVersion)return {answers:sanitizeAnswers(record.answers),previousResponses,migratedIds:[],supported:true};
 const raw=record.answers&&typeof record.answers==='object'&&!Array.isArray(record.answers)?record.answers:{};
 const usable={...raw},migratedIds=[];
 for(const id of revisedIds){if(Object.prototype.hasOwnProperty.call(usable,id)&&usable[id]!=='unknown'){delete usable[id];migratedIds.push(id);}}
 return {answers:sanitizeAnswers(usable),previousResponses:[...previousResponses,{questionnaireVersion:previousVersion,answers:structuredClone(raw)}],migratedIds,supported:true};
}
