/* Native libretro frontend for the verified original 1992 MK1 set.
 * No sprites, sound effects, moves or combat rules are reimplemented here. */
#include <libretro.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include <string.h>
#include <pthread.h>
#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#endif
#define GG_EXPORT __attribute__((visibility("default")))
static char directory[2048],rom_path[2048];
static int loaded,initialized,pixel_format,width,height,frame,show_video=1,play_audio=1,stopped;
static uint16_t buttons[2];
static uint32_t pixels[1024*1024];
static int16_t pcm[48000*4];static int pcm_head,pcm_tail;
static pthread_mutex_t audio_lock=PTHREAD_MUTEX_INITIALIZER;
struct setting {char key[160],value[160];};static struct setting settings[256];static int setting_count;
static void log_message(enum retro_log_level level,const char *format,...){
 if(level<RETRO_LOG_WARN)return;
 va_list args;va_start(args,format);
#ifdef __ANDROID__
 __android_log_vprint(level>=RETRO_LOG_ERROR?ANDROID_LOG_ERROR:ANDROID_LOG_WARN,"GhostKombat",format,args);
#else
 vfprintf(stderr,format,args);
#endif
 va_end(args);
}
static bool environment(unsigned command,void *data){
 switch(command){
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:((struct retro_log_callback*)data)->log=log_message;return true;
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:*(const char**)data=directory;return true;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE:*(bool*)data=true;return true;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*(unsigned*)data=0;return true;
  case RETRO_ENVIRONMENT_GET_LANGUAGE:*(unsigned*)data=RETRO_LANGUAGE_ENGLISH;return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)data=false;return true;
  case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:return true;
  case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:*(int*)data=3;return true;
  case RETRO_ENVIRONMENT_GET_FASTFORWARDING:*(bool*)data=false;return true;
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:pixel_format=*(int*)data;return pixel_format>=0&&pixel_format<=2;
  case RETRO_ENVIRONMENT_SET_VARIABLES:{const struct retro_variable *v=data;setting_count=0;for(;v&&v->key&&setting_count<256;v++){const char *start=strchr(v->value,';');if(!start)continue;start++;while(*start==' ')start++;size_t length=strcspn(start,"|");if(length>=160)length=159;struct setting *s=&settings[setting_count++];snprintf(s->key,sizeof(s->key),"%s",v->key);memcpy(s->value,start,length);s->value[length]=0;}return true;}
  case RETRO_ENVIRONMENT_GET_VARIABLE:{struct retro_variable *v=data;v->value=NULL;if(strstr(v->key,"_skip_disclaimer")||strstr(v->key,"_skip_warnings")){v->value="enabled";return true;}if(strstr(v->key,"_display_setup")){v->value="disabled";return true;}if(strstr(v->key,"_sample_rate")){v->value="48000";return true;}if(strstr(v->key,"_input_interface")){v->value="retropad";return true;}for(int i=0;i<setting_count;i++)if(!strcmp(v->key,settings[i].key)){v->value=settings[i].value;return true;}return false;}
  case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:case RETRO_ENVIRONMENT_SET_ROTATION:case RETRO_ENVIRONMENT_SET_MESSAGE:case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:return true;
  case RETRO_ENVIRONMENT_SHUTDOWN:stopped=1;return true;
  default:return false;
 }
}
static void poll_input(void){}
static int16_t read_input(unsigned port,unsigned device,unsigned index,unsigned id){if(port>1||device!=RETRO_DEVICE_JOYPAD)return 0;if(id==RETRO_DEVICE_ID_JOYPAD_MASK)return (int16_t)buttons[port];return id<16?(buttons[port]>>id)&1:0;}
static void video(const void *data,unsigned w,unsigned h,size_t pitch){
 if(!data||!show_video||w>1024||h>1024)return;width=(int)w;height=(int)h;
 for(unsigned y=0;y<h;y++)for(unsigned x=0;x<w;x++){unsigned r,g,b;if(pixel_format==RETRO_PIXEL_FORMAT_XRGB8888){uint32_t v=((const uint32_t*)((const char*)data+y*pitch))[x];r=(v>>16)&255;g=(v>>8)&255;b=v&255;}else{uint16_t v=((const uint16_t*)((const char*)data+y*pitch))[x];if(pixel_format==RETRO_PIXEL_FORMAT_RGB565){r=((v>>11)&31)*255/31;g=((v>>5)&63)*255/63;b=(v&31)*255/31;}else{r=((v>>10)&31)*255/31;g=((v>>5)&31)*255/31;b=(v&31)*255/31;}}pixels[y*w+x]=r|(g<<8)|(b<<16)|0xff000000;}
}
static size_t audio(const int16_t *samples,size_t frames){if(!play_audio)return frames;pthread_mutex_lock(&audio_lock);for(size_t i=0;i<frames*2;i++){int next=(pcm_head+1)%(48000*4);if(next==pcm_tail)pcm_tail=(pcm_tail+2)%(48000*4);pcm[pcm_head]=samples[i];pcm_head=next;}pthread_mutex_unlock(&audio_lock);return frames;}
GG_EXPORT void gg_close(void){if(loaded){retro_unload_game();loaded=0;}if(initialized){retro_deinit();initialized=0;}pthread_mutex_lock(&audio_lock);pcm_head=pcm_tail=0;pthread_mutex_unlock(&audio_lock);width=height=0;}
GG_EXPORT int gg_open(const char *path,const char *storage){if(!path||!storage||strlen(path)>=sizeof(rom_path)||strlen(storage)>=sizeof(directory))return 0;gg_close();strcpy(directory,storage);strcpy(rom_path,path);setting_count=0;stopped=0;frame=0;pixel_format=0;srand(1);retro_set_environment(environment);retro_set_video_refresh(video);retro_set_audio_sample_batch(audio);retro_set_input_poll(poll_input);retro_set_input_state(read_input);retro_init();initialized=1;struct retro_game_info info={rom_path,NULL,0,NULL};loaded=retro_load_game(&info);if(!loaded){gg_close();return 0;}retro_set_controller_port_device(0,RETRO_DEVICE_JOYPAD);retro_set_controller_port_device(1,RETRO_DEVICE_JOYPAD);return 1;}
GG_EXPORT int gg_step(int a,int b,int present){if(!loaded||stopped)return -1;buttons[0]=(uint16_t)a;buttons[1]=(uint16_t)b;show_video=present!=0;play_audio=present!=0;retro_run();frame++;return frame;}
GG_EXPORT int gg_video(void *destination,size_t capacity){size_t bytes=(size_t)width*height*4;if(!destination||capacity<bytes)return 0;memcpy(destination,pixels,bytes);return (width<<16)|height;}
GG_EXPORT int gg_audio(int16_t *destination,int maximum){int count=0;pthread_mutex_lock(&audio_lock);while(count<maximum&&pcm_head!=pcm_tail){destination[count++]=pcm[pcm_tail];pcm_tail=(pcm_tail+1)%(48000*4);}pthread_mutex_unlock(&audio_lock);return count;}
GG_EXPORT double gg_fps(void){struct retro_system_av_info info;retro_get_system_av_info(&info);return info.timing.fps;}
GG_EXPORT size_t gg_save_size(void){return loaded?retro_serialize_size():0;}
GG_EXPORT int gg_save(void *out,size_t size){return loaded&&size==retro_serialize_size()&&retro_serialize(out,size);}
GG_EXPORT int gg_load(const void *data,size_t size){int ok=loaded&&size==retro_serialize_size()&&retro_unserialize(data,size);if(ok){pthread_mutex_lock(&audio_lock);pcm_head=pcm_tail=0;pthread_mutex_unlock(&audio_lock);}return ok;}
GG_EXPORT uint64_t gg_video_hash(void){uint64_t hash=1469598103934665603ULL;for(int i=0;i<width*height;i++){hash^=pixels[i];hash*=1099511628211ULL;}return hash;}
extern uint16_t *midyunit_scratch_ram;
GG_EXPORT int gg_read_byte(unsigned address){if(!loaded||!midyunit_scratch_ram||address<0x200000||address>=0x220000)return -1;return ((const unsigned char*)midyunit_scratch_ram)[address-0x200000];}
GG_EXPORT int gg_read_word(unsigned address){int a=gg_read_byte(address),b=gg_read_byte(address+1);return a<0||b<0?-1:a|(b<<8);}
GG_EXPORT int gg_ram(void *out,size_t size){if(!loaded||!midyunit_scratch_ram||size<0x20000)return 0;memcpy(out,midyunit_scratch_ram,0x20000);return 0x20000;}
GG_EXPORT uint64_t gg_logic_hash(void){uint64_t hash=1469598103934665603ULL;if(!loaded||!midyunit_scratch_ram)return 0;const uint8_t *data=(const uint8_t*)midyunit_scratch_ram;for(int i=0;i<0x20000;i++){hash^=data[i];hash*=1099511628211ULL;}return hash;}
#ifdef __ANDROID__
JNIEXPORT jboolean JNICALL Java_org_telegram_messenger_GhostKombatArcade_open(JNIEnv *env,jclass type,jstring path,jstring dir){const char *a=(*env)->GetStringUTFChars(env,path,0),*b=(*env)->GetStringUTFChars(env,dir,0);int ok=gg_open(a,b);(*env)->ReleaseStringUTFChars(env,path,a);(*env)->ReleaseStringUTFChars(env,dir,b);return ok;}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_step(JNIEnv *env,jclass type,jint a,jint b,jboolean present){return gg_step(a,b,present);}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_video(JNIEnv *env,jclass type,jobject target){void *out=(*env)->GetDirectBufferAddress(env,target);jlong size=(*env)->GetDirectBufferCapacity(env,target);return gg_video(out,(size_t)size);}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_audio(JNIEnv *env,jclass type,jshortArray target){jsize length=(*env)->GetArrayLength(env,target);jshort *out=(*env)->GetShortArrayElements(env,target,NULL);int n=gg_audio(out,length);(*env)->ReleaseShortArrayElements(env,target,out,0);return n;}
JNIEXPORT jdouble JNICALL Java_org_telegram_messenger_GhostKombatArcade_fps(JNIEnv *env,jclass type){return gg_fps();}
JNIEXPORT jbyteArray JNICALL Java_org_telegram_messenger_GhostKombatArcade_save(JNIEnv *env,jclass type){size_t size=gg_save_size();if(!size||size>16777216)return NULL;jbyteArray array=(*env)->NewByteArray(env,(jsize)size);if(!array)return NULL;jbyte *out=(*env)->GetByteArrayElements(env,array,NULL);int ok=gg_save(out,size);(*env)->ReleaseByteArrayElements(env,array,out,ok?0:JNI_ABORT);return ok?array:NULL;}
JNIEXPORT jboolean JNICALL Java_org_telegram_messenger_GhostKombatArcade_load(JNIEnv *env,jclass type,jbyteArray data){jsize size=(*env)->GetArrayLength(env,data);jbyte *in=(*env)->GetByteArrayElements(env,data,NULL);int ok=gg_load(in,size);(*env)->ReleaseByteArrayElements(env,data,in,JNI_ABORT);return ok;}
JNIEXPORT void JNICALL Java_org_telegram_messenger_GhostKombatArcade_close(JNIEnv *env,jclass type){gg_close();}
JNIEXPORT jlong JNICALL Java_org_telegram_messenger_GhostKombatArcade_videoHash(JNIEnv *env,jclass type){return (jlong)gg_video_hash();}
JNIEXPORT jlong JNICALL Java_org_telegram_messenger_GhostKombatArcade_logicHash(JNIEnv *env,jclass type){return (jlong)gg_logic_hash();}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_stateSize(JNIEnv *env,jclass type){return (jint)gg_save_size();}
JNIEXPORT jboolean JNICALL Java_org_telegram_messenger_GhostKombatArcade_saveInto(JNIEnv *env,jclass type,jobject buffer){void *data=(*env)->GetDirectBufferAddress(env,buffer);jlong size=(*env)->GetDirectBufferCapacity(env,buffer);return data&&size==(jlong)gg_save_size()&&gg_save(data,(size_t)size);}
JNIEXPORT jboolean JNICALL Java_org_telegram_messenger_GhostKombatArcade_loadFrom(JNIEnv *env,jclass type,jobject buffer){void *data=(*env)->GetDirectBufferAddress(env,buffer);jlong size=(*env)->GetDirectBufferCapacity(env,buffer);return data&&size==(jlong)gg_save_size()&&gg_load(data,(size_t)size);}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_readByte(JNIEnv *env,jclass type,jint address){return gg_read_byte((unsigned)address);}
JNIEXPORT jint JNICALL Java_org_telegram_messenger_GhostKombatArcade_readWord(JNIEnv *env,jclass type,jint address){return gg_read_word((unsigned)address);}
#endif
