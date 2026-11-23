#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <pthread.h>

#include <yara/libyara.h>
#include <yara/compiler.h>
#include <yara/rules.h>

namespace {
pthread_once_t g_yara_once = PTHREAD_ONCE_INIT;
int g_yara_init_result = ERROR_INTERNAL_FATAL_ERROR;

void initialize_yara_once() {
    g_yara_init_result = yr_initialize();
}

struct MatchList {
    std::vector<std::string> names;
};

int yara_callback(YR_SCAN_CONTEXT*, int message, void* message_data, void* user_data) {
    if (message == CALLBACK_MSG_RULE_MATCHING && message_data != nullptr && user_data != nullptr) {
        const auto* rule = static_cast<const YR_RULE*>(message_data);
        auto* matches = static_cast<MatchList*>(user_data);
        if (rule->identifier != nullptr) matches->names.emplace_back(rule->identifier);
    }
    return CALLBACK_CONTINUE;
}

std::string java_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    pthread_once(&g_yara_once, initialize_yara_once);
    return g_yara_init_result == ERROR_SUCCESS ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    if (g_yara_init_result == ERROR_SUCCESS) yr_finalize();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ikar_assistant_security_NativeYaraEngine_nativeScanFile(
    JNIEnv* env, jobject, jstring file_path, jstring rule_path) {
    if (g_yara_init_result != ERROR_SUCCESS) {
        return env->NewStringUTF("ERROR:YARA_INITIALIZATION_FAILED");
    }

    const std::string file = java_string(env, file_path);
    const std::string rules_file = java_string(env, rule_path);
    if (file.empty() || rules_file.empty()) {
        return env->NewStringUTF("ERROR:INVALID_PATH");
    }

    FILE* input = std::fopen(rules_file.c_str(), "rb");
    if (input == nullptr) return env->NewStringUTF("ERROR:RULE_FILE_OPEN_FAILED");

    YR_COMPILER* compiler = nullptr;
    YR_RULES* rules = nullptr;
    int result = yr_compiler_create(&compiler);
    if (result != ERROR_SUCCESS || compiler == nullptr) {
        std::fclose(input);
        return env->NewStringUTF("ERROR:YARA_COMPILER_CREATE_FAILED");
    }

    const int compile_errors = yr_compiler_add_file(compiler, input, nullptr, rules_file.c_str());
    std::fclose(input);
    if (compile_errors != 0) {
        yr_compiler_destroy(compiler);
        return env->NewStringUTF("ERROR:YARA_RULE_COMPILE_FAILED");
    }

    result = yr_compiler_get_rules(compiler, &rules);
    yr_compiler_destroy(compiler);
    if (result != ERROR_SUCCESS || rules == nullptr) {
        return env->NewStringUTF("ERROR:YARA_RULES_LOAD_FAILED");
    }

    MatchList matches;
    result = yr_rules_scan_file(
        rules, file.c_str(), SCAN_FLAGS_REPORT_RULES_MATCHING,
        yara_callback, &matches, 20);
    yr_rules_destroy(rules);

    if (result != ERROR_SUCCESS) {
        const std::string error = "ERROR:YARA_SCAN_FAILED_" + std::to_string(result);
        return env->NewStringUTF(error.c_str());
    }

    std::string output;
    for (const auto& name : matches.names) {
        if (!output.empty()) output += "\n";
        output += name;
    }
    return env->NewStringUTF(output.c_str());
}
