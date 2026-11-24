#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <pthread.h>
#include <mutex>
#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>
#include <cstring>
#include <cctype>

#include <yara/error.h>
#include <yara/libyara.h>
#include <yara/compiler.h>
#include <yara/rules.h>
#include <yara/scan.h>

namespace {
pthread_once_t g_yara_once = PTHREAD_ONCE_INIT;
int g_yara_init_result = ERROR_INTERNAL_FATAL_ERROR;
std::mutex g_rules_mutex;
YR_RULES* g_cached_rules = nullptr;
std::string g_cached_path;
jlong g_cached_generation = -1;

void initialize_yara_once() { g_yara_init_result = yr_initialize(); }

struct MatchList {
    std::vector<std::string> names;
    static constexpr size_t MAX_MATCHED_RULES = 512;
};

int yara_callback(YR_SCAN_CONTEXT*, int message, void* message_data, void* user_data) {
    if (message == CALLBACK_MSG_RULE_MATCHING && message_data != nullptr && user_data != nullptr) {
        const auto* rule = static_cast<const YR_RULE*>(message_data);
        auto* matches = static_cast<MatchList*>(user_data);
        if (rule->identifier != nullptr && matches->names.size() < MatchList::MAX_MATCHED_RULES) {
            try { matches->names.emplace_back(rule->identifier); }
            catch (...) { return CALLBACK_ERROR; }
        }
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

bool is_rule_file(const std::string& path) {
    const auto dot = path.find_last_of('.');
    if (dot == std::string::npos) return false;
    std::string ext = path.substr(dot);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(::tolower(c)); });
    return ext == ".yar" || ext == ".yara";
}

void collect_rule_files(const std::string& directory, std::vector<std::string>& files) {
    DIR* dir = opendir(directory.c_str());
    if (dir == nullptr) return;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (std::strcmp(entry->d_name, ".") == 0 || std::strcmp(entry->d_name, "..") == 0) continue;
        const std::string path = directory + "/" + entry->d_name;
        struct stat info {};
        if (stat(path.c_str(), &info) != 0) continue;
        if (S_ISDIR(info.st_mode)) collect_rule_files(path, files);
        else if (S_ISREG(info.st_mode) && is_rule_file(path)) files.push_back(path);
    }
    closedir(dir);
}

int compile_rule_directory(const std::string& directory, YR_RULES** output, size_t* file_count) {
    if (output == nullptr) return ERROR_INVALID_ARGUMENT;
    *output = nullptr;
    std::vector<std::string> files;
    collect_rule_files(directory, files);
    std::sort(files.begin(), files.end());
    if (files.empty()) return ERROR_INVALID_FILE;

    YR_COMPILER* compiler = nullptr;
    int result = yr_compiler_create(&compiler);
    if (result != ERROR_SUCCESS || compiler == nullptr) return result;
    int compile_errors = 0;
    for (const auto& path : files) {
        FILE* input = std::fopen(path.c_str(), "rb");
        if (input == nullptr) { ++compile_errors; continue; }
        // Community rules share a namespace so cross-rule dependencies resolve.
        // IKAR's bundled rules use a separate namespace to avoid identifier collisions.
        const char* name_space = path.find("ikar-local-rules") != std::string::npos ? "ikar_local" : "ikar";
        compile_errors += yr_compiler_add_file(compiler, input, name_space, path.c_str());
        std::fclose(input);
    }
    if (file_count != nullptr) *file_count = files.size();
    if (compile_errors != 0) {
        yr_compiler_destroy(compiler);
        return ERROR_INVALID_FILE;
    }
    result = yr_compiler_get_rules(compiler, output);
    yr_compiler_destroy(compiler);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    pthread_once(&g_yara_once, initialize_yara_once);
    return g_yara_init_result == ERROR_SUCCESS ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ikar_assistant_security_NativeYaraEngine_nativeValidateRules(
    JNIEnv* env, jobject, jstring rules_directory) {
    if (g_yara_init_result != ERROR_SUCCESS) return env->NewStringUTF("ERROR:YARA_INITIALIZATION_FAILED");
    const std::string directory = java_string(env, rules_directory);
    if (directory.empty()) return env->NewStringUTF("ERROR:INVALID_RULE_DIRECTORY");
    YR_RULES* rules = nullptr;
    size_t count = 0;
    const int result = compile_rule_directory(directory, &rules, &count);
    if (rules != nullptr) yr_rules_destroy(rules);
    if (result != ERROR_SUCCESS) return env->NewStringUTF("ERROR:YARA_RULE_DATABASE_INVALID");
    const std::string message = "OK:" + std::to_string(count);
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ikar_assistant_security_NativeYaraEngine_nativeScanFile(
    JNIEnv* env, jobject, jstring file_path, jstring rules_directory, jlong generation) {
    if (g_yara_init_result != ERROR_SUCCESS) return env->NewStringUTF("ERROR:YARA_INITIALIZATION_FAILED");
    const std::string file = java_string(env, file_path);
    const std::string directory = java_string(env, rules_directory);
    if (file.empty() || directory.empty()) return env->NewStringUTF("ERROR:INVALID_PATH");

    std::lock_guard<std::mutex> guard(g_rules_mutex);
    if (g_cached_rules == nullptr || g_cached_path != directory || g_cached_generation != generation) {
        if (g_cached_rules != nullptr) {
            yr_rules_destroy(g_cached_rules);
            g_cached_rules = nullptr;
        }
        size_t count = 0;
        const int compile_result = compile_rule_directory(directory, &g_cached_rules, &count);
        if (compile_result != ERROR_SUCCESS || g_cached_rules == nullptr) {
            g_cached_path.clear();
            g_cached_generation = -1;
            return env->NewStringUTF("ERROR:YARA_RULE_DATABASE_INVALID");
        }
        g_cached_path = directory;
        g_cached_generation = generation;
    }

    MatchList matches;
    const int result = yr_rules_scan_file(
        g_cached_rules, file.c_str(), SCAN_FLAGS_REPORT_RULES_MATCHING,
        yara_callback, &matches, 20);
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
