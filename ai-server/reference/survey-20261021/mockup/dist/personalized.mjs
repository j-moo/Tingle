import {partnerDomain,partnerFit,partnerLife} from './partner-survey.mjs';
// Editorial rules for this prototype. Priority orders explanations, not people.
export const explanationVersion='READING-0.4';
const finite=Number.isFinite;
const ranges={low:[0,40],mid:[30,70],high:[60,100]};
const gapTo=(v,[lo,hi])=>Math.max(lo-v,0,v-hi);
const qids=i=>[1,2,3,4].map(n=>4*i+n);

export function personalize(p,domains,lifeOptions,previous){
 const s=p.scores,a=p.a,items=[];
 const has=(...ids)=>ids.every(i=>finite(s[i]));
 const add=(id,group,priority,title,text,action,ids,rule,extra=[])=>items.push({id,group,priority,title,text,action,evidence:[...ids.map(i=>({label:domains[i].name,value:s[i],questionIds:qids(i)})),...extra],rule});
 if(has(1,2)&&s[1]>=60&&s[2]<=40){
  add('expressive-private','disclosure',120+(s[1]-s[2])/10,'애정을 표현하는 빈도와 속마음을 나누는 정도는 달라요',
   '애정 표현은 '+s[1]+'점이지만 속마음 공유는 '+s[2]+'점으로, 두 응답 점수에 '+(s[1]-s[2])+'점 차이가 있어요. 좋아하는 마음은 잘 전하면서도 개인적인 고민을 많이 공개하는 것은 별개의 선택일 수 있어요.',
   '표현이 많다는 이유로 개인적인 이야기도 많이 나눌 것이라고 가정하지 않고, 편한 주제부터 넓혀가는 상대와 대화해 보세요.',[1,2],'애정 표현 ≥ 60 AND 속마음 공유 ≤ 40. 점수 차이는 응답 비교이며 심리적 거리의 단위가 아니에요.');
 }else if(has(1,2,6)&&s[1]>=60&&s[2]>=60&&s[6]<=40){
  add('quiet-intimacy','disclosure',124,'많은 사람보다, 가까운 한 사람에게 풍부해져요',
   '애정 표현 '+s[1]+'점·속마음 공유 '+s[2]+'점에 비해 모임에서의 교류는 '+s[6]+'점이에요. 여러 사람과 어울리는 자리보다 가까운 상대와 둘이 표현하고 대화하는 시간이 편할 수 있어요.',
   '모임에서 말수가 적다고 애정 표현도 적을 것이라 생각하지 말고, 둘만의 대화가 편한지 함께 확인해 보세요.',[1,2,6],'애정 표현·속마음 공유 ≥ 60 AND 모임에서의 교류 ≤ 40');
 }else if(has(1,2)&&s[1]>=60&&s[2]>=60){
  add('expressive-open','disclosure',103,'표현과 깊은 대화를 함께 나누는 편이에요',
   '애정 표현 '+s[1]+'점과 속마음 공유 '+s[2]+'점이 모두 높은 방향이에요. 좋아한다는 신호와 개인적인 이야기를 주고받는 것이 함께 가까워지는 방법일 수 있어요.',
   '대화의 빈도뿐 아니라 어느 정도 깊이까지 나눌 때 편한지도 물어보세요.',[1,2],'애정 표현·속마음 공유 ≥ 60');
 }else if(has(1,2)&&s[1]<=40&&s[2]>=60){
  add('quiet-open','disclosure',103,'애정 표현은 담백해도, 깊은 이야기는 나눠요',
   '애정 표현 '+s[1]+'점과 속마음 공유 '+s[2]+'점이 다른 방향이에요. 잦은 애정 표현보다 개인적인 생각을 나누는 대화로 가까움을 전할 수 있어요.',
   '표현 횟수만으로 관심을 판단하지 말고, 함께 나누는 이야기에도 귀 기울여 보세요.',[1,2],'애정 표현 ≤ 40 AND 속마음 공유 ≥ 60');
 }
 if(has(3,8)&&s[3]>=60&&s[3]-s[8]>=25){
  add('commitment-not-schedule','planning',98+(s[3]-s[8])/10,'오래 함께하고 싶어도, 매번 계획적인 건 아니에요',
   '관계의 지속성은 '+s[3]+'점, 약속 계획은 '+s[8]+'점이에요. 오래 이어질 관계를 원한다는 것과 데이트 일정을 미리 정하는 습관은 구분해서 봐야 해요.',
   '장기적인 관계 방향은 확인하되, 당장의 약속은 얼마나 미리 정할지 따로 맞춰보세요.',[3,8],'관계 지속성 ≥ 60 AND 지속성 − 약속 계획 ≥ 25');
 }
 if(has(5,9)&&s[5]>=65&&s[9]>=65){
  add('reassurance-conversation','conflict',115+(s[5]+s[9]-130)/20,'걱정이 생기면, 빠른 대화로 관계를 확인하고 싶어요',
   '마음 확인 '+s[5]+'점과 갈등 대화의 즉시성 '+s[9]+'점이 함께 높은 방향이에요. 문제가 생겼을 때 해결책뿐 아니라 관계가 괜찮다는 신호도 일찍 듣고 싶을 수 있어요.',
   '잠시 생각할 시간이 필요하다면, 다시 이야기할 시간과 관계에 대한 마음을 먼저 전해 달라고 말해보세요.',[5,9],'마음 확인 ≥ 65 AND 갈등 대화의 즉시성 ≥ 65');
 }
 if(has(0,2)&&s[0]>=60&&s[2]<=40&&!items.some(x=>x.group==='disclosure')){
  add('approach-not-disclosure','disclosure',102,'가까워지는 시작과, 마음을 여는 과정은 달라요',
   '가까워지는 속도 '+s[0]+'점에 비해 속마음 공유는 '+s[2]+'점이에요. 관계를 시작하는 속도가 빨라도 개인적인 이야기를 많이 공유하지 않을 수 있어요.',
   '만남의 진도와 개인적인 이야기를 나누는 정도를 따로 확인해 보세요.',[0,2],'가까워지는 속도 ≥ 60 AND 속마음 공유 ≤ 40');
 }
 if(Number.isInteger(a[49])&&Number.isInteger(a[50])&&Math.abs(a[49]-a[50])>=2){
  const meetings=a[50]>a[49];
  add('contact-meeting-gap','rhythm',108,meetings?'연락 횟수보다, 직접 만나는 시간이 더 편해요':'자주 만나기보다, 일상 속 연락으로 이어져요',
   '편한 연락은 ‘'+lifeOptions[49][a[49]]+'’, 만남은 ‘'+lifeOptions[50][a[50]]+'’로 골랐어요. 두 빈도의 응답 단계가 '+Math.abs(a[49]-a[50])+'단계 달라, 연락과 만남에 같은 리듬을 기대하지 않는 편이에요.',
   '연락과 만남을 하나의 관심 척도로 묶지 말고 각각 편한 횟수를 이야기해 보세요.',[], '연락·만남 선택 단계 차이 ≥ 2. 서로 다른 빈도 척도의 상대적 응답 위치를 비교해요.',[
    {label:'연락 빈도',value:a[49]*25,questionIds:[49]}, {label:'만남 빈도',value:a[50]*25,questionIds:[50]}]);
 }
 const wanted=domains.map((d,i)=>{const pref=partnerDomain(a,i);return {i,range:pref.active&&!pref.contextual?pref.ranges[0]:null,value:s[i]};}).filter(r=>r.range&&finite(r.value)).map(r=>({...r,gap:gapTo(r.value,r.range)})).sort((x,y)=>y.gap-x.gap||x.i-y.i).find(r=>r.gap>=10);
 if(wanted){
  const {i,range,value,gap}=wanted,higher=value<range[0];
  add('own-wanted-gap','expectation',94+gap/10,'내 방식과, 상대에게 바라는 방식은 조금 달라요',
   '‘'+domains[i].name+'’에서 나는 '+value+'점, 상대에게 원하는 범위는 '+range.join('–')+'점이에요. 내 점수가 바라는 범위의 '+(higher?'아래':'위')+'에 있어, 상대가 나와 같아야 편한 영역은 아니에요.',
   '이 차이는 자동으로 상호 보완된다는 뜻은 아니에요. 상대의 실제 방식과 그 사람이 내게 바라는 범위를 함께 확인해 보세요.',[i],'내 점수와 상대 선호 범위 사이 간격 ≥ 10; 가장 큰 간격을 우선 표시',[
    {label:'상대에게 바라는 범위',value:range.join('–'),questionIds:partnerDomain(a,i).questionIds}]);
 }
 // Previous interpretations remain useful, but specific combinations lead the page.
 const groupFor=id=>id.startsWith('space-')?'disclosure':id.startsWith('expression-')?'expression':id.startsWith('conflict-')?'conflict':id==='support-channel'?'support':id.startsWith('life-')?'rhythm':id==='desired-gap'?'expectation':id.includes('adventure')||id==='date-balance'||id==='familiar-plan'?'planning':id;
 for(const x of previous){
  const specific=!x.rule.includes('外')&&!x.rule.includes('외')&&!['space-context','date-balance','conflict-rhythm','life-contact','start-direction'].includes(x.id);
  const vals=x.evidence.filter(e=>typeof e.value==='number').map(e=>Math.abs(e.value-50));
  const numbers=x.evidence.map(e=>e.label+' '+e.value).join(' · ');
  items.push({...x,text:numbers+'. '+x.text,group:groupFor(x.id),priority:(specific?65:15)+(vals.length?Math.max(...vals)/10:0)});
 }
 const groups=new Set();
 return items.sort((a,b)=>b.priority-a.priority||a.id.localeCompare(b.id)).filter(x=>{if(groups.has(x.group))return false;groups.add(x.group);return true;});
}

// Explanations refer to observed pair data; ANY and unknown never invent a fit.
export function explainPair(pa,pb,match,domains,lifeOptions){
 const a=pa.a,b=pb.a,positive=[],adjustments=[];
 const add=(list,code,priority,text,evidence)=>list.push({code,priority,text,evidence});
 const focus=pa.insights?.[0];
 const indices=[...new Set((focus?.evidence??[]).flatMap(e=>e.questionIds??[]).filter(id=>id<=48).map(id=>Math.floor((id-1)/4)))].filter(i=>finite(pa.scores[i])&&finite(pb.scores[i])).slice(0,3);
 if(indices.length>=2){
  const difference=indices.reduce((sum,i)=>sum+Math.abs(pa.scores[i]-pb.scores[i]),0)/indices.length;
  const details=indices.map(i=>domains[i].name+' 나 '+pa.scores[i]+' / 상대 '+pb.scores[i]).join(' · ');
  const evidence=indices.map(i=>({label:domains[i].name,value:'나 '+pa.scores[i]+' / 상대 '+pb.scores[i],questionIds:qids(i)}));
  if(difference<=20)add(positive,'PERSONAL_CONTEXT',125,'내 주요 해석 ‘'+focus.title+'’에 쓰인 영역을 비교했어요. '+details+'. 평균 차이 '+difference.toFixed(1)+'점으로 가까운 조합이에요.',evidence);
  else add(adjustments,'PERSONAL_CONTEXT_GAP',80,'내 주요 해석에 쓰인 영역은 상대와 평균 '+difference.toFixed(1)+'점 차이가 있어요. '+details+'. 서로 편한 방식을 먼저 이야기해 보세요.',evidence);
 }
 for(let i=0;i<12;i++){
  const mine=pa.scores[i],other=pb.scores[i],name=domains[i].name,pref=partnerDomain(a,i),back=partnerDomain(b,i);
  const fit=partnerFit(pref,other),reverseFit=partnerFit(back,mine);
  if(fit!==null){
   const evidence=[{label:'내 선호 비교',value:Math.round(fit),questionIds:pref.questionIds}];
   const detail=name+': 상대 '+other+'점을 내가 고른 '+[...new Set(pref.ranges.map(r=>r.join('–')))].join(' / ')+' 범위와 각각 비교했어요. '+(pref.contextual?'이전 상세 설문의 상황형 응답과 평소 기대가 달라 두 응답을 따로 반영했어요. ':'')+'선호 부합도 '+Math.round(fit)+'점 · 중요도 '+(pref.importance===null?'미응답 (기본 비중)':pref.prioritySource==='summary'?(pref.importance===3?'우선 선택 · 비중 3':'기본 비중 1'):'이전 개별 응답 · '+pref.importance)+'.';
   if(fit>=80)add(positive,'WANTED_'+domains[i].id,90+pref.weight*15+Math.abs(mine-50)/2+(indices.includes(i)?25:0),detail,evidence);
   else add(adjustments,'WANTED_GAP_'+domains[i].id,70+(100-fit)*pref.weight,detail,evidence);
  }
  if(reverseFit!==null&&reverseFit<80)add(adjustments,'REVERSE_GAP_'+domains[i].id,70+(100-reverseFit)*back.weight,name+': 내 '+mine+'점을 상대의 선호와 비교하면 '+Math.round(reverseFit)+'점이에요. 상대가 바라는 방식도 함께 조율해 보세요.',[{label:'상대 방향 부합도',value:Math.round(reverseFit)}]);
  if(!finite(mine)||!finite(other))continue;
  const distance=Math.abs(mine-other),distinct=Math.abs(mine-50);
  if(distance<=15&&distinct>=15)add(positive,'SIMILAR_'+domains[i].id,45+distinct/2,name+': 나 '+mine+'점 · 상대 '+other+'점으로 '+distance+'점 차이예요. '+(mine>=50?domains[i].high:domains[i].low)+'이라는 접점이 있어요.',[{label:'나',value:mine},{label:'상대',value:other}]);
 }
 const slot=n=>['월','화','수','목','금','토','일'][Math.floor(n/2)]+(n%2?' 저녁':' 낮');
 if(Array.isArray(a[51])&&Array.isArray(b[51])){
  const shared=a[51].filter(v=>b[51].includes(v));
  if(shared.length)add(positive,'TIME_OVERLAP',76,'만날 수 있는 시간이 '+shared.map(slot).join(' · ')+'에 겹쳐요. 내가 고른 '+a[51].length+'개 시간대 중 '+shared.length+'개예요.',[{label:'겹치는 시간대',value:shared.map(slot).join(', ')}]);
  else add(adjustments,'TIME_GAP',75,'선택한 만남 시간대가 겹치지 않아요. 실제로 만날 수 있는 다른 시간을 먼저 조율해 보세요.',[{label:'공통 시간대',value:0}]);
 }
 if(Array.isArray(a[52])&&Array.isArray(b[52])){
  const shared=a[52].filter(v=>b[52].includes(v));
  if(shared.length)add(positive,'SHARED_ACTIVITY',57,'두 사람이 평소 즐기는 활동에 '+shared.map(v=>lifeOptions[52][v]).join(' · ')+'이 있어요.',[{label:'공통 활동 수',value:shared.length}]);
 }
 for(const [id,label] of [[49,'연락'],[50,'만남']]){
  if(!Number.isInteger(a[id])||!Number.isInteger(b[id]))continue;
  const diff=Math.abs(a[id]-b[id]),text=label+': 나 ‘'+lifeOptions[id][a[id]]+'’ / 상대 ‘'+lifeOptions[id][b[id]]+'’. ';
  if(diff<=1)add(positive,'RHYTHM_'+id,60,text+' 빈도 선택이 '+(diff?'한 단계 차이예요.':'같아요.'),[{label:'나',value:a[id]},{label:'상대',value:b[id]}]);
  else add(adjustments,'RHYTHM_GAP_'+id,40+diff*10,text+' 편한 횟수를 따로 맞춰볼 필요가 있어요.',[{label:'선택 단계 차이',value:diff}]);
 }
 for(const [actual,wanted,label] of [[55,56,'흡연'],[57,58,'음주'],[59,60,'관계 방향']]){
  if(Array.isArray(a[wanted])&&Number.isInteger(b[actual])&&!a[wanted].includes(b[actual]))add(adjustments,'LIFE_WANT_'+actual,90,label+': 상대의 선택(‘'+lifeOptions[actual][b[actual]]+'’)은 내가 선택한 허용 범위 밖이에요. 서로 괜찮은지 확인해 보세요.',[{label:'내가 바라는 범위',value:a[wanted].map(v=>lifeOptions[wanted][v]).join(', ')}]);
  if(Array.isArray(b[wanted])&&Number.isInteger(a[actual])&&!b[wanted].includes(a[actual]))add(adjustments,'LIFE_REVERSE_'+actual,89,label+': 내 생활 방식은 이 가상 상대가 선택한 허용 범위 밖이에요. 상대도 편한지 확인할 부분이에요.',[{label:'내 생활 방식',value:lifeOptions[actual][a[actual]]}]);
 }
 for(const spec of partnerLife){
  const want=a[spec.id],actual=b[spec.actual];if(!Array.isArray(want))continue;
  const matched=Array.isArray(actual)?actual.some(v=>want.includes(v)):Number.isInteger(actual)?want.includes(actual):null;
  if(matched===null)continue;
  add(matched?positive:adjustments,'LIFE_PREFERENCE_'+spec.id,spec.actual===51&&!match.actualTimeOverlap?40:matched?85:100,spec.label+': '+(matched?'이 가상 상대의 생활 응답이 내가 고른 범위와 겹쳐요.':'이 가상 상대의 생활 응답이 내가 고른 범위 밖이라 조율이 필요해요.'),[{label:'내가 바라는 생활',value:want.map(v=>spec.actual===51?slot(v):lifeOptions[spec.actual]?.[v]??v).join(', '),questionIds:[spec.id]}]);
 }
 for(const spec of partnerLife){
  const want=b[spec.id],actual=a[spec.actual];if(!Array.isArray(want))continue;
  const matched=Array.isArray(actual)?actual.some(v=>want.includes(v)):Number.isInteger(actual)?want.includes(actual):null;
  if(matched===false)add(adjustments,'LIFE_PREF_REVERSE_'+spec.id,99,spec.label+': 내 생활 응답은 이 가상 상대가 고른 범위 밖이에요. 상대도 편한지 함께 확인해 보세요.',[{label:'상대가 바라는 생활',value:want.map(v=>spec.actual===51?slot(v):lifeOptions[spec.actual]?.[v]??v).join(', '),questionIds:[spec.id]}]);
 }
 const sorted=list=>list.sort((a,b)=>b.priority-a.priority||a.code.localeCompare(b.code));
 // Avoid using all three slots for very similar trait facts.
 const ranked=sorted(positive),reasons=[],usedDomains=new Set();
 const context=ranked.find(x=>x.code==='PERSONAL_CONTEXT');
 const preference=ranked.find(x=>x.code.startsWith('WANTED_'));
 const lifestyle=ranked.find(x=>x.code.startsWith('LIFE_PREFERENCE_')||['TIME_OVERLAP','SHARED_ACTIVITY','RHYTHM_49','RHYTHM_50'].includes(x.code));
 for(const x of [context,preference,lifestyle,...ranked].filter(Boolean)){
  if(reasons.some(r=>r.code===x.code))continue;
  const domain=x.code.match(/^(?:WANTED|SIMILAR)_(.+)$/)?.[1];
  if(domain&&usedDomains.has(domain))continue;
  if(domain)usedDomains.add(domain);
  reasons.push(x);if(reasons.length===3)break;
 }
 if(!reasons.length)reasons.push({code:'OVERALL',priority:0,text:'전체 비교 점수 '+(match.S/100).toFixed(1)+'점이 추천 기준을 넘었어요. 뚜렷한 개별 접점은 현재 응답에서 찾지 못했어요.',evidence:[]});
 return {reasons:match.status==='recommended'?reasons:[],adjustments:sorted(adjustments).slice(0,2)};
}
