cmake_minimum_required (VERSION 3.4.1)

message("CMAKE_CURRENT_BINARY_DIR  : ${CMAKE_CURRENT_BINARY_DIR}")
message("CMake version: ${CMAKE_MAJOR_VERSION}.${CMAKE_MINOR_VERSION}.${CMAKE_PATCH_VERSION}")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(CMakeDependentOption)

project(firebird C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -w -fPIC")

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${ANDROID_CXX_FLAGS} ${CMAKE_C_FLAGS} -mfloat-abi=softfp -mfpu=neon -mthumb")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${ANDROID_CXX_FLAGS} ${CMAKE_C_FLAGS} -flax-vector-conversions")
endif()

add_subdirectory("src/main/cpp/libscrypt")
