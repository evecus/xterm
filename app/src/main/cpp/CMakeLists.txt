cmake_minimum_required(VERSION 3.22.1)

project("xtermpty")

add_library(xtermpty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(xtermpty ${log-lib})
