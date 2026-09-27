/**
 * ggml_jni.cpp —— llama.cpp 的 JNI 桥
 *
 * 把 llama.cpp 的模型加载 / 推理 / 采样全部收在这一个 .so 里（libggml-jni.so），
 * Kotlin 侧只看到一个 LlmEngine 类。
 *
 * 采样链是自己写的（temperature → top-k → top-p → 重复惩罚 → 多项式采样），
 *
 * 接口对齐 llama.cpp 当前 master：KV cache 走 llama_memory_t，
 * model params 里已经没有 use_mmap。
 * 不走 llama.cpp 自带的 sampler，方便以后加 min-p、DRY 之类的玩法。
 *
 * 线程模型：Kotlin 在 Dispatchers.Default 线程上调用 nativeGenerate，
 * native 直接在同一个线程里回调 onToken(String) -> Boolean，返回 false 就停。
 */

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"

#define LOG_TAG "ggml-jni"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 引擎句柄                                                            */
/* ------------------------------------------------------------------ */

struct Params {
    float temp = 0.8f;
    float top_p = 0.9f;
    int32_t top_k = 40;
    float repeat_penalty = 1.1f;
    int32_t repeat_window = 64;
    int32_t max_tokens = 512;
    uint32_t seed = 0; // 0 = 每次随机
};

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;

    std::vector<llama_token> history; // 已经进过 KV cache 的 token
    int32_t n_past = 0;
    bool stop = false;
    Params p;

    std::mt19937 rng{0};
};

static std::mutex g_mutex; // 保护 Engine 生命周期

/* ------------------------------------------------------------------ */
/* 工具                                                                */
/* ------------------------------------------------------------------ */

static bool eval_tokens(Engine *e, const std::vector<llama_token> &toks) {
    if (toks.empty()) return true;
    const int n_batch = llama_n_batch(e->ctx);

    for (size_t i = 0; i < toks.size();) {
        int n = std::min<int>(n_batch, (int)(toks.size() - i));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(toks.data() + i), n);
        if (llama_decode(e->ctx, batch) != 0) {
            LOGE("llama_decode failed at %zu", i);
            return false;
        }
        e->n_past += n;
        i += n;
    }
    e->history.insert(e->history.end(), toks.begin(), toks.end());
    return true;
}

/**
 * 自己实现的采样：temperature → 重复惩罚 → top-k → top-p → 多项式采样
 * recent 用来算重复惩罚（只往回看 repeat_window 个 token）
 */
static llama_token sample_token(Engine *e, const float *logits, const std::vector<llama_token> &recent) {
    const int n_vocab = llama_vocab_n_tokens(e->vocab);
    std::vector<float> sc(logits, logits + n_vocab);

    /* 1. 重复惩罚 */
    if (e->p.repeat_penalty != 1.0f && !recent.empty()) {
        const size_t start =
            recent.size() > (size_t)e->p.repeat_window ? recent.size() - e->p.repeat_window : 0;
        std::set<llama_token> seen(recent.begin() + start, recent.end());
        for (llama_token t : seen) {
            if (t < 0 || t >= n_vocab) continue;
            float &s = sc[t];
            if (s > 0) s /= e->p.repeat_penalty;
            else s *= e->p.repeat_penalty;
        }
    }

    /* 2. temperature（0 = 贪婪） */
    if (e->p.temp <= 0.0f) return (llama_token)std::distance(
        sc.begin(), std::max_element(sc.begin(), sc.end()));

    for (float &s : sc) s /= e->p.temp;

    /* 3. softmax */
    const float mx = *std::max_element(sc.begin(), sc.end());
    float sum = 0.0f;
    for (float &s : sc) { s = std::exp(s - mx); sum += s; }
    for (float &s : sc) s /= sum;

    /* 4. top-k：只取概率最高的 k 个 */
    std::vector<std::pair<float, int>> cand;
    cand.reserve(n_vocab);
    int k = (e->p.top_k > 0 && e->p.top_k < n_vocab) ? e->p.top_k : n_vocab;

    for (int i = 0; i < n_vocab; i++) cand.emplace_back(sc[i], i);
    if (k < n_vocab) {
        std::partial_sort(cand.begin(), cand.begin() + k, cand.end(),
                          [](const auto &a, const auto &b) { return a.first > b.first; });
        cand.resize(k);
    } else {
        std::sort(cand.begin(), cand.end(),
                  [](const auto &a, const auto &b) { return a.first > b.first; });
    }

    /* 5. top-p：按累积概率截断 */
    float cum = 0.0f;
    size_t cut = cand.size();
    for (size_t i = 0; i < cand.size(); i++) {
        cum += cand[i].first;
        if (cum >= e->p.top_p) { cut = i + 1; break; }
    }
    if (cut == 0) cut = 1;
    cand.resize(cut);

    /* 6. 重新归一化 + 多项式采样 */
    float csum = 0.0f;
    for (auto &c : cand) csum += c.first;
    std::uniform_real_distribution<float> dist(0.0f, csum);
    float target = dist(e->rng);
    float acc = 0.0f;
    for (auto &c : cand) {
        acc += c.first;
        if (target <= acc) return (llama_token)c.second;
    }
    return (llama_token)cand.back().second;
}

static std::string piece_to_string(const llama_vocab *vocab, llama_token id) {
    char buf[256];
    int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
    if (n <= 0) return "";
    if (n >= (int)sizeof(buf)) n = sizeof(buf) - 1;
    return std::string(buf, n);
}

/* ------------------------------------------------------------------ */
/* JNI 实现                                                            */
/* ------------------------------------------------------------------ */

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeLoad(JNIEnv *env, jobject,
                                                    jstring jpath, jint n_ctx,
                                                    jint n_threads, jboolean use_mmap) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    std::string spath(path);
    env->ReleaseStringUTFChars(jpath, path);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    // 注意：新版 llama.cpp 已经没有 use_mmap 字段了，
    // mmap 由后端自己决定，这里保留参数只为兼容旧签名。
    (void)use_mmap;

    llama_model *model = llama_model_load_from_file(spath.c_str(), mp);
    if (!model) {
        LOGE("load model failed: %s", spath.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)n_ctx;
    cp.n_threads = (int32_t)n_threads;
    cp.n_threads_batch = (int32_t)n_threads;
    cp.embeddings = false;
    cp.no_perf = true;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    Engine *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_past = 0;
    e->rng.seed((uint32_t)std::random_device{}());

    LOGI("model loaded: %s (ctx=%d, threads=%d)", spath.c_str(), n_ctx, n_threads);
    return (jlong)e;
}

JNIEXPORT void JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    std::lock_guard<std::mutex> lk(g_mutex);
    Engine *e = (Engine *)handle;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
    llama_backend_free();
}

/** 清空 KV cache，重新开始一轮对话 */
JNIEXPORT void JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeReset(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    Engine *e = (Engine *)handle;
    // 新版把 KV cache 抽象成 llama_memory_t，没有 llama_kv_cache_clear 了
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (mem) llama_memory_clear(mem, true);
    e->history.clear();
    e->n_past = 0;
    e->stop = false;
}

JNIEXPORT void JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    ((Engine *)handle)->stop = true;
}

JNIEXPORT jint JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeVocabSize(JNIEnv *, jobject, jlong handle) {
    return handle ? llama_vocab_n_tokens(((Engine *)handle)->vocab) : 0;
}

JNIEXPORT jint JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeEosToken(JNIEnv *, jobject, jlong handle) {
    return handle ? (jint)llama_vocab_eos(((Engine *)handle)->vocab) : -1;
}

JNIEXPORT jint JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    return handle ? (jint)llama_n_ctx(((Engine *)handle)->ctx) : 0;
}

/**
 * 把一段文本塞进上下文（不生成）。多轮对话靠这个做增量 prefill，
 * 比每轮重发整个对话快得多。返回实际吃进去的 token 数，失败返回 -1。
 */
JNIEXPORT jint JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeEvalPrompt(JNIEnv *env, jobject, jlong handle,
                                                          jstring jtext) {
    if (!handle) return -1;
    Engine *e = (Engine *)handle;

    const char *text = env->GetStringUTFChars(jtext, nullptr);
    if (!text) return -1;
    std::string s(text);
    env->ReleaseStringUTFChars(jtext, text);

    std::vector<llama_token> toks;
    toks.resize(s.size() + 16);
    // 关键：parse_special = true，这样 ChatML 里的 <|im_start|> 才会被当成特殊 token
    int n = llama_tokenize(e->vocab, s.c_str(), (int32_t)s.size(), toks.data(),
                           (int32_t)toks.size(), true, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(e->vocab, s.c_str(), (int32_t)s.size(), toks.data(),
                           (int32_t)toks.size(), true, true);
    }
    if (n <= 0) return -1;
    toks.resize(n);

    if (!eval_tokens(e, toks)) return -1;
    return (jint)n;
}

/**
 * 从当前上下文继续生成，每生成一个 token 就回调一次。
 * 回调返回 false（或 nativeStop 被调用）立即停止。
 */
JNIEXPORT jstring JNICALL
Java_com_excuse2580_aas_engine_LlmEngine_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                                        jfloat temp, jfloat top_p, jint top_k,
                                                        jfloat repeat_penalty, jint repeat_window,
                                                        jint max_tokens, jlong seed,
                                                        jobject callback) {
    if (!handle) return env->NewStringUTF("");
    Engine *e = (Engine *)handle;

    e->p.temp = temp;
    e->p.top_p = top_p;
    e->p.top_k = top_k;
    e->p.repeat_penalty = repeat_penalty;
    e->p.repeat_window = repeat_window;
    e->p.max_tokens = max_tokens;
    if (seed != 0) e->rng.seed((uint32_t)seed);
    e->stop = false;

    /* 缓存回调方法，避免每一步都查一次 */
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID cbMid = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (!cbMid) {
        env->DeleteLocalRef(cbCls);
        LOGE("callback.onToken not found");
        return env->NewStringUTF("");
    }

    const llama_token eos = llama_vocab_eos(e->vocab);
    std::vector<llama_token> generated;
    std::string out;
    bool stopped = false;

    for (int i = 0; i < max_tokens; i++) {
        if (e->stop) { stopped = true; break; }

        /* 取当前位置的 logits */
        float *logits = llama_get_logits_ith(e->ctx, 0); // 本轮 batch 只有 1 个 token
        if (!logits) break;

        llama_token id = sample_token(e, logits, e->history);
        if (id == eos) break;

        /* 进 KV cache */
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(e->ctx, batch) != 0) break;
        e->n_past += 1;
        e->history.push_back(id);
        generated.push_back(id);

        std::string piece = piece_to_string(e->vocab, id);
        out += piece;

        /* 回调 Kotlin；返回 false = 用户点了停止 / 命中停止词 */
        jstring jp = env->NewStringUTF(piece.c_str());
        jboolean cont = env->CallBooleanMethod(callback, cbMid, jp);
        env->DeleteLocalRef(jp);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            stopped = true;
            break;
        }
        if (!cont) { stopped = true; break; }
    }

    env->DeleteLocalRef(cbCls);
    LOGI("generated %zu tokens (stopped=%d)", generated.size(), (int)stopped);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
