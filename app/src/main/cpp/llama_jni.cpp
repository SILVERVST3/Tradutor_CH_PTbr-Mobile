// Ponte JNI mínima para rodar o HY-MT1.5-1.8B (GGUF) com llama.cpp no aparelho.
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "HyMT", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string tmpl;
    int n_batch = 512;
};

static bool g_backend = false;

static std::string jbytes_to_string(JNIEnv *env, jbyteArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

static jbyteArray string_to_jbytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (!s.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                                            reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

static std::string build_prompt(Engine *e, const std::string &user) {
    llama_chat_message msg{"user", user.c_str()};
    if (!e->tmpl.empty()) {
        std::vector<char> buf(user.size() * 2 + 512);
        int n = llama_chat_apply_template(e->tmpl.c_str(), &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(e->tmpl.c_str(), &msg, 1, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) return std::string(buf.data(), n);
    }
    // formato do Hunyuan dense caso o GGUF não traga o template
    return std::string("<\xEF\xBD\x9Chy_User\xEF\xBD\x9C>") + user + "<\xEF\xBD\x9Chy_Assistant\xEF\xBD\x9C>";
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_jorge_tradutorzh_LlamaTranslator_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint nThreads, jint nCtx) {
    if (!g_backend) { llama_backend_init(); g_backend = true; }
    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    const char *t = llama_model_chat_template(model, nullptr);
    if (t) e->tmpl = t;

    // parâmetros recomendados pela Tencent para o HY-MT1.5
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    e->smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(e->smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(e->smpl, llama_sampler_init_dist(1234));
    LOGI("modelo carregado, template=%s", e->tmpl.empty() ? "(manual)" : "gguf");
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_br_jorge_tradutorzh_LlamaTranslator_nativeTranslate(JNIEnv *env, jobject, jlong h, jbyteArray jprompt, jint maxTokens) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return string_to_jbytes(env, "");

    const std::string prompt = build_prompt(e, jbytes_to_string(env, jprompt));

    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_sampler_reset(e->smpl);

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n > 0 ? n : 1);
    n = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true);
    if (n <= 0) return string_to_jbytes(env, "");
    toks.resize(n);

    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if (n + maxTokens >= n_ctx) {  // corta o começo (contexto) se o texto for longo demais
        toks.erase(toks.begin(), toks.begin() + (n + maxTokens - n_ctx + 8));
        n = (int) toks.size();
    }

    for (int i = 0; i < n; i += e->n_batch) {
        int len = std::min(e->n_batch, n - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, len)) != 0)
            return string_to_jbytes(env, "");
    }

    std::string out;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        llama_token t = llama_sampler_sample(e->smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        int k = llama_token_to_piece(e->vocab, t, piece, sizeof(piece), 0, false);
        if (k > 0) out.append(piece, k);
        if (out.find("\n\n") != std::string::npos) break;  // tradução concluída
        llama_token next = t;
        if (llama_decode(e->ctx, llama_batch_get_one(&next, 1)) != 0) break;
    }
    return string_to_jbytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_br_jorge_tradutorzh_LlamaTranslator_nativeFree(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    if (e->smpl) llama_sampler_free(e->smpl);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}
