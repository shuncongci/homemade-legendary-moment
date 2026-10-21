from typing import List
import torch
from torch import nn, Tensor
@torch.jit.script
def global_query_chunks(q:Tensor,k:Tensor,v:Tensor,chunk:int)->Tensor:
    outputs=torch.jit.annotate(List[Tensor],[])
    start=0
    n=q.size(2)
    while start<n:
        end=min(start+chunk,n)
        scores=torch.matmul(q[:,:,start:end,:],k.transpose(-2,-1))
        outputs.append(torch.matmul(torch.softmax(scores,dim=-1),v))
        start=end
    return torch.cat(outputs,dim=2)
class ChunkAttention(nn.Module):
    def __init__(self,a,chunk:int=64):
        super().__init__();self.qkv=a.qkv;self.proj=a.proj;self.proj_drop=a.proj_drop;self.num_heads=a.num_heads;self.chunk=chunk;self.scale=a.scale
    def forward(self,x:Tensor)->Tensor:
        b,n,c=x.shape
        qkv=self.qkv(x).reshape(b,n,3,self.num_heads,c//self.num_heads).permute(2,0,3,1,4)
        q,k,v=qkv[0]*self.scale,qkv[1],qkv[2]
        y=global_query_chunks(q,k,v,self.chunk).transpose(1,2).reshape(b,n,c)
        return self.proj_drop(self.proj(y))
