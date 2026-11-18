'use strict';
/* Ports of mobile DevelopMotion.kt / DevelopMath / DepthVisualMath / SpaceCamera.
 * Display clocks NEVER complete compute or create/export pixels. Canvas preview only.
 * Triangle texture interpolation approximates Android's per-quad homography on small cells.
 */
const MobileMotion=(()=>{
 const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v)),ease=t=>{t=clamp(t);return t*t*(3-2*t);},phase=(p,a,b)=>ease((p-a)/(b-a));
 const field=(x,y,t)=>{const xx=x+.07*Math.sin(y*9+t*.73),yy=y+.09*Math.cos(x*7-t*.61);return (Math.sin(xx*13+yy*4+t*1.4)+Math.sin(yy*17-xx*5-t*1.1)+.55*Math.sin(xx*31+yy*23+t*.8))/2.55;};
 const pigment=(x,y,t)=>.06+.94*ease((field(x,y,t)+.42)/.84);
 const colorScale=p=>1-.25*phase(p,0,.18)*(1-phase(p,.90,1));
 const colorOffset=p=>-.14*phase(p,0,.18)*(1-phase(p,.90,1));
 const revealed=(x,y,p)=>p<=.67?0:p>=.94?1:ease((phase(p,.67,.94)*1.32-Math.hypot(x-.68,y-.48)-.025*field(x,y,p*2)+.045)/.09);
 const project=(x,y,z,yaw,pitch)=>{const xx=x*Math.cos(yaw)+z*Math.sin(yaw),zz=-x*Math.sin(yaw)+z*Math.cos(yaw),yy=y*Math.cos(pitch)-zz*Math.sin(pitch),d=y*Math.sin(pitch)+zz*Math.cos(pitch),p=3.8/(3.8-d);return [xx*p,yy*p,d];};
 const focusProject=(x,y,f,lift,aspect)=>project((x-.5)*Math.min(1,aspect),(y-.5)*Math.min(1,1/aspect),(.012+.32*clamp(f))*lift,.28*lift,-.30*lift);
 class Timeline {
  constructor(){this.reset();}
  reset(){this.stage='IDLE';this.elapsed=0;this.seconds=0;this.blurRequested=false;this.finalReady=false;this.colorReady=false;this.bokehReady=false;this.finishLift=0;this.finishDetail=0;}
  get showsBokeh(){return ['BOKEH_WAIT','BOKEH_FINISH'].includes(this.stage);}
  get needsFrames(){return this.stage==='COLOR_WAIT'||['COLOR_FINISH','BOKEH_FINISH'].includes(this.stage)||(this.stage==='BOKEH_WAIT'&&this.elapsed<550);}
  get progress(){return this.stage==='COLOR_FINISH'?clamp(this.elapsed/3300):this.stage==='BOKEH_FINISH'?clamp(this.elapsed/420):0;}
  get bokehLift(){return this.stage==='BOKEH_WAIT'?ease(this.elapsed/550)*.78:this.stage==='BOKEH_FINISH'?this.finishLift*(1-ease(this.progress)):0;}
  get bokehDetail(){return this.stage==='BOKEH_WAIT'?ease(this.elapsed/550):this.stage==='BOKEH_FINISH'?this.finishDetail:0;}
  enter(stage){if(stage==='BOKEH_FINISH'){this.finishLift=this.bokehLift;this.finishDetail=this.bokehDetail;}this.stage=stage;this.elapsed=0;}
  startColor(blur){this.reset();this.blurRequested=blur;this.enter('COLOR_WAIT');}
  colorCompleted(){if(this.stage!=='COLOR_WAIT')return;this.colorReady=true;this.enter('COLOR_FINISH');}
  startBokeh(){if(this.bokehReady||this.stage==='DONE')return;this.bokehReady=true;this.blurRequested=true;if(['IDLE','COLOR_HOLD'].includes(this.stage))this.enter('BOKEH_WAIT');}
  resultCompleted(rendered){if(this.finalReady)return;this.finalReady=true;this.blurRequested=rendered;if(this.stage==='IDLE')this.enter('DONE');else if(['COLOR_HOLD','BOKEH_WAIT'].includes(this.stage))this.enter(rendered&&this.bokehReady?'BOKEH_FINISH':'DONE');}
  advance(ms){if(!Number.isFinite(ms)||ms<0)throw Error('invalid clock');let remaining=ms;while(remaining>0&&this.needsFrames){const duration=this.stage==='COLOR_FINISH'?3300:this.stage==='BOKEH_FINISH'?420:Infinity,step=Math.min(remaining,Math.max(0,duration-this.elapsed));this.seconds=(this.seconds+step/1000)%3600;this.elapsed+=step;remaining-=step;if(this.stage==='COLOR_FINISH'&&this.elapsed>=3300)this.enter(this.blurRequested&&this.bokehReady?(this.finalReady?'BOKEH_FINISH':'BOKEH_WAIT'):this.finalReady?'DONE':'COLOR_HOLD');else if(this.stage==='BOKEH_FINISH'&&this.elapsed>=420)this.enter('DONE');}}
  reduceMotion(){if(this.finalReady)this.enter('DONE');else if(this.stage==='COLOR_FINISH'&&this.colorReady)this.enter(this.bokehReady?'BOKEH_WAIT':'COLOR_HOLD');}
 }
 class Camera {
  constructor(){this.gyro=false;this.reset();}
  reset(){this.yaw=this.gyro?0:.38;this.pitch=this.gyro?0:-.14;this.zoom=1;this.panX=this.panY=this.parallaxYaw=this.parallaxPitch=0;}
  setGyro(value){if(this.gyro===value)return;this.gyro=value;this.yaw=value?0:.38;this.pitch=value?0:-.14;this.panX=this.panY=this.parallaxYaw=this.parallaxPitch=0;}
  releaseGyro(){this.yaw=this.displayYaw;this.pitch=this.displayPitch;this.gyro=false;this.parallaxYaw=this.parallaxPitch=0;}
  get displayYaw(){return clamp(this.yaw+this.parallaxYaw,-.85,.85);}
  get displayPitch(){return clamp(this.pitch+this.parallaxPitch,-.55,.55);}
  rotate(dx,dy){if(!Number.isFinite(dx)||!Number.isFinite(dy))throw Error('invalid camera delta');const yl=this.gyro?.59:.85,pl=this.gyro?.37:.55;this.yaw=clamp(this.yaw+dx*1.8,-yl,yl);this.pitch=clamp(this.pitch-dy,-pl,pl);}
  transform(factor,ox=.5,oy=.5,nx=.5,ny=.5){if(![factor,ox,oy,nx,ny].every(Number.isFinite)||factor<=0)throw Error('invalid camera transform');const next=clamp(this.zoom*factor,.5,4),ratio=next/this.zoom;this.zoom=next;const limit=Math.min(1.5,.45*this.zoom);this.panX=clamp((nx-.5)+(this.panX-(ox-.5))*ratio,-limit,limit);this.panY=clamp((ny-.5)+(this.panY-(oy-.5))*ratio,-limit,limit);}
 }
 function triangle(ctx,im,s,d){
  const [a,b,c]=s,[u,v,w]=d,den=(b[0]-a[0])*(c[1]-a[1])-(c[0]-a[0])*(b[1]-a[1]);if(Math.abs(den)<1e-8)return;
  const x1=b[0]-a[0],y1=b[1]-a[1],x2=c[0]-a[0],y2=c[1]-a[1],dx1=v[0]-u[0],dy1=v[1]-u[1],dx2=w[0]-u[0],dy2=w[1]-u[1];
  const aa=(dx1*y2-dx2*y1)/den,bb=(dy1*y2-dy2*y1)/den,cc=(dx2*x1-dx1*x2)/den,dd=(dy2*x1-dy1*x2)/den;
  ctx.save();ctx.beginPath();ctx.moveTo(...u);ctx.lineTo(...v);ctx.lineTo(...w);ctx.closePath();ctx.clip();ctx.transform(aa,bb,cc,dd,u[0]-aa*a[0]-cc*a[1],u[1]-bb*a[0]-dd*a[1]);ctx.drawImage(im,0,0);ctx.restore();
 }
 function quad(ctx,im,s,q,alpha=1){if(alpha<=0)return;ctx.globalAlpha=clamp(alpha);triangle(ctx,im,[s[0],s[1],s[2]],[q[0],q[1],q[2]]);triangle(ctx,im,[s[0],s[2],s[3]],[q[0],q[2],q[3]]);ctx.globalAlpha=1;}
 function polygon(ctx,q,color){ctx.fillStyle=color;ctx.beginPath();q.forEach((p,i)=>i?ctx.lineTo(...p):ctx.moveTo(...p));ctx.closePath();ctx.fill();}
 function relief(ctx,base,grid,w,h,{lift=.78,previous=null,pass=null,final=null,fade=0,detail=1,age=10000,point={x:.5,y:.5},camera=null,space=false}={}){
  if(!grid?.cols||!Array.isArray(grid.values))return;const {cols,rows}=grid,aspect=base.width/base.height;
  const scale=Math.min(w/Math.min(1,aspect),h/Math.min(1,1/aspect))*(space?.84:(.94-.15*lift))*(camera?.zoom||1);
  const at=(u,v,f)=>{let p;if(space)p=project((u-.5)*Math.min(1,aspect),(v-.5)*Math.min(1,1/aspect),(f-.5)*.50,camera?.displayYaw||0,camera?.displayPitch||0);else p=focusProject(u,v,f,lift,aspect);return [w*.5+(camera?.panX||0)*w+p[0]*scale,h*.5+(camera?.panY||0)*h+p[1]*scale,p[2]];};
  const order=Array.from({length:cols*rows},(_,i)=>i).sort((a,b)=>at((a%cols+.5)/cols,(Math.floor(a/cols)+.5)/rows,space?grid.values[a]:(grid.focusValues?.[a]||0))[2]-at((b%cols+.5)/cols,(Math.floor(b/cols)+.5)/rows,space?grid.values[b]:(grid.focusValues?.[b]||0))[2]);
  for(const i of order){const x=i%cols,y=Math.floor(i/cols),f=space?grid.values[i]:(grid.focusValues?.[i]||0),distance=Math.hypot((x+.5)/cols-point.x,(y+.5)/rows-point.y)/Math.SQRT2,part=clamp((age-distance*360)/300),local=space?0:lift*(1-.12*Math.sin(Math.PI*part));
   const uv=[[x/cols,y/rows],[(x+1)/cols,y/rows],[(x+1)/cols,(y+1)/rows],[x/cols,(y+1)/rows]],q=uv.map(([u,v])=>{const cx=(x+.5)/cols,cy=(y+.5)/rows;return at(cx+(u-cx)*(1-.032*local),cy+(v-cy)*(1-.032*local),f);}),bottom=uv.map(([u,v])=>at(u,v,0)),s=uv.map(([u,v])=>[u*base.width,v*base.height]);
   if(!space&&f>.01&&lift>.001)for(let k=0;k<4;k++){const n=(k+1)%4,a=bottom[k],b=bottom[n],c=q[n],d=q[k],area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])+(c[0]-a[0])*(d[1]-a[1])-(c[1]-a[1])*(d[0]-a[0]);if(area>1e-4)polygon(ctx,[a,b,c,d],sideColors(base,cols,rows)[i]);}
   quad(ctx,previous||base,s,q);if(pass)quad(ctx,pass,uv.map(([u,v])=>[u*pass.width,v*pass.height]),q,detail*ease(part));if(final)quad(ctx,final,uv.map(([u,v])=>[u*final.width,v*final.height]),q,fade);
   if(!space&&part>0&&part<1&&!final){const cx=q.reduce((s,p)=>s+p[0],0)/4,cy=q.reduce((s,p)=>s+p[1],0)/4,r=Math.hypot(q[2][0]-q[0][0],q[2][1]-q[0][1])*(.18+part*.8);ctx.strokeStyle=`rgba(238,232,221,${.35*Math.sin(Math.PI*part)})`;ctx.beginPath();ctx.ellipse(cx,cy,r,r*.8,0,0,Math.PI*2);ctx.stroke();}
  }
 }
 const sampled=new WeakMap(),sideSamples=new WeakMap();
 function pixels(im,points){const c=document.createElement('canvas');c.width=points.length;c.height=1;const x=c.getContext('2d',{willReadFrequently:true});x.imageSmoothingEnabled=false;points.forEach(([u,v],i)=>x.drawImage(im,Math.min(im.width-1,Math.max(0,Math.floor(u*im.width))),Math.min(im.height-1,Math.max(0,Math.floor(v*im.height))),1,1,i,0,1,1));const data=x.getImageData(0,0,c.width,1).data;return points.map((_,i)=>[...data.slice(i*4,i*4+3)]);}
 function palette(im){if(sampled.has(im))return sampled.get(im);const colors=pixels(im,Array.from({length:16},(_,i)=>[.12+.76*((i*7)%16)/15,.12+.76*((i*11)%16)/15]));const value={colors,textures:colors.map(rgb=>{const c=document.createElement('canvas');c.width=c.height=64;const x=c.getContext('2d'),d=x.createImageData(64,64);for(let y=0;y<64;y++)for(let xx=0;xx<64;xx++){const at=(y*64+xx)*4,r=Math.hypot((xx+.5-32)/32,(y+.5-32)/32);d.data.set([...rgb,Math.floor(Math.max(0,1-r)**2*255)],at);}x.putImageData(d,0,0);return c;})};sampled.set(im,value);return value;}
 function vapor(ctx,texture,x,y,r,alpha){if(!(r>0&&alpha>0))return;ctx.save();ctx.globalAlpha=clamp(alpha);ctx.drawImage(texture,x-r,y-r*.8,r*2,r*1.6);ctx.restore();}
 function sideColors(im,cols,rows){let store=sideSamples.get(im);if(!store){store=new Map();sideSamples.set(im,store);}const key=cols+':'+rows;if(!store.has(key))store.set(key,pixels(im,Array.from({length:cols*rows},(_,i)=>[(i%cols+.5)/cols,(Math.floor(i/cols)+.5)/rows])).map(rgb=>'rgb('+rgb.map(v=>Math.floor(v*.72)).join(',')+')'));return store.get(key);}
 let mask,maskctx,layer,layerctx,maskData;
 function color(ctx,src,colored,w,h,timeline,reduced=false){
  const p=timeline.stage==='COLOR_FINISH'?timeline.progress:0,finish=timeline.stage==='COLOR_FINISH';
  const scale=finish?colorScale(p):1,iw=Math.min(w/src.width,h/src.height)*.94*src.width*scale,ih=iw*src.height/src.width,x=(w-iw)/2,y=(h-ih)/2+(finish?colorOffset(p)*h:0);
  ctx.filter='grayscale(1)';ctx.drawImage(src,x,y,iw,ih);ctx.filter='none';
  if(reduced){ctx.drawImage(colored||src,x,y,iw,ih);return;}
  if(!mask){mask=document.createElement('canvas');mask.width=128;mask.height=96;maskctx=mask.getContext('2d');maskData=maskctx.createImageData(128,96);layer=document.createElement('canvas');layerctx=layer.getContext('2d');}
  const mw=128,mh=Math.max(1,Math.round(128*src.height/src.width));if(mask.height!==mh){mask.height=mh;maskData=maskctx.createImageData(mw,mh);}
  if(layer.width!==Math.ceil(iw)||layer.height!==Math.ceil(ih)){layer.width=Math.max(1,Math.ceil(iw));layer.height=Math.max(1,Math.ceil(ih));}
  const drawMask=(image,fn)=>{const d=maskData.data;for(let cy=0;cy<mh;cy++)for(let cx=0;cx<mw;cx++){const n=(cy*mw+cx)*4;d[n]=d[n+1]=d[n+2]=255;d[n+3]=Math.round(clamp(fn((cx+.5)/mw,(cy+.5)/mh))*255);}maskctx.putImageData(maskData,0,0);layerctx.globalCompositeOperation='source-over';layerctx.clearRect(0,0,layer.width,layer.height);layerctx.drawImage(image,0,0,layer.width,layer.height);layerctx.globalCompositeOperation='destination-in';layerctx.drawImage(mask,0,0,layer.width,layer.height);layerctx.globalCompositeOperation='source-over';ctx.drawImage(layer,x,y,iw,ih);};
  if(finish){ctx.globalAlpha=1-phase(p,.16,.43);ctx.drawImage(src,x,y,iw,ih);ctx.globalAlpha=1;if(colored)drawMask(colored,(u,v)=>revealed(u,v,p));
   const strip=phase(p,.16,.50),alpha=Math.max(0,Math.sin(Math.PI*strip)),old=palette(src),next=palette(colored||src);
   if(alpha>0)for(let k=0;k<18;k++){
    const seed=k/18,sx=(x+iw*(.12+.76*((k*7)%18)/17))/w,sy=(y+ih*(.08+.84*seed))/h,points=[];
    for(let j=0;j<=32;j++){const u=j/32,reach=u*strip,e=Math.sin(Math.PI*u),px=(sx-(.8+sx)*reach+.05*Math.sin(u*13+seed*9+strip*5)*e)*w,py=(sy-.20*reach+.065*Math.cos(u*11+seed*12-strip*4)*e)*h;points.push([px,py]);if(j%2===0)vapor(ctx,old.textures[k%16],px,py,w*(.038+.025*Math.sin(j*.4+k+strip*5)**2),alpha*.21);}
    ctx.save();ctx.globalCompositeOperation='screen';ctx.lineCap=ctx.lineJoin='round';ctx.strokeStyle='rgb('+old.colors[k%16].join(',')+')';const width=iw*(.002+.003*(k%3));ctx.beginPath();points.forEach(([px,py],j)=>j?ctx.lineTo(px,py):ctx.moveTo(px,py));for(const [scale,a] of [[3.5,75],[1.7,120],[1,235]]){ctx.lineWidth=width*scale;ctx.globalAlpha=Math.floor(a*alpha*.12)/255;ctx.stroke();}ctx.restore();
   }
   if(p>.36&&p<.81){const travel=phase(p,.42,.67)+.18*phase(p,.67,.78);for(let k=0;k<4;k++)for(let j=0;j<24;j++){const seed=k/3,u=j/23,a=ease(travel-u*.18),arc=Math.sin(Math.PI*a),vx=1.025+(.68-1.025)*a,vy=.48+Math.min(1,src.width/src.height)*(-.09*arc+(seed-.5)*.06*(1-a)*(.6+.4*arc)),r=Math.min(iw,ih)*(.045+.022*Math.sin(Math.PI*u))*(1-.12*a),opacity=.07*phase(p,.36,.48)*(1-phase(p,.67,.81))*Math.sin(Math.PI*(.12+.76*u));vapor(ctx,next.textures[k*4],x+vx*iw,y+vy*ih,r,opacity);}}
  }else drawMask(src,(u,v)=>pigment(u,v,timeline.seconds));
 }
 return {clamp,ease,phase,field,pigment,colorScale,colorOffset,revealed,project,focusProject,Timeline,Camera,relief,color,quad};
})();
