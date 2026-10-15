#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <fstream>
#include <vector>
#include <memory>
#include <string>
#include <sstream>
#include <stdexcept>
#include <algorithm>
static std::atomic<bool> cancelled{false};
static uint32_t u32(const unsigned char *p) { return p[0]|uint32_t(p[1])<<8|uint32_t(p[2])<<16|uint32_t(p[3])<<24; }
static uint16_t u16(const unsigned char *p) {return p[0]|uint16_t(p[1])<<8;}
static std::string path(JNIEnv *e,jstring v) { const char *s=e->GetStringUTFChars(v,nullptr); if(!s) throw std::runtime_error("Invalid path"); std::string r(s);e->ReleaseStringUTFChars(v,s);return r; }
static std::string escaped(const char *p) {
 std::string s="\""; for(;*p;p++){unsigned char c=*p; switch(c){case '"':s+="\\\"";break;case '\\':s+="\\\\";break;case '\n':s+="\\n";break;case '\r':s+="\\r";break;case '\t':s+="\\t";break;default:if(c>=32)s+=char(c);}}return s+"\"";
}
static jstring utf8(JNIEnv *e,const std::string &s) {
 auto bytes=e->NewByteArray(s.size());e->SetByteArrayRegion(bytes,0,s.size(),reinterpret_cast<const jbyte*>(s.data()));
 auto cl=e->FindClass("java/lang/String");auto ctor=e->GetMethodID(cl,"<init>","([BLjava/lang/String;)V");auto enc=e->NewStringUTF("UTF-8");
 auto result=(jstring)e->NewObject(cl,ctor,bytes,enc);e->DeleteLocalRef(bytes);e->DeleteLocalRef(enc);return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_ersaz_hermesmobile_media_WhisperBridge_prepare(JNIEnv*,jobject) {cancelled=false;}
extern "C" JNIEXPORT void JNICALL Java_com_ersaz_hermesmobile_media_WhisperBridge_cancel(JNIEnv*,jobject) {cancelled=true;}
extern "C" JNIEXPORT jstring JNICALL Java_com_ersaz_hermesmobile_media_WhisperBridge_transcribe(JNIEnv *e,jobject,jstring model,jstring audio) {
 try {
 if(cancelled)throw std::runtime_error("Transcription cancelled");
 std::ifstream f(path(e,audio),std::ios::binary|std::ios::ate);auto size=f.tellg();
 if(size<44 || size>100*1024*1024)throw std::runtime_error("Invalid or oversized WAV");
 std::vector<unsigned char> bytes(static_cast<size_t>(size));f.seekg(0);f.read((char*)bytes.data(),bytes.size());if(!f)throw std::runtime_error("Cannot read audio");
 if(std::string((char*)bytes.data(),4)!="RIFF"||std::string((char*)bytes.data()+8,4)!="WAVE")throw std::runtime_error("PCM WAV required");
 bool fmt=false;std::vector<float> pcm;
 for(size_t pos=12;pos+8<=bytes.size();) {
 auto n=u32(bytes.data()+pos+4);auto start=pos+8;if(n>bytes.size()-start)throw std::runtime_error("Truncated WAV");
 std::string type((char*)bytes.data()+pos,4);
 if(type=="fmt "){if(n<16||u16(bytes.data()+start)!=1||u16(bytes.data()+start+2)!=1||u32(bytes.data()+start+4)!=16000||u16(bytes.data()+start+14)!=16)throw std::runtime_error("Use mono 16kHz 16-bit PCM");fmt=true;}
 if(type=="data"){if(!fmt||n%2||n/2>16000*1200)throw std::runtime_error("Audio exceeds 20 minutes");pcm.reserve(n/2);for(size_t i=0;i<n;i+=2)pcm.push_back(static_cast<int16_t>(u16(bytes.data()+start+i))/32768.f);break;}
 pos=start+n+(n%2);
 }
 if(pcm.empty())throw std::runtime_error("No PCM audio");bytes.clear();bytes.shrink_to_fit();
 auto cp=whisper_context_default_params();cp.use_gpu=false;
 std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(path(e,model).c_str(),cp),whisper_free);
 if(cancelled)throw std::runtime_error("Transcription cancelled");
 if(!ctx)throw std::runtime_error("Cannot load model");
 auto p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);p.n_threads=4;p.language="auto";p.translate=false;p.print_realtime=false;p.print_progress=false;p.print_timestamps=false;p.print_special=false;
 p.abort_callback=[](void*){return cancelled.load();};
 if(whisper_full(ctx.get(),p,pcm.data(),pcm.size())!=0||cancelled)throw std::runtime_error("Transcription cancelled or failed");
 std::ostringstream out;out<<"{\"coverage\":\"audio_transcript\",\"method\":\"whisper.cpp tiny multilingual\",\"segments\":[";
 for(int i=0;i<whisper_full_n_segments(ctx.get());i++){if(i)out<<",";out<<"{\"start\":"<<whisper_full_get_segment_t0(ctx.get(),i)/100.0<<",\"end\":"<<whisper_full_get_segment_t1(ctx.get(),i)/100.0<<",\"text\":"<<escaped(whisper_full_get_segment_text(ctx.get(),i))<<"}";}
 out<<"]}";return utf8(e,out.str());
 } catch(const std::exception &x){e->ThrowNew(e->FindClass("java/io/IOException"),x.what());return nullptr;}
}
