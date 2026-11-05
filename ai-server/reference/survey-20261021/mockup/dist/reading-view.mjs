const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function adjustmentsMarkup(match){
 return '<div class="match-adjustments"><span>함께 맞춰볼 부분</span>'+(match.adjustments?.length?'<ul>'+match.adjustments.map(x=>'<li>'+esc(x.text)+'</li>').join('')+'</ul>':'<p>현재 규칙에서 큰 차이가 따로 잡히지는 않았어요. 모든 조건이 맞는다는 뜻은 아니에요.</p>')+'</div>';
}
export function personalShadeMarkup(p){
 if(!p.ready)return '';
 return '<section class="within-type"><div class="eyebrow">YOUR OWN SHADE</div><h3>'+(p.mixed?'지금 내 답변에서 눈에 띄는 조합':esc(p.primary.type.id+' · '+p.primary.type.koName)+', 그 안의 나만의 모습')+'</h3><div class="shade-grid">'+p.insights.slice(0,3).map(x=>'<div><strong>'+esc(x.title)+'</strong><p>'+x.evidence.slice(0,3).map(e=>esc(e.label)+' <b>'+esc(e.value)+'</b>').join('<br>')+'</p></div>').join('')+'</div><p class="caption">내 응답에서 특징이 드러나는 조합을 먼저 골랐어요. 유형별 평균과 비교하거나 다른 사람과 순위를 매기지 않아요.</p></section>';
}
