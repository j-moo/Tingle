import {MAX_TRANSFER_BYTES,parseResultText,exportResult,resultText} from './result-transfer.mjs';
function dialogShell(title){
 const d=document.createElement('dialog');d.className='transfer-dialog';d.setAttribute('aria-labelledby','transfer-title');
 d.innerHTML='<div class="dialog-header"><h2 id="transfer-title"></h2><button class="dialog-close" aria-label="닫기" type="button">×</button></div><div class="transfer-body"></div>';
 d.querySelector('h2').textContent=title;d.querySelector('.dialog-close').onclick=()=>d.close();
 const opener=document.activeElement;d.addEventListener('close',()=>{d.remove();if(opener?.isConnected)opener.focus();});document.body.append(d);return d;
}
export function openImportDialog(onImport){
 const d=dialogShell('기존 결과 불러오기'),body=d.querySelector('.transfer-body');
 body.innerHTML='<p>예전 결과나 팀원에게 받은 파일로 최신 분석과 추천 이유를 바로 확인해요. 뜻이 바뀐 문항은 재응답을 안내해요. 내 답변은 그대로 유지돼요.</p><label class="file-picker"><strong>JSON · TXT 파일 선택</strong><input type="file" accept=".json,.txt,application/json,text/plain" aria-label="결과 파일 선택"></label><p class="transfer-note">파일이 보이지 않으면 파일 선택기의 ‘파일’ 또는 ‘문서’ 위치를 확인해 주세요. 1MB 이하 · 파일 내용은 서버로 전송하지 않아요.</p><div class="transfer-divider">파일 대신 텍스트로 받았다면</div><label for="import-text">받은 결과 전체 붙여넣기</label><textarea id="import-text" rows="5" spellcheck="false" placeholder="결과 복사로 받은 텍스트 또는 JSON 내용을 여기에 붙여넣으세요."></textarea><button class="button secondary" type="button" data-transfer="paste-import">붙여넣은 결과 보기</button><p class="transfer-status" role="status" aria-live="polite"></p>';
 const input=d.querySelector('input'),status=d.querySelector('.transfer-status'),paste=d.querySelector('[data-transfer="paste-import"]');
 const read=(text,label)=>{const result=parseResultText(text);if(!d.isConnected)return;d.close();onImport({...result,label});};
 input.addEventListener('change',async()=>{
  const file=input.files?.[0];if(!file)return;input.disabled=true;paste.disabled=true;status.textContent='원래 답변으로 새 분석을 계산하고 있어요…';
  try{if(file.size>MAX_TRANSFER_BYTES)throw Error('결과 파일은 1MB 이하로 불러와 주세요.');read(await file.text(),file.name.slice(0,160));}
  catch(error){status.textContent=error.message||'파일을 읽지 못했어요. 다시 선택해 주세요.';}
  finally{input.disabled=false;paste.disabled=false;input.value='';}
 });
 paste.onclick=()=>{try{read(d.querySelector('textarea').value,'붙여넣은 결과');}catch(error){status.textContent=error.message;}};
 d.showModal();
}
export function openExportDialog(answers,{imported=false,legacyArchive=null}={}){
 const result=exportResult(answers,{legacyArchive}),fullText=resultText(result),compactText=resultText(result,{compact:true});
 const name='suddenly-see-result-'+new Date().toISOString().replace(/[-:]/g,'').slice(0,15).replace('T','-');
 const file=new File(['\uFEFF'+fullText],name+'.txt',{type:'text/plain'}),d=dialogShell('결과 저장 · 공유'),body=d.querySelector('.transfer-body');
 body.innerHTML='<p>'+(imported?'지금 불러온 결과':'현재 내 결과')+'를 전달해요. 모든 설문 응답이 포함되어, 받는 사람도 결과를 불러올 수 있어요. 문항이 바뀌면 일부 재응답이 필요할 수 있어요.</p><div class="transfer-actions"><button class="button primary" type="button" data-transfer="share">휴대폰 공유</button><button class="button secondary" type="button" data-transfer="txt">TXT 파일 저장</button><button class="button secondary" type="button" data-transfer="copy">결과 복사</button></div><p class="transfer-status" role="status" aria-live="polite"></p><p class="transfer-note">저장이 막히면 ‘결과 복사’로 전달해 주세요. 받는 사람은 ‘기존 결과 불러오기 → 붙여넣기’를 사용하면 돼요. 사진·스크린샷에는 원래 응답이 없어 재분석할 수 없어요.</p><details class="manual-transfer"><summary>복사하거나 공유할 수 없을 때</summary><label for="export-text">결과 전달용 텍스트 전체</label><textarea id="export-text" rows="6" readonly spellcheck="false"></textarea><button class="link-button" type="button" data-transfer="select">텍스트 전체 선택</button><p class="transfer-note">텍스트를 길게 누르거나 선택한 뒤, 기기의 복사 메뉴를 사용해 주세요.</p></details><details class="json-transfer"><summary>기존 JSON 형식으로 저장</summary><p class="transfer-note">기존 파일과 같은 형식이 필요할 때 사용할 수 있어요.</p><button class="button secondary" type="button" data-transfer="json">JSON 파일 저장</button></details>';
 const status=d.querySelector('.transfer-status'),text=d.querySelector('textarea'),manual=d.querySelector('.manual-transfer');text.value=compactText;
 const fallback=message=>{status.textContent=message;manual.open=true;text.focus();text.select();text.setSelectionRange(0,text.value.length);};
 const download=(blob,filename)=>{
  try{const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=filename;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);status.textContent='저장을 요청했어요. 기기의 다운로드나 파일 앱에서 확인해 주세요. 저장되지 않으면 결과 복사를 이용해 주세요.';}
  catch{fallback('이 브라우저에서 파일 저장을 시작하지 못했어요. 아래 텍스트를 복사해 주세요.');}
 };
 body.addEventListener('click',async event=>{
  const button=event.target.closest('button[data-transfer]');if(!button||button.disabled)return;
  const action=button.dataset.transfer;
  if(action==='txt'){download(file,file.name);return;}
  if(action==='json'){download(new Blob([JSON.stringify(result,null,2)],{type:'application/json'}),name+'.json');return;}
  if(action==='select'){text.focus();text.select();text.setSelectionRange(0,text.value.length);return;}
  if(action==='copy'){
   try{if(!window.navigator.clipboard?.writeText)throw Error();await window.navigator.clipboard.writeText(compactText);status.textContent='재분석용 응답을 포함한 결과를 복사했어요. 메시지나 메모에 붙여넣어 전달해 주세요.';}
   catch{fallback('자동 복사를 사용할 수 없어요. 선택된 텍스트를 기기의 복사 메뉴로 복사해 주세요.');}return;
  }
  if(action==='share'){
   button.disabled=true;
   try{
    if(!window.navigator.share){fallback('이 브라우저에는 공유 기능이 없어요. 결과 복사나 TXT 파일 저장을 이용해 주세요.');return;}
    let filesSupported=false;try{filesSupported=!!window.navigator.canShare?.({files:[file]});}catch{}
    // Invoke directly in the click event to retain user activation.
    await window.navigator.share(filesSupported?{files:[file],title:'Suddenly I See 결과'}:{text:compactText,title:'Suddenly I See 결과'});
    status.textContent='기기의 공유 화면으로 결과를 전달했어요. 받는 쪽에서 파일이나 텍스트를 확인해 주세요.';
   }catch(error){if(error.name==='AbortError')status.textContent='공유가 취소되었거나 사용할 수 있는 공유 대상이 없어요. TXT 저장·결과 복사도 사용할 수 있어요.';else fallback('이 브라우저에서 공유를 완료하지 못했어요. 아래 텍스트를 복사하거나 TXT 파일로 저장해 주세요.');}
   finally{button.disabled=false;}
  }
 });
 d.showModal();
}
