#include <jni.h>
#include <android/log.h>
#include <whisper.h>
extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libswresample/swresample.h"
}
#include <algorithm>
#include <chrono>
#include <cmath>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <dlfcn.h>
#include <cstdint>

namespace {
using Clock = std::chrono::steady_clock;
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GhostWhisper", __VA_ARGS__)
std::mutex inference_mutex;
struct ModelCache {
    whisper_context *context = nullptr;
    std::string path;
    time_t modified = 0;
    off_t size = 0;
    ino_t inode = 0;
    ~ModelCache() { if (context) whisper_free(context); }
} model_cache;
struct Deadline { Clock::time_point end; };
bool expired(void *data) { return Clock::now() >= static_cast<Deadline *>(data)->end; }
void check(Deadline &deadline) { if (expired(&deadline)) throw std::runtime_error("TIMEOUT"); }
struct UtfString {
    JNIEnv *env; jstring value; const char *text;
    UtfString(JNIEnv *e, jstring v) : env(e), value(v), text(e->GetStringUTFChars(v, nullptr)) { }
    ~UtfString() { if (text) env->ReleaseStringUTFChars(value, text); }
};
struct Decoder {
    AVFormatContext *format = nullptr;
    AVCodecContext *codec = nullptr;
    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    SwrContext *resampler = nullptr;
    ~Decoder() {
        swr_free(&resampler); av_packet_free(&packet); av_frame_free(&frame);
        avcodec_free_context(&codec); avformat_close_input(&format);
    }
};
void require(bool ok, const char *message) { if (!ok) throw std::runtime_error(message); }
std::vector<float> decode(const char *path, Deadline &deadline) {
    Decoder d;
    require(d.frame && d.packet, "Audio decoder allocation failed");
    require(avformat_open_input(&d.format, path, nullptr, nullptr) >= 0 &&
            avformat_find_stream_info(d.format, nullptr) >= 0, "Cannot open audio or video file");
    int track = av_find_best_stream(d.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    require(track >= 0, "Video has no audio track");
    const AVCodec *codec = avcodec_find_decoder(d.format->streams[track]->codecpar->codec_id);
    require(codec != nullptr, "Unsupported audio codec");
    d.codec = avcodec_alloc_context3(codec);
    require(d.codec && avcodec_parameters_to_context(d.codec, d.format->streams[track]->codecpar) >= 0 &&
            avcodec_open2(d.codec, codec, nullptr) >= 0, "Cannot initialize audio decoder");
    std::vector<float> pcm;
    auto append = [&](const uint8_t **input, int samples) {
        int capacity = std::max(32, swr_get_out_samples(d.resampler, samples));
        std::vector<float> output(capacity);
        uint8_t *plane = reinterpret_cast<uint8_t *>(output.data());
        int count = swr_convert(d.resampler, &plane, capacity, input, samples);
        require(count >= 0, "Cannot resample audio");
        require(pcm.size() + count <= 16000ULL * 60 * 60, "Audio exceeds one hour");
        pcm.insert(pcm.end(), output.begin(), output.begin() + count);
        return count;
    };
    bool finished = false;
    while (!finished) {
        check(deadline);
        int read = av_read_frame(d.format, d.packet);
        if (read >= 0 && d.packet->stream_index != track) { av_packet_unref(d.packet); continue; }
        require(read >= 0 || read == AVERROR_EOF, "Cannot read audio packet");
        require(avcodec_send_packet(d.codec, read < 0 ? nullptr : d.packet) >= 0, "Cannot decode audio packet");
        av_packet_unref(d.packet);
        for (;;) {
            int status = avcodec_receive_frame(d.codec, d.frame);
            if (status == AVERROR(EAGAIN) || status == AVERROR_EOF) break;
            require(status >= 0, "Cannot decode audio frame");
            if (!d.resampler) {
                AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
                require(swr_alloc_set_opts2(&d.resampler, &mono, AV_SAMPLE_FMT_FLT, 16000,
                        &d.frame->ch_layout, static_cast<AVSampleFormat>(d.frame->format),
                        d.frame->sample_rate, 0, nullptr) >= 0 && swr_init(d.resampler) >= 0,
                        "Cannot initialize 16 kHz mono resampler");
            }
            append(const_cast<const uint8_t **>(d.frame->extended_data), d.frame->nb_samples);
            av_frame_unref(d.frame);
        }
        finished = read < 0;
    }
    if (d.resampler) while (append(nullptr, 0) > 0) check(deadline);
    require(!pcm.empty(), "NO_SPEECH");
    double energy = 0;
    for (float sample : pcm) energy += sample * sample;
    double rms = std::sqrt(energy / pcm.size());
    LOGI("decode: samples=%zu duration=%.3fs rms=%.6f", pcm.size(), pcm.size() / 16000.0, rms);
    require(rms > 0.00001, "NO_SPEECH");
    return pcm;
}
void progress(whisper_context *, whisper_state *, int value, void *) { LOGI("progress=%d", value); }
std::string transcribe(const char *model_path, const char *audio_path, int threads) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    auto start = Clock::now();
    Deadline deadline{start + std::chrono::seconds(std::string(model_path).find("large-v3-turbo")!=std::string::npos?120:30)};
    auto pcm = decode(audio_path, deadline);
    struct stat metadata{};
    require(stat(model_path, &metadata) == 0, "Whisper model is missing");
    if (!model_cache.context || model_cache.path != model_path ||
            model_cache.modified != metadata.st_mtime || model_cache.size != metadata.st_size || model_cache.inode != metadata.st_ino) {
        if (model_cache.context) whisper_free(model_cache.context);
        model_cache.context = nullptr;
        auto options = whisper_context_default_params();
        options.use_gpu = false;
        model_cache.context = whisper_init_from_file_with_params(model_path, options);
        require(model_cache.context != nullptr, "Cannot load Whisper model");
        model_cache.path = model_path; model_cache.modified = metadata.st_mtime; model_cache.size = metadata.st_size; model_cache.inode = metadata.st_ino;
        LOGI("model loaded: %.3fs", std::chrono::duration<double>(Clock::now() - start).count());
    }
    check(deadline);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::clamp(threads, 4, 6);
    params.language = "ru";
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
    params.no_timestamps = true;
    params.single_segment = false;
    // Keep the trained audio context and normal segment boundaries. Truncating
    // the encoder context/token count caused incorrect or missing speech.
    params.audio_ctx = 0;
    params.max_tokens = 0;
    params.greedy.best_of = 1;
    params.temperature_inc = 0.0f;
    params.progress_callback = progress;
    params.abort_callback = expired; params.abort_callback_user_data = &deadline;
    LOGI("inference: model=%s language=ru threads=%d", model_path, params.n_threads);
    int status = whisper_full(model_cache.context, params, pcm.data(), static_cast<int>(pcm.size()));
    check(deadline);
    require(status == 0, "Whisper inference failed");
    std::string result;
    for (int i = 0; i < whisper_full_n_segments(model_cache.context); ++i) {
        result += whisper_full_get_segment_text(model_cache.context, i);
    }
    LOGI("complete: elapsed=%.3fs chars=%zu", std::chrono::duration<double>(Clock::now() - start).count(), result.size());
    require(result.find_first_not_of(" \t\r\n") != std::string::npos, "NO_SPEECH");
    return result;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_telegram_messenger_LocalWhisper_transcribeNative(
        JNIEnv *env, jclass, jstring model, jstring audio, jint threads, jstring) {
    UtfString model_path(env, model), audio_path(env, audio);
    if (!model_path.text || !audio_path.text) return nullptr;
    try {
        return env->NewStringUTF(transcribe(model_path.text, audio_path.text, threads).c_str());
    } catch (const std::exception &error) {
        LOGI("failed: %s", error.what());
        return env->NewStringUTF((std::string("ERROR:") + error.what()).c_str());
    }
}

namespace {
struct VoskApi {
    void *library;
    void *(*model_new)(const char *);
    void (*model_free)(void *);
    void *(*recognizer_new)(void *,float);
    void (*recognizer_free)(void *);
    int (*accept)(void *,const char *,int);
    const char *(*result)(void *);
    const char *(*final_result)(void *);
    template<class T> T symbol(const char *name){void *pointer=dlsym(library,name);require(pointer!=nullptr,"Vosk API is unavailable");return reinterpret_cast<T>(pointer);}
    VoskApi(){
        library=dlopen("libvosk.so",RTLD_NOW|RTLD_LOCAL);require(library!=nullptr,"Cannot load Vosk library");
        model_new=symbol<decltype(model_new)>("vosk_model_new");model_free=symbol<decltype(model_free)>("vosk_model_free");
        recognizer_new=symbol<decltype(recognizer_new)>("vosk_recognizer_new");recognizer_free=symbol<decltype(recognizer_free)>("vosk_recognizer_free");
        accept=symbol<decltype(accept)>("vosk_recognizer_accept_waveform");result=symbol<decltype(result)>("vosk_recognizer_result");final_result=symbol<decltype(final_result)>("vosk_recognizer_final_result");
        symbol<void (*)(int)>("vosk_set_log_level")(-1);
    }
};
std::string transcribe_vosk(const char *model_path,const char *audio_path){
    std::lock_guard<std::mutex> lock(inference_mutex);
    Deadline deadline{Clock::now()+std::chrono::seconds(120)};
    auto pcm=decode(audio_path,deadline);
    // Keep only one heavy recognizer resident when switching engines.
    if(model_cache.context){whisper_free(model_cache.context);model_cache.context=nullptr;}
    static VoskApi api;
    struct Session {
        VoskApi &api;void *model=nullptr,*recognizer=nullptr;
        ~Session(){if(recognizer)api.recognizer_free(recognizer);if(model)api.model_free(model);}
    } session{api};
    session.model=api.model_new(model_path);require(session.model!=nullptr,"Cannot load Vosk Russian model");
    session.recognizer=api.recognizer_new(session.model,16000.0f);require(session.recognizer!=nullptr,"Cannot create Vosk recognizer");
    std::string json="[";bool comma=false;
    auto append=[&](const char *text){if(text&&*text){if(comma)json+=",";json+=text;comma=true;}};
    std::vector<int16_t> chunk(4096);
    for(size_t offset=0;offset<pcm.size();offset+=chunk.size()){
        check(deadline);size_t count=std::min(chunk.size(),pcm.size()-offset);
        for(size_t i=0;i<count;i++)chunk[i]=static_cast<int16_t>(std::clamp(pcm[offset+i],-1.0f,1.0f)*32767.0f);
        int accepted=api.accept(session.recognizer,reinterpret_cast<const char *>(chunk.data()),static_cast<int>(count*sizeof(int16_t)));
        require(accepted>=0,"Vosk recognition failed");if(accepted)append(api.result(session.recognizer));
    }
    append(api.final_result(session.recognizer));json+="]";return json;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_telegram_messenger_LocalWhisper_transcribeVoskNative(JNIEnv *env,jclass,jstring model,jstring audio){
    UtfString model_path(env,model),audio_path(env,audio);if(!model_path.text||!audio_path.text)return nullptr;
    try{return env->NewStringUTF(transcribe_vosk(model_path.text,audio_path.text).c_str());}
    catch(const std::exception &error){return env->NewStringUTF((std::string("ERROR:")+error.what()).c_str());}
}
