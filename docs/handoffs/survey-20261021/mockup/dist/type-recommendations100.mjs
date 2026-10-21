import {model,classifyAnswers,wishFit,featureDistance} from './engine100.mjs';
import {domainDefs,featureLabels,answerLabel} from './data100.mjs';
export const typeRecommendationVersion='SIS-TYPE-REF-1';
const traits=['E','A','C','I'];
const target=id=>id<=64?traits[id-61]:'q'+(id-35);
const measured=v=>v!==undefined&&v!==null&&v!=='unknown'&&v!=='any';
const valueLabel=(key,value)=>key.startsWith('q')?answerLabel(Number(key.slice(1)),value):`${featureLabels[key]} ${Math.round(value)}점`;
function reasons(rows,a){
 const priority=Array.isArray(a[96])?a[96]:[];
 const sorted=[...rows].sort((x,y)=>y.fit-x.fit||Number(priority.includes(y.domain))-Number(priority.includes(x.domain)));
 const used=new Set(),out=[];
 for(const r of sorted.filter(r=>r.fit>=.7).slice(0,2)){used.add(r.feature);out.push({title:`${r.label}: 내 선호와 가까워요`,text:`나는 ‘${answerLabel(r.id,r.wish)}’를 골랐고, 이 유형의 초기 대표값은 ‘${valueLabel(r.feature,r.actual)}’예요.`});}
 const diff=[...rows].filter(r=>r.fit<1&&!used.has(r.feature)).sort((x,y)=>x.fit-y.fit)[0];
 if(diff)out.push({title:'함께 살펴볼 차이',text:`${diff.label}에서 내 선호는 ‘${answerLabel(diff.id,diff.wish)}’, 유형 대표값은 ‘${valueLabel(diff.feature,diff.actual)}’예요.`});
 return out.slice(0,3);
}
// Reference types have authored trait centers, not a person's age or lifestyle eligibility.
// Reuse interval/category fit but do not invoke pair(), strict exclusions or match thresholds.
export function typeRecommendations(a){
 let candidates=model.prototypes.map(p=>{
  const rows=[];
  for(let id=61;id<=95;id++){
   const feature=target(id);if(!Object.hasOwn(model.weights,feature)||!measured(a[id]))continue;
   const fit=wishFit(id,a[id],p.features[feature],a[98]);if(fit===null)continue;
   rows.push({id,feature,label:featureLabels[feature],domain:domainDefs.find(d=>d.ids.includes(id)).id,wish:a[id],actual:p.features[feature],fit});
  }
  const domains=domainDefs.flatMap(d=>{const items=rows.filter(r=>r.domain===d.id);return items.length?[{id:d.id,name:d.name,fit:items.reduce((s,r)=>s+r.fit,0)/items.length,weight:Array.isArray(a[96])&&a[96].includes(d.id)?2:1}]:[];});
  const score=domains.length?100*domains.reduce((s,d)=>s+d.fit*d.weight,0)/domains.reduce((s,d)=>s+d.weight,0):null;
  return {...p,score,referenceMode:'partner-preferences',rows,domains,reasons:reasons(rows,a)};
 }).filter(p=>p.score!==null);
 let mode='partner-preferences';
 if(!candidates.length){
  mode='self-similarity';const classification=classifyAnswers(a);
  candidates=classification.ranking.map(p=>{
   const rows=Object.keys(model.weights).filter(k=>measured(classification.features[k])).map(feature=>({feature,label:featureLabels[feature],self:classification.features[feature],actual:p.features[feature],fit:1-featureDistance(feature,classification.features[feature],p.features[feature])}));
   const nearest=[...rows].sort((x,y)=>y.fit-x.fit).slice(0,2);
   return {...p,score:p.fit,referenceMode:mode,rows,domains:[],reasons:nearest.map(r=>({title:`나와 비슷한 ${r.label}`,text:`내 응답은 ‘${valueLabel(r.feature,r.self)}’, 이 유형의 초기 대표값은 ‘${valueLabel(r.feature,r.actual)}’예요. 상대 선호가 없어 나와의 유사성으로 제시했어요.`}))};
  });
 }
 candidates.sort((x,y)=>y.score-x.score||x.type.localeCompare(y.type));
 return {version:typeRecommendationVersion,mode,all:candidates,selected:candidates.slice(0,3)};
}
