'use strict';
/* Unified controls. The native select remains the data/compatibility source only.
 * Popovers live in the owning dialog's top layer and never resize the canvas. */
const WorkbenchControls = (() => {
  const records = new Map(); let active=null, reduced=()=>false, initialized=false, search='', searchedAt=0;
  const options = r => [...r.select.options].filter(o=>!o.disabled&&!o.hidden);
  const animate=(el,frames,duration)=>{if(!reduced()&&el.animate)return el.animate(frames,{duration,easing:'cubic-bezier(.2,0,0,1)'});return null;};
  function sync(){for(const r of records.values()){
    r.text.textContent=r.select.selectedOptions[0]?.textContent||'请选择';r.trigger.disabled=r.select.disabled;
    for(const b of r.list.children){const chosen=b.dataset.value===r.select.value;b.setAttribute('aria-selected',String(chosen));b.classList.toggle('selected',chosen);}
  }}
  function place(r){
    const a=r.trigger.getBoundingClientRect(),gap=7,w=Math.min(Math.max(a.width,204),innerWidth-24);
    const h=Math.min(r.list.scrollHeight,320,innerHeight-32),below=innerHeight-a.bottom-12,above=a.top-12;
    const top=below>=Math.min(h,160)||below>=above?a.bottom+gap:Math.max(12,a.top-gap-h);
    Object.assign(r.list.style,{left:Math.max(12,Math.min(a.right-w,innerWidth-w-12))+'px',top:top+'px',width:w+'px',maxHeight:Math.max(64,Math.min(h,top>a.top?innerHeight-top-12:above-gap))+'px'});
    r.list.style.transformOrigin=top>a.top?'top right':'bottom right';
  }
  function close(restore=true,instant=false){
    const r=active;if(!r)return;active=null;r.generation++;const n=r.generation;
    r.trigger.setAttribute('aria-expanded','false');r.list.inert=true;r.list.setAttribute('aria-hidden','true');
    r.animation?.cancel();const a=instant?null:animate(r.list,[{opacity:1,transform:'translateY(0)',clipPath:'inset(0 round 12px)'},{opacity:0,transform:'translateY(-4px)',clipPath:'inset(0 0 15% round 12px)'}],150);r.animation=a;
    const finish=()=>{if(r.generation===n)r.list.hidden=true;};if(a)a.finished.then(finish,()=>{});else finish();
    if(restore&&!r.trigger.disabled&&r.trigger.isConnected)r.trigger.focus({preventScroll:true});
  }
  function open(r,last=false){
    if(r.trigger.disabled)return;if(active===r){close();return;}close(false,true);sync();active=r;r.generation++;
    // A native modal dialog makes body siblings inert; portal inside that dialog instead.
    (r.trigger.closest('dialog[open]')||document.body).append(r.list);
    r.list.hidden=false;r.list.inert=false;r.list.removeAttribute('aria-hidden');place(r);
    r.trigger.setAttribute('aria-expanded','true');r.animation?.cancel();
    r.animation=animate(r.list,[{opacity:0,transform:'translateY(-6px)',clipPath:'inset(0 0 38% round 12px)'},{opacity:1,transform:'translateY(0)',clipPath:'inset(0 round 12px)'}],260);
    const selected=r.list.querySelector('[aria-selected=true]'),all=[...r.list.children];
    (selected||all[last?all.length-1:0])?.focus({preventScroll:true});
    if(selected){const y=selected.offsetTop;r.list.scrollTop=Math.max(0,Math.min(y-6,y+selected.offsetHeight-r.list.clientHeight+6));}
  }
  function choose(r,value){if(!options(r).some(o=>o.value===value))return;
    const changed=r.select.value!==value;r.select.value=value;sync();close();
    if(changed){r.select.dispatchEvent(new Event('input',{bubbles:true}));r.select.dispatchEvent(new Event('change',{bubbles:true}));}
  }
  function enhanceSelect(select){
    if(records.has(select))return;
    const wrapper=document.createElement('span');wrapper.className='choice-control';
    const trigger=document.createElement('button');trigger.type='button';trigger.className='choice-trigger';trigger.id=select.id+'-trigger';
    const text=document.createElement('span');text.className='choice-value';trigger.append(text);
    const arrow=document.createElement('span');arrow.className='choice-arrow';arrow.setAttribute('aria-hidden','true');trigger.append(arrow);
    const list=document.createElement('div');list.id=select.id+'-listbox';list.className='choice-listbox';list.hidden=true;list.setAttribute('role','listbox');
    const label=select.labels?.[0];const labelText=label?[...label.childNodes].filter(n=>n.nodeType===Node.TEXT_NODE).map(n=>n.textContent).join('').trim():select.getAttribute('aria-label')||select.id;
    trigger.setAttribute('aria-label',labelText);trigger.setAttribute('aria-haspopup','listbox');trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls',list.id);list.setAttribute('aria-label',labelText);
    const r={select,trigger,text,list,wrapper,generation:0,animation:null};records.set(select,r);
    for(const o of options(r)){const b=document.createElement('button');b.type='button';b.className='choice-option';b.dataset.value=o.value;b.textContent=o.textContent;b.tabIndex=-1;b.setAttribute('role','option');b.addEventListener('click',()=>choose(r,o.value));list.append(b);}
    select.before(wrapper);wrapper.append(trigger,select);select.classList.add('enhanced-select');select.tabIndex=-1;select.setAttribute('aria-hidden','true');
    trigger.addEventListener('click',e=>{e.preventDefault();open(r);});
    trigger.addEventListener('keydown',e=>{if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();open(r,e.key==='ArrowUp'||e.key==='End');}});
    select.addEventListener('change',sync);document.body.append(list);
    new MutationObserver(sync).observe(select,{attributes:true,attributeFilter:['disabled'],childList:true,subtree:true});
  }
  function init(config={}){
    if(initialized)return;initialized=true;reduced=config.reduced||reduced;
    document.querySelectorAll('select').forEach(enhanceSelect);sync();
    // Make the remaining semantic checkboxes and native details feel part of the same system.
    document.querySelectorAll('input[type=checkbox]').forEach(e=>e.classList.add('studio-check'));
    document.querySelectorAll('input[type=range]').forEach(input=>{
      const fill=()=>input.style.setProperty('--range-fill',((Number(input.value)-Number(input.min||0))/(Number(input.max||100)-Number(input.min||0))*100)+'%');fill();input.addEventListener('input',fill);
    });
    document.querySelectorAll('details.asset-details').forEach(details=>{
      const summary=details.querySelector('summary'),content=summary.nextElementSibling;let animation=null,gen=0;
      summary.addEventListener('click',e=>{e.preventDefault();const n=++gen,was=details.open;
        const before=content.getBoundingClientRect().height;animation?.cancel();details.open=true;
        const height=content.scrollHeight;content.style.overflow='hidden';
        const a=animate(content,[{height:(was?before:0)+'px',opacity:was?1:0},{height:(was?0:height)+'px',opacity:was?0:1}],260);animation=a;
        const done=()=>{if(gen!==n)return;details.open=!was;content.style.overflow='';};if(a)a.finished.then(done,()=>{});else done();
      });
    });
    document.addEventListener('pointerdown',e=>{if(active&&!active.list.contains(e.target)&&!active.wrapper.contains(e.target))close(false);},true);
    document.addEventListener('keydown',e=>{
      if(!active)return;const r=active,all=[...r.list.children],index=all.indexOf(document.activeElement);
      if(e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();close();return;}
      if(e.key==='Tab'){close(true,true);return;}
      if(['ArrowDown','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();e.stopImmediatePropagation();const next=e.key==='Home'?0:e.key==='End'?all.length-1:(index+(e.key==='ArrowDown'?1:-1)+all.length)%all.length;all[next]?.focus();return;}
      if((e.key==='Enter'||e.key===' ')&&index>=0){e.preventDefault();e.stopImmediatePropagation();choose(r,all[index].dataset.value);return;}
      if(e.key.length===1&&!e.ctrlKey&&!e.metaKey){e.preventDefault();const now=performance.now();search=now-searchedAt>700?e.key:search+e.key;searchedAt=now;all.find(b=>b.textContent.toLocaleLowerCase().startsWith(search.toLocaleLowerCase()))?.focus();}
    },true);
    addEventListener('resize',()=>close(false,true));
    document.addEventListener('scroll',e=>{if(active&&!active.list.contains(e.target))close(active.list.contains(document.activeElement),true);},true);
    document.addEventListener('visibilitychange',()=>{if(document.hidden)close(false,true);});
  }
  function settle(){for(const r of records.values()){r.animation?.cancel();r.animation=null;if(active!==r)r.list.hidden=true;}}
  return {init,sync,close,settle,get active(){return active?.select.id||null;}};
})();
