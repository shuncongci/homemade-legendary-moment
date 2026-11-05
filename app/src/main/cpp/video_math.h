// Ordinary, unencrypted color math. No embedded LUT, key, model or network access.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>
namespace video {
inline void require(bool ok) { if(!ok) throw std::runtime_error("Video plane or color contract failed"); }
inline double clamp(double x,double a=0.,double b=1.) { return std::max(a,std::min(b,x)); }
inline double srgb(double x) { return x<=.0031308?12.92*x:1.055*std::pow(x,1./2.4)-.055; }
inline double unsrgb(double x) { return x<=.04045?x/12.92:std::pow((x+.055)/1.055,2.4); }
inline double decode(double x,int transfer) {
    x=clamp(x);
    if(transfer==13)return unsrgb(x);
    if(transfer==16){constexpr double m1=2610./16384,m2=2523./32,c1=3424./4096,c2=2413./128,c3=2392./128;double p=std::pow(x,1/m2);return std::pow(std::max(p-c1,0.)/(c2-c3*p),1/m1)*10000./203.;}
    if(transfer==18){constexpr double a=.17883277,b=.28466892,c=.55991073;return (x<=.5?x*x/3:(std::exp((x-c)/a)+b)/12)/.264962560421;}
    // SDR presentation is BT.1886 / gamma 2.4, matching the Rec.709-g24 Cube.
    return std::pow(x,2.4);
}
inline double encode(double x,int transfer) {
    x=std::max(0.,x);
    if(transfer==13)return clamp(srgb(x));
    if(transfer==16){constexpr double m1=2610./16384,m2=2523./32,c1=3424./4096,c2=2413./128,c3=2392./128;double p=std::pow(clamp(x*203./10000),m1);return std::pow((c1+c2*p)/(1+c3*p),m2);}
    if(transfer==18){constexpr double a=.17883277,b=.28466892,c=.55991073;double l=clamp(x*.264962560421);return l<=1./12?std::sqrt(3*l):a*std::log(12*l-b)+c;}
    return std::pow(clamp(x),1/2.4);
}
using RGB=std::array<double,3>;
inline RGB to709(RGB v){return {1.6604910021084347*v[0]-.5876411387885497*v[1]-.07284986331988487*v[2],-.1245504745215906*v[0]+1.13289989712596*v[1]-.008349422604369472*v[2],-.018150763354905213*v[0]-.1005788980080074*v[1]+1.1187296613629125*v[2]};}
inline RGB to2020(RGB v){return {.6274038959346988*v[0]+.3292830383778838*v[1]+.04331306568741722*v[2],.06909728935823195*v[0]+.9195403950754587*v[1]+.011362315566309171*v[2],.016391438875150224*v[0]+.08801330787722578*v[1]+.895595253247624*v[2]};}
class Lut {
    std::vector<double> nodes;
public:
    explicit Lut(const uint8_t* bytes,size_t n) {
        require(bytes && n==12+65*65*65*3*8 && std::memcmp(bytes,"PLUT0001",8)==0 && bytes[8]==0 && bytes[9]==0 && bytes[10]==0 && bytes[11]==65);
        nodes.resize(65*65*65*3);
        for(size_t i=0;i<nodes.size();i++){uint64_t u=0;for(int j=0;j<8;j++)u=(u<<8)|bytes[12+i*8+j];double x;std::memcpy(&x,&u,8);require(std::isfinite(x)&&x>=0&&x<=1);nodes[i]=x;}
    }
    ~Lut(){volatile double* p=nodes.data();for(size_t i=0;i<nodes.size();i++)p[i]=0;}
    RGB lookup(RGB v) const {
        int lo[3];double f[3];for(int c=0;c<3;c++){double x=clamp(v[c])*64;lo[c]=std::min(int(x),63);f[c]=x-lo[c];}
        RGB out{};
        for(int r=0;r<=1;r++)for(int g=0;g<=1;g++)for(int b=0;b<=1;b++){
            double w=(r?f[0]:1-f[0])*(g?f[1]:1-f[1])*(b?f[2]:1-f[2]);size_t at=(((lo[2]+b)*65+(lo[1]+g))*65+lo[0]+r)*3;
            for(int c=0;c<3;c++)out[c]+=nodes[at+c]*w;
        }
        return out;
    }
    RGB grade(RGB code,int transfer) const {
        if(transfer==13)return lookup(code);
        RGB linear{};for(int c=0;c<3;c++)linear[c]=decode(code[c],transfer);
        const bool hdr=transfer==16||transfer==18;
        const RGB original=linear;
        if(hdr)linear=to709(linear);
        RGB base{},encoded{};for(int c=0;c<3;c++){base[c]=clamp(linear[c]);encoded[c]=srgb(base[c]);}
        RGB look=lookup(encoded);
        // Scene-invariant HDR extension: retain the signed wide-gamut/highlight residual.
        // No per-frame exposure analysis, temporal adaptation, HDR-to-SDR fallback or 8-bit intermediate.
        // Transform only the LUT delta, not the original HDR signal through a matrix roundtrip.
        // This keeps an identity LUT an identity even for saturated 10000-nit primaries.
        if(hdr){RGB delta{};for(int c=0;c<3;c++)delta[c]=unsrgb(look[c])-base[c];delta=to2020(delta);for(int c=0;c<3;c++)linear[c]=original[c]+delta[c];}
        else for(int c=0;c<3;c++)linear[c]=linear[c]-base[c]+unsrgb(look[c]);
        for(int c=0;c<3;c++)linear[c]=encode(linear[c],transfer);
        return linear;
    }
};
struct Plane {
    uint8_t* data;size_t size;int row,pixel;
    void validate(int w,int h,int bits) const {
        require(data&&w>0&&h>0&&row>0&&pixel>=bits/8&&row>=int64_t(w-1)*pixel+bits/8);
        require(uint64_t(h-1)*row+uint64_t(w-1)*pixel+bits/8<=size);
    }
    int get(int x,int y,int bits)const{const uint8_t*p=data+size_t(y)*row+size_t(x)*pixel;return bits==10?(int(p[0])|(int(p[1])<<8))>>6:*p;}
    void put(int x,int y,int bits,int v)const{uint8_t*p=data+size_t(y)*row+size_t(x)*pixel;if(bits==10){unsigned t=unsigned(v)<<6;p[0]=t&255;p[1]=t>>8;}else *p=uint8_t(v);}
};
inline void render(const Lut& lut,const std::array<Plane,6>& p,int w,int h,int start,int rows,int bits,int transfer,bool full) {
    require(w>0&&h>0&&w%2==0&&h%2==0&&start>=0&&rows>0&&start%2==0&&rows%2==0&&start<=h-rows&&(bits==8||bits==10));
    require((bits==10&&(transfer==16||transfer==18))||(bits==8&&(transfer==1||transfer==6||transfer==13)));
    for(int i=0;i<6;i++)p[i].validate(i%3==0?w:w/2,i%3==0?h:h/2,bits==10?16:8);
    double factor=bits==10?4:1,max=bits==10?1023:255,ymin=full?0:16*factor,ys=full?max:219*factor,cs=full?max:224*factor,mid=128*factor;
    double kr=bits==10?.2627:.2126,kb=bits==10?.0593:.0722,kg=1-kr-kb;
    for(int y=start;y<start+rows;y+=2)for(int x=0;x<w;x+=2){
        double cb=(p[1].get(x/2,y/2,bits)-mid)/cs,cr=(p[2].get(x/2,y/2,bits)-mid)/cs,cbOut=0,crOut=0;
        for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
            double yy=(p[0].get(x+dx,y+dy,bits)-ymin)/ys;
            RGB rgb={yy+2*(1-kr)*cr,yy-2*kb*(1-kb)/kg*cb-2*kr*(1-kr)/kg*cr,yy+2*(1-kb)*cb};
            RGB out=lut.grade(rgb,transfer);double l=kr*out[0]+kg*out[1]+kb*out[2];
            p[3].put(x+dx,y+dy,bits,int(std::floor(clamp(ymin+l*ys,0,max)+.5)));
            cbOut+=(out[2]-l)/(2*(1-kb));crOut+=(out[0]-l)/(2*(1-kr));
        }
        p[4].put(x/2,y/2,bits,int(std::floor(clamp(mid+cs*cbOut/4,0,max)+.5)));
        p[5].put(x/2,y/2,bits,int(std::floor(clamp(mid+cs*crOut/4,0,max)+.5)));
    }
}
}
