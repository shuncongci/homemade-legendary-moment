'use strict';
/* Mobile DevelopMotion/DevelopView reference: separate REAL engine work from display
 * clocks. No result is created by a timer. Canvas is bounded, preview-only, and never
 * read by export. A real color image/scale is required before its reveal can begin. */
const ProcessUI = (() => {
  let initialized=false, focusedId=null, hudId=null, dismissed=null, lastSequence='', tick=0;
  let canvas,ctx,raf=0,lastFrame=0,visualJob=null,visualGeneration=0,source=null,sourceUrl='',images={},loaded={},reveals={},finishing=null,presentationEnabled=true;
  let timeline=null,clock=null;
  const tracked=new Set(), nodes=new Map(), stepNodes=new Map();
  const q=id=>document.getElementById(id), clamp=x=>Math.max(0,Math.min(1,x)), ease=x=>{x=clamp(x);return x*x*(3-2*x);};
  const text=(id,value)=>{const el=q(id);if(el&&el.textContent!==String(value))el.textContent=String(value);};
  const duration=s=>{s=Math.max(0,Number(s)||0);return s>=60?Math.floor(s/60)+'分 '+Math.floor(s%60)+'秒':s.toFixed(s<10?1:0)+'秒';};
  const node=(tag,cls,value)=>{const e=document.createElement(tag);e.className=cls||'';if(value!==undefined)e.textContent=value;return e;};
  const phase=j=>j?.progress||{label:j?.phase||'准备处理',message:'正在计算，此步骤暂不显示百分比。',steps:[],events:[],visuals:{},indeterminate:true};
  const isTerminal=j=>['done','error','canceled','interrupted'].includes(j.status);
  function summary(j){
    const p=phase(j),u=p.units,d=p.detail||{},parts=[],stopped=isTerminal(j);
    if(u&&!stopped)parts.push(`${u.completed} / ${u.total} ${{tiles:'块',frames:'帧',points:'个保护点',files:'份文件'}[u.unit]||'项'} · ${(u.completed/u.total*100).toFixed(1)}%（当前步骤）`);
    else if(j.status==='queued')parts.push('等待引擎');
    else if(!stopped)parts.push('正在计算 · 此步骤暂不显示百分比');
    if(stopped&&j.status!=='done'){
      const last=u||d;
      if(Number.isFinite(last.completed)&&Number.isFinite(last.total)&&last.completed>=0&&last.total>0&&last.completed<=last.total)parts.push(`最后上报 ${last.completed} / ${last.total} ${{tiles:'块',frames:'帧',points:'个保护点',files:'份文件'}[last.unit]||'项'}`);
    }
    if(!stopped){
      if(d.pass)parts.push(`尺度 ${d.pass}${d.passes?' / '+d.passes:''}`);
      if(d.pointIndex)parts.push(`保护点 ${d.pointIndex}${d.points?' / '+d.points:''}`);
      if(d.fps>0)parts.push(d.fps+' fps');
      if(d.etaSeconds>0)parts.push('按已处理帧估计剩余 '+duration(d.etaSeconds));
    }
    if(j.status==='done')parts.push(['photo','video'].includes(j.action)?'已保存完整结果':'分析结果已就绪');
    if(j.status==='error')parts.push(j.error||'处理失败，原片保留');
    if(j.status==='canceled')parts.push('已取消，本次未发布新成片');
    if(j.status==='interrupted')parts.push('已中断，本次未发布新成片；不会自动重试');
    return parts.join(' · ');
  }
  function metricsSummary(p){
    const m=p.metrics||{},parts=[],labels={workerStartupSeconds:'计算进程启动',workerSetupSeconds:'计算环境初始化',pipelineImportSeconds:'处理组件加载',runtimeProbeSeconds:'计算环境检查（含以下分项，请勿重复相加）',probeTorchImportSeconds:'检查·计算库加载（PyTorch）',probeVisionImportSeconds:'检查·图像库加载（torchvision）',probeThreadConfigSeconds:'检查·线程设置',probeKernelSeconds:'检查·设备试运行',torchImportSeconds:'处理时计算库加载（PyTorch）',runtimeSetupSeconds:'计算设备设置',threadConfigSeconds:'CPU 线程设置',decodeOrientSeconds:'解码/方向',rgbResizeSeconds:'RGB/缩放',iccSeconds:'ICC校正',sourceHashSeconds:'源校验',modelLoadSeconds:'深度模型加载',inputSeconds:'输入准备',deviceTransferSeconds:'设备传输',inferenceSeconds:'深度模型计算',outputSeconds:'结果回传',normalizeSeconds:'深度范围整理'};
    if(m.runtimeProbeAttempts>0)parts.push(`计算环境检查 ${m.runtimeProbeAttempts} 次${m.runtimeProbeFailures?'，未通过 '+m.runtimeProbeFailures+' 次':''}`);
    if(m.runtimeReused)parts.push('复用已检查的计算环境');
    if(m.concurrency>0)parts.push(`保护点有效并发 ${m.concurrency}`);
    if(Number.isFinite(m.reusedPoints))parts.push(`复用 ${m.reusedPoints} 个保护点`);
    if(Number.isFinite(m.pendingPoints))parts.push(`本次需计算 ${m.pendingPoints} 个保护点`);
    if(m.analysisWidth>0&&m.analysisHeight>0)parts.push(`深度尺寸 ${m.analysisWidth} × ${m.analysisHeight} · FP32`);
    if(m.cacheSource==='memory'||m.cacheSource==='disk')parts.push(m.cacheSource==='memory'?'复用内存深度':'复用磁盘深度');
    if(typeof m.modelReused==='boolean')parts.push(m.modelReused?'复用已加载的深度模型（CPU）':'首次加载深度模型');
    for(const [key,label] of Object.entries(labels))if(Number.isFinite(m[key])&&m[key]>=0)parts.push(label+' '+m[key].toFixed(3)+'秒');
    return parts.join(' · ');
  }
  function bar(el,j){
    if(!el)return;const p=phase(j),u=p.units,done=j.status==='done';
    el.classList.toggle('indeterminate',!isTerminal(j)&&!u);el.classList.toggle('failed',j.status==='error');
    el.setAttribute('role','progressbar');el.setAttribute('aria-label',p.label+(u?'，当前步骤':'，阶段状态'));
    el.setAttribute('aria-valuemin','0');el.setAttribute('aria-valuemax',u?String(u.total):'100');
    if(u||done)el.setAttribute('aria-valuenow',String(u?u.completed:100));else el.removeAttribute('aria-valuenow');
    el.setAttribute('aria-valuetext',summary(j));el.firstElementChild.style.transform=`scaleX(${u?clamp(u.completed/u.total):done?1:0})`;
  }
  function queueText(){
    const jobs=S.jobs,done=jobs.filter(j=>tracked.has(j.id)&&j.status==='done').length,errors=jobs.filter(j=>tracked.has(j.id)&&j.status==='error').length,canceled=jobs.filter(j=>tracked.has(j.id)&&j.status==='canceled').length;
    return `${S.batchQueue.length} 待提交 · ${jobs.filter(j=>j.status==='queued').length} 排队 · ${jobs.filter(j=>j.status==='running').length} 计算${tracked.size?` · ${done} 完成${errors?' · '+errors+' 失败':''}${canceled?' · '+canceled+' 取消':''}`:''}`;
  }
  function selectJob(){
    const currentJobs=S.jobs.filter(j=>j.inputId===S.current);
    return currentJobs.find(j=>j.status==='running')||currentJobs.find(j=>!isTerminal(j))||S.jobs.find(j=>j.status==='running')||S.jobs.find(j=>!isTerminal(j))||[...currentJobs].reverse().find(j=>tracked.has(j.id))||null;
  }
  function update(){
    if(!initialized)return;const j=selectJob();hudId=j?.id||null;
    const show=!!j&&dismissed!==j.id;const hud=q('process-hud');hud.classList.toggle('shown',show);hud.inert=!show;hud.setAttribute('aria-hidden',String(!show));
    if(j){const p=phase(j);hud.dataset.status=j.status;text('process-title',p.label);text('process-detail',summary(j));text('process-name',j.name);bar(q('process-meter'),j);text('process-queue',queueText());
      text('process-dismiss',isTerminal(j)?'收起':presentationEnabled?'隐藏过程画面':'显示过程画面');
      const live=`${j.id}:${p.code}:${j.status}`;if(lastSequence!==live){lastSequence=live;text('process-announcement',`${j.name}：${p.label}。${p.message}`);}
      q('stage').setAttribute('aria-busy',String(j.inputId===S.current&&!isTerminal(j)));
    }else q('stage').removeAttribute('aria-busy');
    const visual=S.jobs.find(j=>j.inputId===S.current&&j.status==='running');
    if(visual&&visual.action==='photo'||visual&&['depth','subject','composition'].includes(visual.action))syncVisual(visual);
    else if(visualJob){const done=S.jobs.find(j=>j.id===visualJob);if(done?.inputId!==S.current)resetVisual();else if(done?.status==='done'&&done.action==='photo')finishVisual(done);else if(!finishing)resetVisual();}
    if(q('progress-dialog').open)renderDetails();clockTick();
  }
  function track(j){tracked.add(j.id);focusedId=j.id;dismissed=null;presentationEnabled=true;update();}
  function open(id=hudId,source='process-open'){
    focusedId=id||[...S.jobs].reverse()[0]?.id||null;renderDetails();Motion.open('progress-dialog',source);
  }
  function renderDetails(){
    const list=q('progress-jobs');const ids=new Set(S.jobs.map(j=>j.id));
    for(const [id,r] of nodes)if(!ids.has(id)){r.remove();nodes.delete(id);}
    for(const j of [...S.jobs].reverse()){
      let b=nodes.get(j.id);if(!b){b=node('button','progress-job');b.type='button';b.dataset.jobId=j.id;b.append(node('span','progress-job-name',j.name),node('small','progress-job-state'));b.addEventListener('click',()=>{focusedId=j.id;renderDetails();});list.append(b);nodes.set(j.id,b);}
      b.classList.toggle('active',focusedId===j.id);b.setAttribute('aria-pressed',String(focusedId===j.id));b.lastElementChild.textContent=phase(j).label;
    }
    const j=S.jobs.find(j=>j.id===focusedId)||selectJob()||S.jobs.at(-1);
    q('progress-empty').classList.toggle('hidden',!!j);q('progress-content').classList.toggle('hidden',!j);if(!j)return;
    focusedId=j.id;const p=phase(j);text('progress-job-name',j.name);text('progress-phase',p.label);text('progress-message',p.message);text('progress-units',summary(j));text('progress-queue',queueText());bar(q('progress-meter'),j);
    const done=p.steps.filter(s=>s.status==='done').length,skipped=p.steps.filter(s=>s.status==='skipped').length;
    text('progress-count',`${done} / ${p.steps.length} 步骤完成（非总进度）${skipped?' · '+skipped+' 步骤未执行':''}`);
    const root=q('progress-steps');if(root.dataset.job!==j.id){root.replaceChildren();stepNodes.clear();root.dataset.job=j.id;}
    for(const [i,s] of p.steps.entries()){
      let row=stepNodes.get(s.id);if(!row){row=node('li','progress-step');row.append(node('span','progress-step-number',String(i+1)),node('strong','',s.label),node('small','progress-step-state'));root.append(row);stepNodes.set(s.id,row);}
      row.dataset.state=s.status;row.lastElementChild.textContent=({done:'完成',running:'进行中',pending:'等待',skipped:'本次未执行',error:'失败',canceled:'取消'}[s.status]||s.status)+(s.finishedAt&&s.startedAt?' · '+duration(s.finishedAt-s.startedAt):'');
    }
    const events=q('progress-events');if(events.dataset.seq!==j.id+':'+p.sequence){events.dataset.seq=j.id+':'+p.sequence;events.replaceChildren();for(const e of p.events.slice(-12)){const li=node('li');li.append(node('time','',new Date(e.at*1000).toLocaleTimeString('zh-CN',{hour12:false})),node('span','',e.label));events.append(li);}}
    const cancel=q('progress-cancel');cancel.disabled=isTerminal(j)||!!j.cancelRequested;cancel.textContent=j.cancelRequested?'已请求取消':'取消此任务';cancel.dataset.jobId=j.id;
  }
  function clockTick(){
    if(!initialized)return;const now=Date.now()/1000;
    for(const [id,jid] of [['process-elapsed',hudId],['progress-elapsed',focusedId]]){const j=S.jobs.find(j=>j.id===jid);if(!j)continue;const start=j.startedAt||j.createdAt;const elapsed=(isTerminal(j)?j.finishedAt||start+(j.elapsedSeconds||0):now)-start;text(id,(j.status==='queued'?'已等待 ':'已用 ')+duration(elapsed));}
    // Continuous UI clocks do not mutate job counters or render the full workbench.
  }
  function load(url){return new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve(image);image.onerror=reject;image.src=url;});}
  function resetVisual(){
    visualGeneration++;clock=null;timeline=null;cancelAnimationFrame(raf);raf=0;visualJob=null;finishing=null;source=null;sourceUrl='';images={};loaded={};reveals={};if(canvas){canvas.classList.remove('shown');canvas.setAttribute('aria-hidden','true');ctx.clearRect(0,0,canvas.width,canvas.height);}text('process-presentation','');
  }
  function syncVisual(j){
    if(visualJob!==j.id){resetVisual();visualJob=j.id;if(typeof MobileMotion!=='undefined'){timeline=new MobileMotion.Timeline();if(j.action==='photo'&&j.options.colorEnabled)timeline.startColor(j.options.blurEnabled);}}
    const gen=visualGeneration,p=phase(j),item=S.inputs.find(i=>i.id===j.inputId);if(!item)return;
    if(sourceUrl!==item.previewUrl){sourceUrl=item.previewUrl;load(sourceUrl).then(im=>{if(gen!==visualGeneration)return;source=im;schedule();},()=>{});}
    for(const [kind,v] of Object.entries(p.visuals||{}))if(v.url&&loaded[kind]!==v.url){
      loaded[kind]=v.url;load(v.url).then(im=>{if(gen!==visualGeneration||loaded[kind]!==v.url)return;if(kind==='bokeh')images.previous=images.bokeh||images.color||source;images[kind]=im;reveals[kind]=performance.now();if(kind==='color')timeline?.colorCompleted();schedule();},()=>{});
    }
    if(p.stage==='bokeh'&&p.visuals?.grid)timeline?.startBokeh();
    if(!presentationEnabled){canvas.classList.remove('shown');return;}
    canvas.classList.add('shown');canvas.setAttribute('aria-hidden','false');schedule();
    const label=p.code==='gan_color'?'画面预览：等待调色结果':images.bokeh?'画面预览：逐步呈现虚化效果，尚非最终成片':images.color?'画面预览：调色结果':p.stage==='bokeh'?'画面预览：景深效果；处理进度见当前步骤':'加载动画不表示完成比例';text('process-presentation',label);
  }
  function finishVisual(j){
    if(finishing||!presentationEnabled||!j.result?.previewUrl){if(!finishing)resetVisual();return;}
    const gen=visualGeneration;finishing={loading:true,at:0,image:null};load(j.result.previewUrl).then(im=>{if(gen!==visualGeneration||!finishing)return;finishing={image:im,at:performance.now()};if(timeline){if(timeline.stage==='COLOR_WAIT')timeline.enter('IDLE');timeline.resultCompleted(!!j.result.blurRendered);}schedule();},()=>{if(gen===visualGeneration)resetVisual();});
  }
  function schedule(){if(!raf&&!document.hidden)raf=requestAnimationFrame(draw);}
  function draw(now){
    raf=0;if(!visualJob||!source||!presentationEnabled)return;
    if(now-lastFrame<32){schedule();return;}lastFrame=now;
    const j=S.jobs.find(j=>j.id===visualJob);if(!j||j.inputId!==S.current||['canceled','error'].includes(j.status)){resetVisual();return;}
    const p=phase(j),reduce=Motion.reduced(),stage=q('stage'),w=Math.max(1,Math.min(960,stage.clientWidth)),h=Math.max(1,Math.round(stage.clientHeight*w/Math.max(1,stage.clientWidth)));
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
    const dt=clock===null?0:Math.max(0,now-clock);clock=now;timeline?.advance(dt);if(reduce)timeline?.reduceMotion();
    ctx.clearRect(0,0,w,h);ctx.fillStyle='#0d0e0f';ctx.fillRect(0,0,w,h);
    const flat=im=>{const scale=Math.min(w/im.width,h/im.height)*.94;ctx.drawImage(im,(w-im.width*scale)/2,(h-im.height*scale)/2,im.width*scale,im.height*scale);};
    let needs=!!timeline?.needsFrames;
    if(timeline&&['COLOR_WAIT','COLOR_FINISH'].includes(timeline.stage)){
      MobileMotion.color(ctx,source,images.color,w,h,timeline,reduce);
      text('process-presentation',timeline.stage==='COLOR_FINISH'?'画面预览：正在呈现调色效果，不表示计算进度':'画面预览：等待调色结果');
    }else if(timeline?.showsBokeh&&p.visuals?.grid){
      const age=images.bokeh?(reduce?10000:now-reveals.bokeh):-10000;
      MobileMotion.relief(ctx,images.color||source,p.visuals.grid,w,h,{lift:reduce&&timeline.stage==='BOKEH_WAIT'?.78:timeline.bokehLift,previous:images.previous,pass:images.bokeh,final:finishing?.image,fade:timeline.stage==='BOKEH_FINISH'?MobileMotion.ease(timeline.progress):0,detail:reduce?1:timeline.bokehDetail,age,point:j.options.point||{x:.5,y:.5}});
      needs=needs||!reduce&&images.bokeh&&age<720;
      text('process-presentation',timeline.stage==='BOKEH_FINISH'?'画面预览：正在呈现景深效果':'画面预览：呈现已计算的景深与主体保护效果');
    }else if(finishing?.image){
      if(timeline?.stage==='DONE'){resetVisual();return;}flat(finishing.image);
    }else if(images.color)flat(images.color);
    else if(images.depth&&p.stage==='depth')flat(images.depth);
    else flat(source);
    if(finishing?.loading)needs=true;if(needs)schedule();
  }
  function init(){
    if(initialized)return;initialized=true;canvas=q('process-canvas');ctx=canvas.getContext('2d',{alpha:true});
    q('process-open').addEventListener('click',()=>open());q('process-dismiss').addEventListener('click',()=>{const j=S.jobs.find(j=>j.id===hudId);if(j&&isTerminal(j)){dismissed=j.id;update();}else{presentationEnabled=!presentationEnabled;if(!presentationEnabled){cancelAnimationFrame(raf);raf=0;canvas.classList.remove('shown');text('process-presentation','过程画面已隐藏，计算仍在继续');}else update();}});
    q('progress-cancel').addEventListener('click',()=>{const id=q('progress-cancel').dataset.jobId;if(id)cancelJob(id);});
    tick=setInterval(clockTick,250);addEventListener('resize',schedule);document.addEventListener('visibilitychange',()=>{cancelAnimationFrame(raf);raf=0;clock=null;if(!document.hidden){for(const k of Object.keys(reveals))reveals[k]=0;schedule();}});
    update();
  }
  return {init,update,track,open,summary,resetVisual,get tracked(){return [...tracked];}};
})();
