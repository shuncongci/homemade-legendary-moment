"""Export local SAM 2.1 Tiny / ViTMatte Small checkpoints. No network or Android edits."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import sys

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage',choices=('sam','matte'))
    parser.add_argument('--sam-repo',type=Path)
    parser.add_argument('--checkpoint',type=Path)
    parser.add_argument('--model-dir',type=Path,help='local ViTMatte directory with config.json and model.safetensors')
    parser.add_argument('--output-dir',type=Path,required=True,help='new directory outside Android assets')
    args = parser.parse_args()
    if args.stage == 'sam':
        if args.sam_repo is None or not args.sam_repo.is_dir() or args.checkpoint is None:
            parser.error('sam requires --sam-repo and --checkpoint')
        checkpoint = args.checkpoint
    else:
        if args.model_dir is None or not (args.model_dir/'config.json').is_file():
            parser.error('matte requires a local --model-dir with config.json')
        checkpoint = args.model_dir/'model.safetensors'
    if not checkpoint.is_file():
        parser.error('checkpoint does not exist; no file will be downloaded')
    needle = 'SAM 2' if args.stage == 'sam' else 'ViTMatte'
    entry = next(m for m in json.loads((ROOT/'models.json').read_text('utf8'))['models'] if needle in m['name'])
    actual = digest(checkpoint)
    if actual != entry['sourceCheckpointSha256']:
        parser.error('checkpoint SHA-256 differs from models.json: '+actual)
    dest = args.output_dir.resolve()
    if dest.exists() or (ROOT/'app/src/main/assets').resolve() in dest.parents:
        parser.error('output must be a new directory outside Android assets')
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    if args.sam_repo:
        sys.path.insert(0,str(args.sam_repo.resolve()))
    import torch
    import numpy as np
    import onnx
    import onnxruntime as ort
    torch.set_num_threads(4); torch.manual_seed(0)
    report = {'stage':args.stage,'sourceCheckpointSha256':actual,'precision':'FP32','opset':17,
              'torch':torch.__version__,'onnx':onnx.__version__,'onnxruntime':ort.__version__,
              'checks':[],'androidVerified':False}
    dest.mkdir(parents=True)
    path = dest/Path(entry['path']).name
    try:
        if args.stage == 'sam':
            from sam2.build_sam import build_sam2
            class Sam(torch.nn.Module):
                def __init__(self,model):
                    super().__init__(); self.model=model
                def forward(self,image,point):
                    m=self.model
                    back=m.forward_image(image)
                    _,vf,_,_=m._prepare_backbone_features(back)
                    vf[-1]=vf[-1]+m.no_mem_embed
                    features=[v.permute(1,2,0).reshape(1,-1,s,s) for v,s in zip(vf[::-1],[64,128,256])][::-1]
                    sparse,dense=m.sam_prompt_encoder(points=(point,torch.ones((1,1),dtype=torch.int64,device=point.device)),boxes=None,masks=None)
                    masks,scores,_,_=m.sam_mask_decoder(image_embeddings=features[-1],image_pe=m.sam_prompt_encoder.get_dense_pe(),sparse_prompt_embeddings=sparse,dense_prompt_embeddings=dense,multimask_output=True,repeat_image=False,high_res_features=features[:-1])
                    return masks,scores
            model=Sam(build_sam2('configs/sam2.1/sam2.1_hiera_t.yaml',str(checkpoint.resolve()),device='cpu',apply_postprocessing=False)).eval()
            cases=[(torch.randn(1,3,1024,1024),torch.tensor([[[800.,480.]]])),
                   (torch.zeros(1,3,1024,1024),torch.tensor([[[512.,512.]]]))]
            names=['image','point']; outputs=['masks','scores']; axes=None
        else:
            from transformers import VitMatteForImageMatting
            class Matte(torch.nn.Module):
                def __init__(self,model):
                    super().__init__(); self.model=model
                def forward(self,pixel_values):
                    return self.model(pixel_values=pixel_values).alphas
            model=Matte(VitMatteForImageMatting.from_pretrained(str(args.model_dir.resolve()),local_files_only=True)).eval()
            cases=[(torch.rand(1,4,h,w),) for h,w in ((256,256),(320,512))]
            names=['pixel_values']; outputs=['alpha']
            axes={name:{2:'height',3:'width'} for name in names+outputs}
        with torch.inference_mode():
            torch.onnx.export(model,cases[0],str(path),input_names=names,output_names=outputs,
                              dynamic_axes=axes,opset_version=17,dynamo=False,do_constant_folding=True)
        graph=onnx.load(str(path)); onnx.checker.check_model(graph)
        if any(t.data_type==10 for t in graph.graph.initializer):
            raise RuntimeError('FP16 initializer found in an FP32 export')
        options=ort.SessionOptions(); options.intra_op_num_threads=4
        options.enable_cpu_mem_arena=False; options.enable_mem_pattern=False
        options.add_session_config_entry('optimization.disable_specified_optimizers','GeluFusionL1,GeluFusionL2')
        session=ort.InferenceSession(str(path),options,providers=['CPUExecutionProvider'])
        for inputs in cases:
            with torch.inference_mode():
                refs=model(*inputs)
            if not isinstance(refs,tuple): refs=(refs,)
            got=session.run(None,{name:x.numpy() for name,x in zip(names,inputs)})
            if len(got)!=len(refs): raise RuntimeError('Output count differs')
            differences=[]
            for a,b in zip(got,refs):
                if a.shape!=tuple(b.shape) or not np.isfinite(a).all(): raise RuntimeError('Invalid output')
                differences.append(float(np.abs(a-b.numpy()).max()))
            threshold=.02 if args.stage=='sam' else .005
            row={'inputShape':list(inputs[0].shape),'maxAbs':max(differences)}
            ok=max(differences)<threshold
            if args.stage=='sam':
                selected=int(got[1].argmax()); expected=int(refs[1].argmax())
                a=got[0][:,selected]>0; b=refs[0][:,expected].numpy()>0
                iou=float((a&b).sum()/max(1,(a|b).sum()))
                row.update(maskIoU=iou,chosenMask=selected)
                ok=ok and selected==expected and iou>.999
            row['passed']=bool(ok); report['checks'].append(row); print(row,flush=True)
            if not ok: raise RuntimeError('Synthetic numerical check failed')
        report.update(passed=True,sha256=digest(path),bytes=path.stat().st_size)
    except Exception as exc:
        report.update(passed=False,error=f'{type(exc).__name__}: {exc}')
        raise
    finally:
        (dest/'export-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
    print('Synthetic desktop conversion checks passed. Real-photo and device checks remain necessary.')

if __name__=='__main__':
    main()
