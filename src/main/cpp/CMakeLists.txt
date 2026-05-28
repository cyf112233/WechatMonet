cmake_minimum_required(VERSION 3.22.1)
project(wechatmonet_zygisk)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(wechatmonet SHARED
    WechatMonetModule.cpp
)

target_include_directories(wechatmonet PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)

target_link_libraries(wechatmonet
    ${log-lib}
)
