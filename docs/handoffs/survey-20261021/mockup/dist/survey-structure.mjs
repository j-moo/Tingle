import {data,selfQuestionIds,partnerQuestionIds,activeQuestionIds} from './partner-survey.mjs';
// Answer IDs remain stable for all previous JSON/TXT exports. Only presentation order changes.
export const surveyOrderVersion='self-partner-v3';
export const parts=[
 {name:'나의 성향과 생활',short:'나의 성향',title:'먼저, 지금의 나를 알아봐요',description:'평소 나는 어떻게 표현하고 관계를 맺는지, 어떤 일상과 만남이 편한지 답해 주세요.',hint:'행동 문항은 최근 6개월의 실제 경험, 관계 목표 문항은 지금의 생각을 답해 주세요. 경험이 부족하면 잘 모르겠어요를 선택해요.',role:'내 성향과 관계 유형, 생활 리듬을 이해하는 데 사용해요.',ids:[...Array.from({length:55},(_,i)=>i+1),57,59]},
 {name:'원하는 상대의 성향과 생활',short:'원하는 상대',title:'이제, 어떤 상대가 편한지 알려주세요',description:'상대에게 바라는 12가지 성향과 9가지 생활 방식을 살펴요. 마지막 두 질문에서 중요한 영역과 차이를 받아들이는 기준을 한 번씩 정해요.',hint:'나와 달라도 괜찮아요. 선호가 없으면 ‘상관없음’, 판단이 어려우면 ‘아직 모르겠어요’를 골라주세요.',role:'내 유형 점수와 분리해, 상대를 추천하고 서로의 기대를 비교하는 데 사용해요.',ids:partnerQuestionIds}
];
export const questionOrder=activeQuestionIds;
export const surveyQuestions=questionOrder.map(id=>data.questions.find(q=>q.id===id));
export const questionNumber=id=>questionOrder.indexOf(id)+1;
export const questionLabel=id=>questionOrder.includes(id)?'Q'+questionNumber(id):'이전 상세 설문 #'+id;
export const questionIndex=id=>questionOrder.indexOf(id);
export const partOf=id=>selfQuestionIds.includes(id)?0:1;
export const partQuestionNumber=id=>parts[partOf(id)].ids.indexOf(id)+1;
const previousV1=[...selfQuestionIds,...Array.from({length:12},(_,i)=>61+i),56,58,60];
const previousV2=[...selfQuestionIds,...Array.from({length:12},(_,i)=>[61+i,73+i,85+i,97+i]).flat(),109,110,111,112,113,114,56,58,60];
const mapRetired=id=>id>=73&&id<=108?61+(id-73)%12:id;
export function restoreQuestionIndex(saved){
 if(Number.isInteger(saved?.questionId)){const mapped=questionIndex(mapRetired(saved.questionId));if(mapped>=0)return mapped;}
 const index=Number.isInteger(saved?.index)?saved.index:0;
 if(saved?.surveyOrder===surveyOrderVersion)return Math.max(0,Math.min(questionOrder.length-1,index));
 const oldOrder=saved?.surveyOrder==='self-partner-v2'?previousV2:saved?.surveyOrder==='self-partner-v1'?previousV1:Array.from({length:72},(_,i)=>i+1);
 return Math.max(0,questionIndex(mapRetired(oldOrder[Math.max(0,Math.min(oldOrder.length-1,index))])));
}
