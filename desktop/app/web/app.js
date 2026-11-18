'use strict';
/* Shared motion contract: real viewport rectangles, unscaled live text, reversible progress.
 * Adapted from the mobile WorkspaceMotion / ExportMotion geometry, not a CSS fade preset. */
const Motion = (() => {
  const clamp = v => Math.max(0, Math.min(1, v));
  const smooth = v => { const x=clamp(v); return x*x*(3-2*x); };
  const mix = (a,b,p) => Object.fromEntries(['x','y','w','h'].map(k=>[k,a[k]+(b[k]-a[k])*clamp(p)]));
  function bezier(t,x1=.2,y1=0,x2=0,y2=1) {
    t=clamp(t); if(t===0||t===1)return t;
    const curve=(u,a,b)=>3*(1-u)*(1-u)*u*a+3*(1-u)*u*u*b+u*u*u;
    let lo=0,hi=1;
    for(let i=0;i<24;i++){const u=(lo+hi)/2;if(curve(u,x1,x2)<t)lo=u;else hi=u;}
    return curve((lo+hi)/2,y1,y2);
  }
  class State {
    constructor(){this.p=0;this.phase='closed';this.generation=0;this.run=null;}
    begin(open){
      if((open&&['open','opening'].includes(this.phase))||(!open&&['closed','closing'].includes(this.phase)))return null;
      this.phase=open?'opening':'closing';
      return this.run={generation:++this.generation,from:this.p,to:open?1:0};
    }
    advance(run,t){
      if(this.run!==run)return false;
      const q=run.to>run.from?bezier(t):bezier(t,.4,0,.8,1);
      this.p=run.from+(run.to-run.from)*q;return true;
    }
    finish(run){if(this.run!==run)return false;this.p=run.to;this.phase=run.to?'open':'closed';this.run=null;return true;}
  }
  const capable=typeof HTMLElement!=='undefined' && typeof document.addEventListener==='function';
  const records=new Map(), animations=new Map();
  const media=typeof matchMedia==='function'?matchMedia('(prefers-reduced-motion: reduce)'):null;
  let preference='auto',initialized=false,previewKey=null,previewRun=0,previewGhost=null,tabGhost=null,toastTimer=null;
  try{preference=localStorage.getItem('workbench-motion')==='reduce'?'reduce':'auto';}catch(_){/* local UI preference only */}
  const reduced=()=>preference==='reduce'||!!media?.matches;
  const rect=el=>{const r=el.getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height};};
  const visible=el=>el?.isConnected&&el.getBoundingClientRect().width>0&&el.getBoundingClientRect().height>0;
  const byId=id=>typeof id==='string'?document.getElementById(id):id;
  function sanitize(node){
    for(const el of [node,...node.querySelectorAll('*')]){
      el.removeAttribute('id');el.removeAttribute('for');el.removeAttribute('autofocus');el.removeAttribute('name');
      if(el.matches('button,input,select,a,textarea'))el.setAttribute('tabindex','-1');
    }
    node.setAttribute('aria-hidden','true');node.inert=true;return node;
  }
  function animate(el,frames,duration=300,easing='cubic-bezier(.2,0,0,1)'){
    animations.get(el)?.cancel();animations.delete(el);
    if(reduced()||!el?.animate)return null;
    const a=el.animate(frames,{duration,easing,fill:'none'});animations.set(el,a);
    const clear=()=>{if(animations.get(el)===a)animations.delete(el);};
    a.finished.then(clear,clear);return a;
  }
  function safe(r){
    const w=Math.max(1,Math.min(r.w,innerWidth-24)),h=Math.max(1,Math.min(r.h,innerHeight-24));
    return {x:Math.max(12,Math.min(r.x,innerWidth-w-12)),y:Math.max(12,Math.min(r.y,innerHeight-h-12)),w,h};
  }
  function measure(rec){
    const anchor=rec.state.phase==='opening'&&rec.openOrigin?rec.openOrigin:visible(rec.source)?rect(rec.source):rec.origin;
    rec.origin=anchor;
    let target;
    if(rec.kind==='dialog'||rec.kind==='menu'){
      const menu=rec.kind==='menu',w=Math.min(menu?308:rec.el.classList.contains('wide')?720:460,innerWidth-24);
      rec.content.style.width=w+'px';rec.content.style.height='auto';
      const h=Math.min(rec.content.scrollHeight,innerHeight*(menu?.9:.85),innerHeight-24);
      const below=anchor.y+anchor.h+10+h<=innerHeight-12||anchor.y<innerHeight/2;
      const x=anchor.x+anchor.w/2>innerWidth/2?anchor.x+anchor.w-w:anchor.x;
      target=safe({x,y:below?anchor.y+anchor.h+10:anchor.y-h-10,w,h});
    }else{
      const docked=false;
      const slot=rect(rec.slot),w=Math.min(slot.w,innerWidth-(docked?0:24));
      rec.content.style.width=w+'px';rec.content.style.height=rec.kind==='panel'?slot.h+'px':'auto';
      target=docked?{x:Math.max(0,Math.min(slot.x,innerWidth-w)),y:slot.y,w,h:slot.h}:safe({x:slot.x,y:slot.y,w,h:rec.kind==='panel'?slot.h:rec.content.scrollHeight});
    }
    rec.target=target;return target;
  }
  function ensure(id,source,kind){
    let rec=records.get(id);if(rec)return rec;
    const el=byId(id),modal=kind==='dialog',root=modal?el:document.createElement('div');
    const surface=document.createElement('div'),content=document.createElement('div'),ghost=document.createElement('div');
    surface.className='motion-surface';content.className='motion-content'+(modal?' dialog-content':'');ghost.className='motion-source-ghost';
    let slot=null;
    if(modal){
      content.append(...el.childNodes);el.classList.add('motion-root');el.setAttribute('tabindex','-1');
      const heading=content.querySelector('h2');if(heading){heading.id=id+'-title';el.setAttribute('aria-labelledby',heading.id);}
    }else{
      slot=document.createElement('div');slot.className=el.className.replace(/\b(hidden|closed)\b/g,'')+' motion-measure';
      slot.setAttribute('aria-hidden','true');slot.inert=true;el.before(slot);content.append(el);
      root.className='motion-layer motion-'+kind;document.body.append(root);root.hidden=true;
      if(kind==='panel')root.addEventListener('click',e=>{if(e.target===root&&innerWidth<800)setPanel(false);});
    }
    surface.append(ghost,content);root.append(surface);
    rec={id,el,root,surface,content,ghost,slot,kind,source,state:new State(),origin:rect(source),frame:null,raf:0,resolve:null,restoreFocus:true};
    records.set(id,rec);
    if(modal){
      el.addEventListener('cancel',e=>{e.preventDefault();close(id);});
      // A native close event is queued. It may belong to a prior close if already reopened.
      el.addEventListener('close',()=>{if(!el.open&&rec.state.phase!=='closed')settle(rec,false);});
    }
    return rec;
  }
  function sourceAppearance(rec,source){
    rec.source=source;rec.sourceOpacity=source.style.opacity;rec.sourceVisibility=source.style.visibility;rec.sourceInert=source.inert;rec.sourceAria=source.getAttribute('aria-hidden');
    rec.sourceRadius=parseFloat(getComputedStyle(source).borderRadius)||10;
    const bg=getComputedStyle(source).backgroundColor;
    rec.sourceColor=(!bg||bg==='rgba(0, 0, 0, 0)')?'#232526':bg;
    rec.ghost.replaceChildren(sanitize(source.cloneNode(true)));
    const copy=rec.ghost.firstElementChild;
    Object.assign(copy.style,{width:'100%',height:'100%',margin:'0',transform:'none',opacity:'1'});
    source.setAttribute('aria-controls',rec.id);source.setAttribute('aria-expanded','true');source.style.visibility='hidden';source.inert=true;source.setAttribute('aria-hidden','true');
  }
  function paint(rec,r){
    const p=rec.state.p;rec.frame=r;
    Object.assign(rec.surface.style,{left:r.x+'px',top:r.y+'px',width:r.w+'px',height:r.h+'px',
      borderRadius:(rec.sourceRadius+((rec.kind==='panel'?18:18)-rec.sourceRadius)*p)+'px',
      background:`color-mix(in srgb, ${rec.sourceColor} ${(1-p)*100}%, #1C1D1F)`});
    const t=rec.target,c=smooth((p-.12)/.88);
    Object.assign(rec.content.style,{left:(t.x-r.x)+'px',top:(t.y-r.y+8*(1-c))+'px',width:t.w+'px',height:t.h+'px',opacity:String(c)});
    Object.assign(rec.ghost.style,{left:(rec.origin.x-r.x)+'px',top:(rec.origin.y-r.y)+'px',width:rec.origin.w+'px',height:rec.origin.h+'px',opacity:String(1-smooth(p/.23))});
    // The source remains hidden/inert until the reverse rectangle reaches it.
    rec.source.style.visibility='hidden';
    rec.root.style.setProperty('--reveal',String(p));
    rec.el.dataset.motionPhase=rec.state.phase;
  }
  function finish(rec,run){
    if(!rec.state.finish(run))return;
    const open=rec.state.phase==='open';paint(rec,open?rec.target:rec.origin);rec.raf=0;
    rec.content.inert=!open;rec.el.inert=!open;
    rec.source.setAttribute('aria-expanded',String(open));
    if(!open){
      rec.source.style.opacity=rec.sourceOpacity;rec.source.style.visibility=rec.sourceVisibility;rec.source.inert=rec.sourceInert;if(rec.sourceAria===null)rec.source.removeAttribute('aria-hidden');else rec.source.setAttribute('aria-hidden',rec.sourceAria);
      if(rec.kind==='dialog'){if(rec.el.open)rec.el.close();}
      else{rec.root.hidden=true;rec.el.classList.add(rec.kind==='panel'?'closed':'hidden');}
      rec.el.setAttribute('aria-hidden','true');
      if(rec.restoreFocus){
        const candidates=[rec.source,rec.previousFocus,byId('import-btn')];
        candidates.find(e=>visible(e)&&!e.disabled&&!e.closest('[inert]'))?.focus({preventScroll:true});
      }
    }else if(rec.focusOnOpen&&(rec.kind==='dialog'||rec.kind==='menu'||rec.kind==='panel')){
      (rec.content.querySelector('[autofocus],button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled)')||rec.el).focus({preventScroll:true});
    }
    rec.focusOnOpen=false;if(open)rec.openOrigin=null;
    rec.resolve?.(open);rec.resolve=null;sync();
  }
  function drive(rec,open,instant=false,retarget=false){
    cancelAnimationFrame(rec.raf);rec.resolve?.(false);rec.resolve=null;
    if(retarget){rec.state.run=null;rec.state.phase=open?'opening':'closing';}
    const run=retarget?{generation:++rec.state.generation,from:rec.state.p,to:open?1:0}:rec.state.begin(open);
    if(!run)return Promise.resolve(open);
    if(retarget)rec.state.run=run;
    measure(rec);
    const start=rec.frame||rec.origin,end=open?rec.target:rec.origin;
    rec.content.inert=!open;rec.el.inert=!open;
    const duration=retarget?240:Math.max(80,(open?420:300)*Math.abs(run.to-run.from));
    paint(rec,start);
    const promise=new Promise(resolve=>rec.resolve=resolve);
    if(instant||reduced()||document.hidden){rec.state.advance(run,1);finish(rec,run);return promise;}
    const began=performance.now();
    const tick=now=>{
      if(rec.state.run!==run)return;
      const t=clamp((now-began)/duration),q=open?bezier(t):bezier(t,.4,0,.8,1);
      rec.state.advance(run,t);paint(rec,mix(start,end,q));
      if(t>=1)finish(rec,run);else rec.raf=requestAnimationFrame(tick);
    };
    rec.raf=requestAnimationFrame(tick);return promise;
  }
  function settle(rec,open=rec.state.phase==='opening'||rec.state.phase==='open'){
    if(rec.state.phase==='closed')return;
    drive(rec,open,true,true);
  }
  function open(id,source,kind='dialog',instant=false,origin=null){
    const el=byId(id);source=byId(source)||document.activeElement;
    if(!capable){if(kind==='dialog'&&!el.open)el.showModal();else el.classList.remove('hidden','closed');return Promise.resolve(true);}
    init();const rec=ensure(id,source,kind);
    if(['open','opening'].includes(rec.state.phase))return Promise.resolve(true);
    rec.openOrigin=origin;
    if(rec.state.phase==='closed'){
      rec.previousFocus=document.activeElement;rec.focusOnOpen=true;sourceAppearance(rec,source);rec.origin=origin||rect(source);rec.frame=null;
      el.inert=false;el.removeAttribute('aria-hidden');
      if(kind==='dialog'){if(!el.open)el.showModal();}else{rec.root.hidden=false;el.classList.remove('closed','hidden');}
    }
    rec.restoreFocus=true;return drive(rec,true,instant);
  }
  function close(id,{restoreFocus=true}={}){
    const el=byId(id);if(!capable){if(el.open){if(typeof WorkbenchControls!=='undefined')WorkbenchControls.close(false,true);el.close();}else el.classList.add('hidden');return Promise.resolve(false);}
    const rec=records.get(id);if(!rec||['closed','closing'].includes(rec.state.phase))return Promise.resolve(false);
    // Closing an already-closed layer (e.g. outside-click cleanup) must be a no-op.
    if(typeof WorkbenchControls!=='undefined')WorkbenchControls.close(false,true);
    for(const child of [...records.values()].reverse())if(child!==rec&&rec.content.contains(child.source)&&child.state.phase!=='closed')settle(child,false);
    rec.restoreFocus=restoreFocus;return drive(rec,false);
  }
  const isOpen=id=>{const r=records.get(id);return !!r&&['opening','open'].includes(r.state.phase);};
  function panel(show,instant=false,origin=null){
    if(!capable){byId('inspector').classList.toggle('closed',!show);return;}
    if(show)return open('inspector','params-btn','panel',instant,origin);return close('inspector',{restoreFocus:byId('inspector').contains(document.activeElement)});
  }
  function sync(){
    if(!capable)return;
    for(const selector of ['.mode-switch','.panel-tabs','.view-switch']){
      const parent=document.querySelector(selector);if(!parent||!visible(parent))continue;
      const active=parent.querySelector('button.active:not(.hidden)');if(!active)continue;
      let mark=parent.querySelector('.motion-indicator');
      if(!mark){mark=document.createElement('span');mark.className='motion-indicator';mark.setAttribute('aria-hidden','true');parent.prepend(mark);}
      const prev=rect(mark),base=rect(parent),next={x:active.offsetLeft,y:active.offsetTop,w:active.offsetWidth,h:active.offsetHeight};
      const signature=JSON.stringify(next);if(mark.dataset.geometry===signature)continue;
      const initialized=!!mark.dataset.geometry;mark.dataset.geometry=signature;
      Object.assign(mark.style,{left:next.x+'px',top:next.y+'px',width:next.w+'px',height:next.h+'px'});
      if(initialized)animate(mark,[{left:(prev.x-base.x)+'px',top:(prev.y-base.y)+'px',width:prev.w+'px',height:prev.h+'px'},nextStyle(next)],380);
    }
  }
  const nextStyle=r=>({left:r.x+'px',top:r.y+'px',width:r.w+'px',height:r.h+'px'});
  function tabs(name,mutate){
    if(!capable){mutate();return;}
    const old=document.querySelector('.tab-content:not(.hidden)'),next=byId('panel-'+name),box=byId('panel-scroll')||document.querySelector('.panel-scroll');
    if(old===next){mutate();sync();return;}
    tabGhost?.remove();tabGhost=null;
    const can=old&&visible(old)&&visible(byId('inspector'))&&!reduced();
    const order=['panel-process','panel-focus','panel-compose','panel-sizes'],dir=old&&order.indexOf(next.id)<order.indexOf(old.id)?-1:1;
    if(can){
      const r=rect(old),b=rect(box),ghost=sanitize(old.cloneNode(true));ghost.classList.add('motion-tab-ghost');
      Object.assign(ghost.style,{left:(r.x-b.x)+'px',top:(r.y-b.y+box.scrollTop)+'px',width:r.w+'px',height:Math.min(r.h,b.h)+'px'});
      box.append(ghost);tabGhost=ghost;
    }
    mutate();box.scrollTop=0;
    if(can){
      const ghost=tabGhost,a=animate(ghost,[{opacity:1,transform:'translateX(0)'},{opacity:0,transform:`translateX(${-dir*12}px)`}],180);
      a?.finished.then(()=>{ghost.remove();if(tabGhost===ghost)tabGhost=null;},()=>ghost.remove());
      animate(next,[{opacity:0,transform:`translateX(${dir*18}px)`},{opacity:1,transform:'translateX(0)'}],340);
    }
    sync();
  }
  function preview(key,mutate){
    if(!capable){mutate();return;}
    if(key===previewKey){mutate();sync();return;}previewKey=key;
    const generation=++previewRun,frame=byId('image-frame');
    const old=previewGhost||(!frame.classList.contains('hidden')?frame:null);
    let ghost=null;
    if(old&&visible(old)&&!reduced()){
      const r=rect(old);ghost=sanitize(old.cloneNode(true));ghost.classList.add('motion-preview-ghost');
      Object.assign(ghost.style,{position:'fixed',left:r.x+'px',top:r.y+'px',width:r.w+'px',height:r.h+'px',opacity:getComputedStyle(old).opacity,transform:'none'});
      document.body.append(ghost);
    }
    previewGhost?.remove();previewGhost=ghost;mutate();sync();
    if(!ghost)return;
    const images=[...frame.querySelectorAll('img:not(.hidden)')];
    let ended=false;
    const reveal=()=>{
      if(ended||generation!==previewRun)return;ended=true;
      const a=animate(ghost,[{opacity:getComputedStyle(ghost).opacity},{opacity:0}],300);
      if(a)a.finished.then(()=>{ghost.remove();if(previewGhost===ghost)previewGhost=null;},()=>ghost.remove());else{ghost.remove();previewGhost=null;}
    };
    Promise.all(images.map(img=>img.decode?img.decode().catch(()=>{}):Promise.resolve())).then(reveal);
    setTimeout(reveal,900);
  }
  function toast(text){
    const el=byId('toast'),wasVisible=!el.classList.contains('hidden');
    const from=capable&&wasVisible?{opacity:getComputedStyle(el).opacity,translate:getComputedStyle(el).translate}:{opacity:0,translate:'0 10px'};
    el.textContent=text;el.classList.remove('hidden');clearTimeout(toastTimer);
    if(capable)animate(el,[from,{opacity:1,translate:'0 0'}],280);
    toastTimer=setTimeout(()=>{
      if(!capable||reduced()){el.classList.add('hidden');return;}
      const a=animate(el,[{opacity:1,translate:'0 0'},{opacity:0,translate:'0 6px'}],180);
      a?.finished.then(()=>el.classList.add('hidden'),()=>{});
    },5500);
  }
  function updatePreference(value){
    preference=value==='reduce'?'reduce':'auto';try{localStorage.setItem('workbench-motion',preference);}catch(_){}
    preferenceChanged();
  }
  function preferenceChanged(){
    if(!capable)return;document.documentElement.dataset.motion=reduced()?'reduce':'full';
    if(reduced()){
      for(const a of animations.values())a.cancel();animations.clear();
      previewRun++;previewGhost?.remove();previewGhost=null;tabGhost?.remove();tabGhost=null;
      for(const r of records.values())settle(r);
    }
    if(typeof WorkbenchControls!=='undefined')WorkbenchControls.settle();
    sync();
  }
  function init(){
    if(!capable||initialized)return;initialized=true;preferenceChanged();
    media?.addEventListener('change',preferenceChanged);
    addEventListener('resize',()=>{
      for(const rec of records.values())if(rec.state.phase!=='closed')drive(rec,isOpen(rec.id),false,true);
      sync();
    });
    document.addEventListener('visibilitychange',()=>{if(document.hidden)for(const rec of records.values())settle(rec);});
    document.addEventListener('keydown',e=>{
      if(e.key==='Tab'&&innerWidth<800&&isOpen('inspector')&&!document.querySelector('dialog[open]')){
        const root=byId(isOpen('style-menu')?'style-menu':'inspector');
        const stops=[...root.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),summary,[tabindex="0"]')].filter(el=>visible(el)&&!el.closest('.hidden,[hidden],[inert]')&&el.tabIndex>=0);
        const first=stops[0],last=stops[stops.length-1];
        if(stops.length&&(!root.contains(document.activeElement)||e.shiftKey&&document.activeElement===first||!e.shiftKey&&document.activeElement===last)){e.preventDefault();(e.shiftKey?last:first).focus();}
      }
      if(e.key!=='Escape'||document.querySelector('dialog[open]'))return;
      const id=isOpen('style-menu')?'style-menu':isOpen('inspector')?'inspector':null;
      if(!id)return;e.preventDefault();e.stopImmediatePropagation();
      if(id==='inspector')setPanel(false);else close(id);
    },true);
    document.querySelector('.panel-tabs').addEventListener('keydown',e=>{
      if(!['ArrowRight','ArrowLeft','Home','End'].includes(e.key))return;e.preventDefault();
      const buttons=[...document.querySelectorAll('[data-tab]')].filter(b=>!b.disabled),i=buttons.indexOf(document.activeElement);
      const n=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowRight'?1:-1)+buttons.length)%buttons.length;
      if(buttons[n]){tab(buttons[n].dataset.tab);buttons[n].focus();}
    });
    byId('style-menu').addEventListener('keydown',e=>{
      if(!['ArrowDown','ArrowUp','Home','End'].includes(e.key))return;e.preventDefault();
      const buttons=[...byId('style-menu').querySelectorAll('button')],i=buttons.indexOf(document.activeElement);
      const n=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;
      buttons[n]?.focus();
    });
  }
  function inspect(){return [...records.values()].map(r=>({id:r.id,phase:r.state.phase,progress:r.state.p,source:r.source.id,origin:{...r.origin},target:{...r.target},frame:{...r.frame},generation:r.state.generation}));}
  return {math:{clamp,smooth,mix,bezier,State},open,close,isOpen,panel,tabs,preview,sync,toast,init,inspect,reduced,updatePreference,get preference(){return preference;}};
})();
/* END SHARED MOTION */
const $ = id => document.getElementById(id);
const $$ = sel => [...document.querySelectorAll(sel)];
function layoutForWidth(width){return width>=800?'overlay-wide':'overlay-compact';}
const S = {token:'', styles:[], inputs:[], jobs:[], mode:'photo', current:null, selected:new Set(), edits:new Map(), previews:new Map(), playbacks:new Map(), resultPlaybacks:new Map(), playbackFailures:new Set(), processed:new Set(), override:null, view:'original', picking:false, panel:false, subjectPicking:false, videoBatch:false, videoSelected:new Set(), batchQueue:[], batchSubmitting:false, developSubmitting:false, developCanceled:false, batchPlanned:0, batchRunJobs:new Set(), removing:false, capabilities:{}, layout:layoutForWidth(innerWidth), libraryFilter:'', config:{}, audio:false, connected:false, uploading:false, zoom:1};
const terminal = j => ['done','error','canceled','interrupted'].includes(j.status);
const current = () => S.inputs.find(i=>i.id===S.current);
const photos = () => S.inputs.filter(i=>i.kind==='photo');
const visibleInputs = () => S.inputs.filter(i=>i.kind===(S.mode==='video'?'video':'photo'));
function filteredInputs(){const query=S.libraryFilter.trim().toLocaleLowerCase();return visibleInputs().filter(i=>!query||i.name.toLocaleLowerCase().includes(query));}
const make = (tag, cls, text) => { const e=document.createElement(tag); if(cls)e.className=cls; if(text!==undefined)e.textContent=text; return e; };
function icon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');const use=document.createElementNS(svg.namespaceURI,'use');use.setAttribute('href','#i-'+name);svg.append(use);return svg;}
function toast(text){Motion.toast(text);}
function show(id,source){const origins={'history-dialog':'history-btn','export-dialog':'export-btn','monitor-dialog':'monitor-btn','more-dialog':'more-btn','settings-dialog':'open-settings','library-dialog':'library-btn'};return Motion.open(id,source||origins[id]);}

const RES_DEFAULTS={subjectInputLongEdge:1024,subjectOutputLongEdge:2048,colorLongEdge:null,blurLongEdge:null,depthLongEdge:1220,batchLongEdge:null,videoLongEdge:null};
const RES_LABELS={subjectInputLongEdge:'主体识别源尺寸',subjectOutputLongEdge:'主体遮罩细化尺寸',colorLongEdge:'调色工作长边',blurLongEdge:'虚化工作长边',depthLongEdge:'深度分析长边（默认1220）',batchLongEdge:'批量工作长边',videoLongEdge:'视频工作长边'};
function defaultEdit(){return {style:'leica-instant',hdrEnabled:false,colorEnabled:true,blurEnabled:false,smartFocus:false,aperture:2.8,point:null,crop:null,suggestions:[],depth:null,subjectEnabled:false,subjectConcurrency:1,subjectPoints:[],subject:null,resolutions:{...RES_DEFAULTS},dirty:false};}
// Only JSON-shaped editor records and presets, never images or browser handles.
function copyEditSnapshot(value){return typeof structuredClone==='function'?structuredClone(value):JSON.parse(JSON.stringify(value));}
const batchMode=()=>S.mode==='batch'||S.mode==='video'&&S.videoBatch;
const selectedSet=()=>S.mode==='video'?S.videoSelected:S.selected;
function selectionIds(){return batchMode()?visibleInputs().filter(i=>selectedSet().has(i.id)).map(i=>i.id):[S.current].filter(Boolean);}
function depthKey(e,id=S.current){const item=S.inputs.find(i=>i.id===id);if(!item)return '';let {width:w,height:h}=item;const edge=Math.max(w,h),limit=e.resolutions?.depthLongEdge===undefined?RES_DEFAULTS.depthLongEdge:e.resolutions.depthLongEdge;if(limit!==null&&limit<edge){w=Math.max(1,Math.floor((w*limit+Math.floor(edge/2))/edge));h=Math.max(1,Math.floor((h*limit+Math.floor(edge/2))/edge));}return `${w}x${h}`;}
function validDepth(e=edit(),id=S.current){return !!e.depth&&e.depthKey===depthKey(e,id);}
function spaceGrid(id=S.current){const e=edit(id);if(validDepth(e,id))return e.depthData;return [...S.jobs].reverse().find(j=>j.inputId===id&&j.status==='done'&&j.progress?.visuals?.grid&&depthKey({resolutions:j.options?.resolutions},id)===depthKey(e,id))?.progress.visuals.grid;}
function protectionKey(e){return JSON.stringify([e.subjectPoints,e.resolutions.subjectInputLongEdge,e.resolutions.subjectOutputLongEdge]);}
function validProtection(e=edit()){return e.subject&&e.subject.key===protectionKey(e)?e.subject:null;}
function optionsFor(e,action,batch=false,id=S.current){const video=action.startsWith('video');return {hdrEnabled:action==='photo'&&!!e.hdrEnabled&&!!S.inputs.find(i=>i.id===id)?.hdrSource,style:usableStyle(e.style,video),colorEnabled:video?true:e.colorEnabled,blurEnabled:['depth','subject','composition','video-playback'].includes(action)?false:!!e.blurEnabled,smartFocus:['photo','video','video-preview'].includes(action)&&!!e.smartFocus,aperture:e.aperture,point:e.smartFocus?null:e.point,crop:video?null:e.crop,subjectEnabled:video?false:e.subjectEnabled,subjectConcurrency:video?1:(e.subjectConcurrency??1),subjectPoints:video?[]:e.subjectPoints.map(p=>({...p})),resolutions:{...RES_DEFAULTS,...e.resolutions},batch};}
function parameterKey(o){return JSON.stringify([o.hdrEnabled??false,o.style,o.colorEnabled,o.blurEnabled,o.smartFocus??false,o.aperture,o.smartFocus?null:o.point,o.crop,o.subjectEnabled??false,o.subjectPoints??[],{...RES_DEFAULTS,...o.resolutions}]);}
function renderResolutions(){
 const root=$('resolution-controls');
 if(!root.children.length)for(const [key,text] of Object.entries(RES_LABELS)){
  const row=make('div','resolution-row'),label=make('label','',text),input=make('input','resolution-value'),unlimited=make('label','resolution-unlimited'),check=make('input'),scrub=make('button','resolution-scrub','↔ 拖动逐像素调节');
  input.id='res-'+key;input.type='number';input.min='512';input.step='1';input.setAttribute('aria-label',text+'（px）');label.htmlFor=input.id;check.type='checkbox';check.classList.add('studio-check');check.id='unlimited-'+key;unlimited.append(check,document.createTextNode('不限制'));scrub.type='button';scrub.setAttribute('aria-label',text+'连续调节；方向键逐像素调节');
  const commit=value=>{edit().resolutions[key]=value;dirty();};
  input.addEventListener('change',()=>{const value=Number(input.value);if(!Number.isSafeInteger(value)||value<512){input.setCustomValidity('请输入至少512的整数');input.reportValidity();return;}input.setCustomValidity('');commit(value);});
  check.addEventListener('change',()=>{input.setCustomValidity('');commit(check.checked?null:Math.max(512,Number(input.value)||RES_DEFAULTS[key]||2048));});
  scrub.addEventListener('keydown',ev=>{if(['ArrowLeft','ArrowDown','ArrowRight','ArrowUp','Home'].includes(ev.key)){ev.preventDefault();const direction=['ArrowLeft','ArrowDown'].includes(ev.key)?-1:1;commit(ev.key==='Home'?512:Math.max(512,(edit().resolutions[key]||2048)+direction*(ev.shiftKey?100:1)));}});
  let drag=null;scrub.addEventListener('pointerdown',ev=>{if(ev.button!==0)return;scrub.setPointerCapture(ev.pointerId);drag={x:ev.clientX,value:edit().resolutions[key]||2048};});
  scrub.addEventListener('pointermove',ev=>{if(!drag)return;const value=Math.max(512,Math.round(drag.value+ev.clientX-drag.x));edit().resolutions[key]=value;input.value=value;input.disabled=false;check.checked=false;edit().dirty=true;});
  const end=()=>{if(drag){drag=null;dirty();}};scrub.addEventListener('pointerup',end);scrub.addEventListener('pointercancel',end);
  row.append(label,input,scrub,unlimited);root.append(row);
 }
 for(const key of Object.keys(RES_LABELS)){const input=$('res-'+key),unlimited=$('unlimited-'+key),value=edit().resolutions[key];if(document.activeElement!==input||input.validity.valid){input.value=value??(RES_DEFAULTS[key]||2048);input.setCustomValidity('');}input.disabled=value===null;unlimited.checked=value===null;}
}
function commitSubjectConcurrency(input){
 const value=Number(input.value);
 if(!Number.isSafeInteger(value)||value<1){input.setCustomValidity('请输入1或更大的整数；数值过大时请调低');input.reportValidity();return false;}
 input.setCustomValidity('');edit().subjectConcurrency=value;rememberParameters();persist();renderSubject();return true;
}
function renderSubject(){const e=edit(),photo=current()?.kind==='photo',busy=pending().length>0,p=validProtection(e);
 const input=$('subject-concurrency');
 if(document.activeElement!==input||input.validity?.valid){input.value=e.subjectConcurrency??1;input.setCustomValidity('');}
 input.disabled=!photo||busy||!S.capabilities.subjectConcurrency;
 $('subject-concurrency-note').textContent=S.capabilities.subjectConcurrency?(S.capabilities.subjectPointCache?'默认1，不设固定最大值；实际并发不超过本次需要计算的保护点数。内存不足会停止；调大不一定更快。':'当前服务不能分别复用每个保护点的结果，增删点可能需要全部重算；并发不超过全部不同保护点数，内存不足会停止。可使用新版服务入口；本会话与成片保留。'):'当前服务不支持设置保护点并发数。请使用新版服务入口；本会话与成片保留。';
 $('subject-enabled').checked=e.subjectEnabled;$('subject-enabled').disabled=!photo||!S.capabilities.subjectProtection;
 $('pick-subject').disabled=!photo||busy;$('pick-subject').classList.toggle('active',S.subjectPicking);$('finish-subject').disabled=!S.subjectPicking;
 $('compute-subject').disabled=!photo||busy||!e.subjectPoints.length||!S.capabilities.subjectProtection;$('clear-subject').disabled=!e.subjectPoints.length||busy;
 const root=$('subject-points'),signature=JSON.stringify([S.current,e.subjectPoints]);if(root.dataset.points!==signature){root.dataset.points=signature;root.replaceChildren();e.subjectPoints.forEach((point,n)=>{const b=make('button','subject-point',`${n+1} · 删除`);b.disabled=busy;b.setAttribute('aria-label',`删除保护点${n+1}`);b.addEventListener('click',()=>{edit().subjectPoints.splice(n,1);dirty();});root.append(b);});}for(const b of root.children)b.disabled=busy;
 $('subject-hint').textContent=!S.capabilities.subjectProtection?'本机缺少主体保护模型，暂时无法计算保护区。':p?`已计算 ${p.protection.points} 个保护点 · 保护区 ${p.protection.maskSize.join(' × ')}${p.protection.cached?' · 已复用计算结果':''}`:e.subject?'保护点或尺寸已更改，请重新计算保护区。上次结果保留，但不会用于新设置。':'选点后统一计算；保护点与焦点独立。启用景深时保护主体。';
 $('end-picking').classList.toggle('hidden',!S.picking&&!S.subjectPicking);renderResolutions();
}
function sharedParameters(e){return {hdrEnabled:!!e.hdrEnabled,style:e.style,colorEnabled:e.colorEnabled,blurEnabled:e.blurEnabled,smartFocus:!!e.smartFocus,aperture:e.aperture,subjectEnabled:e.subjectEnabled,subjectConcurrency:e.subjectConcurrency??1,resolutions:{...e.resolutions}};}
// PhotoFlow.photoLoaded retains workflow/style/aperture/resolution; geometry belongs to each photo.
const sharedEdits={photo:null,video:null};
function rememberParameters(){if(S.current)sharedEdits[current()?.kind||'photo']=sharedParameters(edit());}
function inheritParameters(id,template){const e=edit(id);if(template)Object.assign(e,copyEditSnapshot(template));const last=latest(id);if(last)e.dirty=parameterKey(last.options)!==parameterKey(optionsFor(e,last.action,!!last.options.batch,id));return e;}
function edit(id=S.current){if(!S.edits.has(id))S.edits.set(id,defaultEdit());return S.edits.get(id);}
let workspaceSaveTimer=null,workspaceSaveChain=Promise.resolve();
function workspaceState(){return {current:S.current,mode:S.mode,view:S.view,override:S.override,sharedEdits,edits:[...S.edits].filter(([id])=>S.inputs.some(i=>i.id===id)).map(([id,e])=>[id,{...sharedParameters(e),point:e.point,crop:e.crop,subjectPoints:e.subjectPoints,dirty:e.dirty}])};}
function persist(){const state=workspaceState();try{sessionStorage.setItem('leica-workspace',JSON.stringify({...state,sessionId:S.sessionId}));}catch(_){}if(!S.capabilities.sessionRecovery)return;clearTimeout(workspaceSaveTimer);workspaceSaveTimer=setTimeout(()=>{workspaceSaveChain=workspaceSaveChain.catch(()=>{}).then(()=>api('/api/workspace',state)).catch(()=>toast('工作区参数尚未保存，请检查连接；已完成计算存档不会清除。'));},350);} 
function restore(saved=null){try{const cached=JSON.parse(sessionStorage.getItem('leica-workspace')||'{}');const old=saved&&Object.keys(saved).length?saved:(!cached.sessionId||cached.sessionId===S.sessionId?cached:{});Object.assign(sharedEdits,old.sharedEdits||{});for(const [id,e] of old.edits||[]){if(S.inputs.some(i=>i.id===id))S.edits.set(id,{...defaultEdit(),...e,resolutions:{...RES_DEFAULTS,...e.resolutions}});}if(['photo','video','batch'].includes(old.mode))S.mode=old.mode;if(S.inputs.some(i=>i.id===old.current))S.current=old.current;}catch(e){}}
async function api(path,body){const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:body===undefined?{}:{'Content-Type':'application/json','X-Workbench-Token':S.token},body:body===undefined?undefined:JSON.stringify(body)});const data=await r.json();if(!r.ok||data.ok===false)throw Error(data.error||'操作未完成');return data;}
function setPanel(open,instant=false){const r=$('params-btn').getBoundingClientRect?.(),origin=r?{x:r.x,y:r.y,w:r.width,h:r.height}:null;S.panel=open;$('workspace').classList.toggle('panel-open',open);$('params-btn').classList.toggle('active',open);$('params-btn').setAttribute('aria-expanded',String(open));$('inspector').setAttribute('role',innerWidth<800?'dialog':'complementary');if(innerWidth<800&&open)$('inspector').setAttribute('aria-modal','true');else $('inspector').removeAttribute('aria-modal');Motion.panel(open,instant,origin);fitImage();}
function tab(name,reveal=true){if(!['process','sizes'].includes(name))return;Motion.tabs(name,()=>{$$('[data-tab]').forEach(b=>{const active=b.dataset.tab===name;b.classList.toggle('active',active);b.setAttribute('aria-selected',String(active));b.tabIndex=active?0:-1;});$$('.tab-content:not(.motion-tab-ghost)').forEach(e=>e.classList.toggle('hidden',e.id!=='panel-'+name));});if(reveal)setPanel(true);}
function latest(id=S.current){if(S.override){const job=S.jobs.find(j=>j.id===S.override);if(job&&job.inputId===id)return job;}return [...S.jobs].reverse().find(j=>j.inputId===id&&j.status==='done'&&['photo','video'].includes(j.action));}
function pending(id=S.current){return S.jobs.filter(j=>j.inputId===id&&!terminal(j));}
function result(){return latest()?.result;}
function dirty(){edit().dirty=true;rememberParameters();S.override=null;S.picking=false;S.subjectPicking=false;S.view=S.mode==='video'?'play':'original';persist();render();}
function setCurrent(id){rememberParameters();S.current=id;inheritParameters(id,sharedEdits[current()?.kind||'photo']);S.override=null;S.picking=false;S.subjectPicking=false;S.zoom=1;const item=current();S.view=item?.kind==='video'?(completedVideoSource(latest(id)?.result)?'compare':'play'):(latest(id)?'result':'original');render();for(const card of $('film-items').children)if(card.dataset.inputId===id)card.scrollIntoView?.({block:'nearest',inline:'nearest',behavior:'auto'});persist();}

function setMode(mode){rememberParameters();S.mode=mode;S.picking=false;S.subjectPicking=false;S.override=null;S.libraryFilter='';$('film-search').value='';S.selected=new Set([...S.selected].filter(id=>photos().some(i=>i.id===id)));S.videoSelected=new Set([...S.videoSelected].filter(id=>S.inputs.some(i=>i.id===id&&i.kind==='video')));const list=visibleInputs();if(!list.some(i=>i.id===S.current))S.current=list[0]?.id||null;if(S.current)inheritParameters(S.current,sharedEdits[current()?.kind||'photo']);if(batchMode()&&!selectedSet().size)list.forEach(i=>selectedSet().add(i.id));S.view=mode==='video'?'play':(latest()?'result':'original');if(mode==='video')tab('process',false);render();persist();}

function availableStyles(video=S.mode==='video'){return video?S.styles.filter(s=>(S.capabilities.videoStyles||[]).includes(s.id)):S.styles;}
function usableStyle(style,video){const options=availableStyles(video);return options.some(s=>s.id===style)?style:(video?'leica-n-instant':'leica-instant');}
function styleMenu(){const options=availableStyles();const menu=$('style-menu'),signature=JSON.stringify([options,edit().style]);if(styleMenu.signature===signature)return;styleMenu.signature=signature;menu.replaceChildren();for(const s of options){const b=make('button','style-option'+(s.id===edit().style?' selected':''));b.setAttribute('role','menuitemradio');b.setAttribute('aria-checked',String(s.id===edit().style));b.append(make('strong','',s.name));if(s.id===edit().style)b.append(icon('check'));b.addEventListener('click',()=>{edit().style=s.id;Motion.close('style-menu');dirty();});menu.append(b);}}
function fact(parent,label,value){const row=make('div','fact');row.append(make('span','',label),make('strong','',String(value)));parent.append(row);}
function styleName(id){return S.styles.find(s=>s.id===id)?.name||id;}
function renderFacts(){const box=$('file-facts'),item=current(),signature=JSON.stringify([S.current,latest()?.id]);if(renderFacts.signature===signature)return;renderFacts.signature=signature;box.replaceChildren();box.classList.toggle('empty-facts',!item);if(!item){box.textContent='尚未导入素材';return;}fact(box,'文件',item.name);fact(box,'工作尺寸',`${item.width} × ${item.height}`);if(item.kind==='photo'){if(item.downscaled){fact(box,'原尺寸',item.originalSize.join(' × '));fact(box,'工作图', '已按像素上限缩小');}else fact(box,'分辨率',((item.width*item.height)/1e6).toFixed(2)+' MP');}else{fact(box,'时长 / 帧率',`${clock(item.durationSeconds)} / ${item.fps} fps`);fact(box,'总帧数',item.totalFrames);}const job=latest();if(job)fact(box,'上次显影',`${job.result.width} × ${job.result.height} · ${job.elapsedSeconds}s`);}
function renderFilm(){
  const list=filteredInputs(),all=visibleInputs(),root=$('film-items'),signature=JSON.stringify([S.mode,S.libraryFilter,list.map(i=>[i.id,i.previewUrl,latest(i.id)?.id])]);
  $('film-count').textContent=String(all.length);$('select-all').classList.toggle('hidden',!batchMode());
  $('library-kind').textContent=S.mode==='video'?'视频':S.mode==='batch'?'批量选择':'照片';
  $('library-selection-count').textContent=batchMode()?selectedSet().size+' 已选':'';
  $('library-empty').classList.toggle('hidden',!!list.length);root.classList.toggle('hidden',!list.length);
  $('library-empty').querySelector('strong').textContent=all.length?'没有匹配的素材':S.mode==='video'?'还没有视频':'还没有照片';
  if(renderFilm.signature!==signature){
    renderFilm.signature=signature;root.replaceChildren();
    for(const item of list){
      const card=make('button','film-card');card.dataset.inputId=item.id;card.title=item.name;card.setAttribute('aria-label',item.name);
      const thumb=make('span','film-thumb');
      if(item.previewUrl){const img=make('img');img.src=item.previewUrl;img.alt='';thumb.append(img);}else thumb.append(icon('play'));
      const copy=make('span','film-copy');copy.append(make('strong','film-label',item.name),make('small','film-meta',`${item.width} × ${item.height}${item.kind==='video'?' · '+clock(item.durationSeconds):''}`));
      if(latest(item.id))copy.append(make('span','done-mark','已显影'));
      card.append(thumb,copy);if(batchMode())card.append(make('span','select-mark',''));
      card.addEventListener('click',()=>{if(batchMode()){if(selectedSet().has(item.id))selectedSet().delete(item.id);else selectedSet().add(item.id);}setCurrent(item.id);});root.append(card);
    }
  }
  for(const card of root.children){const id=card.dataset.inputId;card.classList.toggle('active',id===S.current);card.classList.toggle('selected',selectedSet().has(id));card.setAttribute('aria-pressed',String(batchMode()?selectedSet().has(id):id===S.current));const mark=card.querySelector('.select-mark');if(mark)mark.textContent=selectedSet().has(id)?'✓':'';}
  $('select-all').textContent=all.length&&all.every(i=>selectedSet().has(i.id))?'取消全选':'全选';
}
function renderPresets(){
  const root=$('style-presets'),signature=JSON.stringify(S.styles);
  if(renderPresets.signature!==signature){
    renderPresets.signature=signature;root.replaceChildren();
    for(const s of S.styles){
      const b=make('button','preset-card');b.dataset.style=s.id;b.setAttribute('aria-label',s.name);
      const swatch=make('span','preset-swatch');swatch.style.setProperty('--preset-tone',s.tone);swatch.setAttribute('aria-hidden','true');
      const copy=make('span','preset-copy');copy.append(make('strong','',s.name),make('small','',s.desc));
      b.append(swatch,copy,icon('check'));b.addEventListener('click',()=>{if(edit().style===s.id)return;edit().style=s.id;dirty();});root.append(b);
    }
  }
  for(const b of root.children){const active=b.dataset.style===edit().style;b.classList.toggle('active',active);b.setAttribute('aria-pressed',String(active));b.querySelector('svg').style.visibility=active?'visible':'hidden';}
}
function renderCrops(){
  const root=$('crop-options'),e=edit(),signature=JSON.stringify([S.current,e.suggestions]);
  if(renderCrops.signature!==signature){
    renderCrops.signature=signature;root.replaceChildren();
    e.suggestions.forEach((s,index)=>{const b=make('button','crop-card');b.dataset.index=String(index);const shape=make('span','crop-shape');shape.style.height=Math.max(12,Math.min(35,32*s.height/s.width))+'px';const copy=make('span');copy.append(make('strong','',s.original?'保留原构图':'推荐方案 '+(index+1)),make('small','',`${s.width} × ${s.height}${s.original?'':' · 评分提升 '+s.gain.toFixed(2)}`));b.append(shape,copy);b.addEventListener('click',()=>{const now=edit(),proposal=now.suggestions[index];if(proposal){now.crop=proposal.original?null:proposal.crop;dirty();}});root.append(b);});
  }
  for(const b of root.children){const s=e.suggestions[Number(b.dataset.index)];b.classList.toggle('active',(s.original&&!e.crop)||JSON.stringify(s.crop)===JSON.stringify(e.crop));}
}
// Manual video focus is source-specific geometry, never part of shared parameters.
let videoFocusDraft=false,videoFocusDraftKey=null;
function renderVideoManualFocus(){
 const e=edit(),video=S.mode==='video',supported=!!S.capabilities.videoManualFocus,key=String(S.current)+':'+S.mode;
 if(videoFocusDraftKey!==key){videoFocusDraft=false;videoFocusDraftKey=key;}
 $('video-manual-focus').classList.toggle('hidden',!video||!!e.smartFocus);
 for(const axis of ['x','y']){const input=$('video-focus-'+axis);input.disabled=!supported;if(!videoFocusDraft){input.value=e.point?String(Number((e.point[axis]*100).toFixed(6))):'';input.setCustomValidity('');}}
 $('pick-video-focus').disabled=!supported||!current();$('clear-video-focus').disabled=!e.point;
 $('video-focus-label').textContent=!supported?'此功能暂不可用，请重新打开工作台':e.point?`固定焦点 ${(e.point.x*100).toFixed(1)}% / ${(e.point.y*100).toFixed(1)}%`:'尚未设置手动焦点';
}
function saveVideoFocusPoint(point){const chosenView=S.view;edit().point={...point};edit().smartFocus=false;videoFocusDraft=false;dirty();S.view=chosenView;renderPreview();}
function commitVideoFocus(report=true){
 const point={};let valid=true;
 for(const axis of ['x','y']){const input=$('video-focus-'+axis),raw=String(input.value).trim(),value=Number(raw),ok=raw!==''&&Number.isFinite(value)&&value>=0&&value<=100;input.setCustomValidity(ok?'':'焦点坐标须为0到100之间的数值，不能留空。');valid=ok&&valid;point[axis]=value/100;}
 if(!valid){if(report)for(const axis of ['x','y'])if(!$('video-focus-'+axis).reportValidity())break;return false;}
 saveVideoFocusPoint(point);return true;
}
function startVideoFocusPicking(){
 if(S.mode!=='video'||!current()||!S.capabilities.videoManualFocus)return;
 const player=$('video-player'),hasFrame=S.previews.has(S.current),canPlay=player.videoWidth>0&&player.videoHeight>0&&player.readyState>=2&&!S.playbackFailures.has(S.current);
 if(!hasFrame&&!canPlay){toast('原片暂不可点选；可直接填写横纵坐标，或先关闭虚化并明确点击抽帧预览，再在原图上选焦。');return;}
 player.pause();S.picking=true;S.subjectPicking=false;S.view=hasFrame?'original':'play';render();setPanel(false);toast('点击原片画面中的目标内部设置固定焦点；不会追踪主体或自动显影。');
}
function renderParams(){const e=edit();e.style=usableStyle(e.style,S.mode==='video');const s=availableStyles().find(s=>s.id===e.style);if(s){$('style-name').textContent=s.name;$('pipeline-style').textContent=s.name;$('style-tone').style.background=s.tone;}$('color-enabled').checked=S.mode==='video'||e.colorEnabled;$('blur-enabled').checked=e.blurEnabled;$('blur-row').classList.toggle('hidden',S.mode==='video'&&!S.capabilities.videoSmartFocus&&!S.capabilities.videoManualFocus);$('smart-focus').checked=!!e.smartFocus;$('smart-focus').disabled=!(S.mode==='video'?S.capabilities.videoSmartFocus:S.capabilities.smartFocus)&&!e.smartFocus;$('smart-focus-note').textContent=!(S.mode==='video'?S.capabilities.videoSmartFocus:S.capabilities.smartFocus)?'智能焦点暂不可用，请重新打开工作台。':S.mode==='video'?'仅选择对焦方式，不联动虚化开关；虚化开启时逐帧评分选焦，不裁切、不追踪。关闭后使用明确设置的固定手动焦点。':'仅选择对焦方式，不联动虚化开关；虚化开启时在最高分框内优先选人脸眼线，无脸取框中心，批量逐张计算。';$('go-compose').classList.toggle('hidden',S.mode==='video');$('video-settings').classList.toggle('hidden',S.mode!=='video');$$('[data-tab]').forEach(b=>b.disabled=S.mode==='video'&&!['process','sizes'].includes(b.dataset.tab));$('color-enabled').disabled=S.mode==='video';$('color-mode-note').classList.toggle('hidden',S.mode!=='video');$('crop-summary').textContent=e.crop?'已选裁切区域':'保留原构图';$('focus-label').textContent=e.smartFocus?'智能焦点 · 显影时自动分析':e.point?`焦点 ${(e.point.x*100).toFixed(1)}% / ${(e.point.y*100).toFixed(1)}%`:'尚未设置焦点';$('pick-focus').classList.toggle('active',S.picking);$('pick-focus').disabled=!current();$('clear-focus').disabled=!e.point;$('analyse-depth').disabled=!current()||pending().length>0;$('analyse-compose').disabled=!current()||pending().length>0;const ap=$('apertures');if(!ap.children.length)[.95,1.8,2,2.8,4,5.6].forEach(v=>{const b=make('button','','ƒ/'+(Number.isInteger(v)?v.toFixed(1):v));b.dataset.aperture=String(v);b.addEventListener('click',()=>{edit().aperture=v;dirty();});ap.append(b);});for(const b of ap.children){b.classList.toggle('active',Number(b.dataset.aperture)===e.aperture);b.setAttribute('aria-pressed',String(Number(b.dataset.aperture)===e.aperture));}$('depth-hint').textContent=validDepth(e)?'深度已生成，可切换「深度」视图查看。':e.depth?'深度分析尺寸已更改，旧深度不用于新参数；点击分析后才计算。':'深度图仅用于可视化，不作为成片导出。';$('audio-note').textContent=S.audio?'已检测到 FFmpeg，将尝试同步原音轨。请试听导出结果。':'未检测到 FFmpeg：视频将导出为无声文件。';renderFacts();renderCrops();styleMenu();renderSubject();$('video-batch-enabled').checked=S.videoBatch;renderVideoManualFocus();if(typeof renderFocusConcurrency==='function')renderFocusConcurrency();if(typeof WorkspaceFeatures!=='undefined')WorkspaceFeatures.syncHdr();}

function playbackSource(item=current()){return S.playbacks.get(item?.id)?.playbackUrl||item?.videoUrl||'';}
async function compatiblePlayback(){const item=current();if(item?.kind!=='video'||pending().length)return;if(!S.capabilities.videoPlaybackPreview){toast('当前服务不支持兼容播放预览；仍可抽帧或下载。');return;}if(S.playbacks.get(item.id)?.previewVersion===2){S.view='play';render();return;}try{await submit('video-playback');}catch(e){toast(e.message);}}
let videoComparison=null;
function completedVideoSource(r=result()){if(!r?.downloadUrl)return '';const job=S.jobs.find(j=>j.action==='video'&&j.result?.downloadUrl===r.downloadUrl),proxy=job&&S.resultPlaybacks.get(job.id);if(proxy?.playbackUrl)return proxy.playbackUrl;const [url,query='']=r.downloadUrl.split('?'),params=new URLSearchParams(query);params.delete('download');return url+(params.size?'?'+params:'');}
let resultPlaybackSubmitting=false,resultPlaybackSubmittingId=null,resultPlaybackTimer=null;
const resultPlaybackAttempts=new Set(),resultPlaybackErrors=new Map();
function resultPlaybackJob(id){return [...S.jobs].reverse().find(j=>j.action==='video-playback'&&j.sourceJobId===id);}
// Only the selected completed version may be converted. Failed attempts need an explicit retry.
function queueResultPlayback(){
 const key=videoComparison?.key;if(resultPlaybackTimer!==null)return;
 resultPlaybackTimer=setTimeout(()=>{resultPlaybackTimer=null;const job=latest();if(!videoComparison?.active||!videoComparison.afterFailed||job?.id!==key||videoComparison.key!==key||!['compare','result'].includes(S.view))return;return prepareResultPlayback({automatic:true});},0);
}
async function prepareResultPlayback({automatic=false}={}){
 const job=latest(),attempt=job&&resultPlaybackJob(job.id);
 if(!job||job.action!=='video'||job.status!=='done'||libraryBusy()||resultPlaybackSubmitting||!S.capabilities.videoResultPlaybackPreview||(S.resultPlaybacks.get(job.id)?.previewVersion===2)||attempt&&!terminal(attempt))return;
 if(automatic&&(S.resultPlaybacks.has(job.id)||attempt||resultPlaybackAttempts.has(job.id)))return;
 resultPlaybackAttempts.add(job.id);resultPlaybackErrors.delete(job.id);resultPlaybackSubmitting=true;resultPlaybackSubmittingId=job.id;renderVideoComparison(mediaInfo());
 try{const response=await api('/api/jobs',{inputId:job.inputId,action:'video-playback',sourceJobId:job.id});if(!S.jobs.some(j=>j.id===response.job.id))S.jobs.push(response.job);render();pollSoon();if(!automatic)toast('仅转换所选完整成片供兼容播放，不重新显影、不替换MP4下载。');}
 catch(error){resultPlaybackErrors.set(job.id,error.message);toast('兼容预览未能准备：'+error.message+'；正式MP4保留，不会自动反复重试。');}
 finally{resultPlaybackSubmitting=false;resultPlaybackSubmittingId=null;renderVideoComparison(mediaInfo());}
}
function renderVideoComparison(info){
 if(!info?.completed){videoComparison?.hide();return;}
 const job=latest(),proxy=S.resultPlaybacks.get(job?.id),attempt=job&&resultPlaybackJob(job.id),preparing=resultPlaybackSubmittingId===job?.id||attempt&&!terminal(attempt);
 const error=resultPlaybackErrors.get(job?.id)||(attempt&&['error','canceled','interrupted'].includes(attempt.status)?attempt.error||'上次兼容预览未完成；可明确点击重试。':'');
 videoComparison?.show({key:job?.id,before:info.original,after:info.after,download:result().downloadUrl,compare:S.view==='compare',split:$('compare-range').value,canPreview:!!S.capabilities.videoResultPlaybackPreview&&!libraryBusy()&&!resultPlaybackSubmitting&&(!proxy||proxy.previewVersion!==2)&&!preparing,
   previewUpgrade:!!proxy&&proxy.previewVersion!==2,
  autoPreview:!!S.capabilities.videoResultPlaybackPreview&&!libraryBusy()&&!resultPlaybackSubmitting&&!proxy&&!preparing&&!error&&!resultPlaybackAttempts.has(job?.id),
     previewBlocked:!S.capabilities.videoResultPlaybackPreview?'本机没有可用的兼容预览编码器；请下载正式MP4查看。':libraryBusy()||resultPlaybackSubmitting?'当前正在处理其它任务；空闲后可生成兼容预览。':'',
previewPending:preparing?'正在为当前显影版本准备兼容播放预览，完成后自动显示；不重新显影，下载仍为原MP4。':'',previewError:error,
  compatibility:proxy?(proxy.notice||'当前为完整成片的兼容播放预览；仅供查看，下载仍为原完整MP4。'):''});
}
function mediaInfo(){const item=current(),r=result(),e=edit();if(!item)return null;if(S.mode==='video'){const p=S.previews.get(item.id);if(['result','compare'].includes(S.view)&&completedVideoSource(r))return {video:true,completed:true,w:r.width||item.width,h:r.height||item.height,original:playbackSource(item),after:completedVideoSource(r)};if(S.view==='play')return {video:true,w:item.width,h:item.height};if(!p)return null;return {w:p.width,h:p.height,original:'data:image/jpeg;base64,'+p.originalBase64,after:'data:image/jpeg;base64,'+p.gradedBase64};}if(S.view==='result'||S.view==='compare'){if(r)return {w:r.width,h:r.height,original:r.originalUrl,after:r.hdrDownloadUrl||r.previewUrl};S.view='original';}return {w:item.width,h:item.height,original:item.previewUrl,depth:validDepth(e)?'data:image/jpeg;base64,'+e.depth:null,subject:validProtection(e)?.overlayUrl};}
function fitImage(){const stage=$('stage'),info=mediaInfo(),frame=$(info?.completed?'video-compare-frame':'image-frame');stage.classList.toggle('is-zoomed',!info?.video&&S.zoom===100);if(!info||info.video&&!info.completed)return;const padding=innerWidth>=1600?64:innerWidth<600?28:innerWidth<800?36:48;const available={w:Math.max(10,stage.clientWidth-padding),h:Math.max(10,stage.clientHeight-padding)};let scale=Math.min(available.w/info.w,available.h/info.h);if(S.zoom===100&&!info.video)scale=1;const w=Math.max(1,Math.floor(info.w*scale))+'px',h=Math.max(1,Math.floor(info.h*scale))+'px';if(frame.style.width!==w)frame.style.width=w;if(frame.style.height!==h)frame.style.height=h;}
function renderPreview(){return Motion.preview(JSON.stringify([S.current,S.view,latest()?.id,S.previews.get(S.current)?.frameIndex,S.playbacks.get(S.current)?.playbackUrl,validProtection()?.overlayUrl]),renderPreviewNow);}
function paintComparePosition(){
 const range=$('compare-range'),value=Math.max(0,Math.min(100,Number(range.value)||0));
 range.value=String(value);range.setAttribute('aria-valuetext',`原片 ${value}% · 成片 ${100-value}%`);
 $('preview-result').style.clipPath=`inset(0 0 0 ${value}%)`;$('compare-line').style.left=value+'%';videoComparison?.setSplit(value);
}
function renderCompareControls(info){
 const range=$('compare-range'),active=!!info&&!!(info.completed||!info.video&&info.after)&&['compare','frame'].includes(S.view)&&!S.picking&&!S.subjectPicking;
 const frame=$(info?.completed?'video-compare-frame':'image-frame');
 if(range.parentNode!==frame){dragging=null;videoCompareDragging=null;frame.append(range);}
 if(!active){dragging=null;videoCompareDragging=null;}
 range.classList.toggle('hidden',!active);range.disabled=!active;
 for(const id of ['image-frame','video-compare-frame'])$(id).classList.toggle('is-comparing',active&&$(id)===frame);
 $('compare-line').classList.toggle('hidden',!active||!!info?.completed);
 document.querySelectorAll('.photo-side-label').forEach(label=>label.classList.toggle('hidden',!active||!!info?.completed));
 if(active)paintComparePosition();
}
function renderPreviewNow(){const item=current(),info=mediaInfo(),e=edit(),isVideo=S.mode==='video',r=result();renderVideoComparison(info);$('video-player').classList.toggle('picking',isVideo&&S.picking);$('empty-state').classList.toggle('hidden',!!item);$('empty-title').textContent=isVideo?'导入视频，延续光影':'导入照片，开始显影';$('empty-description').replaceChildren();$('empty-description').append(document.createTextNode(isVideo?'一次导入，预览任意时刻。五种风格贯穿每一帧。':'从本地导入原片，选择风格后显影。'));$('empty-import').replaceChildren(icon('plus'),document.createTextNode(isVideo?'选择视频':'选择照片'));$('format-hint').textContent=isVideo?'MP4 / MOV / AVI / MKV / WebM · 原片始终保留':'JPEG / PNG / WebP · 原片始终保留';$('timeline').classList.toggle('hidden',!isVideo||!item||!!info?.completed);$('stage-toolbar').classList.toggle('hidden',!item);$('image-frame').classList.toggle('hidden',!info||!!info.video);$('video-player').classList.toggle('hidden',!info?.video||!!info.completed);$('video-player').preload=info?.video&&!info.completed?'auto':'none';if(!info?.video||info.completed)$('video-player').pause();$('video-playback-note').classList.toggle('hidden',!info?.video||!!info.completed||!S.playbackFailures.has(item?.id));$('video-player').classList.toggle('decode-failed',!!info?.video&&S.playbackFailures.has(item?.id));$('preview-caption').classList.toggle('hidden',!item);if(info?.completed){$('preview-label').textContent=S.view==='compare'?'处理前 / 处理后 · 左右拖动对比':'已完成视频 · 处理后';$('dimensions').textContent=`${info.w} × ${info.h} · ${clock(item.durationSeconds)}`;fitImage();}if(info?.video&&!info.completed){const playback=S.playbacks.get(item.id);$('preview-label').textContent=playback?'播放原片 · 兼容预览（非成片）':'播放原片';$('dimensions').textContent=`${playback?.width||item.width} × ${playback?.height||item.height} · ${clock(item.durationSeconds)}`;if($('video-player').getAttribute('src')!==playbackSource(item)){$('video-player').src=playbackSource(item);}}if(info&&!info.video){const orig=$('preview-original'),after=$('preview-result'),depth=$('preview-depth');const source=(img,url)=>{if(img.getAttribute('src')!==url)img.src=url;};source(orig,info.original||'');if(info.after)source(after,info.after);const showAfter=!!info.after&&['result','compare','frame'].includes(S.view);after.classList.toggle('hidden',!showAfter);after.style.clipPath=['compare','frame'].includes(S.view)?`inset(0 0 0 ${$('compare-range').value}%)`:'none';if(info.depth)source(depth,info.depth);depth.classList.toggle('hidden',S.view!=='depth'||!info.depth);$('compare-line').classList.toggle('hidden',!['compare','frame'].includes(S.view));$('compare-line').style.left=$('compare-range').value+'%';$('dimensions').textContent=`${info.w} × ${info.h} · 屏幕预览`;$('preview-label').textContent={original:'原片',result:'显影成片',compare:'原片 / 显影成片',frame:'抽帧预览对比 · 非完整成片',depth:'原片深度 · 仅供分析',subject:'蓝色主体保护区 · 仅供检查'}[S.view]||'原片';const subject=$('preview-subject');if(info.subject)source(subject,info.subject);subject.classList.toggle('hidden',S.view!=='subject'||!info.subject);const markers=$('subject-markers');const markerSignature=JSON.stringify([S.current,S.view,e.subjectPoints]);if(markers.dataset.points!==markerSignature){markers.dataset.points=markerSignature;markers.replaceChildren();if(['original','subject'].includes(S.view))e.subjectPoints.forEach((p,n)=>{const marker=make('span','subject-marker',String(n+1));marker.style.left=p.x*100+'%';marker.style.top=p.y*100+'%';markers.append(marker);});}const focused=!!e.point&&!e.smartFocus&&S.view==='original';$('focus-marker').classList.toggle('hidden',!focused);if(focused){$('focus-marker').style.left=(e.point.x*100)+'%';$('focus-marker').style.top=(e.point.y*100)+'%';}const crop=$('crop-overlay');crop.classList.toggle('hidden',!e.crop||S.view!=='original');if(e.crop){const [l,t,rr,b]=e.crop;Object.assign(crop.style,{left:l*100+'%',top:t*100+'%',width:(rr-l)*100+'%',height:(b-t)*100+'%'});}$('image-frame').classList.toggle('picking',S.picking||S.subjectPicking);fitImage();}$$('[data-view]').forEach(b=>{const view=b.dataset.view;b.classList.toggle('active',view===S.view);b.setAttribute('aria-pressed',String(view===S.view));b.classList.toggle('hidden',(isVideo&&['depth','subject'].includes(view))||(!isVideo&&['play','frame'].includes(view))||(view==='frame'&&!(S.previews.get(item?.id)&&r?.downloadUrl)));b.disabled=((['result','compare'].includes(view)||isVideo&&['original','frame'].includes(view))&&!(isVideo?(r?.downloadUrl&&['result','compare'].includes(view)||S.previews.get(item?.id)):r))||(view==='depth'&&!validDepth(e))||(view==='subject'&&!validProtection(e));});$('fit-btn').disabled=!info||!!info.video;$('fit-btn').textContent=S.zoom===100?'100% · 适应':'适应画布';renderCompareControls(info);if(item&&isVideo){$('duration-label').textContent=clock(item.durationSeconds);$('time-label').textContent=clock(item.durationSeconds*Number($('video-position').value)/1000);$('video-preview').disabled=pending().length>0;$('compatible-playback').disabled=pending().length>0||!S.capabilities.videoPlaybackPreview||S.playbacks.get(item.id)?.previewVersion===2;}}

function renderButtons(){const ids=selectionIds(),busy=S.jobs.filter(j=>ids.includes(j.inputId)&&!terminal(j)),queued=S.batchQueue.length;
 $('develop-btn').disabled=!current()||S.uploading||S.developSubmitting||S.batchSubmitting||busy.length>0||queued>0||(batchMode()&&!ids.length);
 $('develop-btn').classList.toggle('busy',S.developSubmitting||busy.length>0||queued>0);$('develop-label').textContent=S.developSubmitting||busy.length||queued?'处理中':batchMode()?`显影 ${ids.length} ${S.mode==='video'?'段':'张'}`:S.mode==='video'?'显影视频':'显影';
 $('cancel-btn').classList.toggle('hidden',!busy.length&&!queued&&!S.developSubmitting);$('export-btn').disabled=batchMode()?!ids.some(id=>latest(id)):!result();$('history-count').textContent=S.jobs.length;
   syncMaterialControls();$('import-btn').disabled=S.uploading||S.removing;$('empty-import').disabled=S.uploading||S.removing;for(const id of ['video-preview','fallback-preview','compatible-playback'])$(id).disabled=S.mode!=='video'||!current()||S.uploading||pending().length>0||(id==='compatible-playback'&&(!S.capabilities.videoPlaybackPreview||S.playbacks.has(S.current)));
 $('selection-hint').textContent=batchMode()?`已选 ${ids.length} / ${visibleInputs().length}`:'本机处理 · 原片保留';
 if(busy.length||queued){const j=busy.find(j=>j.status==='running')||busy[0];$('task-message').textContent=`${j?.phase||'准备批量'} · ${j&&typeof ProcessUI!=='undefined'?ProcessUI.summary(j):'等待计算结果'}${queued?' · 待提交 '+queued+' 项':''}`;}
 else if(S.uploading)$('task-message').textContent='正在导入素材，不会自动显影…';
 else if(current()){const last=latest();$('task-message').textContent=edit().dirty&&last?'参数已更改，重新显影后应用；旧成片保留。':last?(last.result.notice||last.result.audioStatus||`显影完成 · ${last.elapsedSeconds}s · ${last.result.width} × ${last.result.height}`):S.subjectPicking?'点选需要保护的主体，可添加多个点。':S.picking?'请在主体内部点选景深焦点。':'调整参数后，点击显影。';}
 else $('task-message').textContent='准备就绪，等待原片。';renderContext(busy);
}
function render(){const item=current();$('workspace').classList.toggle('video-mode',S.mode==='video');$('workspace-title').textContent={photo:'照片工作台',video:'视频工作台',batch:'批量工作台'}[S.mode];$$('[data-mode]').forEach(b=>{b.classList.toggle('active',b.dataset.mode===S.mode);b.setAttribute('aria-pressed',String(b.dataset.mode===S.mode));});$('file-input').accept=S.mode==='video'?'.mp4,.mov,.m4v,.avi,.mkv,.webm':'.jpg,.jpeg,.png,.webp';renderParams();renderFilm();renderPreview();renderButtons();if($('history-dialog').open)renderHistory();if($('monitor-dialog').open)renderMonitor();Motion.sync();if(typeof ProcessUI!=='undefined')ProcessUI.update();}
async function importFiles(files){if(S.uploading)return;const list=[...files];if(!list.length)return;S.uploading=true;renderButtons();let first=null;try{for(const file of list){const kind=/\.(mp4|mov|m4v|avi|mkv|webm)$/i.test(file.name)?'video':'photo';if(!/\.(jpg|jpeg|png|webp|mp4|mov|m4v|avi|mkv|webm)$/i.test(file.name)){toast('不支持此格式：'+file.name);continue;}const query=new URLSearchParams({name:file.name,kind});const r=await fetch('/api/import?'+query,{method:'POST',headers:{'X-Workbench-Token':S.token,'Content-Type':'application/octet-stream'},body:file});const data=await r.json();if(!r.ok||!data.ok)throw Error(data.error||'导入失败');S.inputs.push(data.input);(data.input.kind==='photo'?S.selected:S.videoSelected).add(data.input.id);if(!first)first=data.input;inheritParameters(data.input.id,sharedEdits[data.input.kind]||sharedParameters(edit()));}}catch(error){toast(error.message);}finally{S.uploading=false;if(first){if((S.mode==='video')!==(first.kind==='video'))S.mode=first.kind==='video'?'video':'photo';S.libraryFilter='';$('film-search').value='';setCurrent(first.id);if(first.kind==='video')tab('process',false);if(Motion.isOpen('library-dialog'))Motion.close('library-dialog');if(first.downscaled)toast('已按工作像素上限缩小预览及处理图；原片未改变。');}else render();$('file-input').value='';}}

async function submit(action,id=S.current,options=null,batch=false){if(!id)throw Error('请先导入素材');if(id===S.current&&(action==='subject'||action==='photo'&&edit().blurEnabled&&edit().subjectEnabled)&&!$('subject-concurrency').reportValidity())throw Error('请先修正保护点并发数。');const e=options||edit(id);if(!options&&action.startsWith('video')&&e.blurEnabled&&!e.smartFocus&&videoFocusDraft&&!commitVideoFocus())throw Error('请先修正视频焦点坐标。');const payload={action,inputId:id,requestId:globalThis.crypto?.randomUUID?.().replace(/-/g,''),...optionsFor(e,action,batch,id),ratio:Number($('video-position').value)/1000};if(payload.blurEnabled&&action.startsWith('video')&&!payload.smartFocus){if(!S.capabilities.videoManualFocus)throw Error('当前服务不支持视频手动固定焦点，请使用新版入口；虚化开关与现有成片保留。');if(!payload.point)throw Error('请先设置视频手动焦点，或开启智能焦点；虚化开关保持不变。');}if(payload.blurEnabled&&payload.smartFocus&&!(action.startsWith('video')?S.capabilities.videoSmartFocus:S.capabilities.smartFocus))throw Error('智能焦点暂不可用，请重新打开工作台。');const data=await api('/api/jobs',payload);if(!S.jobs.some(j=>j.id===data.job.id))S.jobs.push(data.job);render();if(typeof ProcessUI!=='undefined')ProcessUI.track(data.job);pollSoon();return data.job;}
function stopFailedBatch(){
 const failed=S.jobs.find(j=>S.batchRunJobs.has(j.id)&&['error','canceled','interrupted'].includes(j.status));
 if(!failed||!S.batchQueue.length)return false;
 const count=S.batchQueue.length;S.batchQueue=[];toast(`批量已停止，剩余 ${count} 项未开始；已完成结果保留。`);return true;
}
async function pumpBatch(){
 if(S.batchSubmitting||!S.batchQueue.length||!S.connected||stopFailedBatch())return;S.batchSubmitting=true;
 const queue=S.batchQueue;
 try{while(queue===S.batchQueue&&queue.length&&!stopFailedBatch()&&S.jobs.filter(j=>!terminal(j)).length<1){
  const next=queue[0],job=await submit(next.action,next.id,next.options,true);S.batchRunJobs.add(job.id);
  if(queue!==S.batchQueue){if(!terminal(S.jobs.find(j=>j.id===job.id)||job))await cancelJob(job.id);break;}
  queue.shift();
 }}
 catch(error){const unsent=S.batchQueue.length;S.batchQueue=[];toast(`批量停止：${error.message}；${unsent} 项未提交，已完成结果保留。`);}
 finally{S.batchSubmitting=false;renderButtons();}
}
async function develop(){if(S.developSubmitting||$('develop-btn').disabled)return;
 S.developSubmitting=true;S.developCanceled=false;renderButtons();
 try{for(const input of $('resolution-controls').querySelectorAll('input[type=number]:not(:disabled)'))if(!input.reportValidity())throw Error('请先修正长边设置。');
  if(S.mode==='video'&&edit().blurEnabled&&!edit().smartFocus&&videoFocusDraft&&!commitVideoFocus())throw Error('请先修正视频焦点坐标。');
  if(batchMode()){
   const global=copyEditSnapshot(edit()),ids=selectionIds(),video=S.mode==='video';
   if(global.blurEnabled&&!global.smartFocus&&ids.some(id=>!edit(id).point))throw Error(video?'请为每段所选视频设置手动固定焦点，或开启智能焦点；虚化开关保持不变。':'请为每张所选照片设置焦点，或先关闭景深。');
   if(!video&&global.blurEnabled&&global.subjectEnabled&&ids.some(id=>!edit(id).subjectPoints.length))throw Error('请为每张所选照片设置保护点，或先关闭主体保护。');
   if(!video&&!global.colorEnabled&&!global.blurEnabled&&ids.some(id=>!edit(id).crop))throw Error('部分照片没有色彩、景深或构图，请先设置处理流程。');
   const plans=ids.map(id=>{const local=edit(id);Object.assign(local,{hdrEnabled:global.hdrEnabled,style:global.style,colorEnabled:global.colorEnabled,blurEnabled:global.blurEnabled,smartFocus:!!global.smartFocus,aperture:global.aperture,subjectEnabled:global.subjectEnabled,subjectConcurrency:global.subjectConcurrency??1,resolutions:{...global.resolutions},dirty:true});return {id,action:video?'video':'photo',options:copyEditSnapshot(local)};});
       S.batchRunJobs=new Set();S.batchQueue=plans;S.batchPlanned=plans.length;persist();render();toast(`已安排 ${plans.length} 项，按序分批提交；参数变更不会影响已安排项。`);await pumpBatch();
  }else{const job=await submit(S.mode==='video'?'video':'photo');if(S.developCanceled&&!terminal(S.jobs.find(j=>j.id===job.id)||job))await cancelJob(job.id);}
 }catch(error){toast(error.message);}
 finally{S.developSubmitting=false;S.developCanceled=false;renderButtons();}
}
function syncTaskControls(){
 // Only flags, not values/nodes/focus: error/cancel must release the running lock
 // without rebuilding parameters, reloading the preview or clearing invalid edits.
 const photo=current()?.kind==='photo',busy=S.developSubmitting||pending().length>0;
 $('analyse-depth').disabled=!current()||busy;$('analyse-compose').disabled=!current()||busy;
 $('subject-concurrency').disabled=!photo||busy||!S.capabilities.subjectConcurrency;
 $('pick-subject').disabled=!photo||busy;$('finish-subject').disabled=!photo||busy||!S.subjectPicking;
 $('compute-subject').disabled=!photo||busy||!edit().subjectPoints.length||!S.capabilities.subjectProtection;
 $('clear-subject').disabled=!edit().subjectPoints.length||busy;
 for(const button of $('subject-points').children)button.disabled=busy;
}
function taskActivity(jobs){return JSON.stringify(jobs.filter(j=>!terminal(j)).map(j=>[j.id,j.inputId]).sort());}
function applyJobs(jobs,{persistState=true}={}){const activityBefore=taskActivity(S.jobs);let structural=jobs.length!==S.jobs.length;S.jobs=jobs;stopFailedBatch();for(const j of jobs){if(!terminal(j)||S.processed.has(j.id))continue;S.processed.add(j.id);if(j.status==='done'){structural=true;const e=edit(j.inputId);
 if(j.action==='depth'){const key=depthKey({resolutions:j.options.resolutions},j.inputId);if(key===depthKey(e,j.inputId)){e.depth=j.result.thermalBase64;e.depthData=j.result;e.depthKey=key;}}if(j.action==='composition')e.suggestions=j.result.suggestions;if(j.action==='video-preview')S.previews.set(j.inputId,j.result);if(j.action==='video-playback'){if(j.result.sourceJobId)S.resultPlaybacks.set(j.result.sourceJobId,j.result);else{S.playbacks.set(j.inputId,j.result);S.playbackFailures.delete(j.inputId);}}
 if(j.action==='subject'){const key=protectionKey({...e,subjectPoints:j.options.subjectPoints,resolutions:j.options.resolutions});if(key===protectionKey(e))e.subject={...j.result,key};}
 if(['photo','video'].includes(j.action))e.dirty=parameterKey(j.options)!==parameterKey(optionsFor(e,j.action,!!j.options.batch,j.inputId));
 if(j.inputId===S.current){if(j.action==='photo'){S.view='result';S.override=null;}if(j.action==='depth'&&validDepth(e))S.view='depth';if(j.action==='subject'&&validProtection(e))S.view='subject';if(j.action==='video-preview')S.view=completedVideoSource()?'frame':'compare';if(j.action==='video'){S.view='compare';S.override=null;}if(j.action==='video-playback'){if(!j.result.sourceJobId)S.view='play';else if(latest()?.id===j.result.sourceJobId&&['compare','result'].includes(S.view)){S.override=j.result.sourceJobId;}}if(j.result.notice)toast(j.result.notice);}
 }else if(j.status==='error'){toast(j.error||'处理失败');if(persistState&&j.inputId===S.current&&canMemoryRetry(j)&&!Motion.isOpen('memory-retry-dialog'))openMemoryRetry(j.id);}}if(structural){if(persistState)persist();render();}else{if(activityBefore!==taskActivity(S.jobs))syncTaskControls();renderButtons();if($('history-dialog').open)renderHistory();if($('monitor-dialog').open)renderMonitor();}if(typeof ProcessUI!=='undefined')ProcessUI.update();pumpBatch();}
let polling=false,pollTimer=null;
function pollSoon(){clearTimeout(pollTimer);if(!polling)pollTimer=setTimeout(poll,80);}
function jobsRevision(jobs){return JSON.stringify(jobs.map(j=>[j.id,j.status,j.phase,j.cancelRequested,j.progress?.sequence,j.frames]));}
async function poll(){if(polling)return;polling=true;try{const data=await api('/api/jobs');let inputsChanged=false;if(data.inputs&&JSON.stringify(data.inputs)!==JSON.stringify(S.inputs)){S.inputs=data.inputs;inputsChanged=true;if(!current())S.current=visibleInputs()[0]?.id||null;}if(data.recovery)S.recovery=data.recovery;if(jobsRevision(data.jobs)!==jobsRevision(S.jobs))applyJobs(data.jobs);else if(inputsChanged)render();if(Motion.isOpen('memory-retry-dialog'))syncMemoryRetry();if(videoComparison?.afterFailed)renderVideoComparison(mediaInfo());if(typeof renderArchiveSettings==='function')renderArchiveSettings();if($('recovery-dialog').open){syncRecoveryControls();renderSavedArchives();}S.connected=true;if(document.documentElement)document.documentElement.dataset.connected='true';$('connection').textContent='本机处理已就绪';}catch(e){S.connected=false;if(document.documentElement)document.documentElement.dataset.connected='false';$('connection').textContent='连接已断开';if(typeof ProcessUI!=='undefined')ProcessUI.update();}finally{polling=false;clearTimeout(pollTimer);pollTimer=setTimeout(poll,S.jobs.some(j=>!terminal(j))?250:2500);}}
const actionName=a=>({'photo':'照片显影','depth':'深度分析','subject':'主体保护','composition':'构图分析','video-preview':'视频抽帧','video-playback':'兼容播放预览','video':'视频显影'}[a]||a);
function renderHistory(){const root=$('history-list'),signature=JSON.stringify(S.jobs.map(j=>[j.id,j.status,j.memoryRetryAllowed]));if(root.dataset.jobs===signature){for(const j of S.jobs){const row=root.querySelector('[data-job-id="'+j.id+'"]');if(row){row.querySelector('.status-chip').textContent=j.phase;const p=row.querySelector('progress');if(p){const u=j.progress?.units;if(u){p.max=u.total;p.value=u.completed;}else p.removeAttribute('value');}}}return;}root.dataset.jobs=signature;root.replaceChildren();if(!S.jobs.length){root.append(make('p','fine-note','尚无处理记录。导入素材后开始显影。'));return;}for(const j of [...S.jobs].reverse()){const row=make('article','history-row');row.dataset.jobId=j.id;const top=make('div','history-top');top.append(make('strong','',j.name),make('span','status-chip',j.phase));row.append(top,make('p','',`${actionName(j.action)} · ${styleName(j.options.style)}${j.elapsedSeconds?' · '+j.elapsedSeconds+'s':''}`));if(!terminal(j)){const p=make('progress');p.max=100;const u=j.progress?.units;if(u){p.max=u.total;p.value=u.completed;}else p.removeAttribute('value');row.append(p);}if(j.error)row.append(make('p','notice',j.error));const acts=make('div','history-actions');if(canMemoryRetry(j)){const b=make('button','outline-button archive-danger','查看内存不足提示');b.addEventListener('click',()=>openMemoryRetry(j.id));acts.append(b);}if(j.memoryOverrideUsed)row.append(make('p','notice','本任务曾明确跳过估算内存预检；恢复或新任务不会沿用授权。'));if(j.archive)row.append(make('p','fine-note',j.archive.cleared?'此任务的旧存档已清空；原片和完整成片保留。':j.archive.legacy?'旧格式任务记录保留，不作为可恢复存档。':j.archive.saved?'此文件最近完整动作已存档':j.status==='done'?'完整成片与记录保留；此动作快照已更新或淘汰。':'本动作未完成，未生成完整动作存档。'));if(j.status==='done'&&['photo','video'].includes(j.action)){const a=make('a','',j.action==='photo'?'下载 PNG 成片':'下载 MP4 视频');a.href=j.result.downloadUrl;a.setAttribute('download','');acts.append(a);if(['photo','video'].includes(j.action)){const b=make('button','text-button',j.action==='video'?'对比此版本':'查看此版本');b.addEventListener('click',()=>{S.mode=j.action==='video'?'video':'photo';setCurrent(j.inputId);Object.assign(edit(j.inputId),j.options,{dirty:false});S.override=j.id;S.view=j.action==='video'?'compare':'result';persist();render();Motion.close('history-dialog');});acts.append(b);}}if(!terminal(j)){const b=make('button','text-button','取消');b.addEventListener('click',()=>cancelJob(j.id));acts.append(b);}if(typeof ProcessUI!=='undefined'){const detail=make('button','text-button','查看过程');detail.id='process-history-'+j.id;detail.addEventListener('click',()=>ProcessUI.open(j.id,detail.id));acts.append(detail);}row.append(acts);root.append(row);}}
// Typed admission failures only; no text matching, saved preference or batch-wide authorization.
let memoryRetryTarget=null,memoryRetrySubmitting=false,memoryRetryFailure='';
function canMemoryRetry(job){return !!(S.capabilities.memoryOverride&&job?.status==='error'&&job.errorCode==='memory_preflight'&&job.memoryRetryAllowed);}
function syncMemoryRetry(){
 const job=S.jobs.find(j=>j.id===memoryRetryTarget),button=$('confirm-memory-retry');
 button.disabled=memoryRetrySubmitting||libraryBusy()||!canMemoryRetry(job);
 $('memory-retry-status').textContent=memoryRetrySubmitting?'正在提交单次重试…':memoryRetryFailure|| (libraryBusy()?'请等待其他上传、排队和计算结束；不会自动重试。':!canMemoryRetry(job)?'此任务已重试或不可重试；未自动执行。':'');
}
function openMemoryRetry(id){
 const job=S.jobs.find(j=>j.id===id);if(!canMemoryRetry(job))return;
 memoryRetryTarget=id;memoryRetryFailure='';$('memory-retry-task').textContent=job.name+' · '+actionName(job.action);
 $('memory-retry-error').textContent=job.error;syncMemoryRetry();
 return show('memory-retry-dialog','develop-btn');
}
async function confirmMemoryRetry(){
 const id=memoryRetryTarget,job=S.jobs.find(j=>j.id===id);
 if(memoryRetrySubmitting||libraryBusy()||!canMemoryRetry(job))return;
 memoryRetrySubmitting=true;memoryRetryFailure='';syncMemoryRetry();
 try{
  // The server uses the failed job's captured options, never current edits or another material.
  const response=await api('/api/memory-retry',{id,confirm:true});
  S.jobs=S.jobs.map(j=>j.id===id?response.previous:j);
  if(!S.jobs.some(j=>j.id===response.job.id))S.jobs.push(response.job);
  await Motion.close('memory-retry-dialog');memoryRetryTarget=null;render();
  if(typeof ProcessUI!=='undefined')ProcessUI.track(response.job);
  toast('已开始本任务的单次重试；未缩图、未降低自定义并发。内存耗尽仍可能失败。');pollSoon();
 }catch(error){memoryRetryFailure=error.message;toast(error.message);pollSoon();}
 finally{memoryRetrySubmitting=false;syncMemoryRetry();}
}
$('confirm-memory-retry').addEventListener('click',confirmMemoryRetry);

async function cancelJob(id){try{const j=S.jobs.find(j=>j.id===id);if(j?.cancelRequested)return;await api('/api/cancel',{id});if(j)j.cancelRequested=true;pollSoon();if(typeof ProcessUI!=='undefined')ProcessUI.update();toast('正在停止本次计算；原片、已完成缓存和旧成片保留。');}catch(e){toast(e.message);}}
function renderMonitor(){
 const root=$('monitor-content'),running=S.jobs.find(j=>j.status==='running'),last=[...S.jobs].reverse().find(j=>j.result?.device);
 const values=[['服务',S.connected?'本机在线':'连接已断开'],['执行模式',(S.config.execution_mode||'concurrent')==='serial'?'串行 · 1路 / batch 1':'并发 · 按资源预检'],['一瞬计算',(S.config.instant_backend||'auto')==='cpu-exact'?'CPU 精确':'自动 GPU / CPU'],['运行设备',last?.result.device||'首次显影后显示'],['队列',S.jobs.filter(j=>!terminal(j)).length+' 项'],['当前步骤',running?.phase||'空闲'],['当前步骤进度',running&&typeof ProcessUI!=='undefined'?ProcessUI.summary(running):'—'],['处理速度',running?.fps?running.fps+' fps':'—'],['处理帧数',running?.frames!==undefined?running.frames+' / '+running.totalFrames:'—'],['估计剩余',running?.etaSeconds?running.etaSeconds+'s（按已处理帧）':'—'],['已完成',S.jobs.filter(j=>j.status==='done').length+' 项'],['音轨同步',S.audio?'FFmpeg 可用':'FFmpeg 未找到']];
 if(root.children.length!==values.length){root.replaceChildren();for(const [label,value] of values)fact(root,label,value);}else values.forEach(([,value],i)=>{const node=root.children[i].lastElementChild;if(node.textContent!==value)node.textContent=value;});
}
function exportLink(job){const a=make('a','download-link');a.href=job.result.downloadUrl;a.setAttribute('download','');const copy=make('span');copy.append(make('strong','',job.action==='photo'?'下载 PNG 成片':'下载 MP4 视频'),make('small','',`${job.result.width} × ${job.result.height} · ${styleName(job.options.style)}`));a.append(copy,icon('download'));return a;}
function openExport(){const root=$('export-content');root.replaceChildren();const jobs=batchMode()?selectionIds().map(id=>latest(id)).filter(Boolean):[latest()].filter(Boolean);if(!jobs.length){toast('尚无可导出的成片');return;}if(batchMode()){const a=make('a','download-link');a.href='/api/bundle?ids='+jobs.map(j=>j.id).join(',');a.setAttribute('download','');a.append(make('strong','',`打包下载 ${jobs.length} 项${S.mode==='video'?' MP4 视频':' PNG 成片'}`),icon('download'));root.append(a);}else{root.append(exportLink(jobs[0]));if(jobs[0].result.audioStatus)root.append(make('p','notice',jobs[0].result.audioStatus));if(edit().dirty)root.append(make('p','notice','参数已更改，这里下载的是上次显影版本。重新显影后才能导出新参数的结果。'));}root.append(make('p','fine-note','下载的是完整工作尺寸成片，不是屏幕缩略预览。浏览器负责选择保存位置，原片不会被覆盖。'));show('export-dialog');if(typeof WorkspaceFeatures!=='undefined')WorkspaceFeatures.syncExport();}
function clock(seconds){const s=Math.max(0,Math.floor(seconds||0));return Math.floor(s/60).toString().padStart(2,'0')+':'+(s%60).toString().padStart(2,'0');}
function view(name){S.view=name;S.picking=false;S.subjectPicking=false;S.zoom=1;renderPreview();}
function openLibrary(source='library-btn'){
  const root=$('library-list');root.replaceChildren();
  const list=visibleInputs();
  if(!list.length)root.append(make('p','fine-note',S.mode==='video'?'尚未导入视频。':'尚未导入照片。'));
  for(const item of list){
    const card=make('button','library-card'+(item.id===S.current?' active':''));card.setAttribute('aria-label',item.name);
    if(item.previewUrl){const image=make('img');image.src=item.previewUrl;image.alt='';card.append(image);}else{const preview=make('span','library-video');preview.append(icon('play'));card.append(preview);}
    card.append(make('strong','',item.name),make('small','',`${item.width} × ${item.height}${item.kind==='video'?' · '+clock(item.durationSeconds):''}`));
    card.addEventListener('click',()=>{setCurrent(item.id);Motion.close('library-dialog');});const row=make('div','library-entry'),remove=make('button','text-button material-remove','移除');remove.dataset.removeId=item.id;remove.setAttribute('aria-label','移除工作台副本：'+item.name);remove.disabled=libraryBusy();remove.addEventListener('click',()=>requestRemove([item.id]));row.append(card,remove);root.append(row);
  }
  $('clear-library').disabled=!S.inputs.length||libraryBusy();show('library-dialog',source);
}
function renderContext(busy=[]){
  const item=current(),list=filteredInputs(),index=list.findIndex(i=>i.id===S.current),last=latest();
  $('asset-name').textContent=item?.name||(S.mode==='video'?'等待导入视频':'等待导入原片');$('asset-name').title=item?.name||'';
  $('asset-position').textContent=index<0?'—':`${index+1} / ${list.length}`;
  $('previous-asset').disabled=index<=0;$('next-asset').disabled=index<0||index>=list.length-1;
  const active=busy.find(j=>j.status==='running')||busy[0],failed=[...S.jobs].reverse().find(j=>j.inputId===S.current);
  let title='准备开始',description=S.mode==='video'?'导入视频后，抽帧预览或显影整段。':'选择素材后，调整风格并显影。';
  if(S.uploading){title='正在导入素材';description='请稍候，不会自动开始显影。';}
  else if(active){title=active.phase||'正在处理';description=(typeof ProcessUI!=='undefined'?ProcessUI.summary(active):'正在计算，此步骤暂不显示百分比')+(busy.length>1?' · '+busy.length+' 项排队':'');}
  else if(item){
    title=S.picking?'在原片上选择焦点':batchMode()?`已选择 ${selectionIds().length} 项`:edit().dirty&&last?'参数已修改 · 待显影':last?'成片已就绪':'原片已就绪';
    description=S.picking?'点击主体内部，设置后再显影。':batchMode()?'统一风格与尺寸 · 各照片独立焦点 / 保护点 / 构图':edit().dirty&&last?'上次成片保留，点击显影更新。':last?`${last.result.width} × ${last.result.height} · 可从右下角导出`:'参数不自动运行，确认后点击显影。';
    if(failed?.status==='error'){title='处理未完成 · 可重试';description=failed.error||'请在处理记录中查看详情。';}
  }
  $('action-title').textContent=title;$('action-description').textContent=description;
  $('task-progress').classList.toggle('hidden',!active);const units=active?.progress?.units;if(units){$('task-progress').max=units.total;$('task-progress').value=units.completed;}else $('task-progress').removeAttribute('value');
  if(document.documentElement)document.documentElement.dataset.connected=String(S.connected);
}
function navigateAsset(direction){const list=filteredInputs(),index=list.findIndex(i=>i.id===S.current),next=list[index+direction];if(index>=0&&next)setCurrent(next.id);}

function syncResponsiveLayout(){S.layout=layoutForWidth(innerWidth);$('inspector').setAttribute('role',innerWidth<800?'dialog':'complementary');if(innerWidth<800&&S.panel)$('inspector').setAttribute('aria-modal','true');else $('inspector').removeAttribute('aria-modal');fitImage();}
$$('[data-mode]').forEach(b=>b.addEventListener('click',()=>setMode(b.dataset.mode)));
$$('[data-tab]').forEach(b=>b.addEventListener('click',()=>tab(b.dataset.tab)));
$$('[data-view]').forEach(b=>b.addEventListener('click',()=>view(b.dataset.view)));
$$('[data-close]').forEach(b=>b.addEventListener('click',()=>Motion.close(b.closest('dialog').id)));
$$('dialog').forEach(d=>d.addEventListener('click',e=>{if(e.target===d)Motion.close(d.id);}));
$('params-btn').addEventListener('click',()=>setPanel(!S.panel));$('close-panel').addEventListener('click',()=>setPanel(false));
$('style-trigger').addEventListener('click',()=>{if(Motion.isOpen('style-menu'))Motion.close('style-menu');else Motion.open('style-menu','style-trigger','menu');});
document.addEventListener('click',e=>{if(!e.target.closest('#style-trigger,#style-menu'))Motion.close('style-menu',{restoreFocus:false});});
$('monitor-btn').addEventListener('click',()=>{renderMonitor();show('monitor-dialog');});$('more-btn').addEventListener('click',()=>show('more-dialog'));
$('history-btn').addEventListener('click',()=>{renderHistory();show('history-dialog');});$('export-btn').addEventListener('click',openExport);
$('open-settings').addEventListener('click',()=>{$('pixel-cap').value=S.config.max_pixels/1e6;$('pixel-cap-label').textContent=S.config.max_pixels/1e6+' MP';$('cpu-threads').value=S.config.cpu_threads;$('video-batch').value=S.config.video_batch_size;$('video-gpu').checked=S.config.enable_video_gpu;renderFocusConcurrency();renderArchiveSettings();$('motion-preference').value=Motion.preference;if(typeof WorkbenchControls!=='undefined')WorkbenchControls.sync();show('settings-dialog');});
$('motion-preference').addEventListener('change',()=>Motion.updatePreference($('motion-preference').value));
$('pixel-cap').addEventListener('input',()=>$('pixel-cap-label').textContent=$('pixel-cap').value+' MP');
let archiveSaving=false,archiveDraft=false,archiveSignature=null,archivePreview=null;
function parseArchiveLimit(raw){const text=String(raw).trim(),n=Number(text);if(!text||!Number.isInteger(n)||n<1||n>64)throw new Error('存档数量须为1–64的整数');return n;}
function renderArchiveSettings(force=false){
 const box=$('archive-limit');if(!box)return;
 const state=S.recovery?.archives,capable=!!S.capabilities?.actionArchives,signature=JSON.stringify([capable,state?.limit,state?.revision]);
 if(force||!archiveDraft&&archiveSignature!==signature){box.value=String(state?.limit??10);$('archive-limit-range').value=box.value;archiveSignature=signature;archiveDraft=false;}
 const busy=libraryBusy(),locked=!capable||busy||archiveSaving;
 box.disabled=locked;$('archive-limit-range').disabled=locked;$('save-archive-limit').disabled=locked;
 $('archive-settings-status').textContent=!capable?'存档功能暂不可用，已有结果保留。':archiveSaving?'正在保存存档数量…':busy?'上传、排队或计算期间不能调整存档数量。':`已保存 ${state?.count||0} / ${state?.limit||10} 个文件${archiveDraft?' · 有未保存的修改':''}${state?.cleanupPending?' · '+state.cleanupPending+'份旧快照未清理，尚未释放空间':''}`;
}
for(const id of ['archive-limit','archive-limit-range'])$(id).addEventListener('input',()=>{archiveDraft=true;archivePreview=null;const raw=$(id).value;if(id==='archive-limit-range')$('archive-limit').value=raw;else if(Number.isInteger(Number(raw))&&Number(raw)>=1&&Number(raw)<=64)$('archive-limit-range').value=raw;renderArchiveSettings();});
async function applyArchiveLimit(preview){
 if(archiveSaving||libraryBusy())return;
 archiveSaving=true;renderArchiveSettings();$('confirm-archive-limit').disabled=true;
 try{const response=await api('/api/action-archives',{mode:'apply',limit:preview.limit,revision:preview.revision,confirmation:preview.confirmation});S.recovery.archives=response.archives;archiveDraft=false;archivePreview=null;renderArchiveSettings(true);await Motion.close('archive-confirm-dialog');toast(response.archives.cleanupPending?'数量已保存；部分旧快照未清理，尚未释放空间。':'存档数量已保存；未开始计算，原片和历史成片保留。');}
 catch(error){$('archive-settings-status').textContent=error.message;toast(error.message);}
 finally{archiveSaving=false;$('confirm-archive-limit').disabled=false;renderArchiveSettings();}
}
$('save-archive-limit').addEventListener('click',async()=>{
 if(archiveSaving||libraryBusy()||!S.capabilities?.actionArchives)return;
 try{const limit=parseArchiveLimit($('archive-limit').value),revision=S.recovery.archives.revision;archiveSaving=true;renderArchiveSettings();const response=await api('/api/action-archives',{mode:'preview',limit,revision});archivePreview={limit,revision,confirmation:response.confirmation};archiveSaving=false;renderArchiveSettings();
 if(!response.wouldEvict.length){await applyArchiveLimit(archivePreview);return;}
 $('archive-confirm-description').textContent=`将保留最近 ${limit} 个文件的完整动作，淘汰最早完成的 ${response.wouldEvict.length} 个文件快照：`;
 $('archive-eviction-list').replaceChildren();for(const entry of response.wouldEvict)$('archive-eviction-list').append(make('li','',entry.name+' · '+actionName(entry.action)));
 show('archive-confirm-dialog','save-archive-limit');
 }catch(error){toast(error.message);}finally{archiveSaving=false;renderArchiveSettings();}
});
$('confirm-archive-limit').addEventListener('click',()=>{if(archivePreview)applyArchiveLimit(archivePreview);});
let savedArchiveSignature=null;
function renderSavedArchives(){
 const root=$('archive-saved-list');if(!root)return;const state=S.recovery?.archives,entries=state?.saved||[],signature=JSON.stringify([entries,state?.limit,state?.cleanupPending]);
 if(signature===savedArchiveSignature){root.querySelectorAll('[data-archive-job]').forEach(button=>button.disabled=libraryBusy()||recoverySaving||archiveClearing);return;}
 savedArchiveSignature=signature;root.replaceChildren();
 $('archive-saved-count').textContent=`${entries.length} / ${state?.limit||10}`;
 for(const entry of entries){const row=make('article','archive-saved-row'),copy=make('div','archive-saved-copy');copy.append(make('strong','',entry.name),make('small','',`${entry.kind==='video'?'视频':'照片'} · ${actionName(entry.action)} · ${new Date(entry.completedAt*1000).toLocaleString()}`));const button=make('button','text-button','恢复结果与参数');button.dataset.archiveJob=entry.jobId;button.disabled=libraryBusy()||recoverySaving||archiveClearing;button.addEventListener('click',()=>restoreSavedArchive(entry.jobId));row.append(copy,button);root.append(row);}
 if(!entries.length)root.append(make('p','recovery-empty','还没有完整动作存档；动作成功完成后才保存。'));
 $('archive-saved-note').textContent=`按最近成功完成顺序保留；恢复不运行模型，也不改变保留顺序。数量在工作台设置中单独调整。${state?.cleanupPending?'部分旧快照未清理，尚未释放空间。':''}`;
}
async function restoreSavedArchive(id){
 if(recoverySaving||libraryBusy()||archiveClearing)return;recoverySaving=true;syncRecoveryControls();renderSavedArchives();
 try{const response=await api('/api/recovery',{choice:'restore',id}),job=response.restored;S.recovery=response.recovery;S.jobs=S.jobs.map(j=>j.id===job.id?job:j);S.current=job.inputId;S.mode=current()?.kind==='video'?'video':'photo';Object.assign(edit(),job.options,{dirty:false});S.processed.delete(job.id);applyJobs(S.jobs);S.override=['photo','video'].includes(job.action)?job.id:job.action==='video-playback'?job.result.sourceJobId||null:null;S.view=job.action==='video'?'compare':job.action==='photo'?'result':job.action==='depth'?'depth':job.action==='subject'?'subject':job.action==='video-playback'?(job.result.sourceJobId?'compare':'play'):job.action==='video-preview'?'frame':'original';persist();await Motion.close('recovery-dialog');render();toast('已恢复完整动作的结果与参数；未开始计算。');}
 catch(error){$('recovery-status').textContent=error.message;toast(error.message);}finally{recoverySaving=false;syncRecoveryControls();renderSavedArchives();}
}
let archiveClearing=false,archiveClearPreview=null,archiveClearMessage='';
function archiveClearBusy(){return libraryBusy()||!!S.exporting||archiveSaving||recoverySaving;}
function archiveBytes(bytes){return bytes>=1024**3?(bytes/1024**3).toFixed(2)+' GiB':bytes>=1024**2?(bytes/1024**2).toFixed(2)+' MiB':bytes>=1024?(bytes/1024).toFixed(1)+' KiB':bytes+' B';}
function syncArchiveClearControls(){
 const button=$('clear-archives');if(!button)return;
 const capable=!!S.capabilities?.archiveClear,busy=archiveClearBusy();
 button.disabled=!capable||busy||archiveClearing;
 button.textContent=archiveClearing?'正在核对／清理…':'清空存档';
 $('confirm-clear-archives').disabled=!capable||busy||archiveClearing||!archiveClearPreview;
 $('archive-clear-status').textContent=!capable?'暂时无法清空存档，已有存档未改变。':archiveClearing?'正在核对归属与文件校验，请稍候；不会处理视频。':busy?'上传、排队、计算或导出期间不能清空存档。':archiveClearMessage||`先核对并预览空间，再确认清空。${S.recovery?.archives?.clearPending?'还有 '+S.recovery.archives.clearPending+' 个存档文件待核对清理，未自动重试。':''}`;
}
async function previewArchiveClear(){
 if(archiveClearing||archiveClearBusy()||!S.capabilities?.archiveClear)return;
 archiveClearing=true;archiveClearPreview=null;archiveClearMessage='';syncRecoveryControls();renderSavedArchives();
 try{
  const response=await api('/api/clear-archives',{mode:'preview'}),p=response.preview;
  if(!$('recovery-dialog').open)return;
  if(!p.files&&!p.pending){archiveClearMessage=p.skipped.length?'没有可安全清理的存档；部分存档校验未通过，已保留。':'没有可清理的存档；原片和完整成片保留。';toast(archiveClearMessage);return;}
  archiveClearPreview=response.confirmation;
  $('archive-clear-description').textContent=`将清理 ${p.files} 个已核实的存档文件，预计释放 ${archiveBytes(p.bytes)}。仅限当前工作区，不清空素材或处理记录。`;
  $('archive-clear-list').replaceChildren();
  for(const item of p.items)$('archive-clear-list').append(make('li','',`${item.name} · ${item.kind} · ${item.files} 个文件 · ${archiveBytes(item.bytes)}`));
  if(p.skipped.length)$('archive-clear-list').append(make('li','notice',`${p.skipped.length} 组存档校验未通过，将跳过并保留。`));
  $('archive-clear-confirm-status').textContent='取消或关闭将保留所有存档；本次预览5分钟内有效，列表变化后需重新预览。';
  const opened=await show('archive-clear-dialog','clear-archives');
  if(opened!==false&&$('archive-clear-dialog').open)$('cancel-clear-archives').focus({preventScroll:true});
 }catch(error){archiveClearMessage=error.message;toast(error.message);}
 finally{archiveClearing=false;syncRecoveryControls();renderSavedArchives();}
}
async function confirmArchiveClear(){
 if(archiveClearing||archiveClearBusy()||!archiveClearPreview)return;
 const confirmation=archiveClearPreview;archiveClearPreview=null;archiveClearing=true;syncRecoveryControls();renderSavedArchives();
 $('archive-clear-confirm-status').textContent='正在清理已确认的存档；请等待结果，不要重复提交。';
 try{
  const response=await api('/api/clear-archives',{mode:'apply',confirmation});
  S.recovery=response.recovery;S.jobs=response.jobs;savedArchiveSignature=null;
  $('history-list').dataset.jobs='';
  archiveClearMessage=`${response.notice} 实际释放 ${archiveBytes(response.reclaimedBytes)}${response.cleanupPending?'；'+response.cleanupPending+' 个文件尚待清理':''}`;
  await Motion.close('archive-clear-dialog');openRecovery();render();toast(archiveClearMessage);
 }catch(error){archiveClearMessage=error.message;$('archive-clear-confirm-status').textContent=error.message+' 请关闭并重新预览；未自动重试。';toast(error.message);}
 finally{archiveClearing=false;syncRecoveryControls();renderSavedArchives();}
}
$('clear-archives').addEventListener('click',previewArchiveClear);
$('confirm-clear-archives').addEventListener('click',confirmArchiveClear);
$('archive-clear-dialog').addEventListener('close',()=>{archiveClearPreview=null;syncArchiveClearControls();});
let instantBackendSignature=null;
function parseInstantBackend(value){if(!['auto','cpu-exact'].includes(value))throw new Error('一瞬计算方式无效');return value;}
function renderInstantBackend(force=false){
 const select=$('instant-backend'),save=$('save-instant-backend'),status=$('instant-backend-status');if(!select||!save||!status)return;
 const capable=!!S.capabilities?.instantBackend,value=S.config.instant_backend||'auto',signature=JSON.stringify([capable,value]);
 if(force||signature!==instantBackendSignature){select.value=value;instantBackendSignature=signature;}
 const busy=focusConcurrencyBusy(),locked=!capable||busy||focusSaving;select.disabled=locked;save.disabled=locked;
 status.textContent=!capable?'暂不可保存，请等待工作台正常加载。':busy?'任务全部结束后才能保存。':focusSaving?'正在保存…':`已保存：${value==='cpu-exact'?'CPU 精确':'自动 GPU / CPU'}${select.value!==value?' · 有未保存修改':' · 后续新任务生效'}`;
}
$('instant-backend').addEventListener('change',()=>renderInstantBackend());
$('save-instant-backend').addEventListener('click',async()=>{
 if(focusSaving||focusConcurrencyBusy()||!S.capabilities?.instantBackend)return;
 try{const mode=parseInstantBackend($('instant-backend').value);focusSaving=true;renderFocusConcurrency();const data=await api('/api/config',{instant_backend:mode});S.config=data.config;renderInstantBackend(true);toast('一瞬计算方式已保存；不会自动重算。');}
 catch(error){toast(error.message);}finally{focusSaving=false;renderFocusConcurrency();}
});
let executionSignature=null;
function parseExecutionMode(value){if(!['serial','concurrent'].includes(value))throw new Error('执行模式无效');return value;}
function renderExecutionMode(force=false){
 const select=$('execution-mode'),save=$('save-execution-mode'),status=$('execution-status');if(!select||!save||!status)return;
 const capable=!!S.capabilities?.executionMode,value=S.config.execution_mode||'concurrent',signature=JSON.stringify([capable,value]);
 if(force||signature!==executionSignature){select.value=value;executionSignature=signature;}
 const busy=focusConcurrencyBusy(),locked=!capable||busy||focusSaving;select.disabled=locked;save.disabled=locked;
 status.textContent=!capable?'当前后端未声明执行模式能力；请等待正常加载新版，勿中断任务。':busy?'任务全部结束后才能保存执行模式。':focusSaving?'正在保存…':`已保存：${value==='serial'?'串行（1路 / batch 1）':'并发'}${select.value!==value?' · 有未保存修改':' · 后续新任务生效'}`;
}
$('execution-mode').addEventListener('change',()=>renderExecutionMode());
$('save-execution-mode').addEventListener('click',async()=>{
 if(focusSaving||focusConcurrencyBusy()||!S.capabilities?.executionMode)return;
 try{const mode=parseExecutionMode($('execution-mode').value);focusSaving=true;renderFocusConcurrency();const data=await api('/api/config',{execution_mode:mode});S.config=data.config;renderExecutionMode(true);toast('执行模式已保存；仅后续任务生效，不会自动显影。');}
 catch(error){toast(error.message);}finally{focusSaving=false;renderFocusConcurrency();}
});
let colorSignature=null,colorDirty=false;
function parseColorWorkers(raw){const text=String(raw).trim(),n=Number(text);if(!text||!Number.isSafeInteger(n)||n<1)throw new Error('请输入至少1的整数');return n;}
function renderColorConcurrency(force=false){
 const capable=!!S.capabilities?.videoColorConcurrency,value=S.config.video_color_workers??8,signature=JSON.stringify([capable,value]);
 if(force||colorSignature!==signature){$('video-color-workers').value=String(value);colorSignature=signature;colorDirty=false;}
 const busy=focusConcurrencyBusy(),locked=!capable||busy||focusSaving;
 $('video-color-workers').disabled=locked;$('save-color-workers').disabled=locked;
 $('video-color-status').textContent=!capable?'暂时无法更改调色设置，请重新打开工作台。':busy?'正在处理或等待派发；任务结束后才能保存。':focusSaving?'正在保存设置…':`已保存：${value}路${colorDirty?' · 有未保存的修改':' · 后续新任务生效'}`;
}
$('video-color-workers').addEventListener('input',()=>{colorDirty=true;renderColorConcurrency();});
$('save-color-workers').addEventListener('click',async()=>{
 if(focusSaving||focusConcurrencyBusy()||!S.capabilities?.videoColorConcurrency)return;
 try{const value=parseColorWorkers($('video-color-workers').value);focusSaving=true;renderFocusConcurrency();const data=await api('/api/config',{video_color_workers:value});S.config=data.config;renderColorConcurrency(true);toast('调色帧并发已保存；不改变虚化并发，不会自动显影。');}
 catch(error){toast(error.message);}finally{focusSaving=false;renderFocusConcurrency();}
});
let focusInlineSignature=null,focusInlineDirty=false,focusSaving=false;
function focusConcurrencyBusy(){return (S.jobs||[]).some(j=>!['done','error','canceled','interrupted'].includes(j.status))||!!S.developSubmitting||!!S.batchSubmitting||!!S.batchQueue?.length;}
function parseFocusWorkers(adaptive,raw){if(adaptive)return 0;const text=String(raw).trim(),n=Number(text);if(!text||!Number.isSafeInteger(n)||n<1)throw new Error('请输入至少1的整数，或选择智能并发');return n;}
function renderFocusConcurrency(force=false){
 const capable=!!S.capabilities?.videoFocusConcurrency,value=S.config.video_focus_workers??1,signature=JSON.stringify([capable,value]);
 if(force||focusInlineSignature!==signature){$('video-focus-adaptive').checked=value===0;$('video-focus-workers-inline').value=String(value>0?value:1);focusInlineSignature=signature;focusInlineDirty=false;}
 const adaptive=$('video-focus-adaptive').checked,busy=focusConcurrencyBusy(),locked=!capable||busy||focusSaving;
 $('video-focus-custom-inline').classList.toggle('hidden',adaptive);$('video-focus-workers-inline').disabled=locked||adaptive;$('video-focus-adaptive').disabled=locked;$('save-focus-workers').disabled=locked;$('save-settings').disabled=busy||focusSaving;
 $('video-concurrency-status').textContent=!capable?'暂时无法更改虚化设置，请等待任务完成后重新打开工作台。':busy?'正在处理或等待派发；任务全部结束后才能保存并发设置。':focusSaving?'正在保存并发设置…':`已保存：${value===0?'智能并发（自适应）':value+'路自定义'}${focusInlineDirty?' · 有未保存的修改':' · 后续新任务生效'}`;
 renderColorConcurrency();
 renderExecutionMode(force);
 renderInstantBackend(force);
 renderArchiveSettings();
}
$('video-focus-adaptive').addEventListener('change',()=>{focusInlineDirty=true;renderFocusConcurrency();});
$('video-focus-workers-inline').addEventListener('input',()=>{focusInlineDirty=true;renderFocusConcurrency();});
$('save-focus-workers').addEventListener('click',async()=>{
 if(focusSaving||focusConcurrencyBusy()||!S.capabilities?.videoFocusConcurrency)return;
 try{const value=parseFocusWorkers($('video-focus-adaptive').checked,$('video-focus-workers-inline').value);focusSaving=true;renderFocusConcurrency();const data=await api('/api/config',{video_focus_workers:value});S.config=data.config;renderFocusConcurrency(true);toast('虚化并发设置已保存；仅后续新任务生效，不会自动显影。');}
 catch(error){toast(error.message);}finally{focusSaving=false;renderFocusConcurrency();}
});
function focusWorkersSetting(){return parseFocusWorkers($('video-focus-adaptive').checked,$('video-focus-workers-inline').value);}
$('save-settings').addEventListener('click',async()=>{
 if(focusSaving||focusConcurrencyBusy())return;
 try{const config={max_pixels:Number($('pixel-cap').value)*1e6,cpu_threads:Number($('cpu-threads').value),video_batch_size:Number($('video-batch').value),enable_video_gpu:false};if(S.capabilities?.videoFocusConcurrency)config.video_focus_workers=focusWorkersSetting();if(S.capabilities?.videoColorConcurrency)config.video_color_workers=parseColorWorkers($('video-color-workers').value);if(S.capabilities?.instantBackend)config.instant_backend=parseInstantBackend($('instant-backend').value);if(S.capabilities?.executionMode)config.execution_mode=parseExecutionMode($('execution-mode').value);focusSaving=true;renderFocusConcurrency();const data=await api('/api/config',config);S.config=data.config;renderFocusConcurrency(true);renderColorConcurrency(true);Motion.close('settings-dialog');toast('设置已保存，后续新处理任务生效。');}
 catch(e){toast(e.message);}finally{focusSaving=false;renderFocusConcurrency();}
});
$('restore-original').addEventListener('click',()=>{if(!current()){toast('请先导入素材');return;}S.edits.set(S.current,defaultEdit());dirty();Motion.close('more-dialog');toast('已恢复参数，原片和历史成片均保留。');});
$('import-btn').addEventListener('click',()=>$('file-input').click());$('empty-import').addEventListener('click',()=>$('file-input').click());$('library-btn').addEventListener('click',()=>openLibrary('library-btn'));$('library-add').addEventListener('click',()=>$('file-input').click());$('file-input').addEventListener('change',e=>importFiles(e.target.files));
$('film-search').addEventListener('input',()=>{S.libraryFilter=$('film-search').value;renderFilm();renderContext(S.jobs.filter(j=>j.inputId===S.current&&!terminal(j)));});
$('previous-asset').addEventListener('click',()=>navigateAsset(-1));$('next-asset').addEventListener('click',()=>navigateAsset(1));
addEventListener('resize',syncResponsiveLayout);
$('develop-btn').addEventListener('click',develop);$('cancel-btn').addEventListener('click',async()=>{if(S.developSubmitting)S.developCanceled=true;S.batchQueue=[];const ids=selectionIds();for(const j of S.jobs.filter(j=>ids.includes(j.inputId)&&!terminal(j)))await cancelJob(j.id);});
$('color-enabled').addEventListener('change',()=>{edit().colorEnabled=$('color-enabled').checked;dirty();});$('blur-enabled').addEventListener('change',()=>{edit().blurEnabled=$('blur-enabled').checked;dirty();if(edit().blurEnabled&&!edit().smartFocus&&!edit().point){if(S.mode==='video'){toast('虚化已开启；请在下方设置手动焦点，或单独开启智能焦点。');}else{tab('focus');S.picking=true;render();toast('请在原片上点选主体内部。');if(innerWidth<800)setPanel(false);}}});
$('smart-focus').addEventListener('change',()=>{edit().smartFocus=$('smart-focus').checked;S.picking=false;dirty();if(!edit().smartFocus&&edit().blurEnabled&&!edit().point)toast(S.mode==='video'?'虚化保持开启；请设置手动固定焦点后显影。':'虚化保持开启；请在焦点 / 保护页设置手动焦点后显影。');});
$('go-compose').addEventListener('click',()=>tab('compose'));
for(const axis of ['x','y']){$('video-focus-'+axis).addEventListener('input',()=>{videoFocusDraft=true;});$('video-focus-'+axis).addEventListener('change',()=>commitVideoFocus(false));}
$('pick-video-focus').addEventListener('click',startVideoFocusPicking);
$('clear-video-focus').addEventListener('click',()=>{edit().point=null;videoFocusDraft=false;dirty();});
$('video-player').addEventListener('pointerdown',ev=>{if(!S.picking||S.mode!=='video'||!S.capabilities.videoManualFocus)return;const player=$('video-player');if(!player.videoWidth||!player.videoHeight||player.readyState<2)return;const r=player.getBoundingClientRect();if(!r.width||!r.height)return;ev.preventDefault();player.pause();saveVideoFocusPoint({x:Math.max(0,Math.min(1,(ev.clientX-r.left)/r.width)),y:Math.max(0,Math.min(1,(ev.clientY-r.top)/r.height))});toast('固定焦点已设置；虚化开关保持不变，点击显影才处理。');},true);
$('pick-focus').addEventListener('click',()=>{edit().smartFocus=false;S.picking=!S.picking;S.view='original';render();if(S.picking&&innerWidth<800)setPanel(false);toast(S.picking?'点击原片中的主体内部设置焦点。':'已退出焦点选择。');});
$('clear-focus').addEventListener('click',()=>{edit().point=null;edit().smartFocus=false;edit().blurEnabled=false;dirty();});
$('analyse-depth').addEventListener('click',async()=>{try{await submit('depth');}catch(e){toast(e.message);}});$('analyse-compose').addEventListener('click',async()=>{try{await submit('composition');}catch(e){toast(e.message);}});
$('reset-crop').addEventListener('click',()=>{edit().crop=null;dirty();});
$('select-all').addEventListener('click',()=>{if(visibleInputs().every(i=>selectedSet().has(i.id)))selectedSet().clear();else visibleInputs().forEach(i=>selectedSet().add(i.id));renderFilm();renderButtons();});

$('video-batch-enabled').addEventListener('change',()=>{S.videoBatch=$('video-batch-enabled').checked;if(S.videoBatch&&!S.videoSelected.size)visibleInputs().forEach(i=>S.videoSelected.add(i.id));render();});
$('subject-concurrency').addEventListener('change',()=>commitSubjectConcurrency($('subject-concurrency')));
$('subject-enabled').addEventListener('change',()=>{edit().subjectEnabled=$('subject-enabled').checked;dirty();});
function finishPicking(){S.picking=false;S.subjectPicking=false;render();}
$('finish-subject').addEventListener('click',finishPicking);$('end-picking').addEventListener('click',finishPicking);
$('pick-subject').addEventListener('click',()=>{if(current()?.kind!=='photo')return;S.subjectPicking=!S.subjectPicking;S.picking=false;S.view='original';if(S.subjectPicking)setPanel(false);render();toast(S.subjectPicking?'点击需要保护的主体内部，可添加多个保护点。':'已结束主体选点。');});
$('clear-subject').addEventListener('click',()=>{edit().subjectPoints=[];dirty();});
$('compute-subject').addEventListener('click',async()=>{try{finishPicking();await submit('subject');setPanel(false);}catch(error){toast(error.message);}});
if(typeof VideoCompare!=='undefined')videoComparison=new VideoCompare.Controller({frame:$('video-compare-frame'),before:$('video-before'),after:$('video-after'),line:$('video-compare-line'),beforeLabel:$('video-before-label'),controls:$('video-result-controls'),play:$('video-result-play'),seek:$('video-result-seek'),current:$('video-result-time'),total:$('video-result-duration'),mute:$('video-result-mute'),note:$('video-result-note'),message:$('video-result-message'),placeholder:$('video-after-placeholder'),download:$('video-result-download'),compatible:$('compatible-result-playback')},{onUnavailable:queueResultPlayback});
$('compatible-result-playback').addEventListener('click',prepareResultPlayback);
$('compare-range').addEventListener('input',paintComparePosition);
$('compare-range').addEventListener('keydown',e=>{
 const range=e.currentTarget;if(range.disabled)return;const value=Number(range.value),step=e.shiftKey?10:1;
 const positions={ArrowLeft:value-step,ArrowDown:value-step,ArrowRight:value+step,ArrowUp:value+step,PageDown:value-10,PageUp:value+10,Home:0,End:100};
 if(!(e.key in positions))return;e.preventDefault();range.value=String(Math.max(0,Math.min(100,positions[e.key])));paintComparePosition();
});
for(const id of ['image-frame','video-compare-frame'])$(id).addEventListener('dblclick',()=>{if($(id).classList.contains('is-comparing')){$('compare-range').value='50';paintComparePosition();}});
let videoCompareDragging=null;
$('video-compare-frame').addEventListener('pointerdown',e=>{if(S.view!=='compare'||!mediaInfo()?.completed||e.button>0||videoCompareDragging!==null)return;videoCompareDragging=e.pointerId;e.preventDefault();$('compare-range').focus({preventScroll:true});$('video-compare-frame').setPointerCapture(e.pointerId);setCompare(e,$('video-compare-frame').getBoundingClientRect());});
$('video-compare-frame').addEventListener('pointermove',e=>{if(videoCompareDragging===e.pointerId&&S.view==='compare')setCompare(e,$('video-compare-frame').getBoundingClientRect());});
for(const type of ['pointerup','pointercancel','lostpointercapture'])$('video-compare-frame').addEventListener(type,e=>{if(e.pointerId===videoCompareDragging)videoCompareDragging=null;});
document.addEventListener('visibilitychange',()=>{if(document.hidden)videoComparison?.pause();});
addEventListener('pagehide',()=>videoComparison?.hide());

let dragging=null;
$('image-frame').addEventListener('pointerdown',e=>{const r=$('image-frame').getBoundingClientRect();if((S.picking||S.subjectPicking)&&(S.mode!=='video'||S.picking)){const point={x:Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y:Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))};if(S.mode==='video'){saveVideoFocusPoint(point);toast('视频固定焦点已设置，点击显影才处理。');return;}if(S.subjectPicking){if(edit().subjectPoints.length>=64){toast('单张最多64个保护点，请先删除重复点。');return;}edit().subjectPoints.push(point);edit().dirty=true;S.view='original';persist();renderSubject();renderPreview();toast('已添加保护点，选完后统一计算。');}else{edit().smartFocus=false;edit().point=point;S.picking=false;dirty();toast('焦点已设置。启用景深后点击显影。');}return;}if($('image-frame').classList.contains('is-comparing')&&e.button===0&&dragging===null){e.preventDefault();$('compare-range').focus({preventScroll:true});dragging=e.pointerId;$('image-frame').setPointerCapture(e.pointerId);setCompare(e,r);}});
function setCompare(e,r=$('image-frame').getBoundingClientRect()){if(r.width<=0)return;$('compare-range').value=Math.round(Math.max(0,Math.min(100,(e.clientX-r.left)/r.width*100)));paintComparePosition();}
$('image-frame').addEventListener('pointermove',e=>{if(dragging===e.pointerId&&$('image-frame').classList.contains('is-comparing'))setCompare(e);});for(const type of ['pointerup','pointercancel','lostpointercapture'])$('image-frame').addEventListener(type,e=>{if(e.pointerId===dragging)dragging=null;});
$('fit-btn').addEventListener('click',()=>{S.zoom=S.zoom===100?1:100;$('fit-btn').textContent=S.zoom===100?'100% · 点击适应':'适应画布';fitImage();});
$('video-position').addEventListener('input',()=>{if(current())$('time-label').textContent=clock(current().durationSeconds*Number($('video-position').value)/1000);});
async function previewVideo(){if(!current()||pending().length)return;try{$('video-player').pause();await submit('video-preview');}catch(e){toast(e.message);}}
$('video-position').addEventListener('change',previewVideo);$('video-preview').addEventListener('click',previewVideo);$('fallback-preview').addEventListener('click',previewVideo);$('compatible-playback').addEventListener('click',compatiblePlayback);
let dragCount=0;document.addEventListener('dragover',e=>{if(e.dataTransfer.types.includes('Files'))e.preventDefault();});$('stage').addEventListener('dragenter',e=>{if(e.dataTransfer.types.includes('Files')){e.preventDefault();dragCount++;$('stage').classList.add('dragging');}});$('stage').addEventListener('dragleave',()=>{dragCount--;if(dragCount<=0)$('stage').classList.remove('dragging');});document.addEventListener('drop',e=>{if(e.dataTransfer.files.length){e.preventDefault();dragCount=0;$('stage').classList.remove('dragging');importFiles(e.dataTransfer.files);}});
document.addEventListener('keydown',e=>{if(e.target.matches('input,textarea,select')||$$('dialog[open]').length)return;if(e.key==='Escape'){S.picking=false;S.subjectPicking=false;Motion.close('style-menu');renderPreview();}if(e.key.toLowerCase()==='o'&&!e.ctrlKey&&!e.metaKey){e.preventDefault();$('file-input').click();}if(e.key.toLowerCase()==='p'&&!e.ctrlKey&&!e.metaKey)setPanel(!S.panel);if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();if(S.mode==='video')previewVideo();else develop();}});
function checkVideoPlayback(){const item=current(),player=$('video-player');if(item?.kind!=='video'||player.getAttribute('src')!==playbackSource(item))return;if(player.error||!player.videoWidth||!player.videoHeight)S.playbackFailures.add(item.id);else S.playbackFailures.delete(item.id);$('video-playback-note').classList.toggle('hidden',S.view!=='play'||!S.playbackFailures.has(item.id));$('video-player').classList.toggle('decode-failed',S.view==='play'&&S.playbackFailures.has(item.id));}
$('video-player').addEventListener('loadedmetadata',checkVideoPlayback);$('video-player').addEventListener('loadeddata',checkVideoPlayback);$('video-player').addEventListener('error',checkVideoPlayback);
new ResizeObserver(fitImage).observe($('stage'));

function libraryBusy(){return S.removing||S.exporting||S.uploading||S.developSubmitting||S.batchSubmitting||S.batchQueue.length>0||S.jobs.some(j=>!terminal(j));}
function syncMaterialControls(){const busy=libraryBusy();$('remove-current').disabled=busy||!current();$('remove-selected').classList.toggle('hidden',!batchMode());$('remove-selected').disabled=busy||!selectionIds().length;$('clear-materials').disabled=busy||!S.inputs.length;$('clear-library').disabled=busy||!S.inputs.length;for(const b of $('library-list').querySelectorAll('.material-remove'))b.disabled=busy;}
let pendingRemove=[];
function requestRemove(ids){ids=[...new Set(ids)].filter(id=>S.inputs.some(i=>i.id===id));if(!ids.length)return;if(libraryBusy()){toast('请先等待上传、排队和计算结束，或先取消任务。');return;}pendingRemove=[...ids];const names=ids.map(id=>S.inputs.find(i=>i.id===id)?.name).filter(Boolean);$('remove-description').textContent=`将移除 ${names.length} 个工作台素材及其成片、存档和记录：${names.slice(0,4).join('、')}${names.length>4?'…':''}。本机原文件和已下载文件不会删除。此操作无法在工作台撤销。`;$('confirm-remove').disabled=false;show('remove-dialog','library-btn');}
async function confirmRemove(){if(!pendingRemove.length||libraryBusy())return;const ids=[...pendingRemove];S.removing=true;syncMaterialControls();$('confirm-remove').disabled=true;try{clearTimeout(workspaceSaveTimer);await workspaceSaveChain.catch(()=>{});const response=await api('/api/remove-inputs',{ids,confirm:true});const removed=new Set(response.removed);S.inputs=S.inputs.filter(i=>!removed.has(i.id));S.jobs=S.jobs.filter(j=>!removed.has(j.inputId));for(const id of S.resultPlaybacks.keys())if(!S.jobs.some(j=>j.id===id))S.resultPlaybacks.delete(id);for(const id of removed){S.edits.delete(id);S.selected.delete(id);S.videoSelected.delete(id);S.previews.delete(id);S.playbacks.delete(id);S.playbackFailures.delete(id);}if(!current()){videoComparison?.hide();S.current=visibleInputs()[0]?.id||null;S.override=null;S.view=S.mode==='video'?(completedVideoSource()?'compare':'play'):'original';}pendingRemove=[];const state=await api('/api/status');S.recovery=state.recovery;persist();render();await Motion.close('remove-dialog');if(Motion.isOpen('library-dialog'))openLibrary();toast(response.notice);}catch(error){toast(error.message);$('confirm-remove').disabled=false;}finally{S.removing=false;renderButtons();}}
let recoverySaving=false,recoveryOpening=false;
function syncRecoveryControls(){
 syncArchiveClearControls();
 $('close-recovery').disabled=recoverySaving||archiveClearing;
 $('recovery-status').textContent=recoverySaving?'正在恢复完整动作，请稍候…':libraryBusy()||archiveClearing?'上传、排队、计算或清理期间不能恢复存档。':'';
}
function openRecovery(){
 const recovery=S.recovery;
 $('recovery-intro').textContent='每个文件只保留最后一个完整动作，照片与视频共用名额。恢复结果与参数不会开始计算；未完成任务不进入存档列表。';
 renderSavedArchives();
 $('recovery-location').textContent=`${recovery?.location||'当前会话'}。只保存每文件最后一个完整动作，不保存中间步骤或视频帧。失败、取消或中断的记录保留在处理记录中，不作为可恢复存档。`;
 syncRecoveryControls();show('recovery-dialog','more-btn');
}
$('clear-library').addEventListener('click',()=>requestRemove(S.inputs.map(i=>i.id)));
$('remove-current').addEventListener('click',()=>requestRemove([S.current]));
$('remove-selected').addEventListener('click',()=>requestRemove(selectionIds()));
$('clear-materials').addEventListener('click',()=>requestRemove(S.inputs.map(i=>i.id)));
$('confirm-remove').addEventListener('click',confirmRemove);
$('open-recovery').addEventListener('click',async()=>{if(recoveryOpening)return;recoveryOpening=true;try{await Motion.close('more-dialog',{restoreFocus:false});openRecovery();}finally{recoveryOpening=false;}});

async function boot(){try{const data=await api('/api/status');S.token=data.token;S.styles=data.styles;S.inputs=data.inputs;S.jobs=data.jobs;S.config=data.config;S.audio=data.audioAvailable;S.capabilities=data.capabilities||{};S.sessionId=data.recovery?.sessionId;S.recovery=data.recovery;S.connected=true;restore(data.workspace);if(!S.current)S.current=visibleInputs()[0]?.id||null;photos().forEach(i=>S.selected.add(i.id));S.inputs.filter(i=>i.kind==='video').forEach(i=>S.videoSelected.add(i.id));applyJobs(data.jobs,{persistState:false});S.view=S.mode==='video'?(completedVideoSource()?'compare':'play'):latest()?'result':'original';$('connection').textContent='本机处理已就绪';setPanel(S.panel,true);if(S.mode==='video')tab('process',false);render();poll();}catch(e){$('connection').textContent='本地引擎未连接';toast('连接失败，请确认本地服务已启动后刷新页面。');}}
if(typeof WorkbenchControls!=='undefined')WorkbenchControls.init({reduced:Motion.reduced});
if(typeof ProcessUI!=='undefined')ProcessUI.init();
boot();
