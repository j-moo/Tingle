// SIS 0.3: editorial dimensions and rules, not validated psychological factors.
import {preferenceAnalysis} from './partner-survey.mjs';
export {preferenceAnalysis};
export const modelVersion='SIS-0.3 / MATCH-0.5';
export const axesSpec=[
 {id:'approach',name:'다가가는 온도',low:'천천히 스며듦',high:'먼저 다가감',terms:[[0,.7],[1,.3]]},
 {id:'closeness',name:'함께하는 간격',low:'각자의 여백',high:'촘촘한 교류',terms:[[4,.4,true],[2,.25],['49',.2],['50',.15]]},
 {id:'experience',name:'일상을 채우는 방식',low:'익숙한 편안함',high:'새로운 장면',terms:[[7,.55],[6,.25],['53',.2]]},
 {id:'structure',name:'관계를 가꾸는 방식',low:'흐름에 맡김',high:'함께 설계함',terms:[[8,.45],[3,.3],[10,.25]]}
];
const names=[
 ['STILLWAVE','조용한 여백에 머무는 마음'],
 ['MOONROOT','천천히 단단해지는 마음'],
 ['DRIFTLIGHT','여유롭게 새 장면을 찾는 마음'],
 ['STARTRAIL','각자의 궤도로 함께 나아가는 마음'],
 ['SOFTTIDE','자주 만나 서서히 깊어지는 마음'],
 ['HONEYHOME','익숙한 하루를 함께 쌓는 마음'],
 ['DAYDREAM','작은 모험으로 가까워지는 마음'],
 ['WARMORBIT','새로운 일상을 함께 그리는 마음'],
 ['EMBER','마음은 선명하게, 일상은 여유롭게'],
 ['STEADYSPARK','확실한 마음으로 꾸준히 쌓는 관계'],
 ['WILDSPARK','서로의 자유에 불꽃을 더하는 마음'],
 ['BRIGHTTRAIL','나란히 새로운 길을 만드는 마음'],
 ['HEARTWAVE','따뜻한 표현으로 하루를 채우는 마음'],
 ['EVERGLOW','오래 이어질 온기를 만드는 마음'],
 ['SUNBURST','함께 있을 때 더 환해지는 마음'],
 ['CONSTELLA','같은 미래에 새로운 별을 더하는 마음']
];
const koreanNames=['잔잔여백형','차분동행형','여유탐험형','나란히탐험형','포근교감형','일상동행형','설렘산책형','다정탐험형','여유직진형','꾸준직진형','자유모험형','계획모험형','다정직진형','온기동행형','함께모험형','미래탐험형'];
export const types16=names.map(([id,name],i)=>{
 const bits=[3,2,1,0].map(b=>(i>>b)&1),[a,c,e,s]=bits,lo=20,hi=80;
 // Representative profiles are only examples for exploring types.
 const center=[a?hi:lo,a?hi:lo,c?hi:lo,s?hi:lo,c?lo:hi,50,e?hi:lo,e?hi:lo,s?hi:lo,50,s?hi:lo,50,c?75:25,c?75:25,e?75:25];
 return {id,name,koName:koreanNames[i],bits,axes:bits.map(b=>b?80:20),center,color:['#8a879f','#8e7987','#818d76','#a36c7e'][i%4]};
});
const average=a=>a.reduce((s,v)=>s+v,0)/a.length;
const round=x=>Math.round(x*10)/10;
export function calculateAxes(scores,a,domains){
 return axesSpec.map(spec=>{
  const evidence=spec.terms.map(([key,weight,inverse])=>{
   const value=typeof key==='string'?(Number.isInteger(a[key])?a[key]*25:null):scores[key];
   const adjusted=value===null?null:inverse?100-value:value;
   return {key,weight,inverse:!!inverse,value,adjusted,label:typeof key==='string'?({'49':'연락 빈도','50':'만남 빈도','53':'데이트 활동성'})[key]:domains[key].name,questionIds:typeof key==='string'?[Number(key)]:[1,2,3,4].map(n=>key*4+n)};
  });
  const valid=evidence.filter(x=>x.value!==null),coverage=valid.reduce((s,x)=>s+x.weight,0);
  const value=coverage>=.65?round(valid.reduce((s,x)=>s+x.adjusted*x.weight,0)/coverage):null;
  return {...spec,evidence,coverage,value,boundary:value!==null&&Math.abs(value-50)<=5};
 });
}
export function classify(axes){
 if(axes.some(x=>x.value===null))return {ranking:[],weights:[],distances:[]};
 const distances=types16.map(t=>Math.sqrt(average(axes.map((a,i)=>(a.value-t.axes[i])**2))));
 const ranking=distances.map((d,i)=>({index:i,fit:100-d,type:types16[i]})).sort((a,b)=>b.fit-a.fit||a.index-b.index);
 const logits=distances.map(d=>-d*d/(2*20*20)),max=Math.max(...logits),raw=logits.map(x=>Math.exp(x-max)),total=raw.reduce((s,v)=>s+v,0);
 const exact=raw.map(v=>255*v/total),weights=exact.map(Math.floor);
 const remainder=255-weights.reduce((s,v)=>s+v,0),order=exact.map((v,i)=>({i,r:v-weights[i]})).sort((a,b)=>b.r-a.r||a.i-b.i);
 for(let i=0;i<remainder;i++)weights[order[i].i]++;
 return {ranking,weights,distances};
}
const ranges={low:[0,40],mid:[30,70],high:[60,100]};
export function interpret(p,domains){
 const s=p.scores,a=p.a,insights=[];
 const evidence=indices=>indices.map(i=>({label:domains[i].name,value:s[i],questionIds:[1,2,3,4].map(n=>i*4+n)}));
 const add=(id,title,text,action,indices,rule,extra=[])=>insights.push({id,title,text,action,evidence:[...evidence(indices),...extra],rule});
 const has=(...i)=>i.every(n=>s[n]!==null),high=v=>v>=65,low=v=>v<=35;
 if(has(1,5)){
  const gap=s[5]-s[1];
  if(gap>=25)add('expression-receive','내 표현보다, 돌아오는 표현이 더 중요해요','내가 표현하는 빈도보다 상대의 마음을 확인하고 싶은 정도가 더 높게 나타났어요. 조용히 좋아하더라도 상대가 알려주는 애정은 반갑게 느낄 수 있어요.','표현이 적다고 서로의 기대도 적을 거라 짐작하지 말고, 듣고 싶은 말을 구체적으로 나눠보세요.',[1,5],'애정 확인 필요 점수 − 표현 점수 ≥ 25');
  else if(gap<=-25)add('expression-give','마음을 잘 전하되, 같은 양을 요구하진 않을 수 있어요','애정을 표현하는 정도가 마음의 확인을 바라는 정도보다 높아요. 먼저 표현하는 즐거움과 상대에게 확인받고 싶은 마음이 서로 다른 크기로 나타났어요.','표현의 횟수를 맞추기보다 서로 마음을 느끼는 방법을 확인해 보세요.',[1,5],'표현 점수 − 애정 확인 필요 점수 ≥ 25');
  else add('expression-balanced',high(s[1])&&high(s[5])?'표현이 오가는 순간에 가까움을 느껴요':low(s[1])&&low(s[5])?'표현과 확인의 빈도가 모두 낮은 편이에요':'표현과 확인의 기대가 비슷한 높이에 있어요','마음을 전하는 정도와 확인하고 싶은 정도의 차이는 '+Math.abs(gap)+'점이에요. 보내는 신호와 바라는 신호 사이에 큰 간격이 나타나지 않았어요.','서로에게 부담 없는 표현 빈도부터 이야기해 보세요.',[1,5],'두 점수의 절대 차이 < 25');
 }
 if(has(4,2)){
  if(high(s[4])&&high(s[2]))add('space-depth','여백이 필요해도, 마음까지 멀어지는 건 아니에요','개인 시간과 깊은 대화가 동시에 중요하게 나타났어요. 혼자 쉬는 시간을 지키면서도 만났을 때는 속마음을 나누는 관계가 편할 수 있어요.','각자 보내는 시간과 집중해서 이야기하는 시간을 따로 약속해 보세요.',[4,2],'개인 시간 ≥ 65 AND 자기 공개 ≥ 65');
  else if(high(s[4])&&low(s[2]))add('space-private','개인 시간을 확보하고, 속마음은 적게 나누는 편이에요','개인 시간을 지키려는 마음은 높고 속마음을 공유하는 정도는 낮아요. 개인 시간을 확보하는 것과 개인적인 이야기를 많이 나누는 것은 다른 선택일 수 있어요.','곧바로 모든 것을 공유하기보다 편한 주제와 만남 간격부터 정해 보세요.',[4,2],'개인 시간 ≥ 65 AND 자기 공개 ≤ 35');
  else if(low(s[4])&&high(s[2]))add('space-close','함께하는 시간과 깊은 대화를 같이 원해요','자주 함께하려는 방향과 속마음을 많이 나누려는 방향이 함께 나타났어요. 만나는 양과 대화의 깊이를 모두 관계의 일부로 느낄 수 있어요.','서로의 바쁜 시기에도 유지할 수 있는 짧은 대화 시간을 찾아보세요.',[4,2],'개인 시간 ≤ 35 AND 자기 공개 ≥ 65');
  else add('space-context','시간의 거리와 마음의 거리를 따로 조절해요','개인 시간은 '+s[4]+'점, 속마음 공유는 '+s[2]+'점이에요. 둘 중 한쪽만으로 친밀한 정도를 정하기보다, 만나는 간격과 대화의 깊이를 각각 맞춰보는 것이 좋아요.','만나는 횟수와 편한 대화 주제를 별개로 물어보세요.',[4,2],'두 영역 유효; 뚜렷한 결합 구간 외');
 }
 if(has(7,8)){
  if(high(s[7])&&high(s[8]))add('planned-adventure','새로운 경험도, 준비된 출발이 좋아요','새로움과 계획성이 모두 높아요. 즉흥적으로 움직이기보다 미리 찾아본 새로운 장소나 활동에서 편안하게 설렐 수 있어요.','날짜와 큰 동선은 정하고, 현장에서 고를 작은 선택지를 남겨보세요.',[7,8],'새로운 경험 ≥ 65 AND 계획성 ≥ 65');
  else if(high(s[7])&&low(s[8]))add('free-adventure','계획의 빈칸이 설렘이 될 수 있어요','새로운 경험을 향하는 정도에 비해 미리 계획하려는 정도는 낮아요. 당일 발견한 장소로 방향을 바꾸는 데 매력을 느낄 수 있어요.','필수 약속만 정하고 바꿔도 괜찮은 부분을 서로 확인해 보세요.',[7,8],'새로운 경험 ≥ 65 AND 계획성 ≤ 35');
  else if(low(s[7])&&high(s[8]))add('familiar-plan','예측할 수 있는 익숙함에서 쉬어요','익숙한 경험을 선호하면서 계획을 미리 정하는 쪽에 가까워요. 새로운 이벤트보다 좋아하는 장소를 안정적으로 함께 찾는 일이 편할 수 있어요.','늘 좋아하는 코스에 작은 변화 하나만 더해보세요.',[7,8],'새로운 경험 ≤ 35 AND 계획성 ≥ 65');
  else add('date-balance','데이트의 새로움과 준비 정도는 별개의 취향이에요','새로운 경험은 '+s[7]+'점, 계획성은 '+s[8]+'점이에요. 두 점수를 함께 보면 어디에 갈지와 얼마나 미리 정할지를 따로 조율할 수 있어요.','익숙한 코스와 새 코스, 계획과 즉흥 중 편한 조합을 말해보세요.',[7,8],'두 영역 유효; 뚜렷한 결합 구간 외');
 }
 if(has(9,5)){
  if(high(s[9])&&high(s[5]))add('conflict-reassure','갈등 때는 빠른 대화와 마음의 확인이 함께 필요해요','문제를 빨리 이야기하려는 성향과 관계가 괜찮다는 확인을 바라는 성향이 함께 높아요. 해결책뿐 아니라 관계를 이어갈 마음을 먼저 듣는 것도 도움이 될 수 있어요.','잠시 쉬어야 한다면 “마음은 같고, 몇 시에 다시 이야기하자”처럼 시간을 정해보세요.',[9,5],'대화 시점 ≥ 65 AND 안심 ≥ 65');
  else if(low(s[9])&&high(s[5]))add('conflict-pause','대화는 나중에 시작해도, 마음은 확인하고 싶어요','대화를 늦게 시작하는 경향과 상대 마음을 확인하려는 경향이 함께 나타났어요. 잠시 멈추는 것과 연락 없이 멀어지는 것은 다르게 느낄 수 있어요.','쉬는 동안의 짧은 안심 표현과 다시 대화할 시간을 함께 정해보세요.',[9,5],'대화 시점 ≤ 35 AND 안심 ≥ 65');
  else add('conflict-rhythm','대화를 시작하는 시점과 마음 확인의 필요를 맞춰보세요','갈등 대화의 즉시성은 '+s[9]+'점, 마음 확인은 '+s[5]+'점이에요. 대화 시점이 다르면 관심이 없거나 재촉하는 것으로 오해하지 않도록 합의하는 편이 좋아요.','바로 말할 주제와 잠시 생각한 뒤 말할 주제를 구분해 보세요.',[9,5],'두 영역 유효; 뚜렷한 결합 구간 외');
 }
 if(has(0,3)){
  const slow=low(s[0]),long=high(s[3]);
  add('start-direction',slow&&long?'천천히 시작하는 만큼, 오래 함께할 가능성을 살펴요':high(s[0])&&long?'시작은 빠를 수 있어도, 방향은 길게 봐요':'시작의 속도와 관계의 목적지는 다를 수 있어요','가까워지는 속도 '+s[0]+'점과 지속성 '+s[3]+'점을 함께 읽었어요. '+(slow&&long?'신중한 시작은 관심의 크기가 작아서라기보다 오래갈 관계를 살펴보려는 방식일 수 있어요.':high(s[0])&&long?'빠르게 마음을 정하는 성향과 장기적인 관계를 바라는 마음이 함께 있어요.':'얼마나 빨리 시작하는지만으로 가벼운 관계인지 진지한 관계인지 판단하지 않아요.'),'관계의 이름을 정하기 전에 원하는 속도와 방향을 따로 이야기해 보세요.',[0,3],'시작 속도와 지속성의 조합; 낮음 ≤ 35, 높음 ≥ 65');
 }
 if(has(4,5)&&Number.isInteger(a[49])){
  const contact=a[49]*25,extra=[{label:'연락 빈도',value:contact,questionIds:[49]}];
  if(high(s[5])&&contact<=25)add('life-contact','많은 연락보다, 확실한 한마디가 필요할 수 있어요','연락 빈도는 낮은 쪽이지만 마음의 확인은 중요해요. 오래 대화하는 양보다 바쁠 때도 마음이 이어진다는 신호가 더 의미 있을 수 있어요.','연락 횟수를 늘리기보다 바쁠 때 전할 짧은 한마디를 정해보세요.',[5],'안심 ≥ 65 AND 연락 빈도 ≤ 25',extra);
  else if(high(s[4])&&contact>=75)add('life-contact','혼자 있는 시간에도 연결감은 이어가고 싶어요','개인 시간을 충분히 갖고 싶어 하면서 연락은 자주 이어가는 쪽이에요. 함께 활동하는 시간과 연락의 빈도를 다르게 조절하는 조합이에요.','혼자 쉬는 시간에도 답장을 꼭 바로 해야 하는지 기대를 맞춰보세요.',[4],'개인 시간 ≥ 65 AND 연락 빈도 ≥ 75',extra);
  else add('life-contact','연락과 개인 시간의 균형을 따로 살펴요','연락 빈도 '+contact+'점과 개인 시간 '+s[4]+'점을 함께 봤어요. 일상에서 연락하는 간격이 혼자 쉬고 싶은 시간과 어떻게 어우러지는지 확인할 수 있어요.','집중해서 쉬는 시간과 편히 연락할 시간을 나눠보세요.',[4],'연락 빈도와 개인 시간의 조합; 위 특수 구간 외',extra);
 }
 const preferences=preferenceAnalysis(p,domains);
 if(preferences.different.length){
  const r=preferences.different[0],more=r.direction==='higher';
  add('desired-gap','나와 다른 '+r.name+'에 마음이 갈 수 있어요','내 점수는 '+r.value+'점이고, 상대에게 바라는 범위는 '+r.range.join('–')+'점이에요. 나보다 '+(more?'높은':'낮은')+' 쪽의 상대를 편하게 느끼는 것으로 답해, 가장 가까운 범위까지 '+r.gap+'점의 간격이 나타났어요.','닮은 사람이 편할 거라고 가정하기보다, 이 차이가 실제 만남에서도 편안한지 확인해 보세요.',[r.i],'내 점수와 원하는 범위의 최소 거리 ≥ 20; 가장 큰 차이 선택',[{label:'상대에게 원하는 범위',value:r.range.join('–'),questionIds:r.questionIds}]);
 }
 if(has(11,1)){
  const gap=s[11]-s[1];
  add('support-channel',gap>=25?'말보다 행동에 마음이 더 많이 담길 수 있어요':gap<=-25?'애정 표현과 실질적인 도움의 빈도가 달라요':'표현과 도움을 두 가지 통로로 사용해요','실질적인 도움 행동 '+s[11]+'점, 애정 표현 '+s[1]+'점이에요. '+(gap>=25?'자주 말하는 것보다 필요한 일을 해주는 방식으로 마음을 전달할 수 있어요.':gap<=-25?'애정 표현 점수에 비해 실질적인 도움 행동 점수가 낮아요. 이 결과만으로 경청이나 공감 능력을 판단하지 않아요.':'두 영역의 차이가 크지 않아 애정 표현과 실질적 도움을 함께 살펴볼 수 있어요. 경청·공감은 이 점수로 평가하지 않아요.'),'힘들 때 듣고 싶은 말과 실제로 필요한 도움을 서로 물어보세요.',[11,1],'행동으로 돕기 − 표현; 차이 ±25 기준');
 }
 return {insights,preferences};
}
