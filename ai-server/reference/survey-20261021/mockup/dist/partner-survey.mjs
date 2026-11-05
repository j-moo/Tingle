import original,{reviseQuestion} from './revised-data.mjs';
export const partnerVersion='PARTNER-0.5';
const scenarios=[
 ['서로 호감이 생긴 초반, 상대가 다음 만남을 제안한다면?', ['여러 번 알아본 뒤 천천히 제안','서로의 반응을 보며 제안','호감이 생기면 바로 다음 만남을 제안']],
 ['바쁜 평일에도 상대가 애정을 표현한다면 어떤 방식이 편한가요?', ['표현이 적어도 차분하게 함께하기','부담 없는 정도로 마음 전하기','말이나 메시지로 자주 분명하게 전하기']],
 ['상대에게 고민이 생겼을 때, 어느 정도까지 이야기해 주면 편한가요?', ['스스로 정리한 뒤 필요한 만큼만','상황과 감정을 적절히 나누기','복잡한 감정과 속생각까지 깊게 나누기']],
 ['몇 차례 만나 잘 맞는다고 느낄 때, 상대는 어떤 생각이면 좋겠나요?', ['지금 만남을 즐기며 방향은 열어두기','더 알아보며 오래 만날 가능성 생각하기','오래 함께할 관계를 염두에 두고 만나기']],
 ['둘 다 쉬는 주말, 상대가 시간을 보내는 방식은 어떤 쪽이 편한가요?', ['가능한 여가를 함께 보내려 하기','함께하는 일정과 각자 시간을 나누기','개인 취미와 혼자 쉬는 시간을 충분히 지키기']],
 ['내가 바빠서 표현이 줄었을 때, 상대의 어떤 반응이 편한가요?', ['별도 확인 없이 마음이 같다고 믿기','필요할 때 한 번 마음을 확인하기','말이나 표현으로 자주 마음을 확인하고 싶어 하기']],
 ['상대가 쉬는 날 사람들을 만나는 모습은 어떤 쪽이 편한가요?', ['혼자 또는 친한 소수와 조용히 보내기','소수 모임과 여러 사람 모임을 번갈아 즐기기','새로운 사람과 여러 모임을 적극적으로 즐기기']],
 ['데이트 장소를 고를 때, 상대의 어떤 제안이 끌리나요?', ['전에 좋았던 익숙한 곳','익숙한 곳과 새로운 곳을 번갈아','가보지 않은 곳과 새로운 활동']],
 ['함께 짧은 여행을 가기로 했다면, 상대가 어떻게 준비하면 좋겠나요?', ['그날 마음에 따라 움직이기','큰 일정만 잡고 나머지는 현장에서 정하기','시간과 동선을 미리 구체적으로 정하기']],
 ['둘 사이에 서운한 일이 생겼을 때, 상대가 언제 대화를 시작하면 편한가요?', ['생각과 감정을 정리할 시간을 가진 뒤','서로 준비된 시점을 확인한 뒤','가능하면 그때 바로 대화하기']],
 ['서로 끌리지만 생활 습관과 미래 계획이 다르다면, 상대는 어떤 태도면 좋겠나요?', ['끌림을 바탕으로 만나면서 차이를 알아가기','끌림과 생활 조율 가능성을 함께 살피기','생활과 미래 방향이 맞는지 먼저 확인하기']],
 ['내가 힘든 하루를 이야기할 때, 상대가 어떻게 도와주면 좋겠나요?', ['이야기를 듣고 곁에 있어 주기','먼저 들어주고 필요한 도움도 나누기','해결 방법을 찾고 구체적으로 도와주기']]
];
const extra=[];
for(let i=0;i<12;i++){
 const name=original.domains[i].name;
 extra.push({id:73+i,kind:'partner-context',domain:i,text:scenarios[i][0],options:scenarios[i][1],values:['low','mid','high'],allowAny:true,help:'평소의 선호와 달라도 괜찮아요. 이 상황에서 편한 상대의 모습을 골라주세요.'});
 extra.push({id:85+i,kind:'partner-priority',domain:i,text:'상대의 ‘'+name+'’이 내가 바라는 모습에 가까운 것은 얼마나 중요한가요?',options:['다른 장점이 있다면 우선순위가 낮아요','여러 조건 중 중요하게 보는 하나예요','상대를 알아갈 때 특히 중요하게 봐요'],values:[1,2,3],help:'중요도는 추천 점수에서 상대적인 비중을 정해요. 필수 통과 조건은 아니에요.'});
 extra.push({id:97+i,kind:'partner-flexibility',domain:i,text:'‘'+name+'’에서 내가 고른 모습과 상대가 달라도 어느 정도 편하게 받아들일 수 있나요?',options:['내가 고른 모습과 꽤 가까워야 편해요','조금 다른 정도는 편하게 받아들여요','방향이 어느 정도 달라도 맞춰볼 수 있어요','이 영역은 어떤 방식이어도 괜찮아요'],values:['close','some','wide','any'],help:'중요도와 별개예요. 중요하게 보는 영역이어도 차이를 넓게 받아들일 수 있어요.'});
}
export const partnerLife=[{id:109,actual:49,label:'연락',text:'상대가 편하게 느끼는 연락 빈도는 어느 범위면 좋겠나요?'},{id:110,actual:50,label:'만남',text:'상대가 원하는 만남 빈도는 어느 범위면 좋겠나요?'},{id:111,actual:51,label:'바라는 만남 시간',text:'상대가 보통 만날 수 있으면 좋은 시간대를 골라주세요.'},{id:112,actual:52,label:'함께할 활동',text:'상대도 즐겨 주면 좋은 활동을 골라주세요.'},{id:113,actual:53,label:'데이트 활동량',text:'상대가 즐기는 데이트 분위기는 어느 범위면 좋겠나요?'},{id:114,actual:54,label:'데이트 예산',text:'상대가 자기 몫으로 부담하기 편한 데이트 예산은 어느 범위면 좋겠나요?'}];
const slots=Array.from({length:14},(_,i)=>['월','화','수','목','금','토','일'][Math.floor(i/2)]+'요일 '+(i%2?'저녁 (18–23시)':'낮 (10–18시)'));
for(const x of partnerLife)extra.push({...x,kind:'partner-life',options:x.actual===51?slots:original.lifeOptions[x.actual],help:x.id===111?'상대의 일정에 바라는 범위예요. 내 가능한 시간과는 따로 답해 주세요.':x.id===112?'여러 개를 고를 수 있어요. 모두 함께해야 한다는 조건은 아니에요.':'편하게 받아들일 수 있는 범위를 모두 골라주세요. 상관없음과 모름은 구분해요.'});
extra.push({id:115,kind:'partner-priorities',text:'상대에게 특히 중요하게 보는 성향을 최대 3개 골라주세요.',options:original.domains.map(d=>d.name),values:original.domains.map((_,i)=>i),help:'고른 영역에 더 비중을 두어 추천해요. 고르지 않은 영역도 함께 비교하며, 필수 통과 조건은 아니에요.'});
extra.push({id:116,kind:'partner-policy',text:'상대가 내가 바라는 모습과 다를 때, 전반적으로 어떤 기준이 편한가요?',options:['전체적으로 내가 고른 모습과 가까워야 편해요','전체적으로 조금 다른 정도는 괜찮아요','중요하게 고른 영역은 가깝게, 나머지는 넓게 받아들여요','중요한 영역을 포함해 차이를 넓게 받아들여요'],values:['close','some','priority','wide'],help:'이 기준은 성향 선호 12개에 함께 적용돼요. 생활 선호는 앞서 고른 범위를 사용해요. 중요 영역을 따로 고르지 않았다면 세 번째 선택은 모든 영역에 넓게 적용돼요.'});
export const selfQuestionIds=[...Array.from({length:55},(_,i)=>i+1),57,59];
export const partnerQuestionIds=[...Array.from({length:12},(_,i)=>61+i),109,110,111,112,113,114,56,58,60,115,116];
export const activeQuestionIds=[...selfQuestionIds,...partnerQuestionIds];
// Retired IDs stay available for importing and citing older detailed results.
export const data={...original,questions:[...original.questions,...extra.sort((a,b)=>a.id-b.id)].map(reviseQuestion)};
export const newPartnerIds=[109,110,111,112,113,114,115,116];
const centers={low:20,mid:50,high:80},widths={close:10,some:20,wide:35};
const mean=v=>v.reduce((a,b)=>a+b,0)/v.length;
export const rangeGap=(value,r)=>Math.max(r[0]-value,0,value-r[1]);
export function partnerDomain(a,i){
 const choices=[a[61+i],a[73+i]],known=choices.filter(v=>v in centers||v==='any');
 const globalPriority=Object.hasOwn(a,115),globalFlexibility=Object.hasOwn(a,116);
 const prioritiesKnown=Array.isArray(a[115])||a[115]==='none';
 const selected=Array.isArray(a[115])&&a[115].includes(i);
 const importance=globalPriority?(prioritiesKnown?(selected?3:1):null):[1,2,3].includes(a[85+i])?a[85+i]:null;
 const policy=a[116];
 const flexibility=globalFlexibility?(policy==='priority'?(prioritiesKnown?(selected?'close':'wide'):null):Object.hasOwn(widths,policy)?policy:null):a[97+i];
 const active=known.filter(v=>v in centers),width=widths[flexibility]??20;
 const ranges=active.map(v=>[Math.max(0,centers[v]-width),Math.min(100,centers[v]+width)]);
 const open=known.length>0&&(flexibility==='any'||known.every(v=>v==='any'));
 return {i,known:known.length,active:!open&&active.length>0,open,ranges:open?[]:ranges,importance,weight:importance??1,prioritySource:globalPriority?'summary':a[85+i]!==undefined?'legacy':'missing',flexibilitySource:globalFlexibility?'summary':a[97+i]!==undefined?'legacy':'missing',policy,flexibility:widths[flexibility]?flexibility:flexibility==='any'?'any':null,contextual:known.length===2&&choices[0]!==choices[1],questionIds:[61+i,73+i,globalPriority?115:85+i,globalFlexibility?116:97+i,...(policy==='priority'?[115]:[])].filter((id,j,ids)=>ids.indexOf(id)===j&&a[id]!==undefined&&a[id]!=='unknown')};
}
export function partnerFit(d,value){if(!Number.isFinite(value)||!d.active)return null;return mean(d.ranges.map(r=>Math.max(0,100-1.5*rangeGap(value,r))));}
export function preferenceAnalysis(p,domains){
 const rows=domains.map((d,i)=>{const detail=partnerDomain(p.a,i),value=p.scores[i],gap=detail.active&&value!==null?mean(detail.ranges.map(r=>rangeGap(value,r))):null;return {...detail,name:d.name,value,gap,range:detail.ranges[0],band:detail.open?'any':p.a[61+i],state:detail.open?'open':gap===null?'unknown':detail.contextual?'context':gap===0?'similar':gap>=20?'different':'near',direction:gap===null?'same':value<detail.ranges[0][0]?'higher':value>detail.ranges[0][1]?'lower':'same'};});
 return Object.fromEntries([['rows',rows],...['similar','different','near','open','unknown','context'].map(state=>[state,rows.filter(r=>r.state===state).sort((a,b)=>(b.gap??0)-(a.gap??0))])]);
}
export function partnerDirection(from,to){
 const details=Array.from({length:12},(_,i)=>partnerDomain(from.a,i));
 if(details.filter(d=>d.known>0).length<9)return {state:'insufficient'};
 const active=details.filter(d=>d.active);if(!active.length)return details.some(d=>d.open)?{state:'any'}:{state:'insufficient'};
 const values=active.map(d=>({d,value:partnerFit(d,to.scores[d.i])})).filter(x=>x.value!==null);
 if(values.length<Math.ceil(active.length/2))return {state:'insufficient'};
 return {state:'value',value:Math.floor(100*values.reduce((s,x)=>s+x.value*x.d.weight,0)/values.reduce((s,x)=>s+x.d.weight,0))};
}
