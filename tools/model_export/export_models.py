"""Export locally prepared third-party checkpoints; never downloads or edits Android assets."""
from pathlib import Path
import argparse
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('depth', 'bokeh', 'gaic'))
    parser.add_argument('--repo', type=Path, help='local upstream repository, required for depth/bokeh')
    parser.add_argument('--checkpoint', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path, help='new directory outside Android assets')
    args = parser.parse_args()
    if args.stage != 'gaic' and (args.repo is None or not args.repo.is_dir()):
        parser.error('--repo must be an existing upstream directory')
    if not args.checkpoint.is_file():
        parser.error('checkpoint does not exist; no file will be downloaded')
    needle = {'depth': 'Depth Anything', 'bokeh': 'BokehMe', 'gaic': 'GAIC'}[args.stage]
    entry = next(m for m in json.loads((ROOT/'models.json').read_text('utf8'))['models'] if needle in m['name'])
    actual = digest(args.checkpoint)
    if actual != entry['sourceCheckpointSha256']:
        parser.error('checkpoint SHA-256 differs from models.json: ' + actual)
    dest = args.output_dir.resolve()
    if dest.exists() or (ROOT/'app/src/main/assets').resolve() in dest.parents:
        parser.error('output must be a new directory outside Android assets')
    if args.repo:
        sys.path.insert(0, str(args.repo.resolve()))
    import torch
    import numpy as np
    import onnx
    import onnxruntime as ort
    from torch.nn import functional as F
    torch.set_num_threads(4)
    torch.manual_seed(42)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    report = {'stage': args.stage, 'sourceCheckpointSha256': actual, 'opset': 17,
              'torch': torch.__version__, 'onnx': onnx.__version__, 'onnxruntime': ort.__version__,
              'checks': [], 'androidVerified': False}
    dest.mkdir(parents=True)
    try:
        if args.stage == 'depth':
            import inspect
            import textwrap
            import types
            from depth_anything_v2.dpt import DepthAnythingV2, DPTHead
            from chunk_attention_fp32 import ChunkAttention
            from position_resize import PositionResize
            model = DepthAnythingV2(encoder='vits', features=64, out_channels=[48,96,192,384])
            model.load_state_dict(torch.load(args.checkpoint, map_location='cpu', weights_only=True), strict=True)
            model.eval()
            probe = torch.randn(1,3,56,70)
            with torch.inference_mode():
                original_result = model(probe)
            for block in model.pretrained.blocks:
                block.attn = ChunkAttention(block.attn)
            with torch.inference_mode():
                torch.testing.assert_close(original_result, model(probe), atol=.002, rtol=.002)
            original = model.pretrained.interpolate_pos_encoding
            def position(self, x, height, width):
                if not torch.onnx.is_in_onnx_export():
                    return original(x, height, width)
                patch = self.pos_embed[:,1:].reshape(1,37,37,self.embed_dim).permute(0,3,1,2).float()
                patch = PositionResize.apply(patch,height,width).permute(0,2,3,1).reshape(1,-1,self.embed_dim)
                return torch.cat((self.pos_embed[:,:1],patch),dim=1).to(x.dtype)
            model.pretrained.interpolate_pos_encoding = types.MethodType(position,model.pretrained)
            source = textwrap.dedent(inspect.getsource(DPTHead.forward))
            source = source.replace('int(patch_h * 14)','patch_h * 14').replace('int(patch_w * 14)','patch_w * 14')
            namespace = dict(inspect.getmodule(DPTHead).__dict__)
            exec(compile(source, '<upstream DPTHead dynamic-shape adaptation>', 'exec'), namespace)
            model.depth_head.forward = types.MethodType(namespace['forward'],model.depth_head)
            cases = [(torch.randn(1,3,h,w),) for h,w in ((56,70),(70,56),(518,686))]
            names = ['input']
            axes = {'input':{2:'height',3:'width'}, 'output':{1:'height',2:'width'}}
        elif args.stage == 'bokeh':
            from neural_renderer import ARNet
            net = ARNet()
            # The known upstream checkpoint includes NumPy metadata. Keep restricted loading;
            # never fall back to unrestricted pickle execution.
            def numpy_reconstruct(*values):
                return np._core.multiarray._reconstruct(*values)
            numpy_reconstruct.__module__ = 'numpy.core.multiarray'
            numpy_reconstruct.__name__ = '_reconstruct'
            allowed = [numpy_reconstruct,np.ndarray,np.dtype] + [type(np.dtype(n)) for n in ('uint32','int32','int64','float64')]
            with torch.serialization.safe_globals(allowed):
                checkpoint = torch.load(args.checkpoint,map_location='cpu',weights_only=True)
            net.load_state_dict(checkpoint['model'],strict=True)
            net.eval()
            class ExportBokeh(torch.nn.Module):
                def __init__(self, network):
                    super().__init__(); self.net = network
                def forward(self, image, defocus):
                    h,w = image.shape[-2:]
                    x = F.interpolate(torch.cat((image,defocus),1),size=(h//2*2,w//2*2),mode='bilinear',align_corners=True)
                    b,c,hh,ww = x.shape
                    x = x.reshape(b,c,hh//2,2,ww//2,2).permute(0,1,3,5,2,4).reshape(b,c*4,hh//2,ww//2)
                    x = torch.cat((x,torch.ones_like(x[:,:1])*.3),1)
                    x = self.net.upsample(self.net.conv1(self.net.block_stack(self.net.conv0(x))))
                    return F.interpolate(x,size=(h,w),mode='bilinear',align_corners=True)[:,:3]
            model = ExportBokeh(net).eval()
            cases = [(torch.rand(1,3,h,w),torch.rand(1,1,h,w)*2-1) for h,w in ((64,80),(97,129),(480,481))]
            with torch.inference_mode():
                for image,defocus in cases:
                    torch.testing.assert_close(model(image,defocus),net(image,defocus,.3)[0],rtol=0,atol=0)
            names = ['image','defocus']
            axes = {n:{2:'height',3:'width'} for n in names+['output']}
        else:
            from gaic_mobile_port import GaicMobileNetV2
            model = GaicMobileNetV2().eval()
            model.load_state_dict(torch.load(args.checkpoint,map_location='cpu',weights_only=True),strict=True)
            cases = []
            for h,w in ((256,320),(288,256)):
                boxes = torch.tensor([[0.,0.,float(w-1),float(h-1)],[8.,16.,float(w-20),float(h-16)]])
                cases.append((torch.randn(1,3,h,w),boxes))
            names = ['image','boxes']
            axes = {'image':{2:'height',3:'width'},'boxes':{0:'count'},'scores':{0:'count'}}
        output_name = 'scores' if args.stage == 'gaic' else 'output'
        fp32 = dest/(Path(entry['path']).stem.replace('.fp16','.fp32')+'.onnx')
        with torch.inference_mode():
            torch.onnx.export(model,cases[0],str(fp32),input_names=names,output_names=[output_name],
                              dynamic_axes=axes,opset_version=17,dynamo=False,do_constant_folding=True)
        graph = onnx.load(str(fp32))
        onnx.checker.check_model(graph)
        if args.stage == 'depth':
            loops = sum(n.op_type == 'Loop' for n in graph.graph.node)
            if loops != 12:
                raise RuntimeError(f'Expected twelve global-attention Loop nodes, got {loops}')
            report['attentionLoops'] = loops
        variants = [('fp32',fp32)]
        if args.stage == 'bokeh':
            from onnxconverter_common import float16
            fp16 = dest/Path(entry['path']).name
            half = float16.convert_float_to_float16(graph,keep_io_types=True)
            onnx.checker.check_model(half)
            onnx.save(half,str(fp16))
            variants.append(('fp16',fp16))
        for precision,path in variants:
            options = ort.SessionOptions()
            options.intra_op_num_threads = 4
            options.enable_cpu_mem_arena = False
            options.enable_mem_pattern = False
            options.add_session_config_entry('optimization.disable_specified_optimizers','GeluFusionL1,GeluFusionL2')
            session = ort.InferenceSession(str(path),options,providers=['CPUExecutionProvider'])
            for inputs in cases:
                with torch.inference_mode():
                    expected = model(*inputs).numpy()
                actual = session.run(None,{n:x.numpy() for n,x in zip(names,inputs)})[0]
                if actual.shape != expected.shape or not np.isfinite(actual).all():
                    raise RuntimeError('Invalid ONNX shape or non-finite output')
                delta = np.abs(actual.astype(np.float64)-expected.astype(np.float64))
                row = {'precision':precision,'shape':list(actual.shape),'maxAbs':float(delta.max()),'meanAbs':float(delta.mean())}
                if args.stage == 'depth':
                    span = max(1e-5,float(np.percentile(expected,98)-np.percentile(expected,2)))
                    row['relativeSpanMax'] = float(delta.max()/span)
                    ok = row['relativeSpanMax'] < .001
                elif args.stage == 'bokeh':
                    a = (np.clip(expected,0,1)*255).astype(np.uint8).astype(np.float64)
                    b = (np.clip(actual,0,1)*255).astype(np.uint8).astype(np.float64)
                    d = np.abs(a-b); mse = float(np.mean(d*d))
                    psnr = 120. if mse == 0 else float(10*np.log10(255**2/mse))
                    row.update(psnr=psnr,maxAbs8bit=float(d.max()),over2=float((d>2).mean()))
                    ok = (psnr>=50 and d.max()<=2) if precision=='fp32' else (psnr>=40 and (d>2).mean()<=.01)
                else:
                    ok = np.allclose(actual,expected,atol=.001,rtol=.001)
                row['passed'] = bool(ok); report['checks'].append(row)
                print(row,flush=True)
                if not ok:
                    raise RuntimeError('Numerical conversion check failed')
        report['models'] = {p.name:{'sha256':digest(p),'bytes':p.stat().st_size} for _,p in variants}
        report['passed'] = True
    except Exception as exc:
        report.update(passed=False,error=f'{type(exc).__name__}: {exc}')
        raise
    finally:
        (dest/'export-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
    print('Export validated on synthetic desktop inputs only. Review report before using outputs.')

if __name__ == '__main__':
    main()
