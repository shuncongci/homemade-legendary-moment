'use strict';
// Viewing only. Never substitute the original for an undecoded result or mutate exports.
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.VideoCompare=api;})(typeof globalThis!=='undefined'?globalThis:this,()=>{
 const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
 const clock=t=>{t=Math.max(0,Math.floor(t||0));return Math.floor(t/60).toString().padStart(2,'0')+':'+(t%60).toString().padStart(2,'0');};
 const videoReady=media=>media.readyState>=2&&media.videoWidth>0&&media.videoHeight>0;
 class Controller{
  constructor(el,{raf=callback=>requestAnimationFrame(callback),caf=id=>cancelAnimationFrame(id),onUnavailable=()=>{}}={}){
   this.el=el;this.raf=raf;this.caf=caf;this.onUnavailable=onUnavailable;this.run=0;this.active=false;this.wanted=false;this.key=null;this.frame=null;this.problem='';this.beforeFailed=false;this.afterFailed=false;
   this.scrubbing=false;this.scrubRatio=0;this.pendingTime=null;this.buffering=false;this.starting=false;
   const {before,after,play,seek,mute}=el;
   before.muted=true;after.muted=false;
   for(const media of [before,after]){
    for(const event of ['loadedmetadata','loadeddata','canplay'])media.addEventListener(event,()=>{
     if(!this.active)return;
     this.inspect(media);this.align();this.update();this.resumeWhenReady();
    });
    media.addEventListener('error',()=>{if(!this.active)return;if(media===before)this.beforeFailed=true;else this.afterFailed=true;if(media===after||this.compare)this.pause();this.update();});
    media.addEventListener('waiting',()=>{if(this.active&&this.wanted&&(media===after||this.compare))this.waitForData();});
   }
   after.addEventListener('timeupdate',()=>{if(this.active){this.align();this.update();}});
   after.addEventListener('seeked',()=>{if(!this.active)return;if(this.pendingTime!==null&&Math.abs(after.currentTime-this.pendingTime)<.1)this.pendingTime=null;this.align(true);this.update();this.resumeWhenReady();});
   before.addEventListener('seeked',()=>{if(this.active){this.align();this.update();this.resumeWhenReady();}});
   after.addEventListener('ended',()=>{if(this.active){this.pause();this.align(true);this.update();}});
   after.addEventListener('ratechange',()=>{before.playbackRate=after.playbackRate;});
   play.addEventListener('click',()=>this.toggle());
   // Pointer movement is a draft; only release commits one decoder seek.
   seek.addEventListener('pointerdown',()=>{if(!this.active)return;this.pause();this.scrubbing=true;this.scrubRatio=Number(seek.value)/1000;});
   seek.addEventListener('input',()=>{const ratio=Number(seek.value)/1000;if(this.scrubbing){this.scrubRatio=ratio;this.update();}else this.seek(ratio);});
   const commit=()=>{if(!this.scrubbing)return;const ratio=this.scrubRatio;this.scrubbing=false;this.seek(ratio);};
   for(const event of ['change','pointerup','lostpointercapture','blur'])seek.addEventListener(event,commit);
   seek.addEventListener('pointercancel',()=>{this.scrubbing=false;this.pendingTime=null;this.update();});
   mute.addEventListener('click',()=>{after.muted=!after.muted;this.update();});
  }
  inspect(media){
   // Audio can reach HAVE_ENOUGH_DATA with a 0x0 video track and no MediaError.
   if(media.error||media.readyState>=2&&!videoReady(media)){
    if(media===this.el.after)this.afterFailed=true;else this.beforeFailed=true;
    if(this.wanted&&(media===this.el.after||this.compare))this.pause();
   }else if(videoReady(media)){if(media===this.el.after)this.afterFailed=false;else this.beforeFailed=false;}
  }
  duration(){const n=this.el.after.duration;return Number.isFinite(n)&&n>0?n:0;}
  ready(){const {before,after}=this.el;return this.active&&!(this.compare&&this.sameSource)&&!this.afterFailed&&videoReady(after)&&this.duration()>0&&(!this.compare||!this.beforeFailed&&videoReady(before));}
  show({key,before,after,download,compare=true,split=50,compatibility='',canPreview=false,autoPreview=false,previewPending='',previewError='',previewBlocked='',previewUpgrade=false}){
   const changed=key!==this.key||before!==this.beforeUrl||after!==this.afterUrl;
   if(changed){
    this.pause();this.scrubbing=false;this.pendingTime=null;this.key=key;this.beforeUrl=before;this.afterUrl=after;this.beforeFailed=false;this.afterFailed=false;this.problem='';
    this.el.before.src=before;this.el.after.src=after;
   }
   const modeChanged=this.compare!==compare;this.compatibility=compatibility;this.previewUpgrade=previewUpgrade;this.autoPreview=autoPreview;this.previewPending=previewPending;this.previewError=previewError;this.previewBlocked=previewBlocked;this.canPreview=canPreview;this.active=true;this.compare=compare;this.sameSource=before===after;
   this.el.before.preload=compare?'auto':'none';this.el.after.preload='auto';
   this.el.frame.classList.remove('hidden');this.el.controls.classList.remove('hidden');
   this.el.before.classList.toggle('hidden',!compare);this.el.line.classList.toggle('hidden',!compare);this.el.beforeLabel.classList.toggle('hidden',!compare);
   this.el.download.href=download;this.el.before.muted=true;this.setSplit(split);
   if(modeChanged){this.pause();this.align(true);}
   this.inspect(this.el.after);if(compare)this.inspect(this.el.before);this.update();
  }
  hide(){
   if(!this.active)return;this.pause();this.active=false;this.scrubbing=false;this.pendingTime=null;
   this.el.before.preload='none';this.el.after.preload='none';
   this.el.frame.classList.add('hidden');this.el.controls.classList.add('hidden');this.el.note.classList.add('hidden');
  }
  setSplit(value){const split=clamp(Number(value)||0,0,100),clip=this.compare?`inset(0 0 0 ${split}%)`:'none';this.el.after.style.clipPath=clip;if(this.el.placeholder)this.el.placeholder.style.clipPath=clip;this.el.line.style.left=split+'%';}
  align(force=false){
   if(!this.active||!this.compare||this.el.before.readyState<1||this.beforeFailed||this.el.after.seeking||this.scrubbing)return;
   const {before,after}=this.el,limit=Number.isFinite(before.duration)?before.duration:Infinity;
   const time=clamp(after.currentTime||0,0,Math.max(0,limit-.001));let drift=time-before.currentTime;
   // Small clock differences are normal. Seeking every 60ms caused needless reads/waits.
   if(Math.abs(drift)>(this.wanted&&!force ? .35 : .04)&&!before.seeking){try{before.currentTime=time;drift=0;}catch(_){/* Retry after metadata, not with a timer. */}}
   const rate=after.playbackRate||1;
   before.playbackRate=this.wanted&&!this.buffering&&!before.seeking?rate*(1+clamp(drift*.2,-.04,.04)):rate;
  }
  stopMedia(){++this.run;this.starting=false;if(this.frame!==null){this.caf(this.frame);this.frame=null;}this.el.before.pause();this.el.after.pause();}
  pause(){this.wanted=false;this.buffering=false;this.stopMedia();this.el.before.playbackRate=this.el.after.playbackRate||1;this.update();}
  waitForData(){if(this.buffering)return;this.buffering=true;this.stopMedia();this.update();}
  resumeWhenReady(){
   const {before,after}=this.el;
   if(this.active&&this.wanted&&this.buffering&&this.ready()&&!after.seeking&&after.readyState>=3&&(!this.compare||!before.seeking&&before.readyState>=3))this.start();
  }
  async start(){
   if(!this.active||!this.wanted||this.starting||!this.ready())return;
   this.buffering=false;this.problem='';this.starting=true;const run=++this.run;this.align();this.update();
   try{
    await Promise.all([this.el.after.play(),this.compare?this.el.before.play():Promise.resolve()]);
    if(run!==this.run||!this.active||!this.wanted){if(!this.wanted){this.el.before.pause();this.el.after.pause();}return;}
    this.starting=false;
    const tick=()=>{if(run!==this.run||!this.active||!this.wanted||this.buffering)return;this.align();this.update();this.frame=this.raf(tick);};tick();
   }catch(_){if(run===this.run&&this.active){this.pause();this.problem='播放未能开始，请重试；仍可下载已完成视频。';this.update();}}
  }
  async toggle(){
   if(this.wanted){this.pause();return;}
   if(!this.ready())return;
   if(this.el.after.ended||this.el.after.currentTime>=this.duration()-.01)this.seek(0);
   this.problem='';this.wanted=true;return this.start();
  }
  seek(ratio){
   if(!this.active||!this.duration())return;
   this.pause();this.problem='';const target=clamp(Number(ratio)||0,0,1)*this.duration();this.pendingTime=target;this.el.after.currentTime=target;
   this.align(true);this.update();
  }
  update(){
   const {play,seek,current,total,mute,note,message,placeholder}=this.el,ready=this.ready(),duration=this.duration();
   play.disabled=!ready&&!this.wanted;seek.disabled=!ready&&!this.scrubbing&&this.pendingTime===null;
   const label=this.wanted?'暂停':'播放';play.setAttribute('aria-label',label);play.setAttribute('title',label);play.querySelector?.('use')?.setAttribute('href',this.wanted?'#i-pause':'#i-play');play.setAttribute('aria-pressed',String(this.wanted));play.setAttribute('aria-busy',String(this.buffering));
   const shown=this.scrubbing?this.scrubRatio*duration:this.pendingTime??this.el.after.currentTime;
   current.textContent=clock(shown);total.textContent=clock(duration);if(!this.scrubbing)seek.value=String(Math.round(duration?clamp(shown/duration,0,1)*1000:0));
   mute.textContent=this.el.after.muted?'开启声音':'静音';mute.setAttribute('aria-pressed',String(this.el.after.muted));
   const failure=this.compare&&this.sameSource?'原片与成片指向相同文件，已停止对比；请重新选择已完成版本。':this.afterFailed?(this.canPreview?'成片编码无法在此浏览器显示视频画面（可能仅载入音轨）。请生成所选完整成片的兼容播放预览；不重新显影，下载仍为原MP4。':'成片编码无法在此浏览器显示视频画面。可下载用本地播放器查看；不会用原片冒充处理后画面。'):this.compare&&this.beforeFailed?'原片编码无法在此浏览器解码，暂不能同步对比。可切换「成片」单独播放，或在「播放原片」里明确生成兼容预览后再对比。':'';
   const loading=this.active&&!ready&&!failure?'正在读取本地'+(this.compare?'原片和成片':'成片')+'并解码画面，不会重新显影。':'';
   const preparing=this.previewPending||(this.autoPreview&&this.afterFailed&&!this.sameSource?'正在自动准备此版本的兼容预览；不重新显影。':'');
   const previewFailure=this.afterFailed&&this.previewError?'兼容预览未完成：'+this.previewError+' 正式MP4保留；可点击重试，不会自动反复转换。':'';
   if(placeholder){placeholder.classList.toggle('hidden',videoReady(this.el.after)&&!this.afterFailed);placeholder.textContent=preparing?'正在准备当前版本的兼容预览…':previewFailure?'兼容预览未完成 · 可重试':this.afterFailed?(this.canPreview?'处理后画面暂不可解码 · 请生成兼容预览':this.previewBlocked||'兼容预览暂不可用 · 可下载正式MP4'):'正在载入处理后画面…';}
   if(this.el.compatible){this.el.compatible.classList.toggle('hidden',!this.active||(!this.previewUpgrade&&(!this.afterFailed||!!this.compatibility))||this.sameSource||!!preparing);this.el.compatible.disabled=!this.canPreview;this.el.compatible.textContent=this.previewUpgrade?'重新生成清晰预览':this.previewError?'重试兼容预览':'生成兼容预览';this.el.compatible.setAttribute('title',this.previewBlocked||'仅生成当前成片的查看副本，不重新显影');}
   message.textContent=preparing||previewFailure||(this.afterFailed?this.previewBlocked:'')||failure||this.problem||(this.buffering?'正在读取本地视频并等待两路画面就绪，就绪后自动继续。':'')||loading||this.compatibility||'';note.classList.toggle('hidden',!this.active||!message.textContent);
   if(this.active&&this.afterFailed&&this.autoPreview&&!this.sameSource)this.onUnavailable();
  }
 }
 return {Controller,clock,videoReady};
});
