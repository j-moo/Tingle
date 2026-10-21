import {data as sourceData,partnerDirection,partnerDomain,partnerFit,partnerLife,partnerVersion,activeQuestionIds} from './partner-survey.mjs';
import {types16,modelVersion,calculateAxes,classify,interpret} from './model.mjs';
import {personalize,explainPair,explanationVersion} from './personalized.mjs';
const {types:oldTypes,matrix:oldMatrix,...questionData}=sourceData;
export const data={...questionData,version:modelVersion+' / '+explanationVersion+' / '+partnerVersion,types:types16};
const mean=a=>a.reduce((s,n)=>s+n,0)/a.length;
const isNumber=x=>Number.isFinite(x);
const asIndex=(x,n)=>Number.isInteger(x)&&x>=0&&x<n;
export const isSet=x=>Array.isArray(x)&&x.length>0;
export function validAnswer(id,v){
 if(!Number.isInteger(id)||id<1||id>116)return false;
 if(v==='unknown')return true;
 if(id===115)return v==='none'||isSet(v)&&v.length<=3&&new Set(v).size===v.length&&v.every(x=>asIndex(x,12));
 if(id===116)return ['close','some','priority','wide'].includes(v);
 if(id>=109)return v==='any'||isSet(v)&&new Set(v).size===v.length&&v.every(x=>asIndex(x,data.questions[id-1].options.length));
 if(id>=97)return ['close','some','wide','any'].includes(v);
 if(id>=85)return [1,2,3].includes(v);
 if(id<=48)return Number.isInteger(v)&&v>=1&&v<=5;
 if(id>=61)return ['low','mid','high','any'].includes(v);
 if([56,58,60].includes(id)&&v==='any')return true;
 const n=id===51?14:data.lifeOptions[id]?.length;
 if([51,52,56,58,60].includes(id))return isSet(v)&&new Set(v).size===v.length&&v.every(x=>asIndex(x,n))&&(id!==52||v.length<=5);
 return asIndex(v,n);
}
export function sanitizeAnswers(a){return Object.fromEntries(Object.entries(a??{}).filter(([k,v])=>/^\d+$/.test(k)&&Number(k)>=1&&Number(k)<=116&&validAnswer(Number(k),v)));}
export const answeredCount=a=>activeQuestionIds.filter(id=>validAnswer(id,a?.[id])).length;
export const ordinal=(a,id)=>asIndex(a[id],5)?a[id]*25:null;
export function profile(input){
 const a=sanitizeAnswers(input);
 const coverage=data.domains.map((_,d)=>[0,1,2,3].map(i=>a[d*4+i+1]).filter(isNumber).length);
 const scores=data.domains.map((_,d)=>{
  const vals=[0,1,2,3].map(i=>({id:d*4+i+1,v:a[d*4+i+1]})).filter(x=>isNumber(x.v)).map(x=>data.questions[x.id-1].reverse?6-x.v:x.v);
  return vals.length>=3?Math.round((mean(vals)-1)*25):null;
 });
 const life=[49,50,53].map(id=>ordinal(a,id));
 const axes=calculateAxes(scores,a,data.domains);
 const ready=answeredCount(a)===activeQuestionIds.length&&scores.filter(isNumber).length>=9&&life.filter(isNumber).length>=2&&axes.every(x=>x.value!==null);
 const {distances,weights,ranking}=ready?classify(axes):{distances:[],weights:[],ranking:[]};
 const primary=ranking[0]??null,secondary=ranking[1]&&primary.fit-ranking[1].fit<=3?ranking[1]:null;
 const traits=scores.map((v,i)=>({i,value:v,strength:v===null?-1:Math.abs(v-50)})).filter(x=>x.value!==null&&(x.value<40||x.value>60)).sort((a,b)=>b.strength-a.strength||a.i-b.i).slice(0,2);
 const spread=data.domains.map((_,d)=>{
  const values=[0,1,2,3].filter(i=>isNumber(a[d*4+i+1])).map(i=>(data.questions[d*4+i].reverse?6-a[d*4+i+1]:a[d*4+i+1])-1).map(x=>x*25);
  return values.length>=3?Math.max(...values)-Math.min(...values):null;
 });
 const p={a,scores,coverage,spread,life,axes,ready,distances,weights,ranking,primary,secondary,traits,mixed:ready&&axes.every(x=>x.boundary),boundaries:axes.filter(x=>x.boundary)};
 const analysis=interpret(p,data.domains);
 return {...p,...analysis,insights:personalize(p,data.domains,data.lifeOptions,analysis.insights)};
}
export const bands={low:[0,40],mid:[30,70],high:[60,100]};
export function preferenceScore(v,band){if(v===null||!bands[band])return null;const [lo,hi]=bands[band];return Math.max(0,100-1.5*Math.max(lo-v,0,v-hi));}
export function traitPoints(a,b){
 const values=a.map((v,i)=>v===null||b[i]===null?null:100-Math.abs(v-b[i])).filter(isNumber);
 return values.length?Math.floor(100*mean(values)):null;
}
export function recommendedTypes(p){
 if(!p.ready)return [];
 return data.types.map((type,index)=>{
  const match=scorePair(p,profile(demoAnswers(index)));
  return {type,index,score:match.S/100,match,evidence:match.reasons};
 }).filter(r=>r.match.status==='recommended').sort((a,b)=>b.score-a.score||a.index-b.index).slice(0,3);
}
const geom=(a,b)=>Math.floor(Math.sqrt(a*b));
const direction=partnerDirection;
function overlap(a,b){if(!isSet(a)||!isSet(b))return null;return Math.floor(10000*a.filter(x=>b.includes(x)).length/Math.min(a.length,b.length));}
function catDirection(want,value,n){if(want==='any')return null;if(!isSet(want)||!asIndex(value,n))return null;return want.includes(value)?10000:0;}
function categoryPair(a,b,actual,wanted,n){const ab=catDirection(a[wanted],b[actual],n),ba=catDirection(b[wanted],a[actual],n);if(ab!==null&&ba!==null)return geom(ab,ba);return ab??ba;}
export function scorePair(pa,pb){
 if(!pa.ready||!pb.ready)return {status:'insufficient',reasons:[]};
 const a=pa.a,b=pb.a;
 const simple=id=>ordinal(a,id)===null||ordinal(b,id)===null?null:100*(100-Math.abs(ordinal(a,id)-ordinal(b,id)));
 const life=[simple(49),simple(50),overlap(a[51],b[51]),overlap(a[52],b[52]),simple(53),simple(54),categoryPair(a,b,55,56,3),categoryPair(a,b,57,58,4),categoryPair(a,b,59,60,3)];
 for(const spec of partnerLife){
  const directional=(from,to)=>{
   const want=from[spec.id],actual=to[spec.actual];
   if(!isSet(want))return null;
   if([51,52].includes(spec.actual)){if(!isSet(actual))return null;return 10000*want.filter(v=>actual.includes(v)).length/Math.min(want.length,actual.length);}
   return catDirection(want,actual,data.questions[spec.id-1].options.length);
  };
  const ab=directional(a,b),ba=directional(b,a),fit=ab!==null&&ba!==null?geom(ab,ba):ab??ba;
  const pos=[49,50,51,52,53,54].indexOf(spec.actual);
  // Explicit wants replace implicit self-similarity for the same component; no double counting.
  if(fit!==null)life[pos]=spec.actual===51?(life[pos]===null?null:geom(life[pos],fit)):Math.floor(fit);
 }
 const valid=life.filter(x=>x!==null),ab=direction(pa,pb),ba=direction(pb,pa);
 if(valid.length<4||ab.state==='insufficient'||ba.state==='insufficient')return {status:'insufficient',reasons:[]};
 const T=traitPoints(pa.scores,pb.scores),L=Math.floor(mean(valid));
 const P=ab.state==='any'&&ba.state==='any'?null:ab.state==='any'?ba.value:ba.state==='any'?ab.value:geom(ab.value,ba.value);
 const S=P===null?Math.floor((45*T+30*L)/75):Math.floor((45*T+30*L+25*P)/100);
 const reasons=[];
 const push=(code,text)=>reasons.push({code,text});
 if(life[0]!==null&&life[0]>=7500)push('CONTACT_CLOSE','편하게 느끼는 연락 빈도가 비슷해요.');
 if(life[1]!==null&&life[1]>=7500)push('MEETING_CLOSE','원하는 만남 빈도가 비슷해요.');
 if(life[2]>0)push('TIME_OVERLAP','만남을 잡을 수 있는 시간대가 겹쳐요.');
 if(life[3]>0)push('SHARED_ACTIVITY','함께 즐겨보고 싶은 활동이 있어요.');
 if(pa.scores[2]!==null&&pb.scores[2]!==null&&pa.scores[2]>=65&&pb.scores[2]>=65&&Math.abs(pa.scores[2]-pb.scores[2])<=20)push('OPEN_TALK','속마음을 나누는 방식에 접점이 있어요.');
 if(partnerFit(partnerDomain(a,1),pb.scores[1])>=80&&partnerFit(partnerDomain(b,1),pa.scores[1])>=80)push('EXPRESSION_WANTED','서로 편하게 느끼는 애정 표현 방식과 가까워요.');
 if(isSet(a[60])&&isSet(b[60])&&a[60].includes(b[59])&&b[60].includes(a[59]))push('RELATION_DIRECTION','지금 알아가고 싶은 관계의 방향이 맞닿아 있어요.');
 if(T>=8000)push('TRAIT_CONNECTION','유형 이름이 아니라 12개 세부 성향의 차이가 평균 20점 이내예요.');
 const result={status:S>=6000?'recommended':'different',T,L,P,S,ab:ab.value??null,ba:ba.value??null,life,actualTimeOverlap:overlap(a[51],b[51]),reasons:S>=6000?(reasons.length?reasons.slice(0,3):[{code:'OVERALL',text:'전체 응답 조합을 바탕으로 새로운 대화 상대로 추천했어요.'}]):[]};
 return {...result,...explainPair(pa,pb,result,data.domains,data.lifeOptions)};
}
export function demoAnswers(index=1,variant=0){
 const t=data.types[index],a={};
 t.center.slice(0,12).forEach((score,d)=>{
  const total=Math.round((score/25+1)*4),base=Math.floor(total/4),rest=total%4;
  for(let i=0;i<4;i++){const id=d*4+i+1,v=base+(i<rest?1:0);a[id]=data.questions[id-1].reverse?6-v:v;}
 });
 a[49]=Math.round(t.center[12]/25);a[50]=Math.round(t.center[13]/25);
 a[51]=index%2?[9,11,13]:[1,5,11,13];
 a[52]=[[0,1,2],[0,1,3],[2,4,8],[1,6,8],[0,1,5],[4,7,8],[0,3,9],[0,1,5]][index%8];
 a[53]=Math.round(t.center[14]/25);a[54]=index%3+1;a[55]=0;a[56]='any';a[57]=index%3;a[58]='any';a[59]=t.center[3]>=70?2:1;a[60]='any';
 t.center.slice(0,12).forEach((v,i)=>{a[61+i]=[5,10,11].includes(i)?'any':v<40?'low':v>60?'high':'mid';});
 if(variant){for(const d of [5,9,11])for(let i=0;i<4;i++)a[d*4+i+1]=data.questions[d*4+i].reverse?2:4;}
 for(let id=109;id<=114;id++)a[id]='any';a[115]='none';a[116]='some';
 return a;
}
