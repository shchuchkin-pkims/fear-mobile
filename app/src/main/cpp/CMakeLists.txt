cmake_minimum_required(VERSION 3.18.1)

project("fear_opus")

# Opus library - we'll use a prebuilt version
set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opus)

add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${OPUS_DIR}/libs/${ANDROID_ABI}/libopus.so)

# Our JNI wrapper
add_library(fear_opus SHARED
    opus_jni.cpp)

target_include_directories(fear_opus PRIVATE
    ${OPUS_DIR}/include)

target_link_libraries(fear_opus
    opus
    android
    log)
