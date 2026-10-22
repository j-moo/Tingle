import {normalizeRecord} from './answer-revision.mjs';
import {questionnaireVersion} from './revised-data.mjs';
import {data,profile,recommendedTypes,validAnswer,sanitizeAnswers} from './engine.mjs';
export const MAX_TRANSFER_BYTES=1024*1024;
const begin='[SIS_RESULT_DATA]',end='[/SIS_RESULT_DATA]';
const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);

export function parseResultText(input){
 if(typeof input!=='string'||new TextEncoder().encode(input).length>MAX_TRANSFER_BYTES)throw Error('결과 파일은 1MB 이하로 불러와 주세요.');
 let text=input.replace(/^\uFEFF/,'').trim();
 if(text.includes(begin)){
  if(text.split(begin).length!==2||text.split(end).length!==2)throw Error('전달용 텍스트가 잘렸거나 여러 결과가 섞여 있어요. 한 결과를 처음부터 끝까지 붙여넣어 주세요.');
  const first=text.indexOf(begin)+begin.length,last=text.indexOf(end);
  if(last<first)throw Error('결과 텍스트의 순서가 올바르지 않아요.');
  text=text.slice(first,last).trim();
 }
 let saved;try{saved=JSON.parse(text);}catch{throw Error('결과를 읽지 못했어요. 기존 JSON 파일이나 이 페이지에서 저장·복사한 결과 전체를 넣어 주세요.');}
 if(!object(saved)||!object(saved.answers))throw Error('원래 설문 답변이 없는 파일이에요. 점수나 화면 캡처만으로는 분석을 다시 계산할 수 없어요.');
 if(typeof saved.version!=='string'||!/^SIS-0\.[123](?:$|[ /])/.test(saved.version))throw Error('현재 설문과 호환되는 결과 버전이 아니에요. SIS-0.1~0.3 결과를 선택해 주세요.');
 if(saved.questionnaireVersion!==undefined&&saved.questionnaireVersion!==questionnaireVersion)throw Error('아직 지원하지 않는 문항 버전이에요. 원본 파일을 보관해 주세요.');
 const entries=Object.entries(saved.answers);
 if(!entries.length||entries.some(([id,value])=>!/^\d+$/.test(id)||String(Number(id))!==id||Number(id)<1||Number(id)>116||!validAnswer(Number(id),value)))throw Error('설문 답변 형식이 올바르지 않아요. 수정하지 않은 원본 결과를 다시 선택해 주세요.');
 const normalized=normalizeRecord(saved);
 return {...normalized,sourceVersion:saved.version.slice(0,120),count:entries.length};
}

export function exportResult(input,{legacyArchive=null}={}){
 const answers=sanitizeAnswers(input),p=profile(answers);
 return {format:'sis-result-v2',version:data.version,questionnaireVersion,legacyArchive,complete:p.ready,savedAt:new Date().toISOString(),notice:'자체 검사 초안. 심리 진단 또는 관계 성공 확률이 아님.',type:p.mixed?null:p.primary?.type.id??null,typeName:p.mixed?null:p.primary?.type.koName??null,secondary:p.mixed?null:p.secondary?.type.id??null,secondaryName:p.mixed?null:p.secondary?.type.koName??null,axes:p.axes,insights:p.insights,recommendations:recommendedTypes(p).map(r=>({type:r.type.id,typeName:r.type.koName,score:r.score,reasons:r.match.reasons,adjustments:r.match.adjustments,synthetic:true})),preferences:p.preferences,coverage:p.coverage,spread:p.spread,boundaries:p.boundaries.map(x=>x.id),scores:Object.fromEntries(data.domains.map((d,i)=>[d.id,p.scores[i]])),answers};
}
export function resultText(result,{compact=false}={}){
 const title=!result.complete?'응답 보완 필요':result.type?result.type+' · '+result.typeName:'IN BETWEEN · 네 방향의 균형';
 const packet={version:result.version,questionnaireVersion:result.questionnaireVersion,legacyArchive:result.legacyArchive,answers:result.answers};
 const lines=['Suddenly I See — '+title,'분석 기준: '+result.version,''];
 for(const x of result.insights.slice(0,compact?1:3))lines.push(x.title,...(compact?[]:[x.text]),'');
 if(!compact)for(const r of result.recommendations){lines.push('추천 가상 예시: '+r.type+' · '+r.typeName,...r.reasons.map(x=>'• '+x.text),...r.adjustments.map(x=>'함께 맞춰볼 부분: '+x.text),'');}
 lines.push('결과 불러오기 (뜻이 바뀐 문항은 재응답 필요): https://suddenly-see-heart-e106.rusidpia.chatgpt.site/','‘기존 결과 불러오기’에서 파일을 선택하거나 이 텍스트 전체를 붙여넣으세요.','아래에는 재분석에 필요한 설문 응답이 포함돼요.',begin,JSON.stringify(packet),end);
 return lines.join('\n');
}
