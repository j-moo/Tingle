import {applyWording,wordingVersion} from './wording100.mjs';
export {wordingVersion};
export const questionnaireVersion='SIS-Q-1.1-100';
export const unknown='unknown';
export const partNames=['나의 성향과 생활','내가 원하는 상대'];
export const categoryLabels={
 q40:{rest:'집에서 쉬기',talk:'식사·대화',culture:'전시·공연',walk:'산책·여행',active:'운동·체험'},
 q44:{explain:'이유 설명',apologize:'사과 표현',change:'행동 바꾸기',time:'함께 시간 보내기',lighten:'분위기를 부드럽게 만들기'},
 q45:{listen:'끝까지 듣기',empathize:'감정에 공감하기',solve:'해결 방법 찾기',help:'실제 일 돕기',presence:'곁에 있어주기'},
 q49:{half:'대체로 반반',alternate:'번갈아 부담',income:'소득에 따라',inviter:'제안한 쪽이 더 부담',discuss:'그때그때 협의'},
 q50:{practical:'실용성',experience:'함께할 경험',handmade:'직접 만든 정성',memory:'상징·추억',daily:'선물보다 평소 행동'},
 q52:{morning:'오전',afternoon:'오후',evening:'저녁',night:'늦은 밤',irregular:'일정이 불규칙함'},
 q53:{local:'동네 중심',stay:'한 장소에 오래',district:'한 지역에서 여러 곳',nearby:'근교 나들이',far:'먼 곳까지 이동'},
 q56:{none:'없음',protestant:'개신교',catholic:'천주교',buddhist:'불교',won:'원불교',muslim:'이슬람교',other:'기타 종교'},
 q60:{none:'문신 없음',covered:'평소 옷에 가려지는 문신 있음',visible:'일상에서 보이는 문신 있음',cosmetic:'눈썹·아이라인 등 미용 목적 반영구만 있음'},
 q55:{none:'비흡연',electronic:'전자담배',cigarette:'일반담배',both:'둘 다',quitting:'중단 중'},
 q58:{yes:'원함',no:'원하지 않음',undecided:'아직 정하지 않음'}
};
const numeric=(texts)=>texts.map((label,i)=>({value:i+1,label}));
const categorical=id=>Object.entries(categoryLabels['q'+id]).map(([value,label])=>({value,label}));
const agreement=numeric(['전혀 그렇지 않다','그렇지 않은 편','어느 쪽도 아니다','그런 편','매우 그렇다']);
export const selfTexts=[
'나는 모임에서 분위기를 띄우는 사람이다.','나는 다른 사람의 감정에 공감한다.','나는 해야 할 일을 바로 처리한다.','나는 기분이 자주 달라진다.','나는 상상력이 풍부하다.',
'나는 말을 많이 하지 않는다.','나는 다른 사람의 문제에 관심이 없다.','나는 물건을 제자리에 돌려놓는 것을 자주 잊는다.','나는 대부분의 시간에 긴장하지 않고 편안하다.','나는 추상적인 생각에 관심이 없다.',
'나는 모임에서 여러 사람과 대화한다.','나는 다른 사람의 감정을 함께 느낀다.','나는 정돈된 상태를 좋아한다.','나는 쉽게 속상해진다.','나는 추상적인 생각을 이해하는 데 어려움을 느낀다.',
'나는 모임에서 눈에 띄지 않는 편이다.','나는 다른 사람에게 별로 관심이 없다.','나는 주변을 어지럽히는 편이다.','나는 우울한 기분이 드는 일이 드물다.','나는 상상력이 좋지 않다.',
'나는 필요할 때 다른 사람에게 의지하는 것이 도움이 된다.','나는 보통 내 문제와 걱정을 다른 사람과 이야기한다.','나는 다른 사람과 의논하며 생각을 정리한다.','나는 다른 사람에게 의지하는 것이 편하다.',
'나는 다른 사람에게 마음을 여는 것이 편하지 않다.','나는 내 깊은 감정을 다른 사람에게 보여주지 않는 편을 택한다.','나는 사람들이 나를 진심으로 아끼지 않을까 봐 자주 걱정한다.','나는 사람들이 나를 떠날까 봐 두렵다.','나는 내가 아끼는 만큼 다른 사람이 나를 아끼지 않을까 봐 걱정한다.'
];
const life=[
 [30,'호감이 생겼지만 아직 서로 표현하지 않았어. 나는 보통 어떻게 시작할까?',numeric(['상대가 먼저 표현하기를 기다려요','상대의 신호를 보며 기다리는 편이에요','상황에 따라 달라요','내가 먼저 표현하는 편이에요','내가 먼저 분명하게 표현해요'])],
 [31,'서로 호감이 있다는 것을 알게 됐어. 관계를 정하는 속도는 어느 쪽이 편할까?',numeric(['충분히 오래 알아본 뒤가 편해요','시간을 두고 정하고 싶어요','두 사람의 상황에 맞춰요','비교적 빠르게 정하고 싶어요','마음이 맞으면 빠르게 관계를 정해요'])],
 [32,'좋아하는 마음이 있을 때, 나는 말로 얼마나 자주 표현할까?',numeric(['거의 말로 표현하지 않아요','가끔 표현하는 편이에요','필요한 순간에 표현해요','자주 표현하는 편이에요','일상에서 아주 자주 표현해요'])],
 [33,'가까운 연인에게 개인적인 고민을 어느 정도 이야기할까?',numeric(['대부분 혼자 정리해요','일부만 간단히 이야기해요','주제에 따라 달라요','구체적으로 나누는 편이에요','깊은 고민도 자세히 나눠요'])],
 [34,'별일 없는 하루에 내가 편하게 느끼는 연락은 어느 정도일까?',numeric(['매일이 아니어도 괜찮아요','하루 한 번','하루 몇 번','틈날 때마다','거의 실시간'])],
 [35,'일이나 공부에 집중하느라 답하기 어려울 때, 나는 어떻게 하는 편일까?',numeric(['끝난 뒤 답해요','대체로 끝난 뒤 답해요','상황에 따라 달라요','짧게 상황을 알리는 편이에요','짧게라도 상황을 알려요'])],
 [36,'서로의 일정이 허락할 때, 나는 얼마나 자주 만나고 싶을까?',numeric(['격주 이하','주 1회','주 2회','주 3회','주 4회 이상'])],
 [37,'자유롭게 쓸 수 있는 시간이 생기면, 연인과 보내는 비중은 어느 정도가 편할까?',numeric(['각자 보내는 시간이 훨씬 많아요','각자 보내는 시간이 많은 편이에요','둘 사이 균형이 편해요','함께 보내는 시간이 많은 편이에요','함께 보내는 시간이 훨씬 많아요'])],
 [38,'주말 데이트 약속이 생겼어. 시간과 장소를 언제 정하는 편일까?',numeric(['당일에 정해요','큰 방향만 정하고 당일에 골라요','상황에 따라 달라요','대부분 미리 정해요','시간과 장소를 미리 구체적으로 정해요'])],
 [39,'익숙한 곳과 처음 가보는 곳 중 내가 더 자주 선택하는 쪽은?',numeric(['익숙한 곳','익숙한 곳을 고르는 편','그때그때 비슷해요','새로운 곳을 고르는 편','새로운 곳'])],
 [40,'내가 데이트를 고른다면 먼저 떠오르는 활동은?',categorical(40),'multi',2],
 [41,'연인과 만날 때 친구들과 함께하는 자리는 어느 정도가 자연스러울까?',numeric(['대부분 둘이 만나요','둘이 만나는 자리가 많은 편이에요','둘이 또는 여럿이 비슷해요','여럿이 만나는 자리도 꽤 있어요','여럿이 만나는 자리도 자주 가져요'])],
 [42,'서운한 일이 생겼어. 나는 언제 대화를 시작하는 편일까?',numeric(['충분히 생각을 정리한 뒤','어느 정도 생각을 정리한 뒤','상황에 따라 달라요','비교적 빨리 이야기해요','가능한 한 바로 이야기해요'])],
 [43,'대화 중 감정이 크게 올라왔어. 나는 어느 쪽이 필요할까?',numeric(['계속 대화하는 것이 필요해요','이어가는 쪽이 편해요','상황에 따라 달라요','잠시 쉬는 쪽이 편해요','잠시 멈추고 쉬는 시간이 필요해요'])],
 [44,'다툰 뒤 내가 관계를 풀기 위해 먼저 하는 행동은?',categorical(44)],
 [45,'가까운 사람이 힘들다고 하면 내가 먼저 하는 행동은?',categorical(45)],
 [46,'친구와 단둘이 만날 약속이 생겼어. 연인과 일정 공유는 어떻게 하는 편일까?',numeric(['정하기 전에 미리 상의해요','미리 상의하는 편이에요','상황에 따라 달라요','각자 정하고 공유하는 편이에요','각자 정하고 나중에 공유해요'])],
 [47,'연애 사실이나 둘의 일상을 주변에 얼마나 공개하고 싶을까?',numeric(['가까운 사람에게도 최소한만','가까운 일부에게만','상황에 맞게 공개해요','주변에 자연스럽게 공개해요','주변과 SNS에도 자연스럽게 공개해요'])],
 [48,'보통의 데이트 한 번에 내가 쓰기 편한 개인 예산은?',numeric(['2만 원 미만','2~4만 원 미만','4~7만 원 미만','7~10만 원 미만','10만 원 이상'])],
 [49,'데이트 비용을 나누는 방식으로 가장 자연스러운 것은?',categorical(49)],
 [50,'내가 선물을 고를 때 가장 먼저 고려하는 것은?',categorical(50)],
 [51,'약속 시간에 대한 내 기준은 어느 쪽에 가까울까?',numeric(['상황에 따라 유연해요','어느 정도 유연한 편이에요','상황과 약속에 따라 달라요','정한 시간을 지키는 편이에요','정한 시간을 정확히 지켜요'])],
 [52,'반복적으로 만나기 비교적 편한 시간대는?',categorical(52),'multi',5],
 [53,'하루 데이트의 이동 방식으로 내가 선호하는 것은?',categorical(53)],
 [54,'최근 생활에서 술을 마시는 빈도는?',numeric(['마시지 않음','월 1회 미만','월 1~3회','주 1회 정도','주 2회 이상'])],
 [55,'현재 흡연 상태는?',categorical(55)],
 [56,'현재 나의 종교는?',categorical(56)],
 [57,'지금 연애를 시작한다면 어떤 방향에 가까울까?',numeric(['알아가며 방향을 정하고 싶어요','장기적인 방향은 열어두고 싶어요','관계를 이어가며 함께 정하고 싶어요','장기적인 미래를 생각하며 시작하고 싶어요','결혼 등 장기적인 미래를 전제로 시작하고 싶어요'])],
 [58,'미래의 자녀 계획에 대한 현재 생각은?',categorical(58)],
 [59,'내가 자주 쓰는 생활 공간은 보통 어떤 상태일까?',numeric(['편하게 펼쳐두는 편이에요','필요할 때 모아서 정리해요','때에 따라 달라요','사용 후 정리하는 편이에요','사용 후 바로 정리해요'])],
 [60,'현재 내 문신 상태에 가장 가까운 것은?',categorical(60)]
];
export const domainDefs=[
 {id:'personality',name:'기본 성격',ids:[61,62,63,64]},
 {id:'approach',name:'관계 시작·표현',ids:[65,66,67,68]},
 {id:'connection',name:'연락·함께하는 시간',ids:[69,70,71,72]},
 {id:'experience',name:'데이트·경험',ids:[73,74,75,76]},
 {id:'conflict',name:'갈등·도움',ids:[77,78,79,80]},
 {id:'boundaries',name:'친구 관계·공개 범위',ids:[81,82]},
 {id:'money',name:'비용·선물',ids:[83,84,85]},
 {id:'lifestyle',name:'생활 습관·시간',ids:[86,87,88,89,90,91,94,95]},
 {id:'future',name:'장기적인 관계 방향',ids:[92,93]}
];
const wishes=[
'서로 호감이 있을 때, 상대는 어떻게 다가오는 사람이면 좋을까?',
'상대가 관계의 이름을 정하고 싶어 하는 속도는 어느 정도가 편할까?',
'상대에게 좋아한다는 말을 얼마나 자주 듣고 싶을까?',
'상대가 자신의 개인적인 고민을 나에게 어느 정도 나누면 좋을까?',
'특별한 일이 없는 날, 상대가 어느 정도 연락하는 사람이면 편할까?',
'상대가 바빠서 답하기 어려울 때, 어떤 대응을 기대할까?',
'상대가 만나고 싶어 하는 빈도는 어느 정도가 편할까?',
'상대가 여가 시간 중 나와 함께 보내고 싶어 하는 비중은 어느 정도가 좋을까?',
'여행이나 데이트를 앞두고, 상대는 얼마나 미리 준비하는 사람이면 좋을까?',
'상대가 새로운 장소를 제안하는 정도는 어느 쪽이 편할까?',
'상대가 즐겨 제안하는 데이트 활동 중 반가운 것은?',
'상대가 친구들과 함께하는 만남을 제안하는 빈도는 어느 정도가 좋을까?',
'상대가 서운함을 느꼈을 때, 언제 말을 꺼내주면 좋을까?',
'감정이 격해진 대화에서 상대가 잠시 쉬자고 하는 정도는 어디까지 편할까?',
'다툰 뒤 상대가 먼저 보여주면 마음이 풀리는 행동은?',
'내가 힘들다고 말했을 때 상대에게 가장 받고 싶은 반응은?',
'상대가 친구와 단둘이 만날 때, 일정 공유는 어떤 방식이면 편할까?',
'상대가 우리의 관계를 주변에 공개하는 범위는 어느 정도가 좋을까?',
'상대가 평소 데이트에 사용하는 개인 예산은 어느 범위가 부담 없을까?',
'상대가 선호하는 비용 분담 방식 중 받아들일 수 있는 것은?',
'상대가 선물에 담는 기준 중 나에게 잘 맞는 것은?',
'상대의 약속 시간 기준은 어느 정도면 편할까?',
'상대가 반복적으로 만날 수 있는 시간대 중 내 생활과 맞는 것은?',
'상대가 즐기는 데이트 동선 중 함께하기 좋은 것은?',
'상대의 음주 빈도는 어느 정도까지 편하게 받아들일 수 있을까?',
'상대의 현재 흡연 상태 중 받아들일 수 있는 것은?',
'상대의 종교 중 편하게 받아들일 수 있는 것은?',
'상대가 연애를 시작할 때 기대하는 장기적인 방향은 어느 쪽이면 좋을까?',
'상대의 현재 자녀 계획 중 함께 이야기해볼 수 있는 입장은?',
'상대의 생활 공간 정리 방식은 어느 정도가 편할까?',
'상대의 문신 상태 중 편하게 받아들일 수 있는 것은?'];
const qs=selfTexts.map((text,i)=>({id:i+1,text,kind:'single',options:agreement,source:i<20?'Mini-IPIP 한국어 번안':'ECR-RS 일반 관계용 한국어 번안',hint:i<20?'이상적인 모습보다 평소의 나를 떠올려주세요.':'특정 연인이 아니라 가까운 사람들과의 관계 전반을 떠올려주세요.'}));
for(const [id,text,options,kind='single',max] of life)qs.push({id,text,options,kind,max,source:'자체 생활·관계 문항',hint:kind==='multi'?`최대 ${max}개 선택해 주세요.`:'경험이 없다면 지금 나에게 자연스러울 것 같은 행동을 골라주세요.'});
const traitQuestions=['여러 사람과 어울리고 대화하는 정도는 어느 쪽이 편할까?','다른 사람의 감정과 어려움에 관심을 기울이는 정도는 어느 쪽을 원할까?','할 일과 주변 환경을 꾸준히 관리하는 정도는 어느 쪽이 편할까?','상상하거나 추상적인 생각을 즐기는 정도는 어느 쪽이 편할까?'];
traitQuestions.forEach((text,i)=>qs.push({id:61+i,text,kind:'single',options:[{value:'low',label:'낮은 편'},{value:'mid',label:'중간 정도'},{value:'high',label:'높은 편'}],allowAny:true,source:'자체 상대 선호',hint:'좋고 나쁨이 아니라 내가 편안하게 느끼는 쪽을 골라주세요.'}));
for(let id=65;id<=95;id++){const self=qs.find(q=>q.id===id-35),isCategory=!!categoryLabels['q'+self.id];qs.push({id,text:wishes[id-65],kind:isCategory?'multi':'single',max:isCategory?self.options.length:undefined,options:self.options.map(o=>({...o,label:o.label.replaceAll('내가','상대가')})),allowAny:true,source:'자체 상대 선호',hint:isCategory?'받아들일 수 있는 항목을 모두 골라주세요.':id===89?'받아들일 수 있는 최대 빈도를 골라주세요. 더 낮은 음주 빈도도 허용 범위로 봐요.':'가장 편한 지점을 골라주세요. 차이 수용 범위는 마지막에 한 번 정해요.'});}
qs.push(
 {id:96,text:'상대를 알아갈 때 특히 중요하게 보는 영역 세 가지는?',kind:'multi',max:3,allowAny:true,options:domainDefs.map(d=>({value:d.id,label:d.name})),source:'추천 선호',hint:'최대 3개. 고른 영역은 추천에서 두 배의 비중으로 비교해요.'},
 {id:97,text:'다른 점이 잘 맞더라도 추천에서 제외하고 싶은 조건이 있나?',kind:'multi',max:2,allowAny:true,options:[{value:'smoking',label:'흡연 상태 — 90번에서 고른 범위 밖이면 제외'},{value:'children',label:'자녀 계획 — 93번에서 고른 입장 밖이면 제외'},{value:'religion',label:'종교 — 91번에서 고른 범위 밖이면 제외'},{value:'tattoo',label:'문신 — 95번에서 고른 범위 밖이면 제외'}],source:'추천 선호',hint:'기본은 제외 조건 없음. 최대 2개만 엄격하게 적용하고, 선택하지 않은 항목은 선호 점수로 비교해요.'},
 {id:98,text:'원하는 모습과 조금 다른 상대를 어느 정도까지 만나볼 수 있을까?',kind:'single',options:[{value:'close',label:'가까운 쪽 선호'},{value:'some',label:'작은 차이는 괜찮음'},{value:'wide',label:'비교적 넓게 알아보고 싶음'}],source:'추천 선호'},
 {id:99,text:'추천 후보의 유형 구성은 어떻게 받고 싶을까?',kind:'single',options:[{value:'fit',label:'선호 충족도가 높은 순'},{value:'diverse',label:'조건이 맞는 후보 중 다른 유형도 한 명 포함'}],source:'추천 선호'},
 {id:100,text:'상대를 소개받을 때 가장 먼저 확인하고 싶은 정보는?',kind:'single',options:[{value:'common',label:'나와 비슷한 점'},{value:'my_wishes',label:'내가 원한 모습과 맞는 점'},{value:'their_wishes',label:'상대가 원하는 모습과 내가 맞는 점'},{value:'adjust',label:'조율할 차이'}],source:'추천 선호'}
);
qs.find(q=>q.id===56).hint='종교 종류는 순서나 점수의 높낮이가 없어요. 답하고 싶지 않으면 응답하지 않음을 골라주세요.';
qs.find(q=>q.id===60).hint='여러 경우에 해당하면 일상에서 보이는 문신을 기준으로 골라주세요. 사진이나 개수는 묻지 않아요.';
for(const id of [91,95])qs.find(q=>q.id===id).hint='받아들일 수 있는 항목을 모두 선택하거나 크게 상관없음을 골라주세요. 기본은 선호 비교이며, 반드시 제외할 조건은 97번에서 따로 정해요.';
applyWording(qs);
qs.find(q=>q.id===97).options.push({value:'age',label:'나이 - 아래 희망 나이 범위 밖이면 제외'});
export const questions=qs;
export const byId=Object.fromEntries(qs.map(q=>[q.id,q]));
export const featureLabels={E:'외향성',A:'친화성',C:'성실성',N:'정서적 반응성',I:'상상·지적 흥미',anxiety:'가까운 관계에서 느끼는 불안',avoidance:'마음 공개·의지하기의 불편함',q30:'먼저 표현',q31:'관계를 정하는 속도',q32:'말로 애정 표현',q33:'개인적인 고민 공유',q34:'연락 빈도',q35:'바쁠 때 상황 공유',q36:'만나고 싶은 빈도',q37:'함께하는 시간',q38:'약속 준비',q39:'새로운 장소',q40:'데이트 활동',q41:'친구 동반 만남',q42:'대화 시작 시점',q43:'대화 중 휴식',q44:'화해 행동',q45:'도움 방식',q46:'친구 약속 공유',q47:'관계 공개',q48:'데이트 예산',q49:'비용 분담',q50:'선물 기준',q51:'약속 시간',q52:'가능한 시간대',q53:'데이트 동선',q54:'음주 빈도',q55:'흡연 상태',q56:'종교',q57:'관계의 장기적 방향',q58:'자녀 계획',q59:'생활 공간 정리',q60:'문신 상태'};
export function validAnswer(id,value){const q=byId[id];if(!q)return false;if(value===unknown)return true;if(value==='any')return !!q.allowAny;const allowed=q.options.map(o=>o.value);if(q.kind==='multi')return Array.isArray(value)&&value.length>0&&value.length<=(q.max??allowed.length)&&new Set(value).size===value.length&&value.every(v=>allowed.includes(v));return allowed.includes(value);}
export const sanitizeAnswers=a=>a&&typeof a==='object'&&!Array.isArray(a)?Object.fromEntries(questions.filter(q=>validAnswer(q.id,a[q.id])).map(q=>[q.id,a[q.id]])):{};
export const answeredCount=a=>questions.filter(q=>validAnswer(q.id,a?.[q.id])).length;
export function answerLabel(id,value){if(value==='unknown'||value===undefined||value===null)return '미측정';if(value==='any')return id===97?'제외 조건 없음':id===96?'모든 영역을 고르게':'크게 상관없음';const q=byId[id];if(!q)return '미측정';if(Array.isArray(value))return value.map(v=>q.options.find(o=>o.value===v)?.label??'').join(' · ');return q.options.find(o=>o.value===value)?.label??'미측정';}
