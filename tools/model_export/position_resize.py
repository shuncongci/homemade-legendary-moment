"""Dynamic DINOv2 positional interpolation adapted from the local depth exporter."""
import torch
from torch.nn import functional as F

class PositionResize(torch.autograd.Function):
    @staticmethod
    def forward(ctx, patch, height, width):
        hh,ww = int(height)//14,int(width)//14
        if hh==37 and ww==37:
            return patch
        return F.interpolate(patch,scale_factor=((hh+.1)/37,(ww+.1)/37),mode='bicubic',align_corners=False)

    @staticmethod
    def symbolic(g,patch,height,width):
        def c(value,dtype=torch.int64):
            return g.op('Constant',value_t=torch.tensor(value,dtype=dtype))
        hh,ww = g.op('Div',height,c(14)),g.op('Div',width,c(14))
        identity = g.op('And',g.op('Equal',hh,c(37)),g.op('Equal',ww,c(37)))
        offset = g.op('Where',identity,c(0.,torch.float32),c(.1,torch.float32))
        def scale(value):
            value = g.op('Div',g.op('Add',g.op('Cast',value,to_i=1),offset),c(37.,torch.float32))
            return g.op('Unsqueeze',value,c([0]))
        scales = g.op('Concat',c([1.,1.],torch.float32),scale(hh),scale(ww),axis_i=0)
        return g.op('Resize',patch,c([],torch.float32),scales,mode_s='cubic',coordinate_transformation_mode_s='half_pixel',cubic_coeff_a_f=-.75)
