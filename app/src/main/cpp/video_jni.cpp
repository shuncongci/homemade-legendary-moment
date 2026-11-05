#include <jni.h>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <new>
#include <limits>
#include "video_math.h"

namespace {
struct Pending {};
void checked(JNIEnv* e){if(e->ExceptionCheck())throw Pending{};}
void need(bool b){video::require(b);}
std::mutex videoMutex;
std::unordered_map<jlong,std::shared_ptr<video::Lut>> videoLuts;
jlong nextVideo=1;
void failure(JNIEnv* e,const char* type,const char* message){
    if(!e->ExceptionCheck()){jclass c=e->FindClass(type);if(c)e->ThrowNew(c,message);}
}
template<class F> void boundary(JNIEnv* e,F f){
    try{f();}
    catch(const Pending&){}
    catch(const std::bad_alloc&){failure(e,"java/lang/OutOfMemoryError","Video LUT allocation failed");}
    catch(const std::exception&){failure(e,"java/lang/IllegalArgumentException","Invalid video LUT, handle or plane layout");}
    catch(...){failure(e,"java/lang/IllegalStateException","Video LUT operation failed");}
}
struct Frame {
    JNIEnv* e;
    explicit Frame(JNIEnv* p):e(p){if(e->PushLocalFrame(16)<0)throw Pending{};}
    ~Frame(){e->PopLocalFrame(nullptr);}
};
jlong create(JNIEnv* e,jobject,jbyteArray bytes){
    jlong result=0;
    boundary(e,[&]{
        need(bytes && e->GetArrayLength(bytes)==12+65*65*65*3*8);
        std::vector<uint8_t> data(size_t(e->GetArrayLength(bytes)));
        e->GetByteArrayRegion(bytes,0,jsize(data.size()),reinterpret_cast<jbyte*>(data.data()));checked(e);
        auto lut=std::make_shared<video::Lut>(data.data(),data.size());
        std::lock_guard<std::mutex> lock(videoMutex);
        need(nextVideo>0 && nextVideo<std::numeric_limits<jlong>::max());
        const jlong id=nextVideo;
        videoLuts.emplace(id,std::move(lut));++nextVideo;result=id;
    });
    return result;
}
void render(JNIEnv* e,jobject,jlong id,jobjectArray buffers,jintArray layout,jint w,jint h,jint start,jint rows,jint bits,jint transfer,jboolean full){
    boundary(e,[&]{
        Frame frame(e);
        need(buffers && layout && e->GetArrayLength(buffers)==6 && e->GetArrayLength(layout)==12);
        std::shared_ptr<video::Lut> lut;
        {std::lock_guard<std::mutex> lock(videoMutex);auto it=videoLuts.find(id);need(it!=videoLuts.end());lut=it->second;}
        jint spec[12];e->GetIntArrayRegion(layout,0,12,spec);checked(e);
        std::array<video::Plane,6> planes;
        for(int i=0;i<6;i++){
            jobject b=e->GetObjectArrayElement(buffers,i);checked(e);need(b);
            auto p=static_cast<uint8_t*>(e->GetDirectBufferAddress(b));auto n=e->GetDirectBufferCapacity(b);checked(e);
            need(p && n>0);planes[i]={p,size_t(n),spec[i*2],spec[i*2+1]};
        }
        video::render(*lut,planes,w,h,start,rows,bits,transfer,full);
    });
}
void close(JNIEnv* e,jobject,jlong id){boundary(e,[&]{std::lock_guard<std::mutex> lock(videoMutex);videoLuts.erase(id);});}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void*){
    JNIEnv* e=nullptr;if(vm->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    jclass c=e->FindClass("xyz/shuncongci/leica/VideoLutNative");if(!c)return JNI_ERR;
    JNINativeMethod methods[]={
        {const_cast<char*>("create"),const_cast<char*>("([B)J"),reinterpret_cast<void*>(create)},
        {const_cast<char*>("render"),const_cast<char*>("(J[Ljava/nio/ByteBuffer;[IIIIIIIZ)V"),reinterpret_cast<void*>(render)},
        {const_cast<char*>("close"),const_cast<char*>("(J)V"),reinterpret_cast<void*>(close)}
    };
    if(e->RegisterNatives(c,methods,3)!=JNI_OK)return JNI_ERR;
    return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*,void*){
    std::lock_guard<std::mutex> lock(videoMutex);videoLuts.clear();
}
