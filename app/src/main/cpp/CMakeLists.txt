cmake_minimum_required(VERSION 3.18)
project("tide")

add_library(tide SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(tide ${log-lib})
