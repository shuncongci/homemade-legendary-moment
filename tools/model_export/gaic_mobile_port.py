# Adapted from GAIC-Pytorch, https://github.com/bo-zhang-cs/GAIC-Pytorch
# Copyright (c) 2022 bo-zhang. MIT License.
# Full license: ../../app/src/main/assets/runtime-notices/GAIC-code-MIT-LICENSE.txt
# Modification: batch-one PyTorch sampling replaces custom CUDA RoI/RoD operators.
"""FP32 GAIC MobileNetV2 mobile export adapter."""
import torch
from torch import nn
from torch.nn import functional as F
from torchvision.models import mobilenet_v2
class FeatureExtractor(nn.Module):
    def __init__(self):
        super().__init__()
        m=mobilenet_v2(weights=None)
        self.feature3=nn.Sequential(m.features[:7]);self.feature4=nn.Sequential(m.features[7:14]);self.feature5=nn.Sequential(m.features[14:-1])
    def forward(self,x):
        f3=self.feature3(x);f4=self.feature4(f3);f5=self.feature5(f4)
        return f3,f4,f5

def align_features(features,boxes,rod=False):
    # Batch-one inference; incoming boxes are image-pixel x1,y1,x2,y2.
    # Upstream uses a 10x10 point lattice then 2x2 average -> 9x9,
    # not standard torchvision/ONNX RoiAlign bin sampling.
    h,w=features.shape[-2:]
    t=torch.arange(10,dtype=features.dtype,device=features.device)
    b=boxes*(1./16.)
    if rod:
        y=t[None,:,None]*((h-1.001)/9.)+b[:,0,None,None]*0.
        x=t[None,None,:]*((w-1.001)/9.)+b[:,0,None,None]*0.
        y=y.expand(-1,10,10);x=x.expand(-1,10,10)
        valid=~((y>=b[:,1,None,None])&(y<=b[:,3,None,None])&(x>=b[:,0,None,None])&(x<=b[:,2,None,None]))
    else:
        y=b[:,1,None,None]+t[None,:,None]*(torch.clamp(b[:,3]-b[:,1]+1.,min=0.)/9.)[:,None,None]
        x=b[:,0,None,None]+t[None,None,:]*(torch.clamp(b[:,2]-b[:,0]+1.,min=0.)/9.)[:,None,None]
        y=y.expand(-1,10,10);x=x.expand(-1,10,10)
        valid=(y>=0.)&(y<h)&(x>=0.)&(x<w)
    # The original extrapolates from the final TWO pixels; do not substitute border padding.
    iy=torch.clamp(torch.floor(y),min=0,max=h-2).to(torch.int64)
    ix=torch.clamp(torch.floor(x),min=0,max=w-2).to(torch.int64)
    dy=y-iy.to(y.dtype);dx=x-ix.to(x.dtype)
    flat=features[0].flatten(1)
    def pick(yy,xx):
        return flat[:,(yy*w+xx).reshape(-1)].reshape(features.shape[1],-1,10,10).permute(1,0,2,3)
    dy=dy[:,None];dx=dx[:,None]
    sample=pick(iy,ix)*(1.-dy)*(1.-dx)+pick(iy,ix+1)*(1.-dy)*dx+pick(iy+1,ix)*dy*(1.-dx)+pick(iy+1,ix+1)*dy*dx
    return F.avg_pool2d(torch.where(valid[:,None],sample,torch.zeros_like(sample)),2,1)

class GaicMobileNetV2(nn.Module):
    def __init__(self):
        super().__init__()
        self.Feat_ext=FeatureExtractor();self.DimRed=nn.Conv2d(448,16,1)
        self.FC_layers=nn.Sequential(nn.Sequential(nn.Conv2d(32,768,9),nn.ReLU(inplace=True)),nn.Sequential(nn.Conv2d(768,128,1),nn.ReLU(inplace=True)),nn.Conv2d(128,1,1))
    def features(self,image):
        f3,f4,f5=self.Feat_ext(image)
        cat=torch.cat((F.interpolate(f3,size=f4.shape[2:],mode='bilinear',align_corners=True),f4,.5*F.interpolate(f5,size=f4.shape[2:],mode='bilinear',align_corners=True)),1)
        return self.DimRed(cat)
    def score(self,features,boxes):
        return self.FC_layers(torch.cat((align_features(features,boxes),align_features(features,boxes,rod=True)),1)).reshape(-1)
    def forward(self,image,boxes):
        return self.score(self.features(image),boxes)
